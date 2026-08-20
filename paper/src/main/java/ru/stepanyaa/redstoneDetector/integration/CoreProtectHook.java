package ru.stepanyaa.redstoneDetector.integration;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import ru.stepanyaa.redstoneDetector.ChunkCoordinate;
import ru.stepanyaa.redstoneDetector.RedstoneDetector;
import ru.stepanyaa.redstoneDetector.platform.Platforms;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Optional CoreProtect integration.
 *
 * <p>The whole hook is reflection based on purpose: CoreProtect stays a soft dependency,
 * the plugin compiles and runs without it, and every history feature simply hides itself
 * when CoreProtect is missing or disabled.</p>
 *
 * <p>Lookups are always executed off the main thread and cached per chunk, because
 * CoreProtect queries hit its database.</p>
 */
public final class CoreProtectHook {

    /** CoreProtect action id for a block break. */
    public static final int ACTION_BREAK = 0;
    /** CoreProtect action id for a block placement. */
    public static final int ACTION_PLACE = 1;

    private static final int MIN_API_VERSION = 6;

    private final RedstoneDetector plugin;

    private Object api;
    private Method performLookupMethod;
    private Method parseResultMethod;
    private Method getPlayerMethod;
    private Method getTimestampMethod;
    private volatile boolean timestampFallbackWarned;
    private Method getActionIdMethod;
    private Method getTypeMethod;
    private Method getXMethod;
    private Method getYMethod;
    private Method getZMethod;
    private Method isRolledBackMethod;

    private volatile boolean available;
    private volatile String version = "";

    private final Map<ChunkCoordinate, ChunkHistory> cache = new ConcurrentHashMap<>();
    private final Set<ChunkCoordinate> pending = ConcurrentHashMap.newKeySet();

