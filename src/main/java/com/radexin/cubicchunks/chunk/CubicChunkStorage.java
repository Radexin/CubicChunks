package com.radexin.cubicchunks.chunk;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;

/**
 * High-level storage manager for cubic chunks, handling multiple region files.
 */
public class CubicChunkStorage {
    private final File worldDir;
    private final Map<Long, CubicRegionFile> regionFiles = new HashMap<>();
    private final Registry<Biome> biomeRegistry;

    public CubicChunkStorage(File worldDir, Registry<Biome> biomeRegistry) {
        this.worldDir = worldDir;
        this.biomeRegistry = biomeRegistry;
    }

    private long packRegionCoords(int regionX, int regionY, int regionZ) {
        // Pack three 21-bit signed integers into a long (63 bits total)
        return ((long)(regionX & 0x1FFFFF)) | 
               (((long)(regionY & 0x1FFFFF)) << 21) |
               (((long)(regionZ & 0x1FFFFF)) << 42);
    }

    private CubicRegionFile getRegionFile(int cubeX, int cubeY, int cubeZ) {
        int[] regionCoords = CubicRegionFile.getRegionCoords(cubeX, cubeY, cubeZ);
        int regionX = regionCoords[0];
        int regionY = regionCoords[1];
        int regionZ = regionCoords[2];

        long regionKey = packRegionCoords(regionX, regionY, regionZ);
        return regionFiles.computeIfAbsent(regionKey, 
            k -> new CubicRegionFile(worldDir, regionX, regionY, regionZ, biomeRegistry));
    }

    public void saveCube(CubeChunk cube) {
        CubicRegionFile regionFile = getRegionFile(cube.getCubeX(), cube.getCubeY(), cube.getCubeZ());
        regionFile.saveCube(cube);
    }

    public CubeChunk loadCube(int cubeX, int cubeY, int cubeZ) {
        CubicRegionFile regionFile = getRegionFile(cubeX, cubeY, cubeZ);
        return regionFile.loadCube(cubeX, cubeY, cubeZ);
    }

    public void saveAll() {
        for (CubicRegionFile regionFile : regionFiles.values()) {
            regionFile.saveAll();
        }
    }

    public void close() {
        for (CubicRegionFile regionFile : regionFiles.values()) {
            regionFile.close();
        }
        regionFiles.clear();
    }
} 