package com.radexin.cubicchunks.chunk;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a vertical stack of CubeChunks at a given x/z position.
 * Handles cube management, loading/unloading, and access methods.
 */
public class CubeColumn {
    private final int x, z;
    // Map of cubeY to CubeChunk
    private final Map<Integer, CubeChunk> cubes = new HashMap<>();

    public CubeColumn(int x, int z) {
        this.x = x;
        this.z = z;
    }

    /**
     * Gets the CubeChunk at the given Y. Optionally creates it if missing.
     */
    public CubeChunk getCube(int cubeY, boolean createIfMissing) {
        CubeChunk cube = cubes.get(cubeY);
        if (cube == null && createIfMissing) {
            cube = new CubeChunk(x, cubeY, z);
            cubes.put(cubeY, cube);
        }
        return cube;
    }

    /**
     * Unloads (removes) the CubeChunk at the given Y.
     */
    public void unloadCube(int cubeY) {
        cubes.remove(cubeY);
    }

    public int getX() { return x; }
    public int getZ() { return z; }
} 