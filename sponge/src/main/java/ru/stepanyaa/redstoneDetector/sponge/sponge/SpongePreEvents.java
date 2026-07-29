package ru.stepanyaa.redstoneDetector.sponge;

import ru.stepanyaa.redstoneDetector.ChunkCoordinate;

import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.block.ChangeBlockEvent;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.logging.Logger;

public final class SpongePreEvents {

    private final SpongeDetector detector;

    private SpongePreEvents(SpongeDetector detector) {
        this.detector = detector;
    }

    public static void register(SpongeDetector detector, Object pluginContainer, Logger logger) {
        try {
            if (SpongeApi.typeOrNull("org.spongepowered.api.event.block.ChangeBlockEvent$Pre")
                    == null) {
                logger.info("This Sponge API has no ChangeBlockEvent.Pre; "
                        + "redstone is stopped on the regular block events instead.");
                return;
            }
            Object manager = SpongeApi.eventManager();
            Method register = SpongeApi.method(
                    SpongeApi.type("org.spongepowered.api.event.EventManager"),
                    "registerListeners",
                    SpongeApi.type("org.spongepowered.plugin.PluginContainer"),
                    Object.class, MethodHandles.Lookup.class);
            register.invoke(manager, pluginContainer, new SpongePreEvents(detector),
                    MethodHandles.lookup());
            logger.info("Registered the early block change listener.");
        } catch (Throwable failure) {
            logger.warning("Could not register the early block change listener: "
                    + SpongeApi.describe(failure));
        }
    }

    @Listener(order = Order.FIRST, beforeModifications = true)
    public void onPre(ChangeBlockEvent.Pre event) {
        for (ChunkCoordinate coord : positions(event)) {
            if (detector.isSuppressed(coord) || detector.engine().isFrozen(coord)) {
                cancel(event);
                return;
            }
        }
    }

    private java.util.List<ChunkCoordinate> positions(Object event) {
        java.util.List<ChunkCoordinate> found = new java.util.ArrayList<ChunkCoordinate>();
        try {
            Object locations = SpongeApi.callOrNull(event, "locations");
            if (locations != null) {
                for (Object location : SpongeApi.iterable(locations)) {
                    int x = SpongeApi.integer(SpongeApi.callOrNull(location, "blockX"), 0);
                    int z = SpongeApi.integer(SpongeApi.callOrNull(location, "blockZ"), 0);
                    Object key = SpongeApi.callOrNull(location, "worldKey");
                    String world = key == null ? "minecraft:overworld" : String.valueOf(key);
                    found.add(new ChunkCoordinate(world, x >> 4, z >> 4));
                }
            }
        } catch (Throwable ignored) {

        }
        return found;
    }

    private void cancel(Object event) {
        try {
            Class<?> cancellable =
                    SpongeApi.typeOrNull("org.spongepowered.api.event.Cancellable");
            if (cancellable != null && cancellable.isInstance(event)) {
                Method setter = SpongeApi.method(cancellable, "setCancelled", boolean.class);
                setter.invoke(event, Boolean.TRUE);
            }
        } catch (Throwable ignored) {

        }
    }
}
