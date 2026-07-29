package ru.stepanyaa.redstoneDetector;

public final class ActivityAnalyzer {

    private ActivityAnalyzer() {
    }

    public static final String SAFE = "SAFE";
    public static final String LOW = "LOW";
    public static final String MEDIUM = "MEDIUM";
    public static final String HIGH = "HIGH";
    public static final String CRITICAL = "CRITICAL";

    public static void computeScores(ChunkData d, int maxRedstone, int maxEntities, int updateThreshold) {
        if (d == null) return;
        int rs = maxRedstone <= 0 ? 0
                : (int) Math.min(100, Math.round(d.redstoneCount.get() * 100.0 / maxRedstone));
        int es = maxEntities <= 0 ? 0
                : (int) Math.min(100, Math.round(d.entityCount.get() * 100.0 / maxEntities));
        int us = updateThreshold <= 0 ? 0
                : (int) Math.min(100, Math.round(d.updatesPerSec * 100.0 / updateThreshold));

        d.redstoneScore = rs;
        d.entityScore = es;
        d.updateScore = us;

        int lag = (int) Math.round(us + rs / 3.0 + es / 4.0);
        d.lagScore = Math.min(100, Math.max(0, lag));

        d.dangerLevel = dangerFromMspt(d.msptContribution, d.lagScore);
        d.machineType = classify(d);
    }

    public static String dangerFromMspt(double mspt, int lagScore) {
        if (mspt >= 10.0) return CRITICAL;
        if (mspt >= 6.0) return HIGH;
        if (mspt >= 3.0) return MEDIUM;
        if (mspt >= 1.0) return LOW;
        return lagScore >= 35 ? LOW : SAFE;
    }

    public static String dangerFromScore(int lagScore) {
        if (lagScore < 15) return SAFE;
        if (lagScore < 35) return LOW;
        if (lagScore < 60) return MEDIUM;
        if (lagScore < 85) return HIGH;
        return CRITICAL;
    }

    public static boolean isDangerous(String danger) {
        return HIGH.equals(danger) || CRITICAL.equals(danger);
    }

    public static boolean isSuspicious(ChunkData d, int maxRedstone, int maxEntities) {
        if (d == null || d.clearedByAdmin) {
            return false;
        }
        if (isDangerous(d.dangerLevel) || MEDIUM.equals(d.dangerLevel)) {
            return true;
        }
        if (maxRedstone > 0 && d.redstoneCount.get() > maxRedstone) {
            return true;
        }
        return maxEntities > 0 && d.entityCount.get() > maxEntities;
    }

    public static String classify(ChunkData d) {
        if (d == null) return "none";
        int ups = d.updatesPerSec;
        if (ups < 20) return "none";

        if (d.sculkPerSec >= 20) return "sculk_lag_machine";
        if (d.trapdoorPerSec >= 40) return "trapdoor_lag_machine";
        if (d.hopperPerSec >= 60) return "item_transport_machine";

        int piston = d.pistonPerSec;
        int observer = d.observerPerSec;
        int repeater = d.repeaterPerSec;
        int comparator = d.comparatorPerSec;
        int redstone = d.redstonePerSec;
        int neighbor = d.neighborPerSec;

        if (piston >= 4 && observer >= 4) return "flying_machine";

        if (observer >= 5 && observer >= piston && observer >= repeater) return "observer_clock";

        if (piston >= 5 && piston >= observer) return "piston_clock";

        if ((repeater >= 4 || comparator >= 4) && redstone >= 3) return "clock";

        if (ups >= 400) return "rapid_update_machine";

        if (redstone >= 5 && neighbor >= 60) return "infinite_loop";
        return "active";
    }

    public static String machineDisplay(
            ru.stepanyaa.redstoneDetector.core.EngineMessages messages, String type) {
        if (type == null) type = "none";
        switch (type) {
            case "flying_machine": return messages.get("machine.flying_machine", "Flying machine");
            case "observer_clock": return messages.get("machine.observer_clock", "Observer clock");
            case "piston_clock": return messages.get("machine.piston_clock", "Piston clock");
            case "clock": return messages.get("machine.clock", "Redstone clock");
            case "rapid_update_machine": return messages.get("machine.rapid_update_machine", "Rapid update machine");
            case "infinite_loop": return messages.get("machine.infinite_loop", "Infinite update loop");
            case "sculk_lag_machine": return messages.get("machine.sculk_lag_machine", "Sculk sensor lag machine");
            case "trapdoor_lag_machine": return messages.get("machine.trapdoor_lag_machine", "Trapdoor lag machine");
            case "item_transport_machine": return messages.get("machine.item_transport_machine", "Item transport machine");
            case "active": return messages.get("machine.active", "Active redstone");
            default: return messages.get("machine.none", "None");
        }
    }
}
