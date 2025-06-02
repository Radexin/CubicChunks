package com.radexin.cubicchunks.chunk;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;

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
} 