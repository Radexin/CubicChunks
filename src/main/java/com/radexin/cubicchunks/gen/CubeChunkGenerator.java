package com.radexin.cubicchunks.gen;

import com.radexin.cubicchunks.chunk.CubeChunk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Responsible for generating CubeChunks for world generation.
 * This basic implementation fills cubes below y=0 with stone, above with air.
 */
public class CubeChunkGenerator {
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /**
     * Fills the given CubeChunk with basic terrain: stone below y=0, air above.
     */
    public void generateCube(CubeChunk cube) {
        int baseY = cube.getCubeY() * CubeChunk.SIZE;
        for (int y = 0; y < CubeChunk.SIZE; y++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int x = 0; x < CubeChunk.SIZE; x++) {
                    int worldY = baseY + y;
                    if (worldY < 0) {
                        cube.setBlockState(x, y, z, STONE);
                    } else {
                        cube.setBlockState(x, y, z, AIR);
                    }
                }
            }
        }
    }
} 