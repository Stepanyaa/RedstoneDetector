package ru.stepanyaa.redstoneDetector.sponge;

import java.util.logging.Logger;

public final class SpongeLog {

    private SpongeLog() {
    }

    public static String strip(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(message.length());
        for (int index = 0; index < message.length(); index++) {
            char symbol = message.charAt(index);
            if ((symbol == '&' || symbol == '\u00a7') && index + 1 < message.length()
                    && isCode(message.charAt(index + 1))) {
                index++;
                continue;
            }
            out.append(symbol);
        }
        return out.toString();
    }

    private static boolean isCode(char symbol) {
        char lower = Character.toLowerCase(symbol);
        return (lower >= '0' && lower <= '9') || (lower >= 'a' && lower <= 'f')
                || "klmnorx".indexOf(lower) >= 0;
    }

    public static void info(Logger logger, String message) {
        logger.info(strip(message));
    }

    public static void warn(Logger logger, String message) {
        logger.warning(strip(message));
    }

    public static void severe(Logger logger, String message) {
        logger.severe(strip(message));
    }

    public static void banner(Logger logger, String title, String... lines) {
        logger.info("==================================================");
        logger.info("  " + strip(title));
        for (String line : lines) {
            logger.info("  " + strip(line));
        }
        logger.info("==================================================");
    }
}
