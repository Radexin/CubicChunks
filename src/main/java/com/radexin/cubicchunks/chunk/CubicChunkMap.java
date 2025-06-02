package com.radexin.cubicchunks.chunk;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages all loaded CubeColumns and their CubeChunks in a cubic chunks world.
 */
public class CubicChunkMap {
    // Key: packed X/Z, Value: CubeColumn
    private final Map<Long, CubeColumn> columns = new HashMap<>();

    private long packXZ(int x, int z) {
        return (((long)x) & 0xFFFFFFFFL) | ((((long)z) & 0xFFFFFFFFL) << 32);
    }

    public CubeColumn getColumn(int x, int z, boolean createIfMissing) {
        long key = packXZ(x, z);
        CubeColumn column = columns.get(key);
        if (column == null && createIfMissing) {
            column = new CubeColumn(x, z);
            columns.put(key, column);
        }
        return column;
    }

    public CubeChunk getCube(int x, int y, int z, boolean createIfMissing) {
        CubeColumn column = getColumn(x, z, createIfMissing);
        return column != null ? column.getCube(y, createIfMissing) : null;
    }

    public void unloadCube(int x, int y, int z) {
        CubeColumn column = getColumn(x, z, false);
        if (column != null) {
            column.unloadCube(y);
            // Optionally remove column if empty
            if (column.getLoadedCubes().isEmpty()) {
                columns.remove(packXZ(x, z));
            }
        }
    }

    public java.util.Collection<CubeColumn> getAllColumns() {
        return columns.values();
    }

    // Add serialization, iteration, and other management methods as needed
} 