package ru.stepanyaa.redstoneDetector.platform;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public final class Platforms {

    private static volatile PlatformType type = PlatformType.UNKNOWN;
    private static volatile SchedulerBackend scheduler;

    private Platforms() {
    }

    public static void install(JavaPlugin plugin) {
        PlatformType detected = PlatformDetector.detect();
        PlatformDetector.logDetection(plugin.getLogger(), detected, describeServer());

        SchedulerBackend backend = null;
        if (detected.isRegionised()) {
            try {
                backend = new FoliaSchedulerBackend(plugin);
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Regionised schedulers unavailable ("
                        + throwable + "); falling back to the Bukkit scheduler.");
            }
        }
        if (backend == null) {
            backend = new BukkitSchedulerBackend(plugin);
        }
        type = detected;
        scheduler = backend;
    }

    public static SchedulerBackend scheduler() {
        SchedulerBackend current = scheduler;
        if (current == null) {
            throw new IllegalStateException(
                    "Scheduler used before the platform layer was installed.");
        }
        return current;
    }

    public static PlatformType type() {
        return type;
    }

    public static String displayName() {
        return type.displayName();
    }

    public static boolean isFolia() {
        return type == PlatformType.FOLIA;
    }

    public static boolean isRegionised() {
        return type.isRegionised();
    }

    private static volatile boolean ownershipChecked;
    private static volatile java.lang.reflect.Method ownsChunkMethod;

    public static boolean ownsChunk(World world, int chunkX, int chunkZ) {
        if (world == null) {
            return false;
        }
        if (!isRegionised()) {
            return true;
        }
        if (!ownershipChecked) {
            synchronized (Platforms.class) {
                if (!ownershipChecked) {
                    try {
                        ownsChunkMethod = Bukkit.class.getMethod("isOwnedByCurrentRegion",
                                World.class, int.class, int.class);
                    } catch (Throwable ignored) {
                        ownsChunkMethod = null;
                    }
                    ownershipChecked = true;
                }
            }
        }
        java.lang.reflect.Method method = ownsChunkMethod;
        if (method == null) {

            return true;
        }
        try {
            return Boolean.TRUE.equals(method.invoke(null, world,
                    Integer.valueOf(chunkX), Integer.valueOf(chunkZ)));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void shutdown() {
        SchedulerBackend current = scheduler;
        if (current != null) {
            try {
                current.cancelAll();
            } catch (Throwable ignored) {
            }
        }
        scheduler = null;
    }

    private static String describeServer() {
        try {
            return Bukkit.getName() + " " + Bukkit.getVersion();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
