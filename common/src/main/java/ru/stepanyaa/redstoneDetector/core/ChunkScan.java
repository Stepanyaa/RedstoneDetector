package ru.stepanyaa.redstoneDetector.core;

import java.util.LinkedHashMap;
import java.util.Map;

public class ChunkScan {

    public final Map<String, Integer> redstone = new LinkedHashMap<String, Integer>();
    public final Map<String, Integer> entities = new LinkedHashMap<String, Integer>();

    public int redstoneTotal;
    public int entityTotal;

    public void addRedstone(String type) {
        redstoneTotal++;
        Integer previous = redstone.get(type);
        redstone.put(type, previous == null ? 1 : previous + 1);
    }

    public void addEntity(String type) {
        entityTotal++;
        Integer previous = entities.get(type);
        entities.put(type, previous == null ? 1 : previous + 1);
    }
}
