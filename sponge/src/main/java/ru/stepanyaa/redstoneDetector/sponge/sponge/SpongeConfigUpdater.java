package ru.stepanyaa.redstoneDetector.sponge;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

public final class SpongeConfigUpdater {

    public static final String VERSION_KEY = "config-version";

    private SpongeConfigUpdater() {
    }

    public static boolean update(File folder, String resource, String version, Logger logger) {
        String bundled = read(resource);
        if (bundled == null) {
            return false;
        }
        File target = new File(folder, resource);
        if (!target.exists()) {
            return write(target, bundled, logger);
        }

        String current;
        try {
            current = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            logger.warning("Could not read " + resource + ": " + failure);
            return false;
        }

        Set<String> present = topLevelKeys(current);
        List<String> missing = new ArrayList<String>();
        int added = 0;
        for (Block block : blocks(bundled)) {
            if (VERSION_KEY.equals(block.key) || present.contains(block.key)) {
                continue;
            }
            missing.add(block.text);
            added++;
        }

        String merged = current;
        if (added > 0) {
            StringBuilder builder = new StringBuilder(current);
            if (!current.endsWith("\n")) {
                builder.append('\n');
            }
            builder.append("\n# ---------------------------------------------------------\n");
            builder.append("# Options added by RedstoneDetector ").append(version).append('\n');
            builder.append("# ---------------------------------------------------------\n");
            for (String block : missing) {
                builder.append(block);
                if (!block.endsWith("\n")) {
                    builder.append('\n');
                }
            }
            merged = builder.toString();
        }

        String stamped = stamp(merged, version);
        if (stamped.equals(current)) {
            return false;
        }
        if (!write(target, stamped, logger)) {
            return false;
        }
        logger.info("Updated " + resource + " to version " + version
                + (added > 0 ? " (added " + added + " new option" + (added == 1 ? "" : "s") + ")"
                : ""));
        return true;
    }

    public static boolean saveDefault(File folder, String resource, Logger logger) {
        File target = new File(folder, resource);
        if (target.exists()) {
            return false;
        }
        InputStream stream = SpongeConfigUpdater.class.getClassLoader()
                .getResourceAsStream(resource);
        if (stream == null) {
            return false;
        }
        try {
            if (target.getParentFile() != null) {
                target.getParentFile().mkdirs();
            }
            Files.copy(stream, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException failure) {
            logger.warning("Could not create " + resource + ": " + failure);
            return false;
        } finally {
            close(stream);
        }
    }

    public static String read(String resource) {
        InputStream stream = SpongeConfigUpdater.class.getClassLoader()
                .getResourceAsStream(resource);
        if (stream == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
            return text.toString();
        } catch (IOException failure) {
            return null;
        } finally {
            close(reader);
            close(stream);
        }
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {

        }
    }

    private static boolean write(File target, String content, Logger logger) {
        try {
            if (target.getParentFile() != null) {
                target.getParentFile().mkdirs();
            }
            Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException failure) {
            logger.warning("Could not save " + target.getName() + ": " + failure);
            return false;
        }
    }

    private static String stamp(String content, String version) {
        String[] lines = content.split("\n", -1);
        boolean found = false;
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (!found && line.startsWith(VERSION_KEY + ":")) {
                builder.append(VERSION_KEY).append(": \"").append(version).append('"');
                found = true;
            } else {
                builder.append(line);
            }
            if (index < lines.length - 1) {
                builder.append('\n');
            }
        }
        String result = builder.toString();
        if (found) {
            return result;
        }
        if (!result.endsWith("\n")) {
            result = result + "\n";
        }
        return result + "\n# Internal version stamp - do not edit.\n"
                + VERSION_KEY + ": \"" + version + "\"\n";
    }

    private static Set<String> topLevelKeys(String content) {
        Set<String> keys = new LinkedHashSet<String>();
        for (String line : content.split("\n", -1)) {
            String key = keyOf(line);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static String keyOf(String line) {
        if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '\t'
                || line.charAt(0) == '#' || line.charAt(0) == '-') {
            return null;
        }
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String key = line.substring(0, colon).trim();
        if (key.isEmpty() || key.indexOf(' ') >= 0) {
            return null;
        }
        return key;
    }

    private static List<Block> blocks(String content) {
        List<Block> blocks = new ArrayList<Block>();
        String[] lines = content.split("\n", -1);
        List<String> comments = new ArrayList<String>();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String key = keyOf(line);
            if (key == null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    comments.clear();
                } else if (trimmed.charAt(0) == '#') {
                    comments.add(line);
                }
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (String comment : comments) {
                text.append(comment).append('\n');
            }
            comments.clear();
            text.append(line).append('\n');

            int look = index + 1;
            while (look < lines.length) {
                String next = lines[look];
                if (next.isEmpty()) {
                    break;
                }
                char first = next.charAt(0);
                if (first != ' ' && first != '\t' && first != '-') {
                    break;
                }
                text.append(next).append('\n');
                look++;
            }
            index = look - 1;
            blocks.add(new Block(key, text.toString()));
        }
        return blocks;
    }

    private static final class Block {
        private final String key;
        private final String text;

        private Block(String key, String text) {
            this.key = key;
            this.text = text;
        }
    }
}
