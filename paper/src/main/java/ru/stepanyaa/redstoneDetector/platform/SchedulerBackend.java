package ru.stepanyaa.redstoneDetector.platform;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.function.Consumer;

public interface SchedulerBackend {

    DetectorTask run(Runnable task);

    DetectorTask delay(long delayTicks, Runnable task);

    DetectorTask timer(long delayTicks, long periodTicks, Runnable task);

    DetectorTask timer(long delayTicks, long periodTicks, Consumer<DetectorTask> task);

    DetectorTask async(Runnable task);

    void runAtLocation(Location location, Runnable task);

    void runAtChunk(World world, int chunkX, int chunkZ, Runnable task);

    void runAtEntity(Entity entity, Runnable task);

    void teleport(Entity entity, Location target);

    boolean isRegionised();

    void cancelAll();
}
