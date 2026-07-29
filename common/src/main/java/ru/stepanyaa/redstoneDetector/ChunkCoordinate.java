package ru.stepanyaa.redstoneDetector;

import java.util.Objects;

public class ChunkCoordinate {
    private final String world;
    private final int x;
    private final int z;

    public ChunkCoordinate(String world, int x, int z) {
        this.world = world;
        this.x = x;
        this.z = z;
    }

    public String world() {
        return world;
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    @Override
    public String toString() {
        return world + ";" + x + ";" + z;
    }

    public static ChunkCoordinate fromString(String s) {
        String[] parts = s.split(";");
        return new ChunkCoordinate(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    public String toDisplayString() {
        return "[" + x + ", " + z + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChunkCoordinate that = (ChunkCoordinate) o;
        return x == that.x && z == that.z && Objects.equals(world, that.world);
    }

    @Override
    public int hashCode() {
        return Objects.hash(world, x, z);
    }
}
