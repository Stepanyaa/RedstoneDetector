package ru.stepanyaa.redstoneDetector.core;

import ru.stepanyaa.redstoneDetector.ActivityAnalyzer;
import ru.stepanyaa.redstoneDetector.ChunkCoordinate;
import ru.stepanyaa.redstoneDetector.ChunkData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class DetectorEngine {

    public static final int KIND_PHYSICS = 0;
    public static final int KIND_REDSTONE = 1;
    public static final int KIND_PISTON = 2;
    public static final int KIND_COMPARATOR = 3;
    public static final int KIND_OBSERVER = 4;
    public static final int KIND_REPEATER = 5;
    public static final int KIND_NEIGHBOR = 6;
    public static final int KIND_HOPPER = 7;
    public static final int KIND_SCULK = 8;
    public static final int KIND_TRAPDOOR = 9;
    public static final int KIND_SCHEDULED = 10;

    private static final double NANOS_PER_MILLI = 1000000.0;
    private static final double TICKS_PER_SECOND = 20.0;

    private final Map<ChunkCoordinate, ChunkData> chunks =
            new ConcurrentHashMap<ChunkCoordinate, ChunkData>();
    private final Map<ChunkCoordinate, Long> frozen =
            new ConcurrentHashMap<ChunkCoordinate, Long>();

    private final EngineSettings settings;

    private volatile double serverTps = 20.0;
    private volatile double serverMspt = 20.0;
    private volatile int laggingSeconds = 0;

    private final SculkSensorDetector sculkDetector = new SculkSensorDetector();
    private final TrapdoorDetector trapdoorDetector = new TrapdoorDetector();
    private final FreezeJournal journal = new FreezeJournal(64);

    public DetectorEngine(EngineSettings settings) {
        this.settings = settings;
    }

    public EngineSettings settings() {
        return settings;
    }

    public Map<ChunkCoordinate, ChunkData> chunks() {
        return chunks;
    }

    public ChunkData data(ChunkCoordinate coord) {
        ChunkData existing = chunks.get(coord);
        if (existing != null) {
            return existing;
        }
        ChunkData created = new ChunkData();
        ChunkData raced = chunks.putIfAbsent(coord, created);
        return raced == null ? created : raced;
    }

    public ChunkData peek(ChunkCoordinate coord) {
        return chunks.get(coord);
    }

    public SculkSensorDetector sculkDetector() {
        return sculkDetector;
    }

    public TrapdoorDetector trapdoorDetector() {
        return trapdoorDetector;
    }

    public FreezeJournal journal() {
        return journal;
    }

    public boolean isIgnored(ChunkCoordinate coord) {
        if (coord == null) {
            return true;
        }
        if (!settings.ignoredWorlds.isEmpty()) {
            String world = coord.world();
            if (settings.ignoredWorlds.contains(world)) {
                return true;
            }
            int colon = world.indexOf(':');
            if (colon >= 0 && settings.ignoredWorlds.contains(world.substring(colon + 1))) {
                return true;
            }
        }
        if (settings.ignoredChunks.isEmpty()) {
            return false;
        }
        return settings.ignoredChunks.contains(coord.toString())
                || settings.ignoredChunks.contains(coord.x() + "," + coord.z());
    }

    public void recordPosition(ChunkCoordinate coord, int kind, int x, int y, int z, long now) {
        if (kind == KIND_SCULK) {
            if (settings.sculkDetectorEnabled) {
                sculkDetector.record(coord, x, y, z, now);
            }
        } else if (kind == KIND_TRAPDOOR && settings.trapdoorDetectorEnabled) {
            trapdoorDetector.record(coord, x, y, z, now);
        }
    }

    public List<LagSignal> tickDetectors() {
        List<LagSignal> signals = new ArrayList<LagSignal>();
        if (settings.sculkDetectorEnabled) {
            sculkDetector.tickSecond(settings, signals);
        }
        if (settings.trapdoorDetectorEnabled) {
            trapdoorDetector.tickSecond(settings, signals);
        }
        for (LagSignal signal : signals) {
            ChunkData data = peek(signal.coord());
            if (data == null) {
                data = data(signal.coord());
            }
            data.detectorType = signal.detector();
            data.detectorReason = signal.reason();
            data.detectedAt = System.currentTimeMillis();
        }
        return signals;
    }

    public void forgetDetectors(ChunkCoordinate coord) {
        sculkDetector.forget(coord);
        trapdoorDetector.forget(coord);
    }

    public List<ChunkCoordinate> rankedByImpact(int limit) {
        List<ChunkCoordinate> found = new ArrayList<ChunkCoordinate>(chunks.keySet());
        final Map<ChunkCoordinate, ChunkData> snapshot = chunks;
        found.sort(new Comparator<ChunkCoordinate>() {
            @Override
            public int compare(ChunkCoordinate left, ChunkCoordinate right) {
                double first = impact(snapshot.get(right));
                double second = impact(snapshot.get(left));
                int order = Double.compare(first, second);
                return order != 0 ? order : left.toString().compareTo(right.toString());
            }
        });
        if (limit > 0 && found.size() > limit) {
            return new ArrayList<ChunkCoordinate>(found.subList(0, limit));
        }
        return found;
    }

    public static double impact(ChunkData data) {
        return data == null ? 0.0 : data.impactScore;
    }

    private static double computeImpact(ChunkData data) {
        double activity = data.redstonePerSec
                + data.pistonPerSec * 3.0
                + data.observerPerSec * 2.0
                + data.comparatorPerSec * 2.0
                + data.repeaterPerSec * 1.5
                + data.hopperPerSec * 4.0
                + data.sculkPerSec * 6.0
                + data.trapdoorPerSec * 3.0
                + data.scheduledPerSec * 2.0
                + data.neighborPerSec * 0.5
                + data.physicsPerSec * 0.5;
        double statics = data.blockEntityCount * 0.5 + data.entityCount.get() * 0.25;
        return data.msptContribution * 1000.0 + activity + statics;
    }

    public void recordUpdate(ChunkCoordinate coord, int kind) {
        ChunkData data = data(coord);
        data.lastActivity = System.currentTimeMillis();
        window(data, kind).incrementAndGet();
    }

    public void recordNanos(ChunkCoordinate coord, long nanos) {
        if (nanos > 0L) {
            data(coord).nanosWindow.addAndGet(nanos);
        }
    }

    private AtomicInteger window(ChunkData data, int kind) {
        switch (kind) {
            case KIND_REDSTONE: return data.redstoneWindow;
            case KIND_PISTON: return data.pistonWindow;
            case KIND_COMPARATOR: return data.comparatorWindow;
            case KIND_OBSERVER: return data.observerWindow;
            case KIND_REPEATER: return data.repeaterWindow;
            case KIND_NEIGHBOR: return data.neighborWindow;
            case KIND_HOPPER: return data.hopperWindow;
            case KIND_SCULK: return data.sculkWindow;
            case KIND_TRAPDOOR: return data.trapdoorWindow;
            case KIND_SCHEDULED: return data.scheduledWindow;
            default: return data.physicsWindow;
        }
    }

    public void applyScan(ChunkCoordinate coord, ChunkScan scan) {
        ChunkData data = data(coord);
        data.lastScanned = System.currentTimeMillis();
        data.scanned = true;
        data.redstoneCount.set(scan.redstoneTotal);
        data.entityCount.set(scan.entityTotal);
        data.redstoneTypes.clear();
        for (Map.Entry<String, Integer> entry : scan.redstone.entrySet()) {
            data.redstoneTypes.put(entry.getKey(), new AtomicInteger(entry.getValue()));
        }
        data.entityTypes.clear();
        for (Map.Entry<String, Integer> entry : scan.entities.entrySet()) {
            data.entityTypes.put(entry.getKey(), new AtomicInteger(entry.getValue()));
        }
    }

    public void tickSecond() {
        for (Map.Entry<ChunkCoordinate, ChunkData> entry : chunks.entrySet()) {
            ChunkData data = entry.getValue();

            data.physicsPerSec = data.physicsWindow.getAndSet(0);
            data.redstonePerSec = data.redstoneWindow.getAndSet(0);
            data.pistonPerSec = data.pistonWindow.getAndSet(0);
            data.comparatorPerSec = data.comparatorWindow.getAndSet(0);
            data.observerPerSec = data.observerWindow.getAndSet(0);
            data.repeaterPerSec = data.repeaterWindow.getAndSet(0);
            data.neighborPerSec = data.neighborWindow.getAndSet(0);
            data.hopperPerSec = data.hopperWindow.getAndSet(0);
            data.sculkPerSec = data.sculkWindow.getAndSet(0);
            data.trapdoorPerSec = data.trapdoorWindow.getAndSet(0);
            data.scheduledPerSec = data.scheduledWindow.getAndSet(0);
            data.updatesPerSec = data.physicsPerSec + data.redstonePerSec + data.pistonPerSec
                    + data.comparatorPerSec + data.observerPerSec + data.repeaterPerSec
                    + data.neighborPerSec + data.hopperPerSec + data.sculkPerSec
                    + data.trapdoorPerSec + data.scheduledPerSec;

            long nanos = data.nanosWindow.getAndSet(0L);
            data.msptContribution = nanos / NANOS_PER_MILLI / TICKS_PER_SECOND;

            ActivityAnalyzer.computeScores(data, settings.maxRedstone, settings.maxEntities,
                    settings.updateScoreThreshold);

            if (data.msptContribution >= settings.chunkMsptThreshold) {
                data.lagStreak++;
            } else {
                data.lagStreak = 0;
            }

            data.impactScore = computeImpact(data);
        }

        if (isLagging()) {
            laggingSeconds++;
        } else {
            laggingSeconds = 0;
        }
    }

    public void setServerLoad(double tps, double mspt) {
        this.serverTps = tps;
        this.serverMspt = mspt;
    }

    public double serverTps() {
        return serverTps;
    }

    public double serverMspt() {
        return serverMspt;
    }

    public boolean isLagging() {
        return serverTps <= settings.criticalTps || serverMspt >= settings.serverMsptThreshold;
    }

    public int laggingSeconds() {
        return laggingSeconds;
    }

    private long lastFreezeMillis = 0L;

    public boolean shouldFreezeGlobally() {
        if (!settings.globalFreezeEnabled) {
            return false;
        }
        if (lastFreezeMillis != 0L && System.currentTimeMillis() - lastFreezeMillis
                < settings.globalFreezeAfterSeconds * 1000L) {
            return false;
        }
        return laggingSeconds >= settings.globalFreezeAfterSeconds;
    }

    public boolean isSuspicious(ChunkCoordinate coord) {
        return ActivityAnalyzer.isSuspicious(chunks.get(coord), settings.maxRedstone,
                settings.maxEntities);
    }

    public int suspiciousCount() {
        int count = 0;
        for (ChunkData data : chunks.values()) {
            if (ActivityAnalyzer.isSuspicious(data, settings.maxRedstone, settings.maxEntities)) {
                count++;
            }
        }
        return count;
    }

    public List<ChunkCoordinate> culprits() {
        List<ChunkCoordinate> found = new ArrayList<ChunkCoordinate>();
        for (Map.Entry<ChunkCoordinate, ChunkData> entry : chunks.entrySet()) {
            ChunkCoordinate coord = entry.getKey();
            ChunkData data = entry.getValue();
            if (data.clearedByAdmin || isFrozen(coord)) {
                continue;
            }

            boolean costly = data.msptContribution >= settings.chunkMsptThreshold;
            boolean sustained = data.lagStreak >= Math.max(1, settings.sustainedLagSeconds);
            boolean busy = data.updatesPerSec >= settings.culpritMinUpdatesPerSec;
            int mechanisms = data.redstoneCount.get();

            boolean mechanical = data.scanned
                    && mechanisms >= settings.culpritMinMechanisms;
            if (costly && sustained && busy && mechanical) {
                found.add(coord);
            }
        }

        final Map<ChunkCoordinate, ChunkData> snapshot = chunks;
        found.sort(new Comparator<ChunkCoordinate>() {
            @Override
            public int compare(ChunkCoordinate left, ChunkCoordinate right) {
                return Double.compare(weight(snapshot.get(right)), weight(snapshot.get(left)));
            }
        });
        return found;
    }

    private static double weight(ChunkData data) {
        if (data == null) {
            return 0.0;
        }
        return data.msptContribution * 1000.0 + data.updatesPerSec;
    }

    public void markFrozen(ChunkCoordinate coord) {
        long now = System.currentTimeMillis();
        lastFreezeMillis = now;
        frozen.put(coord, now);
        ChunkData data = data(coord);
        data.autoFrozen = true;
        data.autoFrozenSince = now;
    }

    public void clearFrozen(ChunkCoordinate coord) {
        frozen.remove(coord);
        ChunkData data = chunks.get(coord);
        if (data != null) {
            data.autoFrozen = false;
            data.autoFrozenSince = 0L;
        }
    }

    public boolean isFrozen(ChunkCoordinate coord) {
        return frozen.containsKey(coord);
    }

    public List<ChunkCoordinate> frozenChunks() {
        return new ArrayList<ChunkCoordinate>(frozen.keySet());
    }

    public List<ChunkCoordinate> releasable() {
        long now = System.currentTimeMillis();
        long hold = Math.max(1, settings.autoUnfreezeSeconds) * 1000L;
        List<ChunkCoordinate> ready = new ArrayList<ChunkCoordinate>();
        for (Map.Entry<ChunkCoordinate, Long> entry : frozen.entrySet()) {
            if (now - entry.getValue() < hold) {
                continue;
            }
            ChunkData data = chunks.get(entry.getKey());
            if (data == null || data.updatesPerSec < settings.culpritMinUpdatesPerSec) {
                ready.add(entry.getKey());
            }
        }
        return ready;
    }

    public int prune() {
        long cutoff = System.currentTimeMillis()
                - Math.max(1, settings.chunkDataRetentionHours) * 3600000L;
        int removed = 0;
        Iterator<Map.Entry<ChunkCoordinate, ChunkData>> cursor = chunks.entrySet().iterator();
        while (cursor.hasNext()) {
            Map.Entry<ChunkCoordinate, ChunkData> entry = cursor.next();
            ChunkData data = entry.getValue();
            if (frozen.containsKey(entry.getKey())) {
                continue;
            }
            long seen = Math.max(data.lastActivity, data.lastScanned);
            if (seen < cutoff) {
                cursor.remove();
                forgetDetectors(entry.getKey());
                removed++;
            }
        }
        return removed;
    }
}
