package ru.stepanyaa.redstoneDetector.sponge;

import ru.stepanyaa.redstoneDetector.core.EngineScheduler;
import ru.stepanyaa.redstoneDetector.platform.DetectorTask;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class SpongeScheduler implements EngineScheduler {

    private static final long MILLIS_PER_TICK = 50L;

    private final Object pluginContainer;
    private final Logger logger;

    private final Class<?> taskClass;
    private final Class<?> builderClass;
    private final Class<?> ticksClass;
    private final Method taskBuilder;
    private final Method builderExecute;
    private final Method builderDelayTicks;
    private final Method builderIntervalTicks;
    private final Method builderPlugin;
    private final Method builderBuild;
    private final Method ticksOf;

    private final Set<Object> tracked =
            Collections.synchronizedSet(Collections.<Object>newSetFromMap(
                    new WeakHashMap<Object, Boolean>()));

    private volatile boolean stopping;

    public SpongeScheduler(Object pluginContainer, Logger logger)
            throws ReflectiveOperationException {
        this.pluginContainer = pluginContainer;
        this.logger = logger;
        this.taskClass = SpongeApi.type("org.spongepowered.api.scheduler.Task");
        this.builderClass = SpongeApi.type("org.spongepowered.api.scheduler.Task$Builder");
        this.ticksClass = SpongeApi.type("org.spongepowered.api.util.Ticks");
        this.taskBuilder = SpongeApi.method(taskClass, "builder");
        this.builderExecute = SpongeApi.method(builderClass, "execute", Runnable.class);
        this.builderDelayTicks = SpongeApi.method(builderClass, "delay", ticksClass);
        this.builderIntervalTicks = SpongeApi.method(builderClass, "interval", ticksClass);
        this.builderPlugin = SpongeApi.method(builderClass, "plugin",
                SpongeApi.type("org.spongepowered.plugin.PluginContainer"));
        this.builderBuild = SpongeApi.method(builderClass, "build");
        this.ticksOf = SpongeApi.method(ticksClass, "of", long.class);
    }

    private Object ticks(long value) throws ReflectiveOperationException {
        return ticksOf.invoke(null, Long.valueOf(Math.max(0L, value)));
    }

    private Object buildSync(Runnable body, long delayTicks, long periodTicks)
            throws ReflectiveOperationException {
        Object builder = taskBuilder.invoke(null);
        builder = builderExecute.invoke(builder, body);
        builder = builderDelayTicks.invoke(builder, ticks(delayTicks));
        if (periodTicks > 0L) {
            builder = builderIntervalTicks.invoke(builder, ticks(Math.max(1L, periodTicks)));
        }
        builder = builderPlugin.invoke(builder, pluginContainer);
        return builderBuild.invoke(builder);
    }

    private Object buildAsync(Runnable body, long delayTicks, long periodTicks)
            throws ReflectiveOperationException {
        Object builder = taskBuilder.invoke(null);
        builder = builderExecute.invoke(builder, body);
        Method delayMillis = SpongeApi.method(builderClass, "delay", long.class, TimeUnit.class);
        builder = delayMillis.invoke(builder,
                Long.valueOf(Math.max(0L, delayTicks) * MILLIS_PER_TICK), TimeUnit.MILLISECONDS);
        if (periodTicks > 0L) {
            Method intervalMillis =
                    SpongeApi.method(builderClass, "interval", long.class, TimeUnit.class);
            builder = intervalMillis.invoke(builder,
                    Long.valueOf(Math.max(1L, periodTicks) * MILLIS_PER_TICK),
                    TimeUnit.MILLISECONDS);
        }
        builder = builderPlugin.invoke(builder, pluginContainer);
        return builderBuild.invoke(builder);
    }

    private Object serverScheduler() throws ReflectiveOperationException {
        return SpongeApi.call(SpongeApi.server(), "scheduler");
    }

    private Object asyncScheduler() throws ReflectiveOperationException {
        return SpongeApi.staticCall("org.spongepowered.api.Sponge", "asyncScheduler");
    }

    private DetectorTask submit(Object scheduler, Object task) throws ReflectiveOperationException {
        Object handle = SpongeApi.method(scheduler.getClass(), "submit", taskClass)
                .invoke(scheduler, task);
        tracked.add(handle);
        return wrap(handle);
    }

    private DetectorTask wrap(final Object handle) {
        return new DetectorTask() {
            private volatile boolean cancelled;

            @Override
            public void cancel() {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                tracked.remove(handle);
                try {
                    SpongeApi.call(handle, "cancel");
                } catch (Throwable failure) {
                    logger.warning("Could not cancel a Sponge task: " + failure);
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }
        };
    }

    private DetectorTask inline(Runnable body, Throwable reason) {
        if (reason != null && !stopping) {
            logger.warning("Sponge scheduling failed, running inline: "
                    + SpongeApi.describe(reason));
        }
        try {
            body.run();
        } catch (Throwable failure) {
            logger.warning("Inline task failed: " + failure);
        }
        return DetectorTask.NOOP;
    }

    @Override
    public DetectorTask run(Runnable task) {
        return delay(0L, task);
    }

    @Override
    public DetectorTask delay(long delayTicks, Runnable task) {
        try {
            return submit(serverScheduler(), buildSync(task, delayTicks, 0L));
        } catch (Throwable failure) {
            return inline(task, failure);
        }
    }

    @Override
    public DetectorTask timer(long delayTicks, long periodTicks, Runnable task) {
        try {
            return submit(serverScheduler(), buildSync(task, delayTicks, Math.max(1L, periodTicks)));
        } catch (Throwable failure) {
            return inline(task, failure);
        }
    }

    @Override
    public DetectorTask async(Runnable task) {
        try {
            return submit(asyncScheduler(), buildAsync(task, 0L, 0L));
        } catch (Throwable failure) {
            return inline(task, failure);
        }
    }

    public boolean onMainThread() {
        try {
            return Boolean.TRUE.equals(SpongeApi.call(SpongeApi.server(), "onMainThread"));
        } catch (Throwable unavailable) {
            return false;
        }
    }

    public void cancelAll() {
        stopping = true;
        Object[] snapshot;
        synchronized (tracked) {
            snapshot = tracked.toArray();
        }
        for (Object handle : snapshot) {
            try {
                SpongeApi.call(handle, "cancel");
            } catch (Throwable ignored) {

            }
        }
        tracked.clear();
    }
}
