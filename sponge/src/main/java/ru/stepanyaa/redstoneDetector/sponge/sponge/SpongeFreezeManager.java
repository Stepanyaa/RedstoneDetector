package ru.stepanyaa.redstoneDetector.sponge;

import ru.stepanyaa.redstoneDetector.ChunkCoordinate;
import ru.stepanyaa.redstoneDetector.ChunkData;
import ru.stepanyaa.redstoneDetector.core.DetectorEngine;
import ru.stepanyaa.redstoneDetector.core.EngineSettings;
import ru.stepanyaa.redstoneDetector.core.FreezeJournal;
import ru.stepanyaa.redstoneDetector.core.FreezeRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

public final class SpongeFreezeManager {

    private static final int SUSPEND_BUDGET_PER_TICK = 1;
    private static final int RESTORE_BUDGET_PER_TICK = 2;
    private static final long REAPPLY_INTERVAL_MILLIS = 5000L;

    private final SpongeDetector detector;
    private final Logger logger;

    private final Map<ChunkCoordinate, Long> suspended =
            new ConcurrentHashMap<ChunkCoordinate, Long>();
    private final Map<ChunkCoordinate, Long> lastApply =
            new ConcurrentHashMap<ChunkCoordinate, Long>();
    private final Map<ChunkCoordinate, Boolean> queuedSuspend =
            new ConcurrentHashMap<ChunkCoordinate, Boolean>();
    private final Map<ChunkCoordinate, Boolean> queuedRestore =
            new ConcurrentHashMap<ChunkCoordinate, Boolean>();

    private final Queue<ChunkCoordinate> suspendQueue = new ConcurrentLinkedQueue<ChunkCoordinate>();
    private final Queue<ChunkCoordinate> restoreQueue = new ConcurrentLinkedQueue<ChunkCoordinate>();

    public SpongeFreezeManager(SpongeDetector detector, Logger logger) {
        this.detector = detector;
        this.logger = logger;
    }

    public FreezeJournal journal() {
        return detector.engine().journal();
    }

    public boolean isSuspended(ChunkCoordinate coord) {
        return coord != null && suspended.containsKey(coord);
    }

    public int suspendedCount() {
        return suspended.size();
    }

    public List<ChunkCoordinate> suspendedChunks() {
        return new ArrayList<ChunkCoordinate>(suspended.keySet());
    }

    public int pendingWork() {
        return suspendQueue.size() + restoreQueue.size();
    }

    public boolean freeze(ChunkCoordinate coord, String detectorName, String reason) {
        if (coord == null) {
            return false;
        }
        DetectorEngine engine = detector.engine();
        if (engine == null || engine.isIgnored(coord)) {
            return false;
        }
        if (suspended.containsKey(coord)) {
            return false;
        }
        ChunkData data = engine.data(coord);
        FreezeRecord record = new FreezeRecord(coord, detectorName, reason, data.lagScore,
                data.redstoneCount.get(), data.blockEntityCount, data.entityCount.get(),
                data.updatesPerSec, detector.mspt());
        journal().open(record);

        long now = System.currentTimeMillis();
        suspended.put(coord, Long.valueOf(now));
        data.suspended = true;
        engine.markFrozen(coord);
        detector.stopRedstone(coord);
        enqueueSuspend(coord);
        logger.info(SpongeLog.strip(record.describeStart()));
        return true;
    }

