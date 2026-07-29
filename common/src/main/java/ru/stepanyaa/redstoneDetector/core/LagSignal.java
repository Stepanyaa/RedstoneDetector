package ru.stepanyaa.redstoneDetector.core;

import ru.stepanyaa.redstoneDetector.ChunkCoordinate;

public final class LagSignal {

    public static final String REASON_EXCESSIVE = "excessive_activations";
    public static final String REASON_SYNCHRONIZED = "synchronized_group";
    public static final String REASON_BURST = "repeated_events";
    public static final String REASON_LOOP = "continuous_loop";
    public static final String REASON_CLUSTER = "large_cluster";

    private final ChunkCoordinate coord;
    private final String detector;
    private final String reason;
    private final int score;
    private final int eventsPerSecond;
    private final int positions;

    public LagSignal(ChunkCoordinate coord, String detector, String reason, int score,
            int eventsPerSecond, int positions) {
        this.coord = coord;
        this.detector = detector;
        this.reason = reason;
        this.score = score;
        this.eventsPerSecond = eventsPerSecond;
        this.positions = positions;
    }

    public ChunkCoordinate coord() {
        return coord;
    }

    public String detector() {
        return detector;
    }

    public String reason() {
        return reason;
    }

    public int score() {
        return score;
    }

    public int eventsPerSecond() {
        return eventsPerSecond;
    }

    public int positions() {
        return positions;
    }

    @Override
    public String toString() {
        return detector + "/" + reason + " score=" + score + " rate=" + eventsPerSecond
                + " positions=" + positions;
    }
}
