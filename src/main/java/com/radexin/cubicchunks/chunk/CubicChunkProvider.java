package com.radexin.cubicchunks.chunk;

import java.io.File;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;

public class CubicChunkProvider {
    private final CubicChunkMap chunkMap = new CubicChunkMap();
    private CubicChunkStorage storage;
    private CubicEntityManager entityManager;

    public CubicChunkProvider(File worldDir, Level level) {
        this.storage = new CubicChunkStorage(worldDir);
        this.entityManager = new CubicEntityManager(level, this);
    }

    public CubeChunk getCube(int x, int y, int z, boolean createIfMissing) {
        CubeChunk cube = chunkMap.getCube(x, y, z, false);
        if (cube == null) {
            // Try to load from storage
            cube = storage.loadCube(x, y, z);
            if (cube != null) {
                // Add to memory
                CubeColumn column = chunkMap.getColumn(x, z, true);
                column.loadCube(y, cube);
                // Load entities for this cube from its NBT data if available
                // This will be handled by the world loading system
            } else if (createIfMissing) {
                // Create new cube
                cube = chunkMap.getCube(x, y, z, true);
            }
        }
        return cube;
    }

    public void unloadCube(int x, int y, int z) {
        CubeChunk cube = chunkMap.getCube(x, y, z, false);
        if (cube != null) {
            // Save before unloading
            storage.saveCube(cube);
            chunkMap.unloadCube(x, y, z);
        }
    }

    public void trackEntity(Entity entity) {
        entityManager.trackEntity(entity);
    }

    public void untrackEntity(Entity entity) {
        entityManager.untrackEntity(entity);
    }

    public void updateEntityPositions() {
        entityManager.updateEntityPositions();
    }

    public CubeChunk getChunkForEntity(Entity entity) {
        return entityManager.getChunkForEntity(entity);
    }

    public void tickAll() {
        // Update entity positions first
        updateEntityPositions();
        
        // Then tick all cubes
        for (CubeColumn column : chunkMap.getAllColumns()) {
            for (CubeChunk cube : column.getLoadedCubes()) {
                cube.tick();
            }
        }
    }

    public void saveAll() {
        // Save all loaded cubes to storage
        for (CubeColumn column : chunkMap.getAllColumns()) {
            for (CubeChunk cube : column.getLoadedCubes()) {
                storage.saveCube(cube);
            }
        }
        // Flush all region files
        storage.saveAll();
    }

    public void close() {
        saveAll();
        storage.close();
    }

    // Add more methods as needed, e.g., for ticking, saving/loading, etc.
} 