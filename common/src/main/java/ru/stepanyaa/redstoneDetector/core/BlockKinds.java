package ru.stepanyaa.redstoneDetector.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class BlockKinds {

    private BlockKinds() {
    }

    private static final Set<String> PISTONS = set("PISTON", "STICKY_PISTON", "MOVING_PISTON",
            "PISTON_HEAD");
    private static final Set<String> SCULK = set("SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR",
            "SCULK_SHRIEKER");
    private static final Set<String> CONTAINERS = set("HOPPER", "DROPPER", "DISPENSER", "CRAFTER",
            "CHEST", "TRAPPED_CHEST", "BARREL", "FURNACE", "BLAST_FURNACE", "SMOKER",
            "HOPPER_MINECART");
    private static final Set<String> WIRING = set("REDSTONE_WIRE", "REDSTONE_TORCH",
            "REDSTONE_WALL_TORCH", "REDSTONE_BLOCK", "REDSTONE_LAMP", "LEVER", "TARGET",
            "TRIPWIRE", "TRIPWIRE_HOOK", "DAYLIGHT_DETECTOR", "NOTE_BLOCK", "COPPER_BULB");

    private static Set<String> set(String... names) {
        Set<String> values = new HashSet<String>();
        for (String name : names) {
            values.add(name);
        }
        return Collections.unmodifiableSet(values);
    }

    public static String normalize(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String value = name;
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(colon + 1);
        }
        return value.toUpperCase(Locale.ROOT);
    }

    public static int of(String rawName) {
        String name = normalize(rawName);
        if (name.isEmpty()) {
            return DetectorEngine.KIND_PHYSICS;
        }
        if (name.endsWith("TRAPDOOR")) {
            return DetectorEngine.KIND_TRAPDOOR;
        }
        if (SCULK.contains(name)) {
            return DetectorEngine.KIND_SCULK;
        }
        if (PISTONS.contains(name)) {
            return DetectorEngine.KIND_PISTON;
        }
        if (CONTAINERS.contains(name)) {
            return DetectorEngine.KIND_HOPPER;
        }
        if ("OBSERVER".equals(name)) {
            return DetectorEngine.KIND_OBSERVER;
        }
        if ("COMPARATOR".equals(name)) {
            return DetectorEngine.KIND_COMPARATOR;
        }
        if ("REPEATER".equals(name)) {
            return DetectorEngine.KIND_REPEATER;
        }
        if (WIRING.contains(name)) {
            return DetectorEngine.KIND_REDSTONE;
        }
        return DetectorEngine.KIND_PHYSICS;
    }

    public static boolean isTrapdoor(String rawName) {
        return normalize(rawName).endsWith("TRAPDOOR");
    }

    public static boolean isSculk(String rawName) {
        return SCULK.contains(normalize(rawName));
    }

    public static boolean isBlockEntity(String rawName) {
        String name = normalize(rawName);
        return CONTAINERS.contains(name) || SCULK.contains(name) || "CRAFTER".equals(name)
                || "COMPARATOR".equals(name) || "NOTE_BLOCK".equals(name);
    }
}
