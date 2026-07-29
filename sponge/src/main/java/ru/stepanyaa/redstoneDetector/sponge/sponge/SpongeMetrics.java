package ru.stepanyaa.redstoneDetector.sponge;

import dev.faststats.data.Metric;
import dev.faststats.sponge.SpongeContext;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

final class SpongeMetrics {

    private static final String TOKEN = "b518aa9851ae0e15397ea3c258d785f2";

    private final Logger logger;
    private volatile SpongeContext context;

    SpongeMetrics(Logger logger) {
        this.logger = logger;
    }

    void start(final Object pluginContainer, final SpongeDetector detector, final String version) {
        if (context != null || pluginContainer == null
                || TOKEN.isEmpty() || TOKEN.equals("YOUR_TOKEN_HERE")) {
            return;
        }
        try {
            org.spongepowered.plugin.PluginContainer plugin =
                    (org.spongepowered.plugin.PluginContainer) pluginContainer;
            context = new SpongeContext.Factory(plugin, configDirectory())
                    .token(TOKEN)
                    .metrics(factory -> factory
                            .addMetric(Metric.string("plugin_version", () -> version))
                            .addMetric(Metric.number("monitored_chunks",
                                    () -> detector.chunks().size()))
                            .addMetric(Metric.number("frozen_chunks",
                                    () -> detector.engine().frozenChunks().size()))
                            .create())
                    .create();
            context.ready();
            logger.info("FastStats metrics started.");
        } catch (Throwable failure) {
            context = null;
            logger.warning("FastStats metrics are not available: " + SpongeApi.describe(failure));
        }
    }

    void stop() {
        SpongeContext running = context;
        context = null;
        if (running == null) {
            return;
        }
        try {
            running.shutdown();
        } catch (Throwable ignored) {

        }
    }

    private Path configDirectory() {
        try {
            Object configManager = SpongeApi.call(SpongeApi.game(), "configManager");
            Object shared = SpongeApi.invoke(configManager, "sharedConfig",
                    SpongeApi.pluginContainer("redstonedetector"));
            Object directory = SpongeApi.callOrNull(shared, "directory");
            if (directory instanceof Path) {
                return (Path) directory;
            }
        } catch (Throwable unavailable) {

        }
        return Paths.get("config");
    }
}