    public CoreProtectHook(RedstoneDetector plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    /**
     * Detects CoreProtect and wires the reflective API access.
     * Safe to call again after a reload.
     */
    public void setup() {
        available = false;
        api = null;
        cache.clear();

        if (!plugin.getConfig().getBoolean("coreprotect.enabled", true)) {
            plugin.getLogger().info("CoreProtect integration disabled in config.yml.");
            return;
        }

        Plugin coreProtect = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (coreProtect == null || !coreProtect.isEnabled()) {
            return;
        }

        try {
            Object candidate = coreProtect.getClass().getMethod("getAPI").invoke(coreProtect);
            if (candidate == null) {
                return;
            }
            Class<?> apiClass = candidate.getClass();

            boolean enabled = (Boolean) apiClass.getMethod("isEnabled").invoke(candidate);
            if (!enabled) {
                plugin.getLogger().warning("CoreProtect was found but its API is not enabled yet.");
                return;
            }

            int apiVersion = (Integer) apiClass.getMethod("APIVersion").invoke(candidate);
            if (apiVersion < MIN_API_VERSION) {
                plugin.getLogger().warning("CoreProtect API v" + apiVersion
                        + " is too old (v" + MIN_API_VERSION + "+ required); integration disabled.");
                return;
            }

            performLookupMethod = apiClass.getMethod("performLookup",
                    int.class, List.class, List.class, List.class, List.class,
                    List.class, int.class, Location.class);
            parseResultMethod = apiClass.getMethod("parseResult", String[].class);

            Class<?> parseResultClass = parseResultMethod.getReturnType();
            getPlayerMethod = parseResultClass.getMethod("getPlayer");
            getTimestampMethod = parseResultClass.getMethod("getTimestamp");
            getActionIdMethod = parseResultClass.getMethod("getActionId");
            getTypeMethod = parseResultClass.getMethod("getType");
            getXMethod = parseResultClass.getMethod("getX");
            getYMethod = parseResultClass.getMethod("getY");
            getZMethod = parseResultClass.getMethod("getZ");
            isRolledBackMethod = findOptionalMethod(parseResultClass, "isRolledBack");

            api = candidate;
            version = coreProtect.getDescription().getVersion();
            available = true;
            plugin.getLogger().info("CoreProtect " + version
                    + " detected - chunk history integration enabled.");
        } catch (Throwable throwable) {
            available = false;
            api = null;
            plugin.getLogger().warning("Could not hook into CoreProtect: " + throwable);
        }
    }

    private static Method findOptionalMethod(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** {@code true} when CoreProtect is present, enabled and usable. */
    public boolean isAvailable() {
        return available && api != null;
    }

    public String getVersion() {
        return version;
    }

    // ------------------------------------------------------------------
    // Cache access
    // ------------------------------------------------------------------

    /** Cached history for the chunk, or {@code null} when nothing is cached yet. */
    public ChunkHistory getCached(ChunkCoordinate coord) {
        if (!isAvailable() || coord == null) {
            return null;
        }
        ChunkHistory history = cache.get(coord);
        if (history == null) {
            return null;
        }
        long ttl = plugin.getConfig().getLong("coreprotect.cache-seconds", 60L) * 1000L;
        if (ttl > 0 && System.currentTimeMillis() - history.fetchedAt() > ttl) {
            cache.remove(coord);
            return null;
        }
        return history;
    }

    public void invalidate(ChunkCoordinate coord) {
        cache.remove(coord);
    }

    public void clearCache() {
        cache.clear();
    }

    /**
     * Makes sure a fresh history snapshot exists for the chunk.
     *
     * <p>When the data is cached the callback fires immediately. Otherwise a single async
     * lookup is scheduled (duplicate requests for the same chunk are coalesced) and the
     * callback runs on the main thread once the result is ready.</p>
     *
     * @param callback receives the history, or {@code null} when the lookup failed
     */
    public void requestHistory(ChunkCoordinate coord, Consumer<ChunkHistory> callback) {
        if (!isAvailable() || coord == null) {
            if (callback != null) {
                callback.accept(null);
            }
            return;
        }

        ChunkHistory cached = getCached(coord);
        if (cached != null) {
            if (callback != null) {
                callback.accept(cached);
            }
            return;
        }

        if (!pending.add(coord)) {
            return;
        }

        Platforms.scheduler().async(() -> {
            ChunkHistory result = null;
            try {
                result = lookup(coord);
                if (result != null) {
                    cache.put(coord, result);
                }
            } catch (Throwable throwable) {
                plugin.getLogger().warning("CoreProtect lookup failed for chunk "
                        + coord.toDisplayString() + ": " + throwable);
            } finally {
                pending.remove(coord);
            }

            final ChunkHistory delivered = result;
            if (callback != null) {
                Platforms.scheduler().run(() -> callback.accept(delivered));
            }
        });
    }

    // ------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------

    /** Runs the actual CoreProtect query. Must never be called from the main thread. */
    private ChunkHistory lookup(ChunkCoordinate coord) throws Exception {
        World world = Bukkit.getWorld(coord.world());
        if (world == null) {
            return ChunkHistory.EMPTY;
        }

        int days = Math.max(1, plugin.getConfig().getInt("coreprotect.lookup-days", 14));
        int radius = Math.max(16, plugin.getConfig().getInt("coreprotect.lookup-radius", 64));
        int limit = Math.max(1, plugin.getConfig().getInt("coreprotect.history-limit", 45));
        int seconds = days * 24 * 60 * 60;

        int centerX = (coord.x() << 4) + 8;
        int centerZ = (coord.z() << 4) + 8;
        int centerY = clampHeight(world, 64);
        Location center = new Location(world, centerX, centerY, centerZ);

        List<Integer> actions = Arrays.asList(ACTION_BREAK, ACTION_PLACE);

        Object raw = performLookupMethod.invoke(api, seconds, null, null, null, null,
                actions, radius, center);
        if (!(raw instanceof List)) {
            return ChunkHistory.EMPTY;
        }

        @SuppressWarnings("unchecked")
        List<String[]> rows = (List<String[]>) raw;
        if (rows.isEmpty()) {
            return ChunkHistory.EMPTY;
        }

        Set<String> redstoneNames = redstoneNames();
        List<ChunkHistory.Entry> entries = new ArrayList<>();
        Map<String, Integer> placementsByPlayer = new HashMap<>();

        String lastRedstonePlayer = null;
        long lastRedstoneTime = 0L;

        for (String[] row : rows) {
            Object parsed = parseResultMethod.invoke(api, (Object) row);
            if (parsed == null) {
                continue;
            }

            int x = ((Number) getXMethod.invoke(parsed)).intValue();
            int z = ((Number) getZMethod.invoke(parsed)).intValue();
            // performLookup works on a radius, so results are trimmed down to this chunk.
            if ((x >> 4) != coord.x() || (z >> 4) != coord.z()) {
                continue;
            }

            int y = ((Number) getYMethod.invoke(parsed)).intValue();
            int action = ((Number) getActionIdMethod.invoke(parsed)).intValue();
            String player = (String) getPlayerMethod.invoke(parsed);
            long time = resolveTimestamp(parsed, row);
            boolean rolledBack = isRolledBackMethod != null
                    && Boolean.TRUE.equals(isRolledBackMethod.invoke(parsed));

            Object typeObject = getTypeMethod.invoke(parsed);
            String blockName = typeObject instanceof Material
                    ? ((Material) typeObject).name()
                    : String.valueOf(typeObject);

            entries.add(new ChunkHistory.Entry(player, action, blockName, time, x, y, z, rolledBack));

            if (action == ACTION_PLACE && player != null && !player.isEmpty()) {
                placementsByPlayer.merge(player, 1, Integer::sum);
                if (time > lastRedstoneTime && redstoneNames.contains(blockName.toUpperCase(Locale.ROOT))) {
                    lastRedstoneTime = time;
                    lastRedstonePlayer = player;
                }
            }
        }

        if (entries.isEmpty()) {
            return ChunkHistory.EMPTY;
        }

        entries.sort((a, b) -> Long.compare(b.time, a.time));

        String lastBuilder = entries.get(0).player;
        long lastModified = entries.get(0).time;

        String possibleOwner = null;
        int best = 0;
        for (Map.Entry<String, Integer> entry : placementsByPlayer.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                possibleOwner = entry.getKey();
            }
        }

        List<ChunkHistory.Entry> trimmed = entries.size() > limit
                ? new ArrayList<>(entries.subList(0, limit))
                : entries;

        return new ChunkHistory(trimmed, possibleOwner, lastBuilder, lastModified,
                lastRedstonePlayer, lastRedstoneTime);
    }

    private Set<String> redstoneNames() {
        Set<String> names = new java.util.HashSet<>();
        for (Material material : plugin.getRedstoneMaterials()) {
            if (material != null) {
                names.add(material.name().toUpperCase(Locale.ROOT));
            }
        }
        return names.isEmpty() ? Collections.<String>emptySet() : names;
    }

    /**
     * Reads the time of a change.
     *
     * ParseResult#getTimestamp() is the normal path, but it does not behave the same on
     * every CoreProtect build: some return 0 here even though the raw lookup row carries
     * the time just fine (that is why a change can have a date in /co lookup but show up
     * without one in our GUI). The raw row is therefore used as a fallback - CoreProtect
     * keeps the epoch time in its first column.
     */
    private long resolveTimestamp(Object parsed, String[] row) {
        long raw = 0L;

        if (getTimestampMethod != null) {
            try {
                Object value = getTimestampMethod.invoke(parsed);
                if (value instanceof Number) {
                    raw = ((Number) value).longValue();
                } else if (value != null) {
                    raw = parseEpoch(String.valueOf(value));
                }
            } catch (Throwable ignored) {
                // Falls through to the raw row below.
            }
        }

        if (raw <= 0L && row != null && row.length > 0) {
            raw = parseEpoch(row[0]);
            if (raw > 0L && !timestampFallbackWarned) {
                timestampFallbackWarned = true;
                plugin.getLogger().info("[CoreProtect] getTimestamp() returned no value, "
                        + "reading the time from the raw lookup row instead.");
            }
        }

        return normalizeTimestamp(raw);
    }

    private static long parseEpoch(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /** CoreProtect reports seconds on most builds; newer ones already use milliseconds. */
    private static long normalizeTimestamp(long timestamp) {
        if (timestamp <= 0L) {
            return 0L;
        }
        return timestamp < 100000000000L ? timestamp * 1000L : timestamp;
    }

    private static int clampHeight(World world, int y) {
        int max = world.getMaxHeight() - 1;
        int min = 0;
        try {
            min = world.getMinHeight();
        } catch (Throwable ignored) {
            // getMinHeight() only exists on 1.17+; 0 is correct for older versions.
        }
        return Math.max(min, Math.min(max, y));
    }
}
