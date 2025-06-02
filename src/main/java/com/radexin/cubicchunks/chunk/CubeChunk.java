package com.radexin.cubicchunks.chunk;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Represents a single 16x16x16 cube in the cubic chunks world.
 * Handles block storage, entity management, and access.
 */
public class CubeChunk {
    public static final int SIZE = 16;
    private final int cubeX, cubeY, cubeZ;
    private final BlockState[] blocks = new BlockState[SIZE * SIZE * SIZE];
    private final List<Entity> entities = new ArrayList<>();

    public CubeChunk(int cubeX, int cubeY, int cubeZ) {
        this.cubeX = cubeX;
        this.cubeY = cubeY;
        this.cubeZ = cubeZ;
        // Initialize all blocks to air
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = Blocks.AIR.defaultBlockState();
        }
    }

    public BlockState getBlockState(int x, int y, int z) {
        checkBounds(x, y, z);
        return blocks[index(x, y, z)];
    }

    public void setBlockState(int x, int y, int z, BlockState state) {
        checkBounds(x, y, z);
        blocks[index(x, y, z)] = state;
    }

    public int getCubeX() { return cubeX; }
    public int getCubeY() { return cubeY; }
    public int getCubeZ() { return cubeZ; }

    // Entity management methods
    public void addEntity(Entity entity) {
        if (!entities.contains(entity)) {
            entities.add(entity);
        }
    }

    public void removeEntity(Entity entity) {
        entities.remove(entity);
    }

    public List<Entity> getEntities() {
        return new ArrayList<>(entities);
    }

    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> entityTypeTest, Predicate<? super T> predicate) {
        List<T> list = new ArrayList<>();
        for (Entity entity : entities) {
            T t = entityTypeTest.tryCast(entity);
            if (t != null && predicate.test(t)) {
                list.add(t);
            }
        }
        return list;
    }

    public boolean isEntityInCube(Entity entity) {
        BlockPos pos = entity.blockPosition();
        int worldX = pos.getX();
        int worldY = pos.getY();
        int worldZ = pos.getZ();
        
        // Convert world coordinates to cube coordinates
        int entityCubeX = Math.floorDiv(worldX, SIZE);
        int entityCubeY = Math.floorDiv(worldY, SIZE);
        int entityCubeZ = Math.floorDiv(worldZ, SIZE);
        
        return entityCubeX == cubeX && entityCubeY == cubeY && entityCubeZ == cubeZ;
    }

    private int index(int x, int y, int z) {
        return (y * SIZE + z) * SIZE + x;
    }

    private void checkBounds(int x, int y, int z) {
        if (x < 0 || x >= SIZE || y < 0 || y >= SIZE || z < 0 || z >= SIZE) {
            throw new IndexOutOfBoundsException("CubeChunk coordinates out of bounds: " + x + ", " + y + ", " + z);
        }
    }

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("cubeX", cubeX);
        tag.putInt("cubeY", cubeY);
        tag.putInt("cubeZ", cubeZ);
        
        // Serialize block data as a list of registry names
        ListTag blockNames = new ListTag();
        for (BlockState state : blocks) {
            blockNames.add(net.minecraft.nbt.StringTag.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
        }
        tag.put("blocks", blockNames);
        
        // Serialize entities
        ListTag entitiesTag = new ListTag();
        for (Entity entity : entities) {
            CompoundTag entityTag = new CompoundTag();
            if (entity.save(entityTag)) {
                entitiesTag.add(entityTag);
            }
        }
        tag.put("entities", entitiesTag);
        
        return tag;
    }

    public static CubeChunk fromNBT(CompoundTag tag) {
        int cubeX = tag.getInt("cubeX");
        int cubeY = tag.getInt("cubeY");
        int cubeZ = tag.getInt("cubeZ");
        CubeChunk chunk = new CubeChunk(cubeX, cubeY, cubeZ);
        
        // Load blocks
        ListTag blockNames = tag.getList("blocks", 8); // 8 = StringTag
        for (int i = 0; i < blockNames.size() && i < chunk.blocks.length; i++) {
            String name = blockNames.getString(i);
            var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(name));
            chunk.blocks[i] = block != null ? block.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        
        // Note: Entity loading needs to be handled by the world/level during chunk loading
        // as entities require access to the world context. The entity data is preserved
        // in NBT for later restoration by the world loading system.
        
        return chunk;
    }

    public void tick() {
        // TODO: Add block/entity ticking logic here
        // Tick entities in this cube
        for (Entity entity : new ArrayList<>(entities)) {
            if (entity.isRemoved()) {
                entities.remove(entity);
            }
        }
    }
} 