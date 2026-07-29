package ru.stepanyaa.redstoneDetector.sponge;

import ru.stepanyaa.redstoneDetector.ChunkCoordinate;
import ru.stepanyaa.redstoneDetector.core.ChunkScan;
import ru.stepanyaa.redstoneDetector.core.WorldAccess;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class SpongeWorldAccess implements WorldAccess {

    private static final String[] WORLD_TYPES = {
        "org.spongepowered.api.world.server.ServerWorld",
        "org.spongepowered.api.world.World",
        "org.spongepowered.api.world.volume.block.BlockVolume$Modifiable",
        "org.spongepowered.api.world.volume.block.BlockVolume"
    };

    private static final String[] CHUNK_TYPES = {
        "org.spongepowered.api.world.chunk.WorldChunk",
        "org.spongepowered.api.world.chunk.Chunk",
        "org.spongepowered.api.world.volume.entity.EntityVolume"
    };

    private static final String[] PLAYER_TYPES = {
        "org.spongepowered.api.entity.living.player.server.ServerPlayer",
        "org.spongepowered.api.entity.Entity"
    };

    private final SpongeConfig config;
    private final SpongeScheduler scheduler;
    private final Logger logger;

    private final Map<ChunkCoordinate, List<Object[]>> backups =
            new ConcurrentHashMap<ChunkCoordinate, List<Object[]>>();

    private final Map<ChunkCoordinate, Progress> pending =
            new ConcurrentHashMap<ChunkCoordinate, Progress>();

    private final Map<ChunkCoordinate, ChunkScan> finished =
            new ConcurrentHashMap<ChunkCoordinate, ChunkScan>();

    private static final java.util.Set<String> RESTORE_DEFAULT_STATE =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR", "SCULK_SHRIEKER"));

    private final Map<ChunkCoordinate, int[]> mechanismRange =
            new ConcurrentHashMap<ChunkCoordinate, int[]>();

    private volatile Object airState;

    private Object restoreState(String name, Object state) {
        if (!RESTORE_DEFAULT_STATE.contains(name)) {
            return state;
        }
        try {
            Object type = SpongeApi.callOrNull(state, "type");
            Object clean = type == null ? null : SpongeApi.callOrNull(type, "defaultState");
            return clean == null ? state : clean;
        } catch (Throwable unavailable) {
            return state;
        }
    }

    private void rememberMechanismY(ChunkCoordinate coord, int y) {
        int[] range = mechanismRange.get(coord);
        if (range == null) {
            mechanismRange.put(coord, new int[]{y, y});
            return;
        }
        if (y < range[0]) {
            range[0] = y;
        }
        if (y > range[1]) {
            range[1] = y;
        }
    }

    public SpongeWorldAccess(SpongeConfig config, SpongeScheduler scheduler, Logger logger) {
        this.config = config;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    private static final class Progress {
        private int x;
        private int z;
        private int y;
        private boolean started;
        private ChunkScan scan = new ChunkScan();
    }

    private Object worldManager() throws ReflectiveOperationException {
        return SpongeApi.call(SpongeApi.server(), "worldManager");
    }

    private Iterable<?> worlds() {
        try {
            return SpongeApi.iterable(SpongeApi.call(worldManager(), "worlds"));
        } catch (Throwable failure) {
            return new ArrayList<Object>();
        }
    }

    private String nameOf(Object world) {
        Object key = SpongeApi.callOrNull(world, "key");
        return key == null ? "world" : String.valueOf(key);
    }

    private Object worldByName(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.trim();
        Object firstWorld = null;
        for (Object world : worlds()) {
            if (firstWorld == null) {
                firstWorld = world;
            }
            String key = nameOf(world);
            if (key.equalsIgnoreCase(wanted)) {
                return world;
            }
            int colon = key.indexOf(':');
            if (colon >= 0 && key.substring(colon + 1).equalsIgnoreCase(wanted)) {
                return world;
            }
        }
        return null;
    }

    @Override
    public List<String> worldNames() {
        List<String> names = new ArrayList<String>();
        for (Object world : worlds()) {
            names.add(nameOf(world));
        }
        return names;
    }

    @Override
    public List<ChunkCoordinate> loadedChunks(String worldName) {
        List<ChunkCoordinate> found = new ArrayList<ChunkCoordinate>();
        Object world = worldByName(worldName);
        if (world == null) {
            return found;
        }
        String key = nameOf(world);
        try {
            for (Object chunk : SpongeApi.iterable(SpongeApi.call(world, "loadedChunks"))) {
                Object position = SpongeApi.callOrNull(chunk, "chunkPosition");
                if (position == null) {
                    continue;
                }
                int x = SpongeApi.integer(SpongeApi.callOrNull(position, "x"), 0);
                int z = SpongeApi.integer(SpongeApi.callOrNull(position, "z"), 0);
                found.add(new ChunkCoordinate(key, x, z));
            }
        } catch (Throwable failure) {
            logger.warning("Could not list loaded chunks of " + worldName + ": "
                    + SpongeApi.describe(failure));
        }
        return found;
    }

    private int lowestY(Object world) {
        Object min = SpongeApi.callOrNull(world, "blockMin");
        if (min != null) {
            return SpongeApi.integer(SpongeApi.callOrNull(min, "y"), 0);
        }
        return 0;
    }

    private int highestY(Object world) {
        Object max = SpongeApi.callOrNull(world, "blockMax");
        if (max != null) {
            return SpongeApi.integer(SpongeApi.callOrNull(max, "y"), 255);
        }
        return 255;
    }

    private Method blockGetter(Object world) throws NoSuchMethodException {
        return SpongeApi.apiMethod(world, WORLD_TYPES, "block",
                int.class, int.class, int.class);
    }

    private Method blockSetter(Object world) throws ReflectiveOperationException {
        return SpongeApi.apiMethod(world, WORLD_TYPES, "setBlock",
                int.class, int.class, int.class,
                SpongeApi.type("org.spongepowered.api.block.BlockState"));
    }

    private Object air() throws ReflectiveOperationException {
        Object cached = airState;
        if (cached != null) {
            return cached;
        }
        Object reference = SpongeApi.type("org.spongepowered.api.block.BlockTypes")
                .getField("AIR").get(null);
        Object type = SpongeApi.call(reference, "get");
        Object state = SpongeApi.call(type, "defaultState");
        airState = state;
        return state;
    }

    @Override
    public ChunkScan scanChunk(ChunkCoordinate coord) {
        Object world = worldByName(coord.world());
        if (world == null) {
            pending.remove(coord);
            ChunkScan previous = finished.remove(coord);
            return previous == null ? new ChunkScan() : previous;
        }

        Progress progress = pending.get(coord);
        if (progress == null) {
            progress = new Progress();
            progress.y = lowestY(world);
            pending.put(coord, progress);
        }

        long deadline = System.nanoTime() + config.scanBudgetNanos();
        int minY = lowestY(world);
        int maxY = highestY(world);
        int baseX = coord.x() << 4;
        int baseZ = coord.z() << 4;

        try {
            Method getter = blockGetter(world);
            progress.started = true;
            int sinceCheck = 0;

            while (progress.x < 16) {
                Object state = getter.invoke(world, Integer.valueOf(baseX + progress.x),
                        Integer.valueOf(progress.y), Integer.valueOf(baseZ + progress.z));
                if (state != null) {
                    String name = SpongeApi.registryName(state);
                    if (!name.equals("AIR") && !name.equals("UNKNOWN")
                            && config.isMechanism(name)) {
                        progress.scan.addRedstone(name);

                        rememberMechanismY(coord, progress.y);
                    }
                }

                progress.y++;
                if (progress.y > maxY) {
                    progress.y = minY;
                    progress.z++;
                    if (progress.z >= 16) {
                        progress.z = 0;
                        progress.x++;
                    }
                }

                if (++sinceCheck >= 256) {
                    sinceCheck = 0;
                    if (System.nanoTime() >= deadline) {
                        ChunkScan previous = finished.get(coord);
                        return previous == null ? progress.scan : previous;
                    }
                }
            }

            countEntities(world, coord, progress.scan);
        } catch (Throwable failure) {
            logger.warning("Chunk scan failed at " + coord + ": " + SpongeApi.describe(failure));
            pending.remove(coord);
            ChunkScan previous = finished.get(coord);
            return previous == null ? new ChunkScan() : previous;
        }

        ChunkScan complete = progress.scan;
        pending.remove(coord);
        finished.put(coord, complete);
        return complete;
    }

    public boolean isScanPending(ChunkCoordinate coord) {
        Progress progress = pending.get(coord);
        return progress != null && progress.started;
    }

    public void forget(ChunkCoordinate coord) {
        pending.remove(coord);
        finished.remove(coord);
        mechanismRange.remove(coord);
    }

    private void countEntities(Object world, ChunkCoordinate coord, ChunkScan scan) {
        Object chunk = chunkAt(world, coord);
        if (chunk == null) {
            return;
        }
        try {
            for (Object entity : SpongeApi.iterable(SpongeApi.call(chunk, "entities"))) {
                scan.addEntity(SpongeApi.registryName(SpongeApi.callOrNull(entity, "type")));
            }
        } catch (Throwable failure) {

        }
    }

    private Object chunkAt(Object world, ChunkCoordinate coord) {
        try {
            Method chunkGetter = SpongeApi.apiMethod(world, WORLD_TYPES, "chunk",
                    int.class, int.class, int.class);
            return chunkGetter.invoke(world, Integer.valueOf(coord.x()), Integer.valueOf(0),
                    Integer.valueOf(coord.z()));
        } catch (Throwable failure) {
            return null;
        }
    }

    @Override
    public int removeRedstone(ChunkCoordinate coord) {
        Object world = worldByName(coord.world());
        if (world == null) {
            logger.warning("Freeze skipped: world " + coord.world() + " is not loaded.");
            return 0;
        }
        int cleared = 0;
        List<Object[]> backup = backups.get(coord);
        if (backup == null) {
            backup = new ArrayList<Object[]>();
        }
        try {
            Method getter = blockGetter(world);
            Method setter = blockSetter(world);
            Object airBlock = air();
            int baseX = coord.x() << 4;
            int baseZ = coord.z() << 4;
            int minY = lowestY(world);
            int maxY = highestY(world);

            int[] range = mechanismRange.get(coord);
            if (range != null) {
                minY = Math.max(minY, range[0] - 4);
                maxY = Math.min(maxY, range[1] + 4);
            }

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        int worldX = baseX + x;
                        int worldZ = baseZ + z;
                        Object state = getter.invoke(world, Integer.valueOf(worldX),
                                Integer.valueOf(y), Integer.valueOf(worldZ));
                        if (state == null) {
                            continue;
                        }
                        String name = SpongeApi.registryName(state);
                        if (!config.isMechanism(name)) {
                            continue;
                        }

                        backup.add(new Object[]{Integer.valueOf(worldX), Integer.valueOf(y),
                                Integer.valueOf(worldZ), restoreState(name, state)});
                        setter.invoke(world, Integer.valueOf(worldX), Integer.valueOf(y),
                                Integer.valueOf(worldZ), airBlock);
                        cleared++;
                    }
                }
            }
        } catch (Throwable failure) {
            logger.warning("Could not clear redstone at " + coord + ": "
                    + SpongeApi.describe(failure));
        }
        if (!backup.isEmpty()) {
            backups.put(coord, backup);
        }
        logger.info("Freeze at " + coord + ": cleared " + cleared + " mechanism blocks.");
        return cleared;
    }

    @Override
    public int restoreRedstone(ChunkCoordinate coord) {
        List<Object[]> backup = backups.remove(coord);
        if (backup == null || backup.isEmpty()) {
            return 0;
        }
        Object world = worldByName(coord.world());
        if (world == null) {
            backups.put(coord, backup);
            return 0;
        }
        int restored = 0;
        try {
            Method setter = blockSetter(world);
            for (Object[] entry : backup) {
                setter.invoke(world, entry[0], entry[1], entry[2], entry[3]);
                restored++;
            }
        } catch (Throwable failure) {
            logger.warning("Could not restore redstone at " + coord + ": "
                    + SpongeApi.describe(failure));
        }
        return restored;
    }

    public boolean hasBackup(ChunkCoordinate coord) {
        return backups.containsKey(coord);
    }

    @Override
    public int removeEntities(ChunkCoordinate coord) {
        Object world = worldByName(coord.world());
        if (world == null) {
            return 0;
        }
        Object chunk = chunkAt(world, coord);
        if (chunk == null) {
            return 0;
        }
        Class<?> playerClass =
                SpongeApi.typeOrNull("org.spongepowered.api.entity.living.player.Player");
        int removed = 0;
        try {
            List<Object> targets = new ArrayList<Object>();
            for (Object entity : SpongeApi.iterable(SpongeApi.call(chunk, "entities"))) {
                if (playerClass != null && playerClass.isInstance(entity)) {
                    continue;
                }
                targets.add(entity);
            }
            for (Object entity : targets) {
                Method remove = SpongeApi.apiMethod(entity,
                        new String[]{"org.spongepowered.api.entity.Entity"}, "remove");
                remove.invoke(entity);
                removed++;
            }
        } catch (Throwable failure) {
            logger.warning("Could not remove entities at " + coord + ": "
                    + SpongeApi.describe(failure));
        }
        return removed;
    }

    @Override
    public void runAtChunk(ChunkCoordinate coord, Runnable task) {

        if (scheduler.onMainThread()) {
            task.run();
        } else {
            scheduler.run(task);
        }
    }

    public boolean teleport(Object viewer, ChunkCoordinate coord) {
        Object player = SpongeViewers.player(viewer);
        Object world = worldByName(coord.world());
        if (world == null || player == null) {
            return false;
        }
        try {
            double x = (coord.x() << 4) + 8.5;
            double z = (coord.z() << 4) + 8.5;
            int y = 100;
            try {
                Method highest = SpongeApi.apiMethod(world, WORLD_TYPES, "highestYAt",
                        int.class, int.class);
                y = SpongeApi.integer(highest.invoke(world, Integer.valueOf((int) x),
                        Integer.valueOf((int) z)), 100);
            } catch (Throwable noHighest) {

            }
            Class<?> locationClass =
                    SpongeApi.type("org.spongepowered.api.world.server.ServerLocation");
            Method of = SpongeApi.method(locationClass, "of",
                    SpongeApi.type("org.spongepowered.api.world.server.ServerWorld"),
                    double.class, double.class, double.class);
            Object location = of.invoke(null, world, Double.valueOf(x),
                    Double.valueOf(y + 1.0), Double.valueOf(z));
            Method setLocation = SpongeApi.apiMethod(player, PLAYER_TYPES, "setLocation",
                    locationClass);
            setLocation.invoke(player, location);
            return true;
        } catch (Throwable failure) {
            logger.warning("Could not teleport to " + coord + ": "
                    + SpongeApi.describe(failure));
            return false;
        }
    }
}