    public int freezeArea(ChunkCoordinate center, String detectorName, String reason, int radius) {
        if (center == null) {
            return 0;
        }
        int count = freeze(center, detectorName, reason) ? 1 : 0;
        if (radius <= 0) {
            return count;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                ChunkCoordinate around = new ChunkCoordinate(center.world(),
                        center.x() + dx, center.z() + dz);
                if (freeze(around, detectorName, reason)) {
                    count++;
                }
            }
        }
        return count;
    }

    public boolean release(ChunkCoordinate coord) {
        if (coord == null || suspended.remove(coord) == null) {
            return false;
        }
        lastApply.remove(coord);
        DetectorEngine engine = detector.engine();
        if (engine != null) {
            engine.clearFrozen(coord);
            ChunkData data = engine.peek(coord);
            if (data != null) {
                data.suspended = false;
            }
        }
        detector.clearSuppression(coord);
        enqueueRestore(coord);
        return true;
    }

    public int releaseAll() {
        int released = 0;
        for (ChunkCoordinate coord : suspendedChunks()) {
            if (release(coord)) {
                released++;
            }
        }
        return released;
    }

    public void tick() {
        try {
            drainRestores(RESTORE_BUDGET_PER_TICK);
            drainSuspends(SUSPEND_BUDGET_PER_TICK);
            enforceLimits();
        } catch (Throwable failure) {
            logger.warning("Freeze cycle failed: " + SpongeApi.describe(failure));
        }
    }

    public void shutdown() {
        releaseAll();
        drainRestores(Integer.MAX_VALUE);
        suspendQueue.clear();
        queuedSuspend.clear();
        suspended.clear();
        lastApply.clear();
    }

    private void enqueueSuspend(ChunkCoordinate coord) {
        if (queuedSuspend.putIfAbsent(coord, Boolean.TRUE) == null) {
            suspendQueue.add(coord);
        }
    }

    private void enqueueRestore(ChunkCoordinate coord) {
        if (queuedRestore.putIfAbsent(coord, Boolean.TRUE) == null) {
            restoreQueue.add(coord);
        }
    }

    private void drainSuspends(int budget) {
        SpongeWorldAccess worlds = detector.worlds();
        for (int done = 0; done < budget; done++) {
            final ChunkCoordinate coord = suspendQueue.poll();
            if (coord == null) {
                return;
            }
            queuedSuspend.remove(coord);
            if (!suspended.containsKey(coord)) {
                continue;
            }
            lastApply.put(coord, Long.valueOf(System.currentTimeMillis()));
            worlds.runAtChunk(coord, new Runnable() {
                @Override
                public void run() {
                    int cleared = detector.worlds().removeRedstone(coord);
                    ChunkData data = detector.engine() == null ? null
                            : detector.engine().peek(coord);
                    if (data != null) {
                        data.suspendedBlocks = data.suspendedBlocks + cleared;
                    }
                    FreezeRecord record = journal().active(coord);
                    if (record != null) {
                        record.suspendedBlocks(record.suspendedBlocks() + cleared);
                    }
                    if (cleared > 0 && debug()) {
                        logger.info("Suspended " + cleared + " mechanism blocks in " + coord);
                    }
                }
            });
        }
    }

    private void drainRestores(int budget) {
        SpongeWorldAccess worlds = detector.worlds();
        for (int done = 0; done < budget; done++) {
            final ChunkCoordinate coord = restoreQueue.poll();
            if (coord == null) {
                return;
            }
            queuedRestore.remove(coord);
            worlds.runAtChunk(coord, new Runnable() {
                @Override
                public void run() {
                    int restored = detector.worlds().restoreRedstone(coord);
                    ChunkData data = detector.engine() == null ? null
                            : detector.engine().peek(coord);
                    if (data != null) {
                        data.suspendedBlocks = 0;
                    }
                    FreezeRecord record = journal().close(coord, detector.mspt(), restored);
                    if (record != null) {
                        logger.info(SpongeLog.strip(record.describeEnd()));
                    } else if (restored > 0 && debug()) {
                        logger.info("Restored " + restored + " blocks in " + coord);
                    }
                }
            });
        }
    }

    private void enforceLimits() {
        DetectorEngine engine = detector.engine();
        if (engine == null || suspended.isEmpty()) {
            return;
        }
        EngineSettings settings = engine.settings();
        long now = System.currentTimeMillis();
        long maxHold = Math.max(0, settings.maxFreezeSeconds) * 1000L;
        long minHold = Math.max(1, settings.autoUnfreezeSeconds) * 1000L;

        for (Map.Entry<ChunkCoordinate, Long> entry : suspended.entrySet()) {
            ChunkCoordinate coord = entry.getKey();
            long held = now - entry.getValue().longValue();

            if (maxHold > 0L && held >= maxHold) {
                release(coord);
                continue;
            }
            if (settings.autoUnfreezeEnabled && held >= minHold && !engine.isLagging()) {
                ChunkData data = engine.peek(coord);
                if (data == null || data.updatesPerSec < settings.culpritMinUpdatesPerSec) {
                    release(coord);
                    continue;
                }
            }
            ChunkData data = engine.peek(coord);
            if (data == null || data.updatesPerSec <= 0) {
                continue;
            }
            Long applied = lastApply.get(coord);
            if (applied == null || now - applied.longValue() >= REAPPLY_INTERVAL_MILLIS) {
                enqueueSuspend(coord);
            }
        }
    }

    private boolean debug() {
        DetectorEngine engine = detector.engine();
        return engine != null && engine.settings().debugLogging;
    }
}
