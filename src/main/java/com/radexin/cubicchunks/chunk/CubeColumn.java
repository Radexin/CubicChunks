package com.radexin.cubicchunks.chunk;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

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

    /**
     * Loads an existing CubeChunk at the given Y level.
     */
    public void loadCube(int cubeY, CubeChunk cube) {
        cubes.put(cubeY, cube);
    }

    public int getX() { return x; }
    public int getZ() { return z; }

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("columnX", x);
        tag.putInt("columnZ", z);
        
        // Save all loaded cubes
        CompoundTag cubesTag = new CompoundTag();
        for (Map.Entry<Integer, CubeChunk> entry : cubes.entrySet()) {
            cubesTag.put("cube_" + entry.getKey(), entry.getValue().toNBT());
        }
        tag.put("cubes", cubesTag);
        
        return tag;
    }

    public static CubeColumn fromNBT(CompoundTag tag) {
        int columnX = tag.getInt("columnX");
        int columnZ = tag.getInt("columnZ");
        CubeColumn column = new CubeColumn(columnX, columnZ);
        
        // Load all cubes
        CompoundTag cubesTag = tag.getCompound("cubes");
        for (String key : cubesTag.getAllKeys()) {
            if (key.startsWith("cube_")) {
                int cubeY = Integer.parseInt(key.substring(5));
                CubeChunk cube = CubeChunk.fromNBT(cubesTag.getCompound(key));
                column.cubes.put(cubeY, cube);
            }
        }
        
        return column;
    }

    /**
     * Returns a collection of all loaded CubeChunks in this column.
     */
    public Collection<CubeChunk> getLoadedCubes() {
        return cubes.values();
    }
} 