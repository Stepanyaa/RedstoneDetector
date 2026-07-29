package ru.stepanyaa.redstoneDetector.sponge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class SpongeViewers {

    private SpongeViewers() {
    }

    public static Class<?> playerClass() {
        return SpongeApi.typeOrNull(
                "org.spongepowered.api.entity.living.player.server.ServerPlayer");
    }

    public static Object player(Object viewer) {
        if (viewer == null) {
            return null;
        }
        Class<?> playerClass = playerClass();
        if (playerClass == null) {
            return null;
        }
        if (playerClass.isInstance(viewer)) {
            return viewer;
        }

        for (String accessor : new String[]{"root", "subject", "audience", "player"}) {
            Object candidate = SpongeApi.unwrap(SpongeApi.callOrNull(viewer, accessor));
            if (candidate != null && playerClass.isInstance(candidate)) {
                return candidate;
            }
        }

        Object player = firstOf(viewer, playerClass);
        if (player != null) {
            return player;
        }
        Object cause = SpongeApi.callOrNull(viewer, "cause");
        return cause == null ? null : firstOf(cause, playerClass);
    }

    private static Object firstOf(Object holder, Class<?> wanted) {
        try {
            Method first = SpongeApi.method(holder.getClass(), "first", Class.class);
            return SpongeApi.unwrap(first.invoke(holder, wanted));
        } catch (Throwable notACause) {
            return null;
        }
    }

    public static String clientLocale(Object viewer) {
        Object player = player(viewer);
        if (player == null) {
            return null;
        }
        Object reported = SpongeApi.callOrNull(player, "locale");
        if (reported == null) {
            reported = SpongeApi.callOrNull(player, "getLocale");
        }
        if (reported == null) {
            return null;
        }
        if (reported instanceof Locale) {
            Locale locale = (Locale) reported;
            String language = locale.getLanguage();
            if (language == null || language.isEmpty()) {
                return null;
            }
            String country = locale.getCountry();
            return country == null || country.isEmpty()
                    ? language.toLowerCase(Locale.ROOT)
                    : (language + "_" + country).toLowerCase(Locale.ROOT);
        }
        String text = String.valueOf(reported).trim();
        return text.isEmpty() ? null : text.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    public static UUID uniqueId(Object viewer) {
        Object player = player(viewer);
        if (player == null) {
            return null;
        }
        Object id = SpongeApi.callOrNull(player, "uniqueId");
        return id instanceof UUID ? (UUID) id : null;
    }

    public static String name(Object viewer) {
        Object player = player(viewer);
        if (player == null) {
            return "console";
        }
        Object name = SpongeApi.callOrNull(player, "name");
        return name == null ? "player" : String.valueOf(name);
    }

    public static Object playerById(UUID id) {
        if (id == null) {
            return null;
        }
        try {
            Object server = SpongeApi.server();
            Method lookup = SpongeApi.method(server.getClass(), "player", UUID.class);
            return SpongeApi.unwrap(lookup.invoke(server, id));
        } catch (Throwable unavailable) {
            return null;
        }
    }

    public static List<Object> onlinePlayers() {
        List<Object> players = new ArrayList<Object>();
        try {
            for (Object player : SpongeApi.iterable(
                    SpongeApi.call(SpongeApi.server(), "onlinePlayers"))) {
                players.add(player);
            }
        } catch (Throwable unavailable) {

        }
        return players;
    }

    public static boolean hasPermission(Object viewer, String permission) {
        if (viewer == null) {
            return false;
        }
        for (Object candidate : new Object[]{viewer, SpongeApi.callOrNull(viewer, "subject"),
                player(viewer)}) {
            if (candidate == null) {
                continue;
            }
            try {
                Method check = SpongeApi.method(candidate.getClass(), "hasPermission",
                        String.class);
                Object result = check.invoke(candidate, permission);
                if (result instanceof Boolean) {
                    return ((Boolean) result).booleanValue();
                }
            } catch (Throwable ignored) {

            }
        }
        return false;
    }

    public static Object audience(Object viewer) {
        if (viewer == null) {
            return null;
        }
        Class<?> audienceClass = SpongeApi.typeOrNull("net.kyori.adventure.audience.Audience");
        if (audienceClass != null && audienceClass.isInstance(viewer)) {
            return viewer;
        }
        Object audience = SpongeApi.callOrNull(viewer, "audience");
        if (audience != null) {
            return audience;
        }
        Object player = player(viewer);
        return player != null ? player : viewer;
    }

    public static String plain(Object component) {
        if (component == null) {
            return "";
        }
        if (component instanceof String) {
            return (String) component;
        }
        try {
            Class<?> serializerClass = SpongeApi.type(
                    "net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
            Object serializer = SpongeApi.method(serializerClass, "plainText").invoke(null);
            Class<?> componentClass = SpongeApi.type("net.kyori.adventure.text.Component");
            Object text = SpongeApi.method(serializerClass, "serialize", componentClass)
                    .invoke(serializer, component);
            return text == null ? "" : String.valueOf(text);
        } catch (Throwable unavailable) {
            return String.valueOf(component);
        }
    }
}
