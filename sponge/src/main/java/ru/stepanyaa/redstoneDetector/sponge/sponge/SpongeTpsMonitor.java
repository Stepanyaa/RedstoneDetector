package ru.stepanyaa.redstoneDetector.sponge;

import java.util.LinkedList;
import java.util.logging.Logger;

public final class SpongeTpsMonitor {

    private static final int SAMPLES = 100;

    private final SpongeScheduler scheduler;
    private final Logger logger;

    private final LinkedList<Long> history = new LinkedList<Long>();
    private volatile double sampledMspt = 50.0;
    private volatile boolean realTps = true;
    private volatile boolean realMspt = true;
    private volatile boolean started;

    public SpongeTpsMonitor(SpongeScheduler scheduler, Logger logger) {
        this.scheduler = scheduler;
        this.logger = logger;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        scheduler.timer(1L, 1L, new Runnable() {
            private long last = System.nanoTime();

            @Override
            public void run() {
                long now = System.nanoTime();
                long deltaMs = (now - last) / 1000000L;
                last = now;
                if (deltaMs < 0L || deltaMs > 5000L) {
                    return;
                }
                synchronized (history) {
                    history.addLast(Long.valueOf(deltaMs));
                    while (history.size() > SAMPLES) {
                        history.removeFirst();
                    }
                    long sum = 0L;
                    for (Long value : history) {
                        sum += value.longValue();
                    }
                    sampledMspt = history.isEmpty() ? 50.0 : (double) sum / history.size();
                }
            }
        });
        probe();
    }

    private void probe() {
        realTps = readServer("ticksPerSecond") != null;
        realMspt = readServer("averageTickTime") != null;
        if (realMspt) {
            logger.info("Using the Sponge server tick timings for real MSPT.");
        } else {
            logger.info("Real MSPT API unavailable; using the tick interval estimate.");
        }
    }

    private Double readServer(String method) {
        try {
            Object value = SpongeApi.call(SpongeApi.server(), method);
            if (value instanceof Number) {
                return Double.valueOf(((Number) value).doubleValue());
            }
            return null;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    public boolean hasRealMspt() {
        return realMspt;
    }

    public double tps() {
        if (realTps) {
            Double reported = readServer("ticksPerSecond");
            if (reported != null && reported.doubleValue() > 0.0) {
                return Math.min(20.0, reported.doubleValue());
            }
            realTps = false;
        }
        double mspt = sampledMspt;
        if (mspt <= 51.0) {
            return 20.0;
        }
        return Math.max(0.1, 1000.0 / mspt);
    }

    public double mspt() {
        if (realMspt) {
            Double reported = readServer("averageTickTime");
            if (reported != null && reported.doubleValue() > 0.0) {
                return reported.doubleValue();
            }
            realMspt = false;
        }

        return Math.max(0.0, sampledMspt);
    }
}
