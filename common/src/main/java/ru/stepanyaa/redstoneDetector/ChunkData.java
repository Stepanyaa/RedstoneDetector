package ru.stepanyaa.redstoneDetector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ChunkData {
    public AtomicInteger redstoneCount = new AtomicInteger(0);
    public AtomicInteger entityCount = new AtomicInteger(0);
    public long firstDetected = System.currentTimeMillis();
    public long lastScanned = System.currentTimeMillis();

    public volatile boolean scanned = false;
    public boolean clearedByAdmin = false;
    public long clearedTime = 0;
    public final Map<String, AtomicInteger> redstoneTypes = new ConcurrentHashMap<>();
    public final Map<String, AtomicInteger> entityTypes = new ConcurrentHashMap<>();

    public final AtomicInteger physicsWindow = new AtomicInteger(0);
    public final AtomicInteger redstoneWindow = new AtomicInteger(0);
    public final AtomicInteger pistonWindow = new AtomicInteger(0);
    public final AtomicInteger comparatorWindow = new AtomicInteger(0);
    public final AtomicInteger observerWindow = new AtomicInteger(0);
    public final AtomicInteger repeaterWindow = new AtomicInteger(0);
    public final AtomicInteger neighborWindow = new AtomicInteger(0);

    public volatile int updatesPerSec = 0;
    public volatile int physicsPerSec = 0;
    public volatile int redstonePerSec = 0;
    public volatile int pistonPerSec = 0;
    public volatile int comparatorPerSec = 0;
    public volatile int observerPerSec = 0;
    public volatile int repeaterPerSec = 0;
    public volatile int neighborPerSec = 0;

    public volatile int redstoneScore = 0;
    public volatile int entityScore = 0;
    public volatile int updateScore = 0;
    public volatile int lagScore = 0;
    public volatile String dangerLevel = "SAFE";
    public volatile String machineType = "none";
    public volatile long lastActivity = 0L;

    public volatile boolean autoFrozen = false;
    public volatile long autoFrozenSince = 0L;

    public final java.util.concurrent.atomic.AtomicLong nanosWindow = new java.util.concurrent.atomic.AtomicLong(0);

    public volatile double msptContribution = 0.0;

    public volatile int lagStreak = 0;

    public final AtomicInteger hopperWindow = new AtomicInteger(0);
    public final AtomicInteger sculkWindow = new AtomicInteger(0);
    public final AtomicInteger trapdoorWindow = new AtomicInteger(0);
    public final AtomicInteger scheduledWindow = new AtomicInteger(0);

    public volatile int hopperPerSec = 0;
    public volatile int sculkPerSec = 0;
    public volatile int trapdoorPerSec = 0;
    public volatile int scheduledPerSec = 0;

    public volatile int blockEntityCount = 0;
    public volatile int sculkSensorCount = 0;
    public volatile int trapdoorCount = 0;

    public volatile double impactScore = 0.0;
    public volatile String detectorType = "none";
    public volatile String detectorReason = "none";
    public volatile long detectedAt = 0L;
    public volatile boolean suspended = false;
    public volatile int suspendedBlocks = 0;
}
