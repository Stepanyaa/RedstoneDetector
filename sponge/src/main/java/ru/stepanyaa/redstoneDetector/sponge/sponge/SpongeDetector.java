package ru.stepanyaa.redstoneDetector.sponge;

import ru.stepanyaa.redstoneDetector.ActivityAnalyzer;
import ru.stepanyaa.redstoneDetector.ChunkCoordinate;
import ru.stepanyaa.redstoneDetector.ChunkData;
import ru.stepanyaa.redstoneDetector.core.ChunkScan;
import ru.stepanyaa.redstoneDetector.core.DetectorEngine;
import ru.stepanyaa.redstoneDetector.core.EngineSettings;
import ru.stepanyaa.redstoneDetector.core.LagSignal;

import java.io.File;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class SpongeDetector {

    private final Logger logger;
    private final Object pluginContainer;

    private final SpongeConfig config;
    private final SpongeMessages messages;
    private final SpongeScheduler scheduler;
    private final SpongeWorldAccess worlds;
    private final SpongeGuiManager gui;
    private final SpongeTpsMonitor tpsMonitor;
    private final SpongeScanManager scanManager;
    private final SpongeUpdateChecker updateChecker;
    private final SpongeFreezeManager freezeManager;

    private DetectorEngine engine;

    private final Map<ChunkCoordinate, Integer> lagStreaks =
            new ConcurrentHashMap<ChunkCoordinate, Integer>();

    private final Set<ChunkCoordinate> stopped =
            Collections.newSetFromMap(new ConcurrentHashMap<ChunkCoordinate, Boolean>());

    private final Map<ChunkCoordinate, Long> stoppedSince =
            new ConcurrentHashMap<ChunkCoordinate, Long>();

    private volatile boolean globalStop;

    private volatile boolean manualGlobalStop;

    private volatile long globalStopSince;

    private volatile int healthySeconds;

    private volatile long manualReleaseAt;

    private final Map<ChunkCoordinate, Long> freezeActionAt =
            new ConcurrentHashMap<ChunkCoordinate, Long>();

    private volatile long lastAutoScan;

    private int worldCursor;
    private int chunkCursor;
    private boolean running;

    public SpongeDetector(Object pluginContainer, File folder, Logger logger)
            throws ReflectiveOperationException {
        this(pluginContainer, folder, logger, "1.3.0");
    }

    public SpongeDetector(Object pluginContainer, File folder, Logger logger, String version)
            throws ReflectiveOperationException {
        this.pluginContainer = pluginContainer;
        this.logger = logger;
        this.config = new SpongeConfig(folder, logger, version);
        this.messages = new SpongeMessages(config, logger);
        this.scheduler = new SpongeScheduler(pluginContainer, logger);
        this.worlds = new SpongeWorldAccess(config, scheduler, logger);
        this.gui = new SpongeGuiManager(this, pluginContainer, logger);
        this.tpsMonitor = new SpongeTpsMonitor(scheduler, logger);
        this.scanManager = new SpongeScanManager(this, logger);
        this.updateChecker = new SpongeUpdateChecker(scheduler, messages, config.version(), logger);
        this.freezeManager = new SpongeFreezeManager(this, logger);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;

        reloadFiles();
        new SpongeEvents(this, pluginContainer, logger).register();
        try {
            SpongePreEvents.register(this, pluginContainer, logger);
        } catch (Throwable noPreEvent) {
            logger.info("Early block change listener unavailable on this API: "
                    + SpongeApi.describe(noPreEvent));
        }
        new SpongePlayerEvents(this, pluginContainer, logger).register();
        tpsMonitor.start();

        scheduler.timer(20L, 20L, new Runnable() {
            @Override
            public void run() {
                everySecond();
            }
        });
        scheduler.timer(20L, 20L, new Runnable() {
            @Override
            public void run() {
                scanSlice();
            }
        });
        final long refreshTicks = Math.max(5, engine.settings().guiRefreshTicks);
        scheduler.timer(refreshTicks, refreshTicks, new Runnable() {
            @Override
            public void run() {
                gui.refreshOpenScreens();
            }
        });
        scheduler.timer(20L * 60 * 30, 20L * 60 * 30, new Runnable() {
            @Override
            public void run() {
                int removed = engine.prune();
                if (removed > 0) {
                    logger.info("Dropped " + removed + " stale chunk records.");
                }
            }
        });

        if (config.updateCheckEnabled()) {
            updateChecker.check();
        }

        SpongeLog.banner(logger, "RedstoneDetector " + config.version() + " on Sponge",
                "Language: " + messages.serverLanguage()
                        + (messages.perPlayerLanguage() ? " (client language per player)" : ""),
                "Scan budget: " + engine.settings().chunksPerTick + " chunk(s) per tick",
                "Tracking floor: " + config.minTrackedMechanisms() + " mechanisms / "
                        + config.minTrackedEntities() + " entities");
    }

    public void reloadFiles() {
        config.reload();
        messages.reload();
        EngineSettings settings = config.settings();
        if (engine == null) {
            engine = new DetectorEngine(settings);
        } else {
            EngineSettings live = engine.settings();
            live.maxRedstone = settings.maxRedstone;
            live.maxEntities = settings.maxEntities;
            live.criticalTps = settings.criticalTps;
            live.intelligentFreeze = settings.intelligentFreeze;
            live.updateScoreThreshold = settings.updateScoreThreshold;
            live.autoFreezeUpdatesPerSec = settings.autoFreezeUpdatesPerSec;
            live.lowTpsFreezeUpdatesPerSec = settings.lowTpsFreezeUpdatesPerSec;
            live.autoUnfreezeSeconds = settings.autoUnfreezeSeconds;
            live.serverMsptThreshold = settings.serverMsptThreshold;
            live.chunkMsptThreshold = settings.chunkMsptThreshold;
            live.sustainedLagSeconds = settings.sustainedLagSeconds;
            live.baselineMspt = settings.baselineMspt;
            live.culpritMinUpdatesPerSec = settings.culpritMinUpdatesPerSec;
            live.culpritMinMechanisms = settings.culpritMinMechanisms;
            live.globalFreezeAfterSeconds = settings.globalFreezeAfterSeconds;
            live.globalFreezeEnabled = settings.globalFreezeEnabled;
            live.chunksPerTick = settings.chunksPerTick;
            live.chunkDataRetentionHours = settings.chunkDataRetentionHours;
        }

        sweepIdle(true);
    }

    public void stop() {
        running = false;
        try {
            freezeManager.shutdown();
        } catch (Throwable failure) {
            logger.warning("Could not restore suspended chunks on shutdown: "
                    + SpongeApi.describe(failure));
        }
        try {
            gui.closeAll();
        } catch (Throwable ignored) {
            logger.fine("No open interfaces to close.");
        }
        stopped.clear();
        stoppedSince.clear();
        freezeActionAt.clear();
        lagStreaks.clear();
        if (engine != null) {
            engine.sculkDetector().clear();
            engine.trapdoorDetector().clear();
        }
        scheduler.cancelAll();
        logger.info("RedstoneDetector stopped.");
    }

    public SpongeFreezeManager freezes() {
        return freezeManager;
    }

    public void clearSuppression(ChunkCoordinate coord) {
        if (coord != null) {
            stopped.remove(coord);
            stoppedSince.remove(coord);
        }
    }

    public DetectorEngine engine() {
        return engine;
    }

    public SpongeMessages messages() {
        return messages;
    }

    public SpongeConfig config() {
        return config;
    }

    public SpongeScheduler scheduler() {
        return scheduler;
    }

    public SpongeWorldAccess worlds() {
        return worlds;
    }

    public SpongeGuiManager gui() {
        return gui;
    }

    public SpongeScanManager scans() {
        return scanManager;
    }

    public SpongeUpdateChecker updates() {
        return updateChecker;
    }

    public String version() {
        return config.version();
    }

    private void everySecond() {
        try {
            engine.setServerLoad(tps(), mspt());
            engine.tickSecond();
            estimateChunkCost();
            handleDetections();
            freezeManager.tick();
            sweepIdle(false);

            if (engine.settings().intelligentFreeze) {
                manageFreezes();
            }
            enforceStops();
            manageGlobalStop();
            maybeAutoScan();
        } catch (Throwable failure) {
            logger.warning("Second tick failed: " + failure);
        }
    }

    private void estimateChunkCost() {
        EngineSettings settings = engine.settings();
        int minUpdates = config.costEstimateMinUpdatesPerSec();
        double overhead = Math.max(0.0, engine.serverMspt() - settings.baselineMspt);
        boolean charge = engine.isLagging() && overhead > 0.0;

        long busyTotal = 0L;
        if (charge) {
            for (ChunkData data : engine.chunks().values()) {
                if (data.updatesPerSec >= minUpdates) {
                    busyTotal += data.updatesPerSec;
                }
            }
            charge = busyTotal > 0L;
        }

        int sustained = Math.max(1, settings.sustainedLagSeconds);
        for (Map.Entry<ChunkCoordinate, ChunkData> entry : engine.chunks().entrySet()) {
            ChunkCoordinate coord = entry.getKey();
            ChunkData data = entry.getValue();

            double cost = 0.0;
            if (charge && data.updatesPerSec >= minUpdates) {
                cost = overhead * data.updatesPerSec / busyTotal;
            }
            data.msptContribution = cost;

            int streak;
            if (cost >= settings.chunkMsptThreshold) {
                Integer previous = lagStreaks.get(coord);
                streak = (previous == null ? 0 : previous.intValue()) + 1;
                lagStreaks.put(coord, Integer.valueOf(streak));
            } else {
                streak = 0;
                lagStreaks.remove(coord);
            }
            data.lagStreak = Math.min(streak, sustained * 10);

            ActivityAnalyzer.computeScores(data, settings.maxRedstone, settings.maxEntities,
                    settings.updateScoreThreshold);
        }
    }

    private void handleDetections() {
        EngineSettings settings = engine.settings();
        List<LagSignal> signals = engine.tickDetectors();
        if (signals.isEmpty()) {
            return;
        }
        for (int index = 0; index < signals.size(); index++) {
            LagSignal signal = signals.get(index);
            ChunkCoordinate coord = signal.coord();
            if (settings.debugLogging) {
                logger.info("Detector signal " + signal + " at " + coord);
            }
            if (!settings.intelligentFreeze || engine.isFrozen(coord)
                    || freezeManager.isSuspended(coord) || !canActOn(coord)) {
                continue;
            }
            int affected = freezeManager.freezeArea(coord, signal.detector(), signal.reason(),
                    Math.max(0, settings.freezeRadius));
            if (affected > 0) {
                logger.warning("Froze " + affected + " chunk(s) around " + coord + " after "
                        + signal);
            }
        }
    }

    private int sweepIdle(boolean force) {
        int forgetAfter = config.forgetIdleAfterSeconds();
        if (forgetAfter <= 0 && !force) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - Math.max(1, forgetAfter) * 1000L;
        int minMechanisms = config.minTrackedMechanisms();
        int minEntities = config.minTrackedEntities();
        int minUpdates = config.costEstimateMinUpdatesPerSec();

        int removed = 0;
        Iterator<Map.Entry<ChunkCoordinate, ChunkData>> cursor =
                engine.chunks().entrySet().iterator();
        while (cursor.hasNext()) {
            Map.Entry<ChunkCoordinate, ChunkData> entry = cursor.next();
            ChunkCoordinate coord = entry.getKey();
            ChunkData data = entry.getValue();

            if (isProtected(coord)) {
                continue;
            }
            if (data.updatesPerSec >= minUpdates || data.msptContribution > 0.0) {
                continue;
            }
            boolean sizeable = data.redstoneCount.get() >= minMechanisms
                    || data.entityCount.get() >= minEntities;
            if (sizeable) {
                continue;
            }
            if (!force && data.lastActivity > cutoff) {
                continue;
            }
            cursor.remove();
            lagStreaks.remove(coord);
            removed++;
        }
        return removed;
    }

    private boolean isProtected(ChunkCoordinate coord) {
        return engine.isFrozen(coord) || stopped.contains(coord) || worlds.hasBackup(coord);
    }

    private void enforceStops() {
        int graceSeconds = config.hardStopAfterSeconds();
        if (graceSeconds <= 0 || stopped.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long grace = graceSeconds * 1000L;

        int budget = 1;
        for (ChunkCoordinate coord : stopped) {
            if (budget <= 0) {
                return;
            }
            Long since = stoppedSince.get(coord);
            if (since == null) {
                stoppedSince.put(coord, Long.valueOf(now));
                continue;
            }
            if (now - since.longValue() < grace || worlds.hasBackup(coord)) {
                continue;
            }
            ChunkData data = engine.peek(coord);
            if (data == null || data.updatesPerSec <= 0) {
                continue;
            }
            budget--;
            final ChunkCoordinate target = coord;
            worlds.runAtChunk(target, new Runnable() {
                @Override
                public void run() {
                    int cleared = worlds.removeRedstone(target);
                    logger.warning("Stop at " + target + " kept ticking, removed " + cleared
                            + " mechanism blocks. They return on resume.");
                }
            });
        }
    }

    private boolean canActOn(ChunkCoordinate coord) {
        long cooldown = config.freezeActionCooldownSeconds() * 1000L;
        Long last = freezeActionAt.get(coord);
        if (last != null && System.currentTimeMillis() - last.longValue() < cooldown) {
            return false;
        }
        freezeActionAt.put(coord, Long.valueOf(System.currentTimeMillis()));
        return true;
    }

    private void manageFreezes() {
        if (engine.settings().autoUnfreezeEnabled) {
            for (ChunkCoordinate coord : engine.releasable()) {
                if (!canActOn(coord)) {
                    continue;
                }
                if (freezeManager.release(coord)) {
                    continue;
                }
                final ChunkCoordinate target = coord;
                engine.clearFrozen(target);
                worlds.runAtChunk(target, new Runnable() {
                    @Override
                    public void run() {
                        int restored = worlds.restoreRedstone(target);
                        if (restored > 0) {
                            logger.info("Restored " + restored + " blocks in " + target);
                        }
                    }
                });
            }
        }

        if (!engine.isLagging() || scanManager.isRunning()) {
            return;
        }
        List<ChunkCoordinate> culprits = engine.culprits();
        int budget = Math.min(1, culprits.size());
        for (int index = 0; index < budget; index++) {
            ChunkCoordinate target = culprits.get(index);
            if (!canActOn(target)) {
                continue;
            }
            freezeManager.freeze(target, "lag_watchdog", "sustained_chunk_cost");
        }
    }

    private void manageGlobalStop() {
        if (manualGlobalStop) {

            return;
        }
        if (!engine.settings().globalFreezeEnabled) {
            globalStop = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (!engine.isLagging()) {
            healthySeconds++;
            boolean held = now - globalStopSince >= config.globalFreezeMinSeconds() * 1000L;

            if (globalStop && held && healthySeconds >= 5) {
                globalStop = false;
                logger.info(SpongeLog.strip(messages.get("cmd.freeze.off",
                        "&aGlobal redstone freeze &2DISABLED&a.")));
            }
            return;
        }
        healthySeconds = 0;
        if (now - manualReleaseAt < 30000L) {

            return;
        }
        if (!globalStop && engine.shouldFreezeGlobally()) {
            globalStop = true;
            globalStopSince = now;
            logger.warning(SpongeLog.strip(messages.get("cmd.freeze.on",
                    "&aGlobal redstone freeze &cENABLED&a.")));
        }
    }

    private void maybeAutoScan() {
        if (!config.scanOnLowTps() || scanManager.isRunning() || !engine.isLagging()) {
            return;
        }

        if (globalStop()) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = config.forcedScanCooldownSeconds() * 1000L;
        if (now - lastAutoScan < cooldown
                || now - scanManager.lastScanTime() < cooldown) {
            return;
        }
        lastAutoScan = now;
        logger.info(SpongeLog.strip(messages.get("chunk.scan_forced",
                "Forced scanning of all chunks due to low TPS")));
        scanManager.start(null);
    }

    private void scanSlice() {
        if (scanManager.isRunning()) {
            return;
        }
        if (globalStop()) {

            return;
        }
        try {
            List<String> names = worlds.worldNames();
            if (names.isEmpty()) {
                return;
            }
            if (worldCursor >= names.size()) {
                worldCursor = 0;
            }
            String worldName = names.get(worldCursor);
            List<ChunkCoordinate> chunks = worlds.loadedChunks(worldName);
            if (chunks.isEmpty()) {
                worldCursor++;
                chunkCursor = 0;
                return;
            }
            int budget = Math.max(1, engine.settings().chunksPerTick);
            for (int done = 0; done < budget; done++) {
                if (chunkCursor >= chunks.size()) {
                    chunkCursor = 0;
                    worldCursor++;
                    return;
                }
                scanAndTrack(chunks.get(chunkCursor++));
            }
        } catch (Throwable failure) {
            logger.warning("Scan cycle failed: " + failure);
        }
    }

    public boolean scanAndTrack(ChunkCoordinate coord) {
        if (coord == null) {
            return false;
        }
        ChunkScan scan = worlds.scanChunk(coord);
        if (!worthTracking(coord, scan)) {
            forget(coord);
            return false;
        }
        engine.applyScan(coord, scan);
        EngineSettings settings = engine.settings();
        ActivityAnalyzer.computeScores(engine.data(coord), settings.maxRedstone,
                settings.maxEntities, settings.updateScoreThreshold);
        return engine.isSuspicious(coord);
    }

    private boolean worthTracking(ChunkCoordinate coord, ChunkScan scan) {
        if (isProtected(coord)) {
            return true;
        }
        if (scan.redstoneTotal >= config.minTrackedMechanisms()) {
            return true;
        }
        if (scan.entityTotal >= config.minTrackedEntities()) {
            return true;
        }
        ChunkData data = engine.peek(coord);
        return data != null
                && (data.updatesPerSec >= config.costEstimateMinUpdatesPerSec()
                    || data.msptContribution > 0.0);
    }

    public boolean forget(ChunkCoordinate coord) {
        if (coord == null || isProtected(coord)) {
            return false;
        }
        lagStreaks.remove(coord);
        return engine.chunks().remove(coord) != null;
    }

    public int scanEverything() {
        int scanned = 0;
        for (String worldName : worlds.worldNames()) {
            for (ChunkCoordinate coord : worlds.loadedChunks(worldName)) {
                scanAndTrack(coord);
                scanned++;
            }
        }
        return scanned;
    }

    public void stopRedstone(ChunkCoordinate coord) {
        if (coord != null) {
            stopped.add(coord);
            stoppedSince.put(coord, Long.valueOf(System.currentTimeMillis()));
        }
    }

    public void resumeRedstone(ChunkCoordinate coord) {
        if (coord == null) {
            return;
        }
        stopped.remove(coord);
        stoppedSince.remove(coord);
        if (freezeManager.release(coord)) {
            return;
        }
        if (worlds.hasBackup(coord)) {
            final ChunkCoordinate target = coord;
            worlds.runAtChunk(target, new Runnable() {
                @Override
                public void run() {
                    int restored = worlds.restoreRedstone(target);
                    logger.info("Resumed " + target + ": restored " + restored + " blocks.");
                }
            });
        }
    }

    public boolean isStopped(ChunkCoordinate coord) {
        return coord != null && stopped.contains(coord);
    }

    public List<ChunkCoordinate> stoppedChunks() {
        return new java.util.ArrayList<ChunkCoordinate>(stopped);
    }

    public boolean globalStop() {
        return manualGlobalStop || globalStop;
    }

    public void setGlobalStop(boolean value) {
        manualGlobalStop = value;
        if (value) {
            globalStopSince = System.currentTimeMillis();
        } else {
            globalStop = false;

            manualReleaseAt = System.currentTimeMillis();
        }
        logger.info(SpongeLog.strip(messages.get(value ? "cmd.freeze.on" : "cmd.freeze.off",
                value ? "&aGlobal redstone freeze &cENABLED&a."
                        : "&aGlobal redstone freeze &2DISABLED&a.")));
    }

    public boolean isSuppressed(ChunkCoordinate coord) {
        return globalStop() || isStopped(coord);
    }

    public int freezeCulprits() {
        List<ChunkCoordinate> targets = engine.culprits();
        if (targets.isEmpty()) {
            targets = busiestChunks();
        }
        int frozen = 0;
        for (ChunkCoordinate coord : targets) {
            if (freezeManager.freeze(coord, "smart_freeze", "manual_request")) {
                frozen++;
            }
        }
        return frozen;
    }

    private List<ChunkCoordinate> busiestChunks() {
        EngineSettings settings = engine.settings();
        int minUpdates = Math.max(20, settings.culpritMinUpdatesPerSec / 4);
        int minMechanisms = Math.max(config.minTrackedMechanisms(),
                settings.culpritMinMechanisms / 4);

        List<ChunkCoordinate> found = new java.util.ArrayList<ChunkCoordinate>();
        for (Map.Entry<ChunkCoordinate, ChunkData> entry : engine.chunks().entrySet()) {
            ChunkCoordinate coord = entry.getKey();
            final ChunkData data = entry.getValue();
            if (engine.isFrozen(coord) || data.clearedByAdmin || !data.scanned) {
                continue;
            }
            boolean busy = data.updatesPerSec >= minUpdates;
            boolean mechanical = data.redstoneCount.get() >= minMechanisms;
            if (busy && mechanical) {
                found.add(coord);
            }
        }

        final Map<ChunkCoordinate, ChunkData> snapshot = engine.chunks();
        java.util.Collections.sort(found, new java.util.Comparator<ChunkCoordinate>() {
            @Override
            public int compare(ChunkCoordinate left, ChunkCoordinate right) {
                return Double.compare(score(snapshot.get(right)), score(snapshot.get(left)));
            }

            private double score(ChunkData data) {
                return data == null ? 0.0
                        : data.msptContribution * 1000.0 + data.updatesPerSec;
            }
        });
        return found.size() > 3 ? found.subList(0, 3) : found;
    }

    public double tps() {
        return tpsMonitor.tps();
    }

    public double mspt() {
        return tpsMonitor.mspt();
    }

    public SpongeTpsMonitor tpsMonitor() {
        return tpsMonitor;
    }

    public Map<ChunkCoordinate, ChunkData> chunks() {
        return engine.chunks();
    }
}
