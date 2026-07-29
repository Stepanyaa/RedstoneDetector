package ru.stepanyaa.redstoneDetector.sponge;

import ru.stepanyaa.redstoneDetector.ChunkCoordinate;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.logging.Logger;

public final class SpongePlayerEvents {

    private final SpongeDetector detector;
    private final Object pluginContainer;
    private final Logger logger;

    public SpongePlayerEvents(SpongeDetector detector, Object pluginContainer, Logger logger) {
        this.detector = detector;
        this.pluginContainer = pluginContainer;
        this.logger = logger;
    }

    public void register() {
        listen("org.spongepowered.api.event.network.ServerSideConnectionEvent$Join", "join");

        boolean any = false;
        String[] chatEvents = {
            "org.spongepowered.api.event.message.PlayerChatEvent$Submit",
            "org.spongepowered.api.event.message.PlayerChatEvent",
            "org.spongepowered.api.event.message.MessageChannelEvent$Chat"
        };
        for (String chatEvent : chatEvents) {
            any |= listen(chatEvent, "chat");
        }
        if (!any) {
            logger.warning("No usable chat event on this Sponge API; "
                    + "use /rd search <x> <z> to look a chunk up.");
        }
    }

    private boolean listen(String eventClassName, final String action) {
        Class<?> eventClass = SpongeApi.typeOrNull(eventClassName);
        if (eventClass == null) {
            return false;
        }
        try {
            Class<?> listenerClass = SpongeApi.type("org.spongepowered.api.event.EventListener");
            Object listener = Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{listenerClass}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if (name.equals("handle") && args != null && args.length == 1) {
                                dispatch(action, args[0]);
                                return null;
                            }
                            if (name.equals("hashCode")) {
                                return Integer.valueOf(System.identityHashCode(proxy));
                            }
                            if (name.equals("equals")) {
                                return Boolean.valueOf(proxy == args[0]);
                            }
                            if (name.equals("toString")) {
                                return "RedstoneDetectorPlayerEvents";
                            }
                            return null;
                        }
                    });
            SpongeApi.registerListener(SpongeApi.eventManager(), pluginContainer, eventClass,
                    listener);
            return true;
        } catch (Throwable failure) {
            logger.warning("Could not listen to " + eventClassName + ": "
                    + SpongeApi.describe(failure));
            return false;
        }
    }

    private void dispatch(String action, Object event) {
        try {
            if (action.equals("join")) {
                onJoin(event);
            } else if (action.equals("chat")) {
                onChat(event);
            }
        } catch (Throwable failure) {
            logger.warning("Player event '" + action + "' failed: " + SpongeApi.describe(failure));
        }
    }

    private void onJoin(Object event) {
        if (!detector.config().updateCheckEnabled()) {
            return;
        }
        Object player = SpongeViewers.player(event);
        if (player == null) {
            Object connection = SpongeApi.callOrNull(event, "player");
            player = SpongeViewers.player(connection);
        }
        if (player == null) {
            return;
        }
        final Object target = player;
        detector.scheduler().delay(40L, new Runnable() {
            @Override
            public void run() {
                detector.updates().notifyViewer(target);
            }
        });
    }

    private void onChat(Object event) {
        Object player = chatPlayer(event);
        UUID id = SpongeViewers.uniqueId(player);
        if (id == null || !detector.gui().isAwaitingSearch(id)) {
            return;
        }
        String typed = SpongeViewers.plain(readMessage(event)).trim();
        if (typed.isEmpty()) {
            return;
        }
        cancel(event);

        if (typed.equalsIgnoreCase("cancel") || typed.equalsIgnoreCase("/rdcancel")) {
            detector.gui().cancelSearch(id);
            reply(player, "chat.search.cancelled", "&7Chunk search cancelled.");
            return;
        }
        String[] parts = typed.split("[\\s,;]+");
        if (parts.length < 2) {
            reply(player, "chat.search.invalid_format",
                    "&cUse two numbers: &f<x> <z>&c, or type &fcancel&c.");
            return;
        }
        int x;
        int z;
        try {
            x = toChunk(Integer.parseInt(parts[0].trim()));
            z = toChunk(Integer.parseInt(parts[1].trim()));
        } catch (NumberFormatException notNumbers) {
            reply(player, "chat.search.not_numbers", "&cThose are not numbers.");
            return;
        }

        detector.gui().cancelSearch(id);
        String world = detector.gui().pendingWorld(id);
        if (world == null) {
            world = currentWorld(player);
        }
        openSearchResult(detector, player, new ChunkCoordinate(world, x, z));
    }

    static void openSearchResult(final SpongeDetector detector, final Object viewer,
            final ChunkCoordinate coord) {
        detector.scheduler().run(new Runnable() {
            @Override
            public void run() {
                if (detector.engine().peek(coord) == null) {
                    detector.scanAndTrack(coord);
                }
                if (detector.engine().peek(coord) == null) {

                    send(detector, viewer, "chat.search.not_found",
                            "&7No data for &f{coord}&7.", coord);
                    send(detector, viewer, "chat.search.not_found_hint",
                            "&7Only chunks with real activity are monitored.", coord);
                    return;
                }
                send(detector, viewer, "chat.search.found", "&aFound chunk &f{coord}&a.", coord);
                detector.gui().openChunk(viewer, coord);
            }
        });
    }

    private static void send(SpongeDetector detector, Object viewer, String key, String fallback,
            ChunkCoordinate coord) {
        SpongeApi.send(SpongeViewers.audience(viewer), detector.messages().format(viewer, key,
                fallback,
                "{coord}", coord.toDisplayString(),
                "{chunk}", coord.toDisplayString(),
                "{world}", coord.world(),
                "{x1}", String.valueOf(coord.x() << 4),
                "{x2}", String.valueOf((coord.x() << 4) + 15),
                "{z1}", String.valueOf(coord.z() << 4),
                "{z2}", String.valueOf((coord.z() << 4) + 15)));
    }

    private Object chatPlayer(Object event) {
        Object player = SpongeViewers.player(event);
        if (player != null) {
            return player;
        }
        Object cause = SpongeApi.callOrNull(event, "cause");
        return cause == null ? null : SpongeViewers.player(cause);
    }

    static int toChunkCoordinate(int value) {
        return toChunk(value);
    }

    private static int toChunk(int value) {
        return Math.abs(value) >= 300 ? Math.floorDiv(value, 16) : value;
    }

    private String currentWorld(Object player) {
        try {
            Object world = SpongeApi.callOrNull(player, "world");
            Object key = world == null ? null : SpongeApi.callOrNull(world, "key");
            if (key != null) {
                return String.valueOf(key);
            }
        } catch (Throwable ignored) {

        }
        java.util.List<String> names = detector.worlds().worldNames();
        return names.isEmpty() ? "minecraft:overworld" : names.get(0);
    }

    private Object readMessage(Object event) {
        for (String accessor : new String[]{"message", "originalMessage"}) {
            Object message = SpongeApi.callOrNull(event, accessor);
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    private void cancel(Object event) {
        try {
            Class<?> cancellable = SpongeApi.typeOrNull(
                    "org.spongepowered.api.event.Cancellable");
            if (cancellable != null && cancellable.isInstance(event)) {
                Method setter = SpongeApi.method(cancellable, "setCancelled", boolean.class);
                setter.invoke(event, Boolean.TRUE);
            }
        } catch (Throwable ignored) {

        }
    }

    private void reply(Object player, String key, String fallback, String... replacements) {
        SpongeApi.send(SpongeViewers.audience(player),
                detector.messages().format(player, key, fallback, replacements));
    }
}
