package ru.stepanyaa.redstoneDetector;

import org.bukkit.ChatColor;

import java.util.logging.Logger;

public final class LogUtil {

    private LogUtil() {
    }

    public static void info(RedstoneDetector plugin, String message) {
        log(plugin, message, false, false);
    }

    public static void warn(RedstoneDetector plugin, String message) {
        log(plugin, message, true, false);
    }

    public static void severe(RedstoneDetector plugin, String message) {
        log(plugin, message, false, true);
    }

    private static void log(RedstoneDetector plugin, String message, boolean warn, boolean severe) {
        Logger logger = plugin.getLogger();
        String clean = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', message));
        if (severe) {
            logger.severe(clean);
        } else if (warn) {
            logger.warning(clean);
        } else {
            logger.info(clean);
        }
    }

    public static void banner(RedstoneDetector plugin, String title, String... lines) {
        Logger logger = plugin.getLogger();
        logger.info("==================================================");
        logger.info("  " + ChatColor.stripColor(title));
        for (String line : lines) {
            logger.info("  " + ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', line)));
        }
        logger.info("==================================================");
    }
}
