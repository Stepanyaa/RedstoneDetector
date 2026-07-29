package ru.stepanyaa.redstoneDetector.core;

import ru.stepanyaa.redstoneDetector.ChunkCoordinate;

public final class FreezeRecord {

    private final ChunkCoordinate coord;
    private final String detector;
    private final String reason;
    private final int lagScore;
    private final int mechanisms;
    private final int blockEntities;
    private final int entities;
    private final int updatesPerSecond;
    private final long startMillis;
    private final double msptBefore;

    private volatile long endMillis;
    private volatile double msptAfter;
    private volatile int suspendedBlocks;
    private volatile int restoredBlocks;

    public FreezeRecord(ChunkCoordinate coord, String detector, String reason, int lagScore,
            int mechanisms, int blockEntities, int entities, int updatesPerSecond,
            double msptBefore) {
        this.coord = coord;
        this.detector = detector == null ? "manual" : detector;
        this.reason = reason == null ? "none" : reason;
        this.lagScore = lagScore;
        this.mechanisms = mechanisms;
        this.blockEntities = blockEntities;
        this.entities = entities;
        this.updatesPerSecond = updatesPerSecond;
        this.msptBefore = msptBefore;
        this.startMillis = System.currentTimeMillis();
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

    public int lagScore() {
        return lagScore;
    }

    public int mechanisms() {
        return mechanisms;
    }

    public int blockEntities() {
        return blockEntities;
    }

    public int entities() {
        return entities;
    }

    public int updatesPerSecond() {
        return updatesPerSecond;
    }

    public long startMillis() {
        return startMillis;
    }

    public long endMillis() {
        return endMillis;
    }

    public double msptBefore() {
        return msptBefore;
    }

    public double msptAfter() {
        return msptAfter;
    }

    public int suspendedBlocks() {
        return suspendedBlocks;
    }

    public int restoredBlocks() {
        return restoredBlocks;
    }

    public void suspendedBlocks(int value) {
        this.suspendedBlocks = value;
    }

    public void restoredBlocks(int value) {
        this.restoredBlocks = value;
    }

    public void close(double serverMsptAfter) {
        this.msptAfter = serverMsptAfter;
        this.endMillis = System.currentTimeMillis();
    }

    public boolean closed() {
        return endMillis > 0L;
    }

    public long durationMillis() {
        long end = endMillis > 0L ? endMillis : System.currentTimeMillis();
        return Math.max(0L, end - startMillis);
    }

    public double improvementMspt() {
        return msptAfter <= 0.0 ? 0.0 : msptBefore - msptAfter;
    }

    public String describeStart() {
        return "freeze start world=" + coord.world() + " chunk=" + coord.x() + "," + coord.z()
                + " detector=" + detector + " reason=" + reason + " lagScore=" + lagScore
                + " mechanisms=" + mechanisms + " blockEntities=" + blockEntities
                + " entities=" + entities + " updates=" + updatesPerSecond
                + " mspt=" + round(msptBefore);
    }

    public String describeEnd() {
        return "freeze end world=" + coord.world() + " chunk=" + coord.x() + "," + coord.z()
                + " detector=" + detector + " heldSeconds=" + durationMillis() / 1000L
                + " suspended=" + suspendedBlocks + " restored=" + restoredBlocks
                + " msptBefore=" + round(msptBefore) + " msptAfter=" + round(msptAfter)
                + " gain=" + round(improvementMspt());
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
