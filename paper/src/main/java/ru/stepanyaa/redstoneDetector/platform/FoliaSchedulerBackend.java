package ru.stepanyaa.redstoneDetector.platform;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.function.Consumer;

public final class FoliaSchedulerBackend extends BukkitSchedulerBackend {

    private final Object globalRegionScheduler;
    private final Object regionScheduler;
    private final Object asyncScheduler;

    private final Method globalRun;
    private final Method globalRunDelayed;
    private final Method globalRunAtFixedRate;

    private final Method regionRunLocation;
    private final Method regionRunChunk;

    private final Method asyncRunNow;

    private final Method getEntityScheduler;
    private final Method entityRun;

    private final Method taskCancel;
    private final Method teleportAsync;

    public FoliaSchedulerBackend(Plugin plugin) throws ReflectiveOperationException {
        super(plugin);

        this.globalRegionScheduler =
                Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
        this.regionScheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
        this.asyncScheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);

        Class<?> globalClass = globalRegionScheduler.getClass();
        this.globalRun = find(globalClass, "run", Plugin.class, Consumer.class);
        this.globalRunDelayed =
                find(globalClass, "runDelayed", Plugin.class, Consumer.class, long.class);
        this.globalRunAtFixedRate = find(globalClass, "runAtFixedRate",
                Plugin.class, Consumer.class, long.class, long.class);

        Class<?> regionClass = regionScheduler.getClass();
        this.regionRunLocation =
                find(regionClass, "run", Plugin.class, Location.class, Consumer.class);
        this.regionRunChunk = optional(regionClass, "run",
                Plugin.class, World.class, int.class, int.class, Consumer.class);

        this.asyncRunNow =
                find(asyncScheduler.getClass(), "runNow", Plugin.class, Consumer.class);

        this.getEntityScheduler = Entity.class.getMethod("getScheduler");
        this.entityRun = find(getEntityScheduler.getReturnType(), "run",
                Plugin.class, Consumer.class, Runnable.class);

        this.taskCancel = Class.forName(
                "io.papermc.paper.threadedregions.scheduler.ScheduledTask").getMethod("cancel");

        this.teleportAsync = optional(Entity.class, "teleportAsync", Location.class);
    }

    private static Method find(Class<?> owner, String name, Class<?>... params)
            throws NoSuchMethodException {
        Method method = owner.getMethod(name, params);
        method.setAccessible(true);
        return method;
    }

    private static Method optional(Class<?> owner, String name, Class<?>... params) {
        try {
            return find(owner, name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long ticks(long value) {
        return Math.max(1L, value);
    }

    private static Consumer<Object> adapt(final Runnable body) {
        return new Consumer<Object>() {
            @Override
            public void accept(Object scheduledTask) {
                body.run();
            }
        };
    }

    private Consumer<Object> adaptRepeating(final Consumer<DetectorTask> body) {
        return new Consumer<Object>() {
            @Override
            public void accept(Object scheduledTask) {
                body.accept(handleFor(scheduledTask));
            }
        };
    }

    private DetectorTask handleFor(final Object scheduledTask) {
        return new DetectorTask() {
            private volatile boolean cancelled;

            @Override
            public void cancel() {
                cancelled = true;
                try {
                    taskCancel.invoke(scheduledTask);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }
        };
    }

    private DetectorTask invoke(Method method, Object target, Object... args) {
        if (!canSchedule()) {
            return DetectorTask.NOOP;
        }
        try {
            Object scheduled = method.invoke(target, args);
            return scheduled == null ? DetectorTask.NOOP : track(handleFor(scheduled));
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Folia scheduling failed: " + throwable);
            return DetectorTask.NOOP;
        }
    }

    @Override
    public DetectorTask run(Runnable task) {
        if (!canSchedule()) {
            task.run();
            return DetectorTask.NOOP;
        }
        return invoke(globalRun, globalRegionScheduler, plugin, adapt(task));
    }

    @Override
    public DetectorTask delay(long delayTicks, Runnable task) {
        return invoke(globalRunDelayed, globalRegionScheduler, plugin,
                adapt(task), ticks(delayTicks));
    }

    @Override
    public DetectorTask timer(long delayTicks, long periodTicks, Runnable task) {
        return invoke(globalRunAtFixedRate, globalRegionScheduler, plugin,
                adapt(task), ticks(delayTicks), ticks(periodTicks));
    }

    @Override
    public DetectorTask timer(long delayTicks, long periodTicks, Consumer<DetectorTask> task) {
        return invoke(globalRunAtFixedRate, globalRegionScheduler, plugin,
                adaptRepeating(task), ticks(delayTicks), ticks(periodTicks));
    }

    @Override
    public DetectorTask async(Runnable task) {
        if (!canSchedule()) {
            task.run();
            return DetectorTask.NOOP;
        }
        return invoke(asyncRunNow, asyncScheduler, plugin, adapt(task));
    }

    @Override
    public void runAtLocation(Location location, Runnable task) {
        if (location == null || location.getWorld() == null) {
            run(task);
            return;
        }
        invoke(regionRunLocation, regionScheduler, plugin, location, adapt(task));
    }

    @Override
    public void runAtChunk(World world, int chunkX, int chunkZ, Runnable task) {
        if (world == null) {
            run(task);
            return;
        }
        if (regionRunChunk != null) {
            invoke(regionRunChunk, regionScheduler, plugin, world,
                    Integer.valueOf(chunkX), Integer.valueOf(chunkZ), adapt(task));
            return;
        }
        runAtLocation(new Location(world, chunkX * 16 + 8, 64, chunkZ * 16 + 8), task);
    }

    @Override
    public void runAtEntity(Entity entity, Runnable task) {
        if (entity == null) {
            run(task);
            return;
        }
        try {
            Object entityScheduler = getEntityScheduler.invoke(entity);
            invoke(entityRun, entityScheduler, plugin, adapt(task), noop());
        } catch (Throwable throwable) {
            run(task);
        }
    }

    @Override
    public void teleport(final Entity entity, final Location target) {
        if (teleportAsync != null) {
            try {
                teleportAsync.invoke(entity, target);
                return;
            } catch (Throwable ignored) {
            }
        }
        runAtEntity(entity, new Runnable() {
            @Override
            public void run() {
                entity.teleport(target);
            }
        });
    }

    private static Runnable noop() {
        return new Runnable() {
            @Override
            public void run() {
            }
        };
    }

    @Override
    public boolean isRegionised() {
        return true;
    }

    @Override
    public void cancelAll() {

        cancelTracked();
    }
}
