package com.radexin.cubicchunks.chunk;

import java.io.File;
import java.io.IOException;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.registries.Registries;

public class CubicChunkProvider {
    private final CubicChunkMap chunkMap = new CubicChunkMap();
    private UnifiedCubicChunkStorage storage;
    private CubicEntityManager entityManager;
    private final Level level;

    public CubicChunkProvider(File worldDir, Level level) {
        this.level = level;
        // Get biome registry from level
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        this.storage = new UnifiedCubicChunkStorage(worldDir, biomeRegistry);
        
        // Create entity manager with a simple chunk provider interface
        this.entityManager = new CubicEntityManager((ServerLevel) level, new SimpleCubicChunkManager());
    }

    // Simple chunk manager implementation for entity manager
    private class SimpleCubicChunkManager extends UnifiedCubicChunkManager {
        public SimpleCubicChunkManager() {
            // Create with minimal dependencies - just need the getCube method for entity manager
            super(null, null, null, null, null); // We'll override getCube method
        }

        @Override
        public CubeChunk getCube(int x, int y, int z, boolean load) {
            return CubicChunkProvider.this.getCube(x, y, z, load);
        }
        
        @Override
        public void shutdown() {
            // No-op for simple implementation
        }
    }

    public CubeChunk getCube(int x, int y, int z, boolean createIfMissing) {
        CubeChunk cube = chunkMap.getCube(x, y, z, false);
        if (cube == null) {
            try {
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
            } catch (IOException e) {
                System.err.println("Failed to load cube at " + x + "," + y + "," + z + ": " + e.getMessage());
                if (createIfMissing) {
                    cube = chunkMap.getCube(x, y, z, true);
                }
            }
        }
        return cube;
    }

    public void unloadCube(int x, int y, int z) {
        CubeChunk cube = chunkMap.getCube(x, y, z, false);
        if (cube != null) {
            try {
                // Save before unloading
                storage.saveCube(cube);
                chunkMap.unloadCube(x, y, z);
            } catch (IOException e) {
                System.err.println("Failed to save cube at " + x + "," + y + "," + z + ": " + e.getMessage());
            }
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
                cube.tick(level);
            }
        }
    }

    public void saveAll() {
        try {
            // Save all loaded cubes to storage
            for (CubeColumn column : chunkMap.getAllColumns()) {
                for (CubeChunk cube : column.getLoadedCubes()) {
                    storage.saveCube(cube);
                }
            }
            // Flush all region files
            storage.saveAll();
        } catch (IOException e) {
            System.err.println("Failed to save cubes: " + e.getMessage());
        }
    }

    public void close() {
        saveAll();
        // Close storage and cleanup
        storage.flush();
        entityManager.shutdown();
    }

    // Add more methods as needed, e.g., for ticking, saving/loading, etc.
} 