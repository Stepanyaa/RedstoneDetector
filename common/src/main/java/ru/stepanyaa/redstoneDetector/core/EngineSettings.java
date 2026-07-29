package ru.stepanyaa.redstoneDetector.core;

public class EngineSettings {

    public int maxRedstone = 100;
    public int maxEntities = 100;
    public int updateScoreThreshold = 200;

    public double criticalTps = 15.0;
    public double serverMsptThreshold = 45.0;
    public double chunkMsptThreshold = 8.0;
    public double baselineMspt = 20.0;

    public boolean intelligentFreeze = true;
    public int autoFreezeUpdatesPerSec = 800;
    public int lowTpsFreezeUpdatesPerSec = 300;
    public int autoUnfreezeSeconds = 60;
    public int sustainedLagSeconds = 3;
    public int culpritMinUpdatesPerSec = 400;
    public int culpritMinMechanisms = 30;
    public int globalFreezeAfterSeconds = 10;
    public boolean globalFreezeEnabled = true;

    public int chunksPerTick = 3;
    public int chunkDataRetentionHours = 24;

    public double detectorSensitivity = 1.0;
    public boolean sculkDetectorEnabled = true;
    public boolean trapdoorDetectorEnabled = true;
    public boolean autoUnfreezeEnabled = true;
    public boolean debugLogging = false;

    public int freezeRadius = 0;
    public int maxFreezeSeconds = 300;
    public int guiRefreshTicks = 20;
    public int scanIntervalTicks = 20;

    public int sculkActivationsPerSecond = 60;
    public int sculkGroupSize = 6;
    public int sculkPositionRate = 5;
    public int sculkRepeatBurst = 30;
    public int sculkLoopSeconds = 5;
    public long sculkRepeatWindowMillis = 150L;

    public int trapdoorTogglesPerSecond = 80;
    public int trapdoorClusterSize = 8;
    public int trapdoorPositionRate = 6;
    public int trapdoorRepeatBurst = 40;
    public int trapdoorLoopSeconds = 4;
    public long trapdoorRepeatWindowMillis = 120L;

    public final java.util.Set<String> ignoredWorlds =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    public final java.util.Set<String> ignoredChunks =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    public final java.util.Set<String> ignoredBlocks =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
}
