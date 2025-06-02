package com.radexin.cubicchunks.world;

import com.radexin.cubicchunks.chunk.CubeColumn;
import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.gen.CubeChunkGenerator;
import java.util.HashMap;
import java.util.Map;
import java.util.Collection;
import java.util.ArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

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

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        ListTag columnsList = new ListTag();
        for (CubeColumn column : columns.values()) {
            columnsList.add(column.toNBT());
        }
        tag.put("columns", columnsList);
        return tag;
    }

    public static CubeWorld fromNBT(CompoundTag tag, CubeChunkGenerator generator) {
        CubeWorld world = new CubeWorld(generator);
        ListTag columnsList = tag.getList("columns", Tag.TAG_COMPOUND);
        for (int i = 0; i < columnsList.size(); i++) {
            CompoundTag columnTag = columnsList.getCompound(i);
            CubeColumn column = CubeColumn.fromNBT(columnTag);
            if (column != null) {
                long key = ((long) column.getX() & 0xFFFFFFFFL) | (((long) column.getZ() & 0xFFFFFFFFL) << 32);
                world.columns.put(key, column);
            }
        }
        return world;
    }

    /**
     * Returns a collection of all loaded CubeChunks in all columns.
     */
    public Collection<CubeChunk> getLoadedCubes() {
        Collection<CubeChunk> cubes = new ArrayList<>();
        for (CubeColumn column : columns.values()) {
            cubes.addAll(column.getLoadedCubes());
        }
        return cubes;
    }
} 