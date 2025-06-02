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

    public int getX() { return x; }
    public int getZ() { return z; }

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("z", z);
        ListTag cubesList = new ListTag();
        for (Map.Entry<Integer, CubeChunk> entry : cubes.entrySet()) {
            cubesList.add(entry.getValue().toNBT());
        }
        tag.put("cubes", cubesList);
        return tag;
    }

    public static CubeColumn fromNBT(CompoundTag tag) {
        int x = tag.getInt("x");
        int z = tag.getInt("z");
        CubeColumn column = new CubeColumn(x, z);
        ListTag cubesList = tag.getList("cubes", Tag.TAG_COMPOUND);
        for (int i = 0; i < cubesList.size(); i++) {
            CompoundTag cubeTag = cubesList.getCompound(i);
            CubeChunk cube = CubeChunk.fromNBT(cubeTag);
            if (cube != null) {
                column.cubes.put(cube.getCubeY(), cube);
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