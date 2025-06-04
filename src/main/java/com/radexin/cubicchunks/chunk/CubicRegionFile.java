package com.radexin.cubicchunks.chunk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles saving and loading of cubic chunks in a region-like format.
 * Similar to vanilla .mca files but supports 3D coordinates.
 */
public class CubicRegionFile {
    private static final int REGION_SIZE = 32; // 32x32x32 cubic chunks per region
    private final File regionFile;
    private final int regionX, regionY, regionZ;
    private final Map<Long, CubeColumn> loadedColumns = new HashMap<>();
    private final Registry<Biome> biomeRegistry;

    public CubicRegionFile(File worldDir, int regionX, int regionY, int regionZ, Registry<Biome> biomeRegistry) {
        this.regionX = regionX;
        this.regionY = regionY;
        this.regionZ = regionZ;
        this.biomeRegistry = biomeRegistry;
        this.regionFile = new File(worldDir, "region/r." + regionX + "." + regionY + "." + regionZ + ".ccr");
        this.regionFile.getParentFile().mkdirs();
    }

    private long packColumnCoords(int localX, int localZ) {
        return (((long)localX) & 0xFFFFFFFFL) | ((((long)localZ) & 0xFFFFFFFFL) << 32);
    }

    private int[] unpackColumnCoords(long packed) {
        return new int[]{(int)(packed & 0xFFFFFFFFL), (int)(packed >>> 32)};
    }

    public static int[] getRegionCoords(int cubeX, int cubeY, int cubeZ) {
        return new int[]{
            Math.floorDiv(cubeX, REGION_SIZE),
            Math.floorDiv(cubeY, REGION_SIZE),
            Math.floorDiv(cubeZ, REGION_SIZE)
        };
    }

    public static int[] getLocalCoords(int cubeX, int cubeY, int cubeZ) {
        return new int[]{
            Math.floorMod(cubeX, REGION_SIZE),
            Math.floorMod(cubeY, REGION_SIZE),
            Math.floorMod(cubeZ, REGION_SIZE)
        };
    }

    public void saveCube(CubeChunk cube) {
        int[] localCoords = getLocalCoords(cube.getCubeX(), cube.getCubeY(), cube.getCubeZ());
        int localX = localCoords[0];
        int localZ = localCoords[2];
        
        long columnKey = packColumnCoords(localX, localZ);
        CubeColumn column = loadedColumns.computeIfAbsent(columnKey, 
            k -> new CubeColumn(cube.getCubeX(), cube.getCubeZ()));
        
        column.loadCube(cube.getCubeY(), cube);
    }

    public CubeChunk loadCube(int cubeX, int cubeY, int cubeZ) {
        int[] localCoords = getLocalCoords(cubeX, cubeY, cubeZ);
        int localX = localCoords[0];
        int localZ = localCoords[2];
        
        long columnKey = packColumnCoords(localX, localZ);
        CubeColumn column = loadedColumns.get(columnKey);
        
        if (column == null) {
            column = loadColumn(cubeX, cubeZ);
            if (column != null) {
                loadedColumns.put(columnKey, column);
            }
        }
        
        return column != null ? column.getCube(cubeY, false) : null;
    }

    private CubeColumn loadColumn(int cubeX, int cubeZ) {
        if (!regionFile.exists()) {
            return null;
        }

        try (DataInputStream input = new DataInputStream(new FileInputStream(regionFile))) {
            CompoundTag regionTag = NbtIo.read(input);
            if (regionTag == null) {
                return null;
            }

            String columnKey = "column_" + cubeX + "_" + cubeZ;
            if (regionTag.contains(columnKey)) {
                return CubeColumn.fromNBT(regionTag.getCompound(columnKey), biomeRegistry);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        return null;
    }

    public void saveAll() {
        if (loadedColumns.isEmpty()) {
            return;
        }

        try {
            CompoundTag regionTag = new CompoundTag();
            
            // Load existing data if file exists
            if (regionFile.exists()) {
                try (DataInputStream input = new DataInputStream(new FileInputStream(regionFile))) {
                    CompoundTag existingTag = NbtIo.read(input);
                    if (existingTag != null) {
                        regionTag = existingTag;
                    }
                }
            }

            // Save all loaded columns with their entity data
            for (CubeColumn column : loadedColumns.values()) {
                String columnKey = "column_" + column.getX() + "_" + column.getZ();
                CompoundTag columnNBT = column.toNBT();
                
                // Entity data is already included in the cube NBT from CubeChunk.toNBT()
                // No additional processing needed here as entities are serialized per-cube
                
                regionTag.put(columnKey, columnNBT);
            }

            // Write to file
            try (DataOutputStream output = new DataOutputStream(new FileOutputStream(regionFile))) {
                NbtIo.write(regionTag, output);
            }
            
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void close() {
        saveAll();
        loadedColumns.clear();
    }
} 