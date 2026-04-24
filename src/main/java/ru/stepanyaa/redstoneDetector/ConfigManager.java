/**
 * MIT License
 *
 * RedstoneDetector
 * Copyright (c) 2026 Stepanyaa
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package ru.stepanyaa.redstoneDetector;

import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {
    private final RedstoneDetector plugin;
    
    private double criticalTPS = 15.0;
    private int maxRedstone = 100;
    private int maxEntities = 100;
    private int freezeDuration = 300;
    private int chunksPerTick = 3;
    private boolean scanOnLowTPS = true;
    private long chunkDataRetentionHours = 24;

    public ConfigManager(RedstoneDetector plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        FileConfiguration config = plugin.getConfig();
        
        criticalTPS = config.getDouble("critical-tps", 15.0);
        maxRedstone = config.getInt("max-redstone", 100);
        maxEntities = config.getInt("max-entities", 100);
        freezeDuration = config.getInt("freeze-duration", 300);
        chunksPerTick = config.getInt("chunks-per-tick", 3);
        scanOnLowTPS = config.getBoolean("scan-on-low-tps", true);
        chunkDataRetentionHours = config.getLong("chunk-data-retention", 24);
    }

    public double getCriticalTPS() {
        return criticalTPS;
    }

    public int getMaxRedstone() {
        return maxRedstone;
    }

    public int getMaxEntities() {
        return maxEntities;
    }

    public int getFreezeDuration() {
        return freezeDuration;
    }

    public int getChunksPerTick() {
        return chunksPerTick;
    }

    public boolean isScanOnLowTPS() {
        return scanOnLowTPS;
    }

    public long getChunkDataRetentionHours() {
        return chunkDataRetentionHours;
    }
}
