package com.radexin.cubicchunks.chunk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Manages entity loading, tracking, and movement between cubic chunks.
 */
public class CubicEntityManager {
    private final Level level;
    private final CubicChunkProvider chunkProvider;
    private final Map<Integer, CubeChunk> entityToChunkMap = new HashMap<>();

    public CubicEntityManager(Level level, CubicChunkProvider chunkProvider) {
        this.level = level;
        this.chunkProvider = chunkProvider;
    }

    /**
     * Loads entities from NBT data into the appropriate cubic chunk.
     */
    public void loadEntitiesFromNBT(CompoundTag chunkNBT, int cubeX, int cubeY, int cubeZ) {
        if (!chunkNBT.contains("entities")) {
            return;
        }

        CubeChunk cube = chunkProvider.getCube(cubeX, cubeY, cubeZ, true);
        ListTag entitiesTag = chunkNBT.getList("entities", 10); // 10 = CompoundTag

        for (int i = 0; i < entitiesTag.size(); i++) {
            CompoundTag entityTag = entitiesTag.getCompound(i);
            loadEntityFromNBT(entityTag, cube);
        }
    }

    private void loadEntityFromNBT(CompoundTag entityTag, CubeChunk cube) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return; // Client-side entity loading is handled differently
        }

        try {
            Optional<EntityType<?>> entityTypeOptional = EntityType.by(entityTag);
            if (entityTypeOptional.isPresent()) {
                Entity entity = entityTypeOptional.get().create(serverLevel);
                if (entity != null) {
                    entity.load(entityTag);
                    // Verify entity is actually in this cube
                    if (cube.isEntityInCube(entity)) {
                        cube.addEntity(entity);
                        entityToChunkMap.put(entity.getId(), cube);
                        serverLevel.addFreshEntity(entity);
                    }
                }
            }
        } catch (Exception e) {
            // Log error but continue loading other entities
            System.err.println("Failed to load entity from NBT: " + e.getMessage());
        }
    }

    /**
     * Tracks an entity and assigns it to the appropriate cubic chunk.
     */
    public void trackEntity(Entity entity) {
        CubeChunk currentCube = getCubeForEntity(entity);
        CubeChunk previousCube = entityToChunkMap.get(entity.getId());

        if (currentCube != previousCube) {
            // Entity moved to a different cube
            if (previousCube != null) {
                previousCube.removeEntity(entity);
            }
            if (currentCube != null) {
                currentCube.addEntity(entity);
                entityToChunkMap.put(entity.getId(), currentCube);
            } else {
                entityToChunkMap.remove(entity.getId());
            }
        }
    }

    /**
     * Removes an entity from tracking.
     */
    public void untrackEntity(Entity entity) {
        CubeChunk cube = entityToChunkMap.remove(entity.getId());
        if (cube != null) {
            cube.removeEntity(entity);
        }
    }

    /**
     * Gets the cubic chunk that should contain the given entity based on its position.
     */
    private CubeChunk getCubeForEntity(Entity entity) {
        BlockPos pos = entity.blockPosition();
        int cubeX = Math.floorDiv(pos.getX(), CubeChunk.SIZE);
        int cubeY = Math.floorDiv(pos.getY(), CubeChunk.SIZE);
        int cubeZ = Math.floorDiv(pos.getZ(), CubeChunk.SIZE);
        
        return chunkProvider.getCube(cubeX, cubeY, cubeZ, false);
    }

    /**
     * Updates entity positions and moves them between cubes as needed.
     */
    public void updateEntityPositions() {
        for (Map.Entry<Integer, CubeChunk> entry : entityToChunkMap.entrySet()) {
            CubeChunk cube = entry.getValue();
            for (Entity entity : cube.getEntities()) {
                if (!cube.isEntityInCube(entity)) {
                    // Entity moved out of its cube, track it to find new cube
                    trackEntity(entity);
                }
            }
        }
    }

    /**
     * Gets the cube chunk containing the specified entity.
     */
    public CubeChunk getChunkForEntity(Entity entity) {
        return entityToChunkMap.get(entity.getId());
    }

    /**
     * Saves all entities from the given cube to NBT format.
     */
    public void saveEntitiesToNBT(CubeChunk cube, CompoundTag chunkNBT) {
        ListTag entitiesTag = new ListTag();
        for (Entity entity : cube.getEntities()) {
            if (!entity.isRemoved() && entity.shouldBeSaved()) {
                CompoundTag entityTag = new CompoundTag();
                if (entity.save(entityTag)) {
                    entitiesTag.add(entityTag);
                }
            }
        }
        chunkNBT.put("entities", entitiesTag);
    }
} 