/**
 * MIT License
 *
 * RedstoneDetector
 * Copyright (c) 2025 Stepanyaa
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
    private boolean scanLoadedChunks = true;
    private long chunkDataRetentionHours = 24;

    private long statsCacheMs = 1000;
    private long scanCooldownMs = 3000;
    private int heavyScanRadius = 10000;
    private int heavyScanChunksPerTickBase = 5;

    public ConfigManager(RedstoneDetector plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        FileConfiguration config = plugin.getConfig();

        criticalTPS = config.getDouble("critical-tps", 15.0);
        maxRedstone = config.getInt("max-redstone", 100);
        maxEntities = config.getInt("max-entities", 100);
        freezeDuration = config.getInt("freeze-duration", 300);
        chunkDataRetentionHours = config.getLong("chunk-data-retention", 24);

        FileConfiguration perf = plugin.getFileManager() != null
                ? plugin.getFileManager().getPerformance() : null;
        chunksPerTick = readInt(perf, config, "chunks-per-tick", 3);
        scanOnLowTPS = readBoolean(perf, config, "scan-on-low-tps", true);
        scanLoadedChunks = readBoolean(perf, config, "scan-loaded-chunks", true);
        heavyScanRadius = readInt(perf, config, "heavy-scan-radius", 10000);
        heavyScanChunksPerTickBase = readInt(perf, config, "heavy-scan-chunks-per-tick-base", 5);
        statsCacheMs = perf != null ? perf.getLong("stats-cache-ms", 1000) : 1000;
        scanCooldownMs = perf != null ? perf.getLong("scan-cooldown-ms", 3000) : 3000;
    }

    private int readInt(FileConfiguration primary, FileConfiguration fallback, String key, int def) {
        if (primary != null && primary.contains(key)) return primary.getInt(key, def);
        return fallback.getInt(key, def);
    }

    private boolean readBoolean(FileConfiguration primary, FileConfiguration fallback, String key, boolean def) {
        if (primary != null && primary.contains(key)) return primary.getBoolean(key, def);
        return fallback.getBoolean(key, def);
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

    public boolean isScanLoadedChunks() {
        return scanLoadedChunks;
    }

    public long getStatsCacheMs() {
        return statsCacheMs;
    }

    public long getScanCooldownMs() {
        return scanCooldownMs;
    }

    public int getHeavyScanRadius() {
        return heavyScanRadius;
    }

    public int getHeavyScanChunksPerTickBase() {
        return heavyScanChunksPerTickBase;
    }
}
