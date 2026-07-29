package ru.stepanyaa.redstoneDetector.core;

import ru.stepanyaa.redstoneDetector.ChunkCoordinate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FreezeJournal {

    private final Map<ChunkCoordinate, FreezeRecord> active =
            new ConcurrentHashMap<ChunkCoordinate, FreezeRecord>();
    private final Deque<FreezeRecord> history = new ArrayDeque<FreezeRecord>();
    private final int capacity;

    public FreezeJournal(int capacity) {
        this.capacity = Math.max(8, capacity);
    }

    public FreezeRecord open(FreezeRecord record) {
        if (record == null) {
            return null;
        }
        FreezeRecord previous = active.putIfAbsent(record.coord(), record);
        return previous == null ? record : previous;
    }

    public FreezeRecord active(ChunkCoordinate coord) {
        return coord == null ? null : active.get(coord);
    }

    public FreezeRecord close(ChunkCoordinate coord, double serverMsptAfter, int restoredBlocks) {
        if (coord == null) {
            return null;
        }
        FreezeRecord record = active.remove(coord);
        if (record == null) {
            return null;
        }
        record.restoredBlocks(restoredBlocks);
        record.close(serverMsptAfter);
        synchronized (history) {
            history.addFirst(record);
            while (history.size() > capacity) {
                history.removeLast();
            }
        }
        return record;
    }

    public List<FreezeRecord> recent(int limit) {
        List<FreezeRecord> copy = new ArrayList<FreezeRecord>();
        synchronized (history) {
            for (FreezeRecord record : history) {
                if (copy.size() >= limit) {
                    break;
                }
                copy.add(record);
            }
        }
        return copy;
    }

    public List<FreezeRecord> activeRecords() {
        return new ArrayList<FreezeRecord>(active.values());
    }

    public int activeCount() {
        return active.size();
    }

    public void clear() {
        active.clear();
        synchronized (history) {
            history.clear();
        }
    }
}
