package ru.stepanyaa.redstoneDetector.core;

import ru.stepanyaa.redstoneDetector.platform.DetectorTask;

public interface EngineScheduler {

    DetectorTask run(Runnable task);

    DetectorTask delay(long delayTicks, Runnable task);

    DetectorTask timer(long delayTicks, long periodTicks, Runnable task);

    DetectorTask async(Runnable task);
}
