package ru.stepanyaa.redstoneDetector.sponge;

import ru.stepanyaa.redstoneDetector.ChunkCoordinate;
import ru.stepanyaa.redstoneDetector.core.BlockKinds;
import ru.stepanyaa.redstoneDetector.core.DetectorEngine;

import java.lang.invoke.MethodHandles;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.block.ChangeBlockEvent;
import org.spongepowered.api.event.block.NotifyNeighborBlockEvent;
import org.spongepowered.api.event.block.TickBlockEvent;

import java.lang.reflect.Method;
import java.util.logging.Logger;

public final class SpongeEvents {

    private static final Class<?> CANCELLABLE =
            SpongeApi.typeOrNull("org.spongepowered.api.event.Cancellable");
    private static final Method SET_CANCELLED = cancelSetter();
    private static final Class<?> SCHEDULED_TICK =
            SpongeApi.typeOrNull("org.spongepowered.api.event.block.TickBlockEvent$Scheduled");
    private static final Class<?> LOCATION_TYPE =
            SpongeApi.typeOrNull("org.spongepowered.api.world.server.ServerLocation");
    private static final Class<?> SNAPSHOT_TYPE =
            SpongeApi.typeOrNull("org.spongepowered.api.block.BlockSnapshot");

    private static Method cancelSetter() {
        try {
            return CANCELLABLE == null ? null
                    : SpongeApi.method(CANCELLABLE, "setCancelled", boolean.class);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static final class Hit {
        private ChunkCoordinate coord;
        private int x;
        private int y;
        private int z;
        private String name;

        private void reset() {
            coord = null;
            x = 0;
            y = 0;
            z = 0;
            name = null;
        }
    }

    private final Hit hit = new Hit();

    private final SpongeDetector detector;
    private final DetectorEngine engine;
    private final Object pluginContainer;
    private final Logger logger;

    private volatile boolean registered;

    public SpongeEvents(SpongeDetector detector, Object pluginContainer, Logger logger) {
        this.detector = detector;
        this.engine = detector.engine();
        this.pluginContainer = pluginContainer;
        this.logger = logger;
    }

    public synchronized void register() {
        if (registered) {
            return;
        }
        try {
            Object manager = SpongeApi.eventManager();
            Method register = SpongeApi.method(
                    SpongeApi.type("org.spongepowered.api.event.EventManager"),
                    "registerListeners",
                    SpongeApi.type("org.spongepowered.plugin.PluginContainer"),
                    Object.class, MethodHandles.Lookup.class);
            register.invoke(manager, pluginContainer, this, MethodHandles.lookup());
            registered = true;
            logger.info("Registered Sponge block activity listeners.");
        } catch (Throwable failure) {
            logger.warning("Could not register Sponge block listeners: "
                    + SpongeApi.describe(failure));
        }
    }

    @Listener(order = Order.FIRST, beforeModifications = true)
    public void onNeighbor(NotifyNeighborBlockEvent event) {
        handle(event, DetectorEngine.KIND_NEIGHBOR);
    }

    @Listener(order = Order.FIRST, beforeModifications = true)
    public void onChange(ChangeBlockEvent.All event) {
        handle(event, DetectorEngine.KIND_PHYSICS);
    }

    @Listener(order = Order.FIRST, beforeModifications = true)
    public void onTick(TickBlockEvent event) {
        int kind = SCHEDULED_TICK != null && SCHEDULED_TICK.isInstance(event)
                ? DetectorEngine.KIND_SCHEDULED
                : DetectorEngine.KIND_REDSTONE;
        handle(event, kind);
    }

    private void handle(Object event, int kind) {
        if (detector.globalStop()) {
            cancel(event);
            return;
        }
        hit.reset();
        if (!locate(event)) {
            return;
        }
        ChunkCoordinate coord = hit.coord;
        if (coord == null) {
            return;
        }
        if (detector.isSuppressed(coord) || engine.isFrozen(coord)) {
            cancel(event);
            return;
        }
        if (engine.isIgnored(coord)) {
            return;
        }

        int resolved = kind;
        String name = hit.name;
        if (name != null) {
            if (!engine.settings().ignoredBlocks.isEmpty()
                    && engine.settings().ignoredBlocks.contains(BlockKinds.normalize(name))) {
                return;
            }
            int classified = BlockKinds.of(name);
            if (classified != DetectorEngine.KIND_PHYSICS) {
                resolved = classified;
            }
        }

        long started = System.nanoTime();
        engine.recordUpdate(coord, resolved);
        if (resolved == DetectorEngine.KIND_SCULK || resolved == DetectorEngine.KIND_TRAPDOOR) {
            engine.recordPosition(coord, resolved, hit.x, hit.y, hit.z,
                    System.currentTimeMillis());
        }
        engine.recordNanos(coord, System.nanoTime() - started);
    }

    private void cancel(Object event) {
        Method setter = SET_CANCELLED;
        if (setter == null || CANCELLABLE == null || !CANCELLABLE.isInstance(event)) {
            return;
        }
        try {
            setter.invoke(event, Boolean.TRUE);
        } catch (Throwable ignored) {
            return;
        }
    }

    private boolean locate(Object event) {
        if (fromTargetBlock(event)) {
            return true;
        }
        if (fromCause(event)) {
            return true;
        }
        return fromTransactions(event);
    }

    private boolean fromTargetBlock(Object event) {
        Object snapshot = SpongeApi.callOrNull(event, "targetBlock");
        return snapshot != null && fromSnapshot(snapshot);
    }

    private boolean fromCause(Object event) {
        try {
            Object cause = SpongeApi.callOrNull(event, "cause");
            if (cause == null) {
                return false;
            }
            Method first = SpongeApi.method(cause.getClass(), "first", Class.class);
            if (LOCATION_TYPE != null) {
                Object location = SpongeApi.unwrap(first.invoke(cause, LOCATION_TYPE));
                if (location != null) {
                    hit.x = SpongeApi.integer(SpongeApi.callOrNull(location, "blockX"), 0);
                    hit.y = SpongeApi.integer(SpongeApi.callOrNull(location, "blockY"), 0);
                    hit.z = SpongeApi.integer(SpongeApi.callOrNull(location, "blockZ"), 0);
                    hit.name = SpongeApi.registryName(SpongeApi.callOrNull(location, "block"));
                    hit.coord = coordinate(SpongeApi.callOrNull(location, "worldKey"),
                            hit.x, hit.z);
                    return hit.coord != null;
                }
            }
            if (SNAPSHOT_TYPE != null) {
                Object snapshot = SpongeApi.unwrap(first.invoke(cause, SNAPSHOT_TYPE));
                if (snapshot != null) {
                    return fromSnapshot(snapshot);
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    private boolean fromTransactions(Object event) {
        try {
            Object transactions = SpongeApi.callOrNull(event, "transactions");
            if (transactions == null) {
                return false;
            }
            for (Object transaction : SpongeApi.iterable(transactions)) {
                Object snapshot = SpongeApi.callOrNull(transaction, "original");
                if (snapshot == null) {
                    continue;
                }
                if (fromSnapshot(snapshot)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    private boolean fromSnapshot(Object snapshot) {
        Object position = SpongeApi.callOrNull(snapshot, "position");
        if (position == null) {
            return false;
        }
        hit.x = SpongeApi.integer(SpongeApi.callOrNull(position, "x"), 0);
        hit.y = SpongeApi.integer(SpongeApi.callOrNull(position, "y"), 0);
        hit.z = SpongeApi.integer(SpongeApi.callOrNull(position, "z"), 0);
        hit.name = SpongeApi.registryName(SpongeApi.callOrNull(snapshot, "state"));
        hit.coord = coordinate(SpongeApi.callOrNull(snapshot, "world"), hit.x, hit.z);
        return hit.coord != null;
    }

    private ChunkCoordinate coordinate(Object worldKey, int blockX, int blockZ) {
        String world = worldKey == null ? "minecraft:overworld" : String.valueOf(worldKey);
        return new ChunkCoordinate(world, blockX >> 4, blockZ >> 4);
    }
}
