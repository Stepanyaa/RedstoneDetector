package ru.stepanyaa.redstoneDetector;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public class FileManager {
    private final RedstoneDetector plugin;

    private FileConfiguration performance;
    private FileConfiguration blocks;
    private FileConfiguration gui;

    public FileManager(RedstoneDetector plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        performance = load("performance.yml");
        blocks = load("blocks.yml");
        gui = load("gui.yml");
    }

    private FileConfiguration load(String name) {
        File file = new File(plugin.getDataFolder(), name);

        if (!file.exists() && plugin.getResource(name) != null) {
            plugin.saveResource(name, false);
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    public FileConfiguration getPerformance() {
        return performance;
    }

    public FileConfiguration getBlocks() {
        return blocks;
    }

    public FileConfiguration getGui() {
        return gui;
    }

    public FileConfiguration getMessages() {
        return plugin.getMessageManager() != null
                ? plugin.getMessageManager().getMessagesConfig()
                : null;
    }

    public String getMessage(String key, String def) {
        return plugin.getMessageManager() != null
                ? plugin.getMessageManager().getMessage(key, def)
                : def;
    }
}
