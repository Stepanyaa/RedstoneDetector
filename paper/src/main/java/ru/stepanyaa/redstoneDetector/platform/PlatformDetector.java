package ru.stepanyaa.redstoneDetector.platform;

import java.util.Locale;
import java.util.logging.Logger;

public final class PlatformDetector {

    private static final String FOLIA_MARKER =
            "io.papermc.paper.threadedregions.RegionizedServer";

    private static final String FOLIA_SCHEDULER_MARKER =
            "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler";

    private static final String PURPUR_MARKER = "org.purpurmc.purpur.PurpurConfig";

    private static final String PURPUR_LEGACY_MARKER = "net.pl3x.purpur.PurpurConfig";

    private static final String PAPER_MARKER = "io.papermc.paper.configuration.Configuration";

    private static final String PAPER_LEGACY_MARKER = "com.destroystokyo.paper.PaperConfig";

    private static final String SPIGOT_MARKER = "org.spigotmc.SpigotConfig";

    private static final String BUKKIT_MARKER = "org.bukkit.Bukkit";

    private static volatile PlatformType detected;

    private PlatformDetector() {
    }

    public static PlatformType detect() {
        PlatformType cached = detected;
        if (cached != null) {
            return cached;
        }
        synchronized (PlatformDetector.class) {
            if (detected == null) {
                detected = probe();
            }
            return detected;
        }
    }

    private static PlatformType probe() {
        if (present(FOLIA_MARKER) || present(FOLIA_SCHEDULER_MARKER)) {
            return PlatformType.FOLIA;
        }
        if (present(PURPUR_MARKER) || present(PURPUR_LEGACY_MARKER)) {
            return PlatformType.PURPUR;
        }
        if (present(PAPER_MARKER) || present(PAPER_LEGACY_MARKER)) {
            return PlatformType.PAPER;
        }
        if (present(SPIGOT_MARKER)) {
            return PlatformType.SPIGOT;
        }
        if (present(BUKKIT_MARKER)) {
            return PlatformType.BUKKIT;
        }
        return PlatformType.UNKNOWN;
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, PlatformDetector.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void logDetection(Logger logger, PlatformType type, String brand) {
        logger.info("Detected platform: " + type.displayName());
        if (type == PlatformType.FOLIA
                && !brand.toLowerCase(Locale.ROOT).contains("folia")) {
            logger.info("Server brand: " + brand
                    + " (Folia reports itself as Paper; detected by its regionised classes)");
        } else {
            logger.info("Server brand: " + brand);
        }
        if (type.isRegionised()) {
            logger.info("Regionised threading detected - using the global, region, "
                    + "entity and async schedulers.");
        }
        if (type == PlatformType.UNKNOWN) {
            logger.warning("Unrecognised server core. Falling back to the generic "
                    + "Bukkit scheduler.");
        }
    }
}
