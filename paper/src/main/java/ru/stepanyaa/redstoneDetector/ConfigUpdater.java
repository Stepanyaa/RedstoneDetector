package ru.stepanyaa.redstoneDetector;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class ConfigUpdater {

    public static final String VERSION_KEY = "config-version";

    private ConfigUpdater() {
    }

    public static boolean update(Plugin plugin, String resourcePath, java.io.File file) {
        if (plugin.getResource(resourcePath) == null) {
            return false;
        }

        if (!file.exists()) {
            try {
                if (file.getParentFile() != null) {
                    file.getParentFile().mkdirs();
                }
                try (InputStream fresh = plugin.getResource(resourcePath)) {
                    Files.copy(fresh, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create " + file.getName() + ": " + e.getMessage());
            }
            return true;
        }

        YamlConfiguration def;
        try (Reader reader = new InputStreamReader(plugin.getResource(resourcePath), StandardCharsets.UTF_8)) {
            def = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read bundled " + resourcePath + ": " + e.getMessage());
            return false;
        }

        String pluginVersion = plugin.getDescription().getVersion();
        FileConfiguration current = YamlConfiguration.loadConfiguration(file);
        String fileVersion = current.getString(VERSION_KEY, null);

        if (pluginVersion.equals(fileVersion)) {
            return false;
        }

        int added = 0;
        for (String key : def.getKeys(true)) {
            if (def.isConfigurationSection(key)) {
                continue;
            }
            if (VERSION_KEY.equals(key)) {
                continue;
            }
            if (!current.contains(key)) {
                current.set(key, def.get(key));
                added++;
            }
        }
        current.set(VERSION_KEY, pluginVersion);

        try {
            current.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save updated " + file.getName() + ": " + e.getMessage());
            return false;
        }

        plugin.getLogger().info("Updated " + file.getName() + " to version " + pluginVersion
                + (added > 0 ? " (added " + added + " new key" + (added == 1 ? "" : "s") + ")" : ""));
        return true;
    }
}
