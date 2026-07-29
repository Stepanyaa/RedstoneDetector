package ru.stepanyaa.redstoneDetector.core;

import ru.stepanyaa.redstoneDetector.ChunkCoordinate;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public abstract class PositionActivityDetector {

    public static final class Thresholds {
        public volatile int eventsPerSecond = 80;
        public volatile int clusterSize = 8;
        public volatile int perPositionRate = 6;
        public volatile int repeatBurst = 40;
        public volatile int loopSeconds = 5;
        public volatile long repeatWindowMillis = 120L;
    }

    private static final int MAX_TRACKED_POSITIONS = 128;
    private static final int IDLE_SECONDS_BEFORE_EVICTION = 30;

    private static final class State {
        private final Map<Long, AtomicInteger> positions =
                new ConcurrentHashMap<Long, AtomicInteger>();
        private final AtomicInteger events = new AtomicInteger();
        private final AtomicInteger repeats = new AtomicInteger();
        private final AtomicLong lastEvent = new AtomicLong();
        private volatile int sustainedSeconds;
        private volatile int idleSeconds;
        private volatile int eventsPerSecond;
        private volatile int activePositions;
        private volatile int synchronizedPositions;
    }

    private final Map<ChunkCoordinate, State> states =
            new ConcurrentHashMap<ChunkCoordinate, State>();
    private final Thresholds thresholds = new Thresholds();
    private final String name;

    protected PositionActivityDetector(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    protected abstract void configure(Thresholds target, EngineSettings settings);

    protected static int scale(int base, double sensitivity) {
        double factor = sensitivity <= 0.0 ? 1.0 : sensitivity;
        return Math.max(1, (int) Math.round(base / factor));
    }

    public void record(ChunkCoordinate coord, int x, int y, int z, long now) {
        if (coord == null) {
            return;
        }
        State state = state(coord);
        state.events.incrementAndGet();
        long previous = state.lastEvent.getAndSet(now);
        if (previous != 0L && now - previous <= thresholds.repeatWindowMillis) {
            state.repeats.incrementAndGet();
        }
        Long key = Long.valueOf(pack(x, y, z));
        AtomicInteger counter = state.positions.get(key);
        if (counter == null) {
            if (state.positions.size() >= MAX_TRACKED_POSITIONS) {
                return;
            }
            AtomicInteger created = new AtomicInteger();
            AtomicInteger raced = state.positions.putIfAbsent(key, created);
            counter = raced == null ? created : raced;
        }
        counter.incrementAndGet();
    }

    public void tickSecond(EngineSettings settings, List<LagSignal> out) {
        configure(thresholds, settings);
        Iterator<Map.Entry<ChunkCoordinate, State>> cursor = states.entrySet().iterator();
        while (cursor.hasNext()) {
            Map.Entry<ChunkCoordinate, State> entry = cursor.next();
            ChunkCoordinate coord = entry.getKey();
            State state = entry.getValue();

            int events = state.events.getAndSet(0);
            int repeats = state.repeats.getAndSet(0);
            int active = 0;
            int synced = 0;

            Iterator<Map.Entry<Long, AtomicInteger>> positions =
                    state.positions.entrySet().iterator();
            while (positions.hasNext()) {
                Map.Entry<Long, AtomicInteger> position = positions.next();
                int count = position.getValue().getAndSet(0);
                if (count <= 0) {
                    positions.remove();
                    continue;
                }
                active++;
                if (count >= thresholds.perPositionRate) {
                    synced++;
                }
            }

            state.eventsPerSecond = events;
            state.activePositions = active;
            state.synchronizedPositions = synced;

            if (events <= 0) {
                state.sustainedSeconds = 0;
                state.idleSeconds++;
                if (state.idleSeconds >= IDLE_SECONDS_BEFORE_EVICTION) {
                    cursor.remove();
                }
                continue;
            }

            state.idleSeconds = 0;
            if (events >= Math.max(1, thresholds.eventsPerSecond / 2)) {
                state.sustainedSeconds++;
            } else {
                state.sustainedSeconds = 0;
            }

            String reason = classify(state, events, repeats, synced, active);
            if (reason == null) {
                continue;
            }
            int score = Math.min(100,
                    events * 100 / Math.max(1, thresholds.eventsPerSecond));
            out.add(new LagSignal(coord, name, reason, Math.max(1, score), events, active));
        }
    }

    private String classify(State state, int events, int repeats, int synced, int active) {
        if (events >= thresholds.eventsPerSecond) {
            return LagSignal.REASON_EXCESSIVE;
        }
        if (synced >= thresholds.clusterSize) {
            return LagSignal.REASON_SYNCHRONIZED;
        }
        if (repeats >= thresholds.repeatBurst) {
            return LagSignal.REASON_BURST;
        }
        if (state.sustainedSeconds >= thresholds.loopSeconds) {
            return LagSignal.REASON_LOOP;
        }
        if (active >= thresholds.clusterSize * 3
                && events >= Math.max(1, thresholds.eventsPerSecond / 3)) {
            return LagSignal.REASON_CLUSTER;
        }
        return null;
    }

    public int eventsPerSecond(ChunkCoordinate coord) {
        State state = states.get(coord);
        return state == null ? 0 : state.eventsPerSecond;
    }

    public int activePositions(ChunkCoordinate coord) {
        State state = states.get(coord);
        return state == null ? 0 : state.activePositions;
    }

    public int synchronizedPositions(ChunkCoordinate coord) {
        State state = states.get(coord);
        return state == null ? 0 : state.synchronizedPositions;
    }

    public void forget(ChunkCoordinate coord) {
        if (coord != null) {
            states.remove(coord);
        }
    }

    public void clear() {
        states.clear();
    }

    public int trackedChunks() {
        return states.size();
    }

    private State state(ChunkCoordinate coord) {
        State existing = states.get(coord);
        if (existing != null) {
            return existing;
        }
        State created = new State();
        State raced = states.putIfAbsent(coord, created);
        return raced == null ? created : raced;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }
}
