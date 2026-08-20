package ru.stepanyaa.redstoneDetector.integration;

import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of the CoreProtect history of a single chunk.
 *
 * <p>Instances are produced by {@link CoreProtectHook} on an async thread and then
 * cached, so every field is final and safe to read from the main thread.</p>
 */
public final class ChunkHistory {

    /** A single logged block change inside the chunk. */
    public static final class Entry {
        public final String player;
        /** CoreProtect action id: 0 = broken, 1 = placed. */
        public final int action;
        public final String block;
        /** Epoch milliseconds. */
        public final long time;
        public final int x;
        public final int y;
        public final int z;
        public final boolean rolledBack;

        public Entry(String player, int action, String block, long time,
                     int x, int y, int z, boolean rolledBack) {
            this.player = player == null ? "?" : player;
            this.action = action;
            this.block = block == null ? "?" : block;
            this.time = time;
            this.x = x;
            this.y = y;
            this.z = z;
            this.rolledBack = rolledBack;
        }

        public boolean isPlacement() {
            return action == 1;
        }
    }

    public static final ChunkHistory EMPTY =
            new ChunkHistory(Collections.<Entry>emptyList(), null, null, 0L, null, 0L);

    private final List<Entry> entries;
    private final String possibleOwner;
    private final String lastBuilder;
    private final long lastModified;
    private final String lastRedstonePlayer;
    private final long lastRedstoneTime;
    private final long fetchedAt;

    public ChunkHistory(List<Entry> entries, String possibleOwner, String lastBuilder,
                        long lastModified, String lastRedstonePlayer, long lastRedstoneTime) {
        this.entries = entries == null
                ? Collections.<Entry>emptyList()
                : Collections.unmodifiableList(entries);
        this.possibleOwner = possibleOwner;
        this.lastBuilder = lastBuilder;
        this.lastModified = lastModified;
        this.lastRedstonePlayer = lastRedstonePlayer;
        this.lastRedstoneTime = lastRedstoneTime;
        this.fetchedAt = System.currentTimeMillis();
    }

    /** Most recent changes first, already trimmed to the configured limit. */
    public List<Entry> entries() {
        return entries;
    }

    /** Player with the most placements in the chunk, or {@code null} when unknown. */
    public String possibleOwner() {
        return possibleOwner;
    }

    /** Player behind the most recent change, or {@code null} when unknown. */
    public String lastBuilder() {
        return lastBuilder;
    }

    /** Epoch milliseconds of the most recent change, or 0 when unknown. */
    public long lastModified() {
        return lastModified;
    }

    /** Player who placed the most recent redstone component, or {@code null}. */
    public String lastRedstonePlayer() {
        return lastRedstonePlayer;
    }

    /** Epoch milliseconds of the most recent redstone placement, or 0. */
    public long lastRedstoneTime() {
        return lastRedstoneTime;
    }

    public long fetchedAt() {
        return fetchedAt;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
