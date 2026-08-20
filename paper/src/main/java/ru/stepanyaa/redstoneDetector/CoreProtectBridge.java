package ru.stepanyaa.redstoneDetector;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class CoreProtectBridge {

    public static final int LOOKUP_SECONDS = 60 * 60 * 24 * 30;         public static final int HISTORY_LIMIT = 900;

    private final RedstoneDetector plugin;
    private Object api;     private boolean available;
    private boolean loggedUnavailable;

        private final Map<String, ChunkOwnerInfo> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 60_000L;

    public CoreProtectBridge(RedstoneDetector plugin) {
        this.plugin = plugin;
        this.available = false;
        this.api = null;
        tryHook();
    }

    public boolean isAvailable() {
        return available && api != null;
    }

    public void tryHook() {
        available = false;
        api = null;
        try {
            Plugin cp = Bukkit.getPluginManager().getPlugin("CoreProtect");
            if (cp == null || !cp.isEnabled()) {
                logOnce("CoreProtect not found – ownership features hidden.");
                return;
            }
                        Object coreProtect = cp;
            Object getApi = coreProtect.getClass().getMethod("getAPI").invoke(coreProtect);
            if (getApi == null) {
                logOnce("CoreProtect API is null.");
                return;
            }
            Boolean enabled = (Boolean) getApi.getClass().getMethod("isEnabled").invoke(getApi);
            if (enabled == null || !enabled) {
                logOnce("CoreProtect API is disabled in config.");
                return;
            }
            Integer version = (Integer) getApi.getClass().getMethod("APIVersion").invoke(getApi);
            if (version == null || version < 8) {
                logOnce("CoreProtect API version too old (need >= 8, got " + version + ").");
                return;
            }
            this.api = getApi;
            this.available = true;
            plugin.getLogger().info("CoreProtect integration enabled (API v" + version + ").");
        } catch (Throwable t) {
            logOnce("Failed to hook CoreProtect: " + t.getMessage());
            available = false;
            api = null;
        }
    }

    private void logOnce(String msg) {
        if (!loggedUnavailable) {
            plugin.getLogger().info(msg);
            loggedUnavailable = true;
        }
    }

            
    public static class ChunkOwnerInfo {
        public String possibleOwner = null;
        public String lastBuilder = null;
        public long lastModified = 0L;
        public String lastRedstonePlacer = null;
        public long lastRedstonePlacement = 0L;
        public final List<HistoryEntry> history = new ArrayList<>();
        public long fetchedAt = System.currentTimeMillis();
        public boolean success = false;

        public boolean isStale() {
            return System.currentTimeMillis() - fetchedAt > CACHE_TTL_MS;
        }
    }

    public static class HistoryEntry {
        public final String player;
        public final String action;         public final String material;
        public final long timestamp;
        public final int x, y, z;

        public HistoryEntry(String player, String action, String material, long timestamp, int x, int y, int z) {
            this.player = player;
            this.action = action;
            this.material = material;
            this.timestamp = timestamp;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

            
        public CompletableFuture<ChunkOwnerInfo> lookupChunkAsync(ChunkCoordinate coord) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(emptyInfo());
        }
        String key = coord.world() + ":" + coord.x() + ":" + coord.z();
        ChunkOwnerInfo cached = cache.get(key);
        if (cached != null && !cached.isStale() && cached.success) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                ChunkOwnerInfo info = performLookup(coord);
                if (info.success) {
                    cache.put(key, info);
                }
                return info;
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "CoreProtect lookup failed for " + key, t);
                return emptyInfo();
            }
        });
    }

    public void invalidate(ChunkCoordinate coord) {
        cache.remove(coord.world() + ":" + coord.x() + ":" + coord.z());
    }

    public void clearCache() {
        cache.clear();
    }

    private ChunkOwnerInfo emptyInfo() {
        ChunkOwnerInfo i = new ChunkOwnerInfo();
        i.success = false;
        return i;
    }

    private ChunkOwnerInfo performLookup(ChunkCoordinate coord) throws Exception {
        World world = Bukkit.getWorld(coord.world());
        if (world == null) return emptyInfo();

                int blockX = coord.x() * 16 + 8;
        int blockZ = coord.z() * 16 + 8;
        int y = Math.max(world.getMinHeight() + 1, Math.min(64, world.getMaxHeight() - 1));
        Location center = new Location(world, blockX, y, blockZ);

                int radius = 12;

                List<Integer> actions = new ArrayList<>();
        actions.add(0);
        actions.add(1);
        actions.add(2);

        @SuppressWarnings("unchecked")
        List<String[]> results = (List<String[]>) api.getClass()
                .getMethod("performLookup",
                        int.class,
                        List.class, List.class,
                        List.class, List.class,
                        List.class,
                        int.class,
                        Location.class)
                .invoke(api,
                        LOOKUP_SECONDS,
                        null, null,
                        null, null,
                        actions,
                        radius,
                        center);

        if (results == null || results.isEmpty()) {
            ChunkOwnerInfo empty = emptyInfo();
            empty.success = true;             return empty;
        }

                Map<String, Integer> placeCounts = new HashMap<>();
        Map<String, Long> lastPlaceTime = new HashMap<>();
        String lastBuilder = null;
        long lastModified = 0L;
        String lastRedstonePlacer = null;
        long lastRedstonePlacement = 0L;
        List<HistoryEntry> history = new ArrayList<>();

        Set<String> redstoneNames = buildRedstoneNameSet();

        for (String[] row : results) {
            Object parseResult = api.getClass().getMethod("parseResult", String[].class).invoke(api, (Object) row);
            if (parseResult == null) continue;

            String player = (String) parseResult.getClass().getMethod("getPlayer").invoke(parseResult);
            long ts = ((Number) parseResult.getClass().getMethod("getTimestamp").invoke(parseResult)).longValue();
                        if (ts < 1_000_000_000_000L) ts *= 1000L;

            int actionId = ((Number) parseResult.getClass().getMethod("getActionId").invoke(parseResult)).intValue();
            Material type = null;
            try {
                type = (Material) parseResult.getClass().getMethod("getType").invoke(parseResult);
            } catch (Throwable ignored) {}
            String matName = type != null ? type.name() : "UNKNOWN";

            int x = ((Number) parseResult.getClass().getMethod("getX").invoke(parseResult)).intValue();
            int yy = ((Number) parseResult.getClass().getMethod("getY").invoke(parseResult)).intValue();
            int z = ((Number) parseResult.getClass().getMethod("getZ").invoke(parseResult)).intValue();

                        int cx = Math.floorDiv(x, 16);
            int cz = Math.floorDiv(z, 16);
            if (cx != coord.x() || cz != coord.z()) continue;

            String actionStr = actionId == 1 ? "place" : (actionId == 0 ? "remove" : "interact");

            history.add(new HistoryEntry(player, actionStr, matName, ts, x, yy, z));

            if (ts > lastModified) {
                lastModified = ts;
                lastBuilder = player;
            }

            if (actionId == 1) {                 placeCounts.merge(player, 1, Integer::sum);
                lastPlaceTime.merge(player, ts, Math::max);

                if (isRedstoneMaterial(matName, redstoneNames) && ts > lastRedstonePlacement) {
                    lastRedstonePlacement = ts;
                    lastRedstonePlacer = player;
                }
            }
        }

                String possibleOwner = null;
        int maxPlaces = 0;
        for (Map.Entry<String, Integer> e : placeCounts.entrySet()) {
            if (e.getValue() > maxPlaces) {
                maxPlaces = e.getValue();
                possibleOwner = e.getKey();
            } else if (e.getValue() == maxPlaces && possibleOwner != null) {
                                long t1 = lastPlaceTime.getOrDefault(e.getKey(), 0L);
                long t2 = lastPlaceTime.getOrDefault(possibleOwner, 0L);
                if (t1 > t2) possibleOwner = e.getKey();
            }
        }

                history.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        if (history.size() > HISTORY_LIMIT) {
            history = new ArrayList<>(history.subList(0, HISTORY_LIMIT));
        }

        ChunkOwnerInfo info = new ChunkOwnerInfo();
        info.possibleOwner = possibleOwner;
        info.lastBuilder = lastBuilder;
        info.lastModified = lastModified;
        info.lastRedstonePlacer = lastRedstonePlacer;
        info.lastRedstonePlacement = lastRedstonePlacement;
        info.history.addAll(history);
        info.success = true;
        info.fetchedAt = System.currentTimeMillis();
        return info;
    }

    private Set<String> buildRedstoneNameSet() {
        Set<String> set = new HashSet<>();
                String[] names = {
                "REDSTONE_WIRE", "REDSTONE", "REPEATER", "COMPARATOR",
                "PISTON", "STICKY_PISTON", "MOVING_PISTON", "PISTON_HEAD",
                "OBSERVER", "DISPENSER", "DROPPER", "HOPPER",
                "REDSTONE_TORCH", "REDSTONE_WALL_TORCH", "REDSTONE_BLOCK",
                "LEVER", "STONE_BUTTON", "OAK_BUTTON", "SPRUCE_BUTTON",
                "BIRCH_BUTTON", "JUNGLE_BUTTON", "ACACIA_BUTTON", "DARK_OAK_BUTTON",
                "CRIMSON_BUTTON", "WARPED_BUTTON", "POLISHED_BLACKSTONE_BUTTON",
                "TRIPWIRE_HOOK", "TRIPWIRE", "TARGET",
                "SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR",
                "DAYLIGHT_DETECTOR", "NOTE_BLOCK", "TNT",
                "POWERED_RAIL", "DETECTOR_RAIL", "ACTIVATOR_RAIL",
                "TRAPPED_CHEST", "LIGHT_WEIGHTED_PRESSURE_PLATE",
                "HEAVY_WEIGHTED_PRESSURE_PLATE", "STONE_PRESSURE_PLATE",
                "OAK_PRESSURE_PLATE", "SPRUCE_PRESSURE_PLATE", "BIRCH_PRESSURE_PLATE",
                "JUNGLE_PRESSURE_PLATE", "ACACIA_PRESSURE_PLATE", "DARK_OAK_PRESSURE_PLATE",
                "CRIMSON_PRESSURE_PLATE", "WARPED_PRESSURE_PLATE",
                "POLISHED_BLACKSTONE_PRESSURE_PLATE"
        };
        Collections.addAll(set, names);
                try {
            if (plugin.getFileManager() != null && plugin.getFileManager().getBlocks() != null) {
                for (String n : plugin.getFileManager().getBlocks().getStringList("redstone-blocks")) {
                    if (n != null) set.add(n.trim().toUpperCase());
                }
                for (String n : plugin.getFileManager().getBlocks().getStringList("optional-blocks")) {
                    if (n != null) set.add(n.trim().toUpperCase());
                }
            }
        } catch (Throwable ignored) {}
        return set;
    }

    private boolean isRedstoneMaterial(String name, Set<String> set) {
        if (name == null) return false;
        String u = name.toUpperCase();
        if (set.contains(u)) return true;
                return u.contains("REDSTONE") || u.contains("REPEATER") || u.contains("COMPARATOR")
                || u.contains("PISTON") || u.contains("OBSERVER") || u.contains("HOPPER")
                || u.contains("DROPPER") || u.contains("DISPENSER") || u.contains("SCULK_SENSOR")
                || u.contains("PRESSURE_PLATE") || u.contains("BUTTON") || u.equals("LEVER")
                || u.equals("TARGET") || u.equals("NOTE_BLOCK") || u.equals("DAYLIGHT_DETECTOR");
    }

        public String formatTimeAgo(org.bukkit.entity.Player player, long timestampMs) {
        if (timestampMs <= 0) {
            return plugin.getMessage(player, "gui.chunkinfo.unknown", "unknown");
        }
        long agoSec = (System.currentTimeMillis() - timestampMs) / 1000L;
        if (agoSec < 60) {
            return plugin.getMessage(player, "gui.time_just_now", "Just now");
        }
        if (agoSec < 3600) {
            return (agoSec / 60) + plugin.getMessage(player, "gui.time_minutes_ago", " minutes ago");
        }
        if (agoSec < 86400) {
            return (agoSec / 3600) + plugin.getMessage(player, "gui.time_hours_ago", " hours ago");
        }
        long days = agoSec / 86400;
        if (days == 1) {
            return plugin.getMessage(player, "gui.time_yesterday", "Yesterday");
        }
        return days + plugin.getMessage(player, "gui.time_days_ago", " days ago");
    }
}
