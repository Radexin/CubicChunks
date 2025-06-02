package com.radexin.cubicchunks.world;

import com.radexin.cubicchunks.chunk.CubeColumn;
import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.gen.CubeChunkGenerator;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages CubeColumns and overall cubic chunks world logic.
 * Integrates column and cube management.
 */
public class CubeWorld {
    // Map of (x, z) to CubeColumn
    private final Map<Long, CubeColumn> columns = new HashMap<>();
    private final CubeChunkGenerator generator;

    public CubeWorld(CubeChunkGenerator generator) {
        this.generator = generator;
    }

    /**
     * Gets the CubeColumn at the given x/z. Optionally creates it if missing.
     */
    public CubeColumn getColumn(int x, int z, boolean createIfMissing) {
        long key = getKey(x, z);
        CubeColumn column = columns.get(key);
        if (column == null && createIfMissing) {
            column = new CubeColumn(x, z);
            columns.put(key, column);
        }
        return column;
    }

    /**
     * Unloads (removes) the CubeColumn at the given x/z.
     */
    public void unloadColumn(int x, int z) {
        columns.remove(getKey(x, z));
    }

    /**
     * Gets the CubeChunk at the given x/y/z. Optionally creates column/cube if missing.
     * If a cube is created, it is generated using the generator.
     */
    public CubeChunk getCube(int x, int y, int z, boolean createIfMissing) {
        CubeColumn column = getColumn(x, z, createIfMissing);
        if (column == null) return null;
        CubeChunk cube = column.getCube(y, createIfMissing);
        if (cube != null && createIfMissing && isCubeEmpty(cube)) {
            generator.generateCube(cube);
        }
        return cube;
    }

    private boolean isCubeEmpty(CubeChunk cube) {
        // Simple check: if all blocks are air, consider it empty (could be optimized)
        // For now, just check the first block
        return cube.getBlockState(0, 0, 0).isAir();
    }

    private long getKey(int x, int z) {
        return ((long)x << 32) | (z & 0xFFFFFFFFL);
    }
} 