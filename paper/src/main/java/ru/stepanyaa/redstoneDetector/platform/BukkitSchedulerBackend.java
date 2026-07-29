package ru.stepanyaa.redstoneDetector.platform;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

public class BukkitSchedulerBackend implements SchedulerBackend {

    protected final Plugin plugin;

    private final Set<DetectorTask> tracked =
            Collections.newSetFromMap(new WeakHashMap<DetectorTask, Boolean>());

    public BukkitSchedulerBackend(Plugin plugin) {
        this.plugin = plugin;
    }

    protected boolean canSchedule() {
        try {
            return plugin.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    protected DetectorTask track(DetectorTask handle) {
        synchronized (tracked) {
            tracked.add(handle);
        }
        return handle;
    }

    protected void cancelTracked() {
        synchronized (tracked) {
            for (DetectorTask task : tracked) {
                try {
                    task.cancel();
                } catch (Throwable ignored) {
                }
            }
            tracked.clear();
        }
    }

    private DetectorTask wrap(final BukkitTask scheduled) {
        if (scheduled == null) {
            return DetectorTask.NOOP;
        }
        return track(new DetectorTask() {
            private volatile boolean cancelled;

            @Override
            public void cancel() {
                cancelled = true;
                try {
                    scheduled.cancel();
                } catch (Throwable ignored) {
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }
        });
    }

    @Override
    public DetectorTask run(Runnable task) {
        if (!canSchedule()) {
            task.run();
            return DetectorTask.NOOP;
        }
        return wrap(Bukkit.getScheduler().runTask(plugin, task));
    }

    @Override
    public DetectorTask delay(long delayTicks, Runnable task) {
        if (!canSchedule()) {
            return DetectorTask.NOOP;
        }
        return wrap(Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1L, delayTicks)));
    }

    @Override
    public DetectorTask timer(long delayTicks, long periodTicks, Runnable task) {
        if (!canSchedule()) {
            return DetectorTask.NOOP;
        }
        return wrap(Bukkit.getScheduler().runTaskTimer(plugin, task,
                Math.max(1L, delayTicks), Math.max(1L, periodTicks)));
    }

    @Override
    public DetectorTask timer(long delayTicks, long periodTicks,
                             final Consumer<DetectorTask> task) {
        if (!canSchedule()) {
            return DetectorTask.NOOP;
        }
        final MutableHandle handle = new MutableHandle();
        BukkitTask scheduled = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                task.accept(handle);
            }
        }, Math.max(1L, delayTicks), Math.max(1L, periodTicks));
        handle.bind(scheduled);
        return track(handle);
    }

    @Override
    public DetectorTask async(Runnable task) {
        if (!canSchedule()) {
            task.run();
            return DetectorTask.NOOP;
        }
        return wrap(Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
    }

    @Override
    public void runAtLocation(Location location, Runnable task) {
        runSync(task);
    }

    @Override
    public void runAtChunk(World world, int chunkX, int chunkZ, Runnable task) {
        runSync(task);
    }

    @Override
    public void runAtEntity(Entity entity, Runnable task) {
        runSync(task);
    }

    @Override
    public void teleport(final Entity entity, final Location target) {
        runSync(new Runnable() {
            @Override
            public void run() {
                entity.teleport(target);
            }
        });
    }

    private void runSync(Runnable task) {
        boolean primary;
        try {
            primary = Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {
            primary = false;
        }
        if (primary) {
            task.run();
            return;
        }
        run(task);
    }

    @Override
    public boolean isRegionised() {
        return false;
    }

    @Override
    public void cancelAll() {
        cancelTracked();
        try {
            Bukkit.getScheduler().cancelTasks(plugin);
        } catch (Throwable ignored) {
        }
    }

    protected static final class MutableHandle implements DetectorTask {
        private volatile BukkitTask scheduled;
        private volatile boolean cancelled;

        void bind(BukkitTask task) {
            this.scheduled = task;
            if (cancelled) {
                try {
                    task.cancel();
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            BukkitTask current = scheduled;
            if (current != null) {
                try {
                    current.cancel();
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
