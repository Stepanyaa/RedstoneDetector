package ru.stepanyaa.redstoneDetector.platform;

public interface DetectorTask {

    DetectorTask NOOP = new DetectorTask() {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    };

    void cancel();

    boolean isCancelled();
}
