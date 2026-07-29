package ru.stepanyaa.redstoneDetector.core;

public final class SculkSensorDetector extends PositionActivityDetector {

    public static final String NAME = "sculk_sensor";

    public SculkSensorDetector() {
        super(NAME);
    }

    @Override
    protected void configure(Thresholds target, EngineSettings settings) {
        double sensitivity = settings.detectorSensitivity;
        target.eventsPerSecond = scale(settings.sculkActivationsPerSecond, sensitivity);
        target.clusterSize = scale(settings.sculkGroupSize, sensitivity);
        target.perPositionRate = scale(settings.sculkPositionRate, sensitivity);
        target.repeatBurst = scale(settings.sculkRepeatBurst, sensitivity);
        target.loopSeconds = Math.max(1, settings.sculkLoopSeconds);
        target.repeatWindowMillis = Math.max(20L, settings.sculkRepeatWindowMillis);
    }
}
