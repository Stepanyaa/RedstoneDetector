package ru.stepanyaa.redstoneDetector.sponge;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SpongeApi {

    private static final Map<String, Class<?>> CLASSES = new ConcurrentHashMap<String, Class<?>>();
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<String, Method>();
    private static volatile Object serverOverride;

    private SpongeApi() {
    }

    public static boolean present() {
        try {
            type("org.spongepowered.api.Sponge");
            return true;
        } catch (Throwable notSponge) {
            return false;
        }
    }

    public static Class<?> type(String name) throws ClassNotFoundException {
        Class<?> cached = CLASSES.get(name);
        if (cached != null) {
            return cached;
        }
        Class<?> resolved = Class.forName(name);
        CLASSES.put(name, resolved);
        return resolved;
    }

    public static Class<?> typeOrNull(String name) {
        try {
            return type(name);
        } catch (Throwable missing) {
            return null;
        }
    }

    public static Method method(Class<?> owner, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        StringBuilder key = new StringBuilder(owner.getName()).append('#').append(name);
        for (Class<?> parameter : parameters) {
            key.append(':').append(parameter.getName());
        }
        String cacheKey = key.toString();
        Method cached = METHODS.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Method resolved = accessible(owner, owner.getMethod(name, parameters), name, parameters);
        METHODS.put(cacheKey, resolved);
        return resolved;
    }

    private static Method accessible(Class<?> owner, Method resolved, String name,
            Class<?>[] parameters) {
        Method best = resolved;
        if (!exported(best.getDeclaringClass())) {
            Method fromInterface = searchInterfaces(owner, name, parameters);
            if (fromInterface != null) {
                best = fromInterface;
            }
        }
        try {
            best.setAccessible(true);
        } catch (Throwable sealed) {

        }
        return best;
    }

    private static boolean exported(Class<?> type) {
        if (!java.lang.reflect.Modifier.isPublic(type.getModifiers())) {
            return false;
        }
        try {
            Module module = type.getModule();
            if (module == null || !module.isNamed()) {
                return true;
            }
            return module.isExported(type.getPackageName());
        } catch (Throwable unknown) {
            return true;
        }
    }

    private static Method searchInterfaces(Class<?> owner, String name, Class<?>[] parameters) {
        for (Class<?> candidate : allInterfaces(owner)) {
            if (!exported(candidate)) {
                continue;
            }
            try {
                return candidate.getMethod(name, parameters);
            } catch (NoSuchMethodException notHere) {

            }
        }
        return null;
    }

    public static Object call(Object target, String name) throws ReflectiveOperationException {
        return method(target.getClass(), name).invoke(target);
    }

    public static Method apiMethod(Object target, String[] interfaceNames, String name,
            Class<?>... parameters) throws NoSuchMethodException {
        if (target == null) {
            throw new NoSuchMethodException(name);
        }
        for (String interfaceName : interfaceNames) {
            Class<?> declared = typeOrNull(interfaceName);
            if (declared == null || !declared.isInstance(target)) {
                continue;
            }
            try {
                return method(declared, name, parameters);
            } catch (NoSuchMethodException notHere) {

            }
        }
        for (Class<?> declared : allInterfaces(target.getClass())) {
            try {
                return method(declared, name, parameters);
            } catch (NoSuchMethodException notHere) {

            }
        }
        return method(target.getClass(), name, parameters);
    }

    private static java.util.List<Class<?>> allInterfaces(Class<?> start) {
        java.util.List<Class<?>> found = new java.util.ArrayList<Class<?>>();
        java.util.ArrayDeque<Class<?>> queue = new java.util.ArrayDeque<Class<?>>();
        queue.add(start);
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            for (Class<?> candidate : current.getInterfaces()) {
                if (!found.contains(candidate)) {
                    found.add(candidate);
                    queue.add(candidate);
                }
            }
            if (current.getSuperclass() != null) {
                queue.add(current.getSuperclass());
            }
        }
        return found;
    }

    public static Object invoke(Object target, String name, Object... args)
            throws ReflectiveOperationException {
        if (target == null) throw new NullPointerException("target");
        Method best = null;
        search:
        for (Method candidate : target.getClass().getMethods()) {
            if (!candidate.getName().equals(name)) continue;
            Class<?>[] types = candidate.getParameterTypes();
            if (types.length != args.length) continue;
            for (int i = 0; i < types.length; i++) {
                if (args[i] == null) {
                    if (types[i].isPrimitive()) continue search;
                } else if (!box(types[i]).isAssignableFrom(args[i].getClass())) {
                    continue search;
                }
            }
            best = candidate; break;
        }
        if (best == null) throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
        best = accessible(target.getClass(), best, name, best.getParameterTypes());
        return best.invoke(target, args);
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    public static Object callOrNull(Object target, String name) {
        try {
            return call(target, name);
        } catch (Throwable failure) {
            return null;
        }
    }

    public static Object staticCall(String className, String name)
            throws ReflectiveOperationException {
        return method(type(className), name).invoke(null);
    }

    public static Object game() throws ReflectiveOperationException {
        return staticCall("org.spongepowered.api.Sponge", "game");
    }

    public static Object server() throws ReflectiveOperationException {
        Object cached = serverOverride;
        return cached != null ? cached : staticCall("org.spongepowered.api.Sponge", "server");
    }

    public static void setServer(Object server) {
        serverOverride = server;
    }

    public static Object eventManager() throws ReflectiveOperationException {
        try {
            return staticCall("org.spongepowered.api.Sponge", "eventManager");
        } catch (ReflectiveOperationException olderApi) {
            return call(game(), "eventManager");
        }
    }

    public static void registerListener(Object manager, Object pluginContainer,
            Class<?> eventClass, Object listener) throws ReflectiveOperationException {
        Class<?> registrationClass = typeOrNull(
                "org.spongepowered.api.event.EventListenerRegistration");
        if (registrationClass != null) {
            try {
                Object builder = method(registrationClass, "builder", Class.class)
                        .invoke(null, eventClass);
                Class<?> builderClass = type(
                        "org.spongepowered.api.event.EventListenerRegistration$Builder");
                Class<?> pluginClass = type("org.spongepowered.plugin.PluginContainer");
                Class<?> listenerClass = type("org.spongepowered.api.event.EventListener");
                builder = method(builderClass, "plugin", pluginClass)
                        .invoke(builder, pluginContainer);
                builder = method(builderClass, "listener", listenerClass)
                        .invoke(builder, listener);
                Object registration = method(builderClass, "build").invoke(builder);
                method(type("org.spongepowered.api.event.EventManager"),
                        "registerListener", registrationClass).invoke(manager, registration);
                return;
            } catch (NoSuchMethodException unsupportedModernShape) {

            }
        }

        for (Method candidate : manager.getClass().getMethods()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (candidate.getName().equals("registerListener") && parameters.length == 3
                    && parameters[1] == Class.class) {
                candidate.setAccessible(true);
                candidate.invoke(manager, pluginContainer, eventClass, listener);
                return;
            }
        }
        throw new NoSuchMethodException("No compatible Sponge listener registration API");
    }

    public static Object pluginContainer(String pluginId) {
        try {
            Object manager;
            try {
                manager = staticCall("org.spongepowered.api.Sponge", "pluginManager");
            } catch (ReflectiveOperationException olderApi) {
                manager = call(game(), "pluginManager");
            }
            Object optional = method(manager.getClass(), "plugin", String.class)
                    .invoke(manager, pluginId);
            return unwrap(optional);
        } catch (Throwable failure) {
            return null;
        }
    }

    public static Object unwrap(Object optional) {
        if (optional == null) {
            return null;
        }
        if (!(optional instanceof java.util.Optional)) {
            return optional;
        }
        java.util.Optional<?> value = (java.util.Optional<?>) optional;
        return value.isPresent() ? value.get() : null;
    }

    public static Object component(String legacy) throws ReflectiveOperationException {
        Class<?> serializerClass =
                type("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
        Object serializer = method(serializerClass, "legacyAmpersand").invoke(null);
        return method(serializer.getClass(), "deserialize", String.class)
                .invoke(serializer, legacy == null ? "" : legacy);
    }

    public static void send(Object audience, String legacy) {
        if (audience == null) {
            return;
        }
        try {
            Class<?> audienceClass = type("net.kyori.adventure.audience.Audience");
            Class<?> componentClass = type("net.kyori.adventure.text.Component");
            method(audienceClass, "sendMessage", componentClass)
                    .invoke(audience, component(legacy));
        } catch (Throwable ignored) {

        }
    }

    public static Iterable<?> iterable(Object value) {
        if (value instanceof Iterable) {
            return (Iterable<?>) value;
        }
        if (value instanceof Object[]) {
            return java.util.Arrays.asList((Object[]) value);
        }
        return Collections.emptyList();
    }

    public static String describe(Throwable failure) {
        Throwable current = failure;
        while (true) {
            Throwable next = null;
            if (current instanceof java.lang.reflect.InvocationTargetException) {
                next = ((java.lang.reflect.InvocationTargetException) current).getCause();
            } else if (current instanceof java.lang.reflect.UndeclaredThrowableException) {
                next = ((java.lang.reflect.UndeclaredThrowableException) current)
                        .getUndeclaredThrowable();
            }
            if (next == null || next == current) break;
            current = next;
        }
        return current.getClass().getName() + ": " + String.valueOf(current.getMessage());
    }

    public static double number(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    public static int integer(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    public static String registryName(Object value) {
        if (value == null) {
            return "UNKNOWN";
        }
        String text = String.valueOf(value);
        int start = text.indexOf("minecraft:");
        if (start < 0) {
            Object key = callOrNull(value, "key");
            if (key != null) {
                text = String.valueOf(key);
                start = text.indexOf("minecraft:");
            }
        }
        if (start < 0) {
            return "UNKNOWN";
        }
        int cursor = start + "minecraft:".length();
        int end = cursor;
        while (end < text.length()) {
            char symbol = text.charAt(end);
            boolean part = (symbol >= 'a' && symbol <= 'z') || (symbol >= '0' && symbol <= '9')
                    || symbol == '_';
            if (!part) {
                break;
            }
            end++;
        }
        String path = text.substring(cursor, end);
        return path.isEmpty() ? "UNKNOWN" : path.toUpperCase(java.util.Locale.ROOT);
    }
}
