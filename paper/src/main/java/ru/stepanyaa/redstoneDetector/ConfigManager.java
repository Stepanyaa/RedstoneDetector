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

    private boolean intelligentFreezeEnabled = true;
    private int updateScoreThreshold = 200;
    private int autoFreezeUpdatesPerSec = 800;
    private int lowTpsFreezeUpdatesPerSec = 300;
    private int autoUnfreezeSeconds = 60;

    private double serverMsptThreshold = 45.0;
    private double chunkMsptThreshold = 8.0;
    private int sustainedLagSeconds = 3;
    private double baselineMspt = 20.0;
    private int culpritMinUpdatesPerSec = 400;
    private int culpritMinMechanisms = 30;
    private int globalFreezeAfterSeconds = 10;
    private boolean globalFreezeEnabled = true;
    private boolean mergeAdjacentChunks = true;

    private boolean perPlayerLanguage = true;

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

        intelligentFreezeEnabled = readBoolean(perf, config, "intelligent-freeze-enabled", true);
        updateScoreThreshold = readInt(perf, config, "update-score-threshold", 200);
        autoFreezeUpdatesPerSec = readInt(perf, config, "auto-freeze-updates-per-second", 800);
        lowTpsFreezeUpdatesPerSec = readInt(perf, config, "low-tps-freeze-updates-per-second", 300);
        autoUnfreezeSeconds = readInt(perf, config, "auto-unfreeze-seconds", 60);

        serverMsptThreshold = readDouble(perf, config, "server-mspt-threshold", 45.0);
        chunkMsptThreshold = readDouble(perf, config, "chunk-mspt-threshold", 8.0);
        sustainedLagSeconds = readInt(perf, config, "sustained-lag-seconds", 3);
        baselineMspt = readDouble(perf, config, "baseline-mspt", 20.0);
        culpritMinUpdatesPerSec = readInt(perf, config, "culprit-min-updates-per-second", 400);
        culpritMinMechanisms = readInt(perf, config, "culprit-min-mechanisms", 30);
        globalFreezeAfterSeconds = readInt(perf, config, "global-freeze-after-seconds", 10);
        globalFreezeEnabled = readBoolean(perf, config, "global-freeze-enabled", true);
        mergeAdjacentChunks = readBoolean(perf, config, "merge-adjacent-chunks", true);
        perPlayerLanguage = readBoolean(perf, config, "per-player-language", true);
    }

    private int readInt(FileConfiguration primary, FileConfiguration fallback, String key, int def) {
        if (primary != null && primary.contains(key)) return primary.getInt(key, def);
        return fallback.getInt(key, def);
    }

    private boolean readBoolean(FileConfiguration primary, FileConfiguration fallback, String key, boolean def) {
        if (primary != null && primary.contains(key)) return primary.getBoolean(key, def);
        return fallback.getBoolean(key, def);
    }

    private double readDouble(FileConfiguration primary, FileConfiguration fallback, String key, double def) {
        if (primary != null && primary.contains(key)) return primary.getDouble(key, def);
        return fallback.getDouble(key, def);
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

    public boolean isIntelligentFreezeEnabled() {
        return intelligentFreezeEnabled;
    }

    public int getUpdateScoreThreshold() {
        return updateScoreThreshold;
    }

    public int getAutoFreezeUpdatesPerSec() {
        return autoFreezeUpdatesPerSec;
    }

    public int getLowTpsFreezeUpdatesPerSec() {
        return lowTpsFreezeUpdatesPerSec;
    }

    public int getAutoUnfreezeSeconds() {
        return autoUnfreezeSeconds;
    }

    public double getServerMsptThreshold() {
        return serverMsptThreshold;
    }

    public double getChunkMsptThreshold() {
        return chunkMsptThreshold;
    }

    public int getSustainedLagSeconds() {
        return sustainedLagSeconds;
    }

    public double getBaselineMspt() {
        return baselineMspt;
    }

    public int getCulpritMinUpdatesPerSec() {
        return culpritMinUpdatesPerSec;
    }

    public int getCulpritMinMechanisms() {
        return culpritMinMechanisms;
    }

    public int getGlobalFreezeAfterSeconds() {
        return globalFreezeAfterSeconds;
    }

    public boolean isGlobalFreezeEnabled() {
        return globalFreezeEnabled;
    }

    public boolean isPerPlayerLanguage() {
        return perPlayerLanguage;
    }

    public boolean isMergeAdjacentChunks() {
        return mergeAdjacentChunks;
    }
}
