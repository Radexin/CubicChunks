package com.radexin.cubicchunks.chunk;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;

/**
 * Represents a column of CubeChunks at a given X/Z coordinate.
 * Manages loading and unloading of cubes vertically.
 */
public class CubeColumn {
    private final int x, z;
    private final Map<Integer, CubeChunk> cubes = new HashMap<>();
    private static final Registry<Biome> BIOME_REGISTRY = null; // Will be set from server context

    public CubeColumn(int x, int z) {
        this.x = x;
        this.z = z;
    }

    /**
     * Gets the cube at the given Y coordinate. Optionally creates it if missing.
     */
    public CubeChunk getCube(int y, boolean createIfMissing) {
        CubeChunk cube = cubes.get(y);
        if (cube == null && createIfMissing) {
            // Use a default biome registry - this should be injected from the server context
            Registry<Biome> biomeRegistry = BIOME_REGISTRY;
            if (biomeRegistry == null) {
                // Fallback - create a minimal cube without biome support for now
                cube = new CubeChunk(x, y, z, null);
            } else {
                cube = new CubeChunk(x, y, z, biomeRegistry);
            }
            cubes.put(y, cube);
        }
        return cube;
    }

    /**
     * Gets the cube at the given Y coordinate with a specific biome registry.
     */
    public CubeChunk getCube(int y, boolean createIfMissing, Registry<Biome> biomeRegistry) {
        CubeChunk cube = cubes.get(y);
        if (cube == null && createIfMissing) {
            cube = new CubeChunk(x, y, z, biomeRegistry);
            cubes.put(y, cube);
        }
        return cube;
    }

    /**
     * Loads a cube at the given Y coordinate.
     */
    public void loadCube(int y, CubeChunk cube) {
        cubes.put(y, cube);
    }

    /**
     * Unloads the cube at the given Y coordinate.
     */
    public void unloadCube(int y) {
        cubes.remove(y);
    }

    /**
     * Gets all loaded cubes in this column.
     */
    public Collection<CubeChunk> getLoadedCubes() {
        return new ArrayList<>(cubes.values());
    }

    /**
     * Gets the number of loaded cubes in this column.
     */
    public int getLoadedCubeCount() {
        return cubes.size();
    }

    /**
     * Checks if this column has any loaded cubes.
     */
    public boolean hasLoadedCubes() {
        return !cubes.isEmpty();
    }

    /**
     * Unloads all cubes in this column.
     */
    public void unloadAll() {
        cubes.clear();
    }

    public int getX() { return x; }
    public int getZ() { return z; }

    /**
     * Gets the Y coordinates of all loaded cubes.
     */
    public Collection<Integer> getLoadedYCoordinates() {
        return new ArrayList<>(cubes.keySet());
    }

    /**
     * Checks if a cube is loaded at the given Y coordinate.
     */
    public boolean isCubeLoaded(int y) {
        return cubes.containsKey(y);
    }

    /**
     * Gets the highest loaded cube Y coordinate.
     */
    public int getHighestLoadedY() {
        return cubes.keySet().stream().mapToInt(Integer::intValue).max().orElse(Integer.MIN_VALUE);
    }

    /**
     * Gets the lowest loaded cube Y coordinate.
     */
    public int getLowestLoadedY() {
        return cubes.keySet().stream().mapToInt(Integer::intValue).min().orElse(Integer.MAX_VALUE);
    }

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("z", z);
        
        ListTag cubesTag = new ListTag();
        for (Map.Entry<Integer, CubeChunk> entry : cubes.entrySet()) {
            CompoundTag cubeTag = new CompoundTag();
            cubeTag.putInt("y", entry.getKey());
            cubeTag.put("data", entry.getValue().toNBT());
            cubesTag.add(cubeTag);
        }
        tag.put("cubes", cubesTag);
        
        return tag;
    }

    public static CubeColumn fromNBT(CompoundTag tag, Registry<Biome> biomeRegistry) {
        int x = tag.getInt("x");
        int z = tag.getInt("z");
        CubeColumn column = new CubeColumn(x, z);
        
        ListTag cubesTag = tag.getList("cubes", Tag.TAG_COMPOUND);
        for (int i = 0; i < cubesTag.size(); i++) {
            CompoundTag cubeTag = cubesTag.getCompound(i);
            int y = cubeTag.getInt("y");
            CompoundTag cubeData = cubeTag.getCompound("data");
            CubeChunk cube = CubeChunk.fromNBT(cubeData, biomeRegistry);
            column.cubes.put(y, cube);
        }
        
        return column;
    }
} 