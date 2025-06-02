package com.radexin.cubicchunks.world;

import com.radexin.cubicchunks.chunk.CubeColumn;
import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.chunk.UnifiedCubicChunkManager;
import com.radexin.cubicchunks.lighting.UnifiedCubicLightEngine;
import com.radexin.cubicchunks.gen.CubeChunkGenerator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import java.util.HashMap;
import java.util.Map;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Manages CubeColumns and overall cubic chunks world logic.
 * Integrates column and cube management with lighting and ticking.
 */
public class CubeWorld {
    // Map of (x, z) to CubeColumn
    private final Map<Long, CubeColumn> columns = new HashMap<>();
    private final CubeChunkGenerator generator;
    private final UnifiedCubicLightEngine lightEngine;
    private final Set<CubeChunk> tickingCubes = new HashSet<>();

    public CubeWorld(CubeChunkGenerator generator, UnifiedCubicChunkManager chunkManager, Level level) {
        this.generator = generator;
        this.lightEngine = new UnifiedCubicLightEngine(chunkManager, this, level);
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
        long key = getKey(x, z);
        CubeColumn column = columns.remove(key);
        if (column != null) {
            // Remove all cubes from ticking set
            for (CubeChunk cube : column.getLoadedCubes()) {
                tickingCubes.remove(cube);
            }
        }
    }

    /**
     * Gets the CubeChunk at the given x/y/z. Optionally creates column/cube if missing.
     * If a cube is created, it is generated using the generator.
     */
    public CubeChunk getCube(int x, int y, int z, boolean createIfMissing) {
        CubeColumn column = getColumn(x, z, createIfMissing);
        if (column == null) return null;
        CubeChunk cube = column.getCube(y, createIfMissing);
        if (cube != null && createIfMissing && !cube.isGenerated()) {
            generator.generateCube(cube);
            // Add to ticking cubes if it has content
            if (!cube.isEmpty()) {
                tickingCubes.add(cube);
            }
        }
        return cube;
    }

    /**
     * Sets a block in the world and updates lighting
     */
    public boolean setBlock(int worldX, int worldY, int worldZ, BlockState newState) {
        int cubeX = Math.floorDiv(worldX, CubeChunk.SIZE);
        int cubeY = Math.floorDiv(worldY, CubeChunk.SIZE);
        int cubeZ = Math.floorDiv(worldZ, CubeChunk.SIZE);
        
        CubeChunk cube = getCube(cubeX, cubeY, cubeZ, true);
        if (cube == null) return false;
        
        int localX = Math.floorMod(worldX, CubeChunk.SIZE);
        int localY = Math.floorMod(worldY, CubeChunk.SIZE);
        int localZ = Math.floorMod(worldZ, CubeChunk.SIZE);
        
        BlockState oldState = cube.getBlockState(localX, localY, localZ);
        cube.setBlockState(localX, localY, localZ, newState);
        
        // Update lighting using the unified lighting engine
        BlockPos pos = new BlockPos(worldX, worldY, worldZ);
        lightEngine.updateLightingForBlockChange(pos, oldState, newState);
        
        // Add to ticking cubes if it now has content
        if (!cube.isEmpty()) {
            tickingCubes.add(cube);
        }
        
        return true;
    }

    /**
     * Gets a block from the world
     */
    public BlockState getBlock(int worldX, int worldY, int worldZ) {
        int cubeX = Math.floorDiv(worldX, CubeChunk.SIZE);
        int cubeY = Math.floorDiv(worldY, CubeChunk.SIZE);
        int cubeZ = Math.floorDiv(worldZ, CubeChunk.SIZE);
        
        CubeChunk cube = getCube(cubeX, cubeY, cubeZ, false);
        if (cube == null) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        
        int localX = Math.floorMod(worldX, CubeChunk.SIZE);
        int localY = Math.floorMod(worldY, CubeChunk.SIZE);
        int localZ = Math.floorMod(worldZ, CubeChunk.SIZE);
        
        return cube.getBlockState(localX, localY, localZ);
    }

    /**
     * Gets the light level at a position
     */
    public int getLightLevel(int worldX, int worldY, int worldZ) {
        BlockPos pos = new BlockPos(worldX, worldY, worldZ);
        return lightEngine.getLightLevel(pos);
    }

    /**
     * Ticks all active cubes
     */
    public void tick(net.minecraft.world.level.Level level) {
        // Tick all cubes that have content
        for (CubeChunk cube : new ArrayList<>(tickingCubes)) {
            if (cube.isEmpty()) {
                tickingCubes.remove(cube);
            } else {
                cube.tick(level);
            }
        }
    }

    /**
     * Loads cubes in a radius around a position
     */
    public void loadCubesAround(int centerX, int centerY, int centerZ, int horizontalRadius, int verticalRadius) {
        for (int x = centerX - horizontalRadius; x <= centerX + horizontalRadius; x++) {
            for (int z = centerZ - horizontalRadius; z <= centerZ + horizontalRadius; z++) {
                for (int y = centerY - verticalRadius; y <= centerY + verticalRadius; y++) {
                    // Check if within circular radius
                    double distSq = Math.pow(x - centerX, 2) + Math.pow(z - centerZ, 2);
                    if (distSq <= horizontalRadius * horizontalRadius) {
                        getCube(x, y, z, true);
                    }
                }
            }
        }
    }

    /**
     * Unloads cubes outside a radius around a position
     */
    public void unloadCubesOutside(int centerX, int centerY, int centerZ, int horizontalRadius, int verticalRadius) {
        for (CubeColumn column : new ArrayList<>(columns.values())) {
            int columnX = column.getX();
            int columnZ = column.getZ();
            
            // Check if column is outside horizontal radius
            double distSq = Math.pow(columnX - centerX, 2) + Math.pow(columnZ - centerZ, 2);
            if (distSq > (horizontalRadius + 2) * (horizontalRadius + 2)) {
                unloadColumn(columnX, columnZ);
                continue;
            }
            
            // Unload cubes outside vertical radius
            for (CubeChunk cube : new ArrayList<>(column.getLoadedCubes())) {
                int cubeY = cube.getCubeY();
                if (Math.abs(cubeY - centerY) > verticalRadius + 2) {
                    column.unloadCube(cubeY);
                    tickingCubes.remove(cube);
                }
            }
        }
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

    public static CubeWorld fromNBT(CompoundTag tag, CubeChunkGenerator generator, 
                                   UnifiedCubicChunkManager chunkManager, Level level,
                                   net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomeRegistry) {
        CubeWorld world = new CubeWorld(generator, chunkManager, level);
        ListTag columnsList = tag.getList("columns", Tag.TAG_COMPOUND);
        for (int i = 0; i < columnsList.size(); i++) {
            CompoundTag columnTag = columnsList.getCompound(i);
            CubeColumn column = CubeColumn.fromNBT(columnTag, biomeRegistry);
            if (column != null) {
                long key = world.getKey(column.getX(), column.getZ());
                world.columns.put(key, column);
                
                // Add non-empty cubes to ticking set
                for (CubeChunk cube : column.getLoadedCubes()) {
                    if (!cube.isEmpty()) {
                        world.tickingCubes.add(cube);
                    }
                }
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

    /**
     * Returns the number of loaded columns
     */
    public int getLoadedColumnCount() {
        return columns.size();
    }

    /**
     * Returns the number of loaded cubes
     */
    public int getLoadedCubeCount() {
        return getLoadedCubes().size();
    }

    /**
     * Gets the lighting engine
     */
    public UnifiedCubicLightEngine getLightEngine() {
        return lightEngine;
    }
} 