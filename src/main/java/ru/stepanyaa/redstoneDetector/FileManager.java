/**
 * MIT License
 *
 * RedstoneDetector
 * Copyright (c) 2025 Stepanyaa
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction.
 */
package ru.stepanyaa.redstoneDetector;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public class FileManager {
    private final RedstoneDetector plugin;

    private FileConfiguration performance;
    private FileConfiguration blocks;
    private FileConfiguration gui;
    private FileConfiguration messages;

    public FileManager(RedstoneDetector plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        performance = load("performance.yml");
        blocks = load("blocks.yml");
        gui = load("gui.yml");
        messages = load("messages.yml");
    }

    private FileConfiguration load(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            try {
                plugin.saveResource(name, false);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Bundled resource " + name + " not found in jar.");
            }
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
        return messages;
    }

    public String getMessage(String key, String def) {
        if (messages == null) return def;
        return messages.getString(key, def);
    }
}
