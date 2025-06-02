package com.radexin.cubicchunks.chunk;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.ListTag;

/**
 * Represents a single 16x16x16 cube in the cubic chunks world.
 * Handles block storage and access.
 */
public class CubeChunk {
    public static final int SIZE = 16;
    private final int cubeX, cubeY, cubeZ;
    private final BlockState[] blocks = new BlockState[SIZE * SIZE * SIZE];

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
        return tag;
    }

    public static CubeChunk fromNBT(CompoundTag tag) {
        int cubeX = tag.getInt("cubeX");
        int cubeY = tag.getInt("cubeY");
        int cubeZ = tag.getInt("cubeZ");
        CubeChunk chunk = new CubeChunk(cubeX, cubeY, cubeZ);
        ListTag blockNames = tag.getList("blocks", 8); // 8 = StringTag
        for (int i = 0; i < blockNames.size() && i < chunk.blocks.length; i++) {
            String name = blockNames.getString(i);
            var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(name));
            chunk.blocks[i] = block != null ? block.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        return chunk;
    }
} 