package ru.stepanyaa.redstoneDetector.sponge;

import ru.stepanyaa.redstoneDetector.ChunkCoordinate;
import ru.stepanyaa.redstoneDetector.platform.DetectorTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class SpongeScanManager {

    private static final int[] MILESTONES = {25, 50, 75, 100};

    private final SpongeDetector detector;
    private final Logger logger;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Deque<ChunkCoordinate> queue = new ArrayDeque<ChunkCoordinate>();

    private volatile boolean cancelRequested;
    private volatile int totalChunks;
    private volatile int scannedChunks;
    private volatile int suspiciousFound;
    private volatile int nextMilestone;
    private volatile UUID initiator;
    private volatile long lastStart;
    private volatile long lastEnd;
    private volatile long lastDurationMs;
    private volatile long lastManualStart;

    private DetectorTask task;

    public SpongeScanManager(SpongeDetector detector, Logger logger) {
        this.detector = detector;
        this.logger = logger;
    }

    public boolean isRunning() {
        return running.get();
    }

    public int totalChunks() {
        return totalChunks;
    }

    public int scannedChunks() {
        return scannedChunks;
    }

    public int suspiciousFound() {
        return suspiciousFound;
    }

    public long lastScanTime() {
        return lastEnd;
    }

    public long lastScanDurationMs() {
        return lastDurationMs;
    }

    public int progressPercent() {
        int total = totalChunks;
        if (total <= 0) {
            return running.get() ? 0 : 100;
        }
        return Math.min(100, (int) ((scannedChunks * 100L) / total));
    }

    public boolean start(Object initiatorViewer) {
        long now = System.currentTimeMillis();
        boolean manual = initiatorViewer != null;
        if (manual && now - lastManualStart < detector.config().scanCooldownMs()) {
            return false;
        }
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        if (manual) {
            lastManualStart = now;
        }
        cancelRequested = false;
        scannedChunks = 0;
        suspiciousFound = 0;
        nextMilestone = 0;
        lastStart = now;
        initiator = SpongeViewers.uniqueId(initiatorViewer);

        queue.clear();
        for (String world : detector.worlds().worldNames()) {
            for (ChunkCoordinate coord : detector.worlds().loadedChunks(world)) {
                queue.add(coord);
            }
        }
        totalChunks = queue.size();

        final int perTick = detector.config().chunksPerTick();
        task = detector.scheduler().timer(1L, 1L, new Runnable() {
            @Override
            public void run() {
                step(perTick);
            }
        });

        logger.info(SpongeLog.strip(detector.messages().formatServer("log.scan_start",
                "Scanning {world}...", "{world}", "all worlds")));
        return true;
    }

    private void step(int perTick) {
        try {
            if (cancelRequested) {
                finish(true);
                return;
            }
            int processed = 0;
            while (processed < perTick && !queue.isEmpty()) {
                ChunkCoordinate coord = queue.poll();
                processed++;
                scannedChunks++;
                if (coord == null) {
                    continue;
                }
                if (detector.scanAndTrack(coord)) {
                    suspiciousFound++;
                }
            }
            int percent = progressPercent();
            while (nextMilestone < MILESTONES.length && percent >= MILESTONES[nextMilestone]) {
                announce(MILESTONES[nextMilestone]);
                nextMilestone++;
            }
            if (queue.isEmpty()) {
                finish(false);
            }
        } catch (Throwable failure) {
            logger.warning("Scan step failed: " + failure);
            finish(true);
        }
    }

    public boolean requestCancel() {
        if (!running.get()) {
            return false;
        }
        cancelRequested = true;
        return true;
    }

    private void announce(int percent) {
        Object player = SpongeViewers.playerById(initiator);
        String message = detector.messages().format(player, "cmd.scan.progress",
                "&7Scan progress: &e{percent}% &7({scanned}/{total})",
                "{percent}", String.valueOf(percent),
                "{scanned}", String.valueOf(scannedChunks),
                "{total}", String.valueOf(totalChunks));
        if (player != null) {
            SpongeApi.send(SpongeViewers.audience(player), message);
        } else {
            logger.info(SpongeLog.strip(message));
        }
    }

    private void finish(boolean cancelled) {
        lastEnd = System.currentTimeMillis();
        lastDurationMs = lastEnd - lastStart;
        queue.clear();
        running.set(false);
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (cancelled) {
            logger.info(SpongeLog.strip(detector.messages().formatServer("log.scan_cancelled",
                    "Scan cancelled after {scanned}/{total} chunks.",
                    "{scanned}", String.valueOf(scannedChunks),
                    "{total}", String.valueOf(totalChunks))));
            return;
        }
        String seconds = String.format(Locale.US, "%.1f", Double.valueOf(lastDurationMs / 1000.0));
        logger.info(SpongeLog.strip(detector.messages().formatServer("log.scan_finished",
                "Found {count} suspicious chunks. Finished in {seconds} seconds.",
                "{count}", String.valueOf(suspiciousFound),
                "{seconds}", seconds)));
    }
}
