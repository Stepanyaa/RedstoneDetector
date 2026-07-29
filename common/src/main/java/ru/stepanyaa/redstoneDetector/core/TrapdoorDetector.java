package ru.stepanyaa.redstoneDetector.core;

public final class TrapdoorDetector extends PositionActivityDetector {

    public static final String NAME = "trapdoor";

    public TrapdoorDetector() {
        super(NAME);
    }

    @Override
    protected void configure(Thresholds target, EngineSettings settings) {
        double sensitivity = settings.detectorSensitivity;
        target.eventsPerSecond = scale(settings.trapdoorTogglesPerSecond, sensitivity);
        target.clusterSize = scale(settings.trapdoorClusterSize, sensitivity);
        target.perPositionRate = scale(settings.trapdoorPositionRate, sensitivity);
        target.repeatBurst = scale(settings.trapdoorRepeatBurst, sensitivity);
        target.loopSeconds = Math.max(1, settings.trapdoorLoopSeconds);
        target.repeatWindowMillis = Math.max(20L, settings.trapdoorRepeatWindowMillis);
    }
}
