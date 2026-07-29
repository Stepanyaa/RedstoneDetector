package ru.stepanyaa.redstoneDetector.core;

import ru.stepanyaa.redstoneDetector.ChunkCoordinate;

import java.util.List;

public interface WorldAccess {

    List<String> worldNames();

    List<ChunkCoordinate> loadedChunks(String worldName);

    ChunkScan scanChunk(ChunkCoordinate coord);

    int removeRedstone(ChunkCoordinate coord);

    int restoreRedstone(ChunkCoordinate coord);

    int removeEntities(ChunkCoordinate coord);

    void runAtChunk(ChunkCoordinate coord, Runnable task);
}
