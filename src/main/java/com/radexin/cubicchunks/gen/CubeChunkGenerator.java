package com.radexin.cubicchunks.gen;

import com.radexin.cubicchunks.chunk.CubeChunk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;

/**
 * Responsible for generating CubeChunks for world generation.
 * This implementation uses 3D noise for terrain generation with caves and basic biome support.
 */
public class CubeChunkGenerator {
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS_BLOCK = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState COAL_ORE = Blocks.COAL_ORE.defaultBlockState();
    private static final BlockState IRON_ORE = Blocks.IRON_ORE.defaultBlockState();
    private static final BlockState DIAMOND_ORE = Blocks.DIAMOND_ORE.defaultBlockState();
    
    private final PerlinNoise terrainNoise;
    private final PerlinNoise caveNoise;
    private final PerlinNoise oreNoise;
    private final PerlinNoise biomeNoise;
    private final RandomSource random;
    
    public CubeChunkGenerator() {
        this.random = new LegacyRandomSource(12345L); // Fixed seed for consistent generation
        WorldgenRandom worldgenRandom = new WorldgenRandom(new LegacyRandomSource(12345L));
        
        // Create noise generators for different aspects of terrain
        this.terrainNoise = PerlinNoise.create(worldgenRandom, -8, 1.0, 1.0, 1.0, 1.0);
        this.caveNoise = PerlinNoise.create(worldgenRandom, -6, 1.0, 1.0, 1.0);
        this.oreNoise = PerlinNoise.create(worldgenRandom, -4, 1.0, 1.0, 1.0);
        this.biomeNoise = PerlinNoise.create(worldgenRandom, -7, 1.0, 1.0);
    }

    /**
     * Generates terrain for the given CubeChunk using 3D noise.
     */
    public void generateCube(CubeChunk cube) {
        if (cube.isGenerated()) {
            return; // Already generated
        }
        
        int baseX = cube.getCubeX() * CubeChunk.SIZE;
        int baseY = cube.getCubeY() * CubeChunk.SIZE;
        int baseZ = cube.getCubeZ() * CubeChunk.SIZE;
        
        for (int y = 0; y < CubeChunk.SIZE; y++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int x = 0; x < CubeChunk.SIZE; x++) {
                    int worldX = baseX + x;
                    int worldY = baseY + y;
                    int worldZ = baseZ + z;
                    
                    BlockState blockToPlace = generateBlockAt(worldX, worldY, worldZ);
                    cube.setBlockState(x, y, z, blockToPlace);
                    
                    // Set basic lighting
                    if (blockToPlace.isAir()) {
                        // Calculate sky light (simplified - assumes open sky above)
                        byte skyLight = calculateSkyLight(worldY);
                        cube.setSkyLight(x, y, z, skyLight);
                    } else {
                        // Set block light for light-emitting blocks
                        int lightLevel = blockToPlace.getLightEmission();
                        if (lightLevel > 0) {
                            cube.setBlockLight(x, y, z, (byte) lightLevel);
                        }
                    }
                }
            }
        }
        
        cube.setGenerated(true);
        cube.setDirty(true);
    }
    
    private BlockState generateBlockAt(int x, int y, int z) {
        // Generate terrain height using 2D noise
        double terrainHeight = getTerrainHeight(x, z);
        
        // Generate 3D density for caves and overhangs
        double density = getTerrainDensity(x, y, z);
        
        // Generate biome type
        BiomeType biome = getBiomeAt(x, z);
        
        // Water level
        int waterLevel = 63;
        
        // Basic terrain generation logic
        if (y < terrainHeight - 5) {
            // Deep underground - stone with ores
            if (density > 0.6) {
                return generateOre(x, y, z);
            } else if (density > 0.3) {
                return STONE;
            } else {
                return AIR; // Cave
            }
        } else if (y < terrainHeight - 1) {
            // Near surface - dirt/stone
            if (density > 0.4) {
                return biome == BiomeType.DESERT ? SAND : DIRT;
            } else {
                return AIR; // Cave
            }
        } else if (y < terrainHeight) {
            // Surface layer
            if (density > 0.2) {
                return getSurfaceBlock(biome, y, waterLevel);
            } else {
                return AIR; // Cave opening
            }
        } else if (y <= waterLevel) {
            // Below water level but above terrain
            return WATER;
        } else {
            // Above terrain and water
            return AIR;
        }
    }
    
    private double getTerrainHeight(int x, int z) {
        // Use 2D noise to generate terrain height
        double noise = terrainNoise.getValue(x * 0.01, 0.0, z * 0.01);
        return 64 + noise * 32; // Base height 64, variation of ±32
    }
    
    private double getTerrainDensity(int x, int y, int z) {
        // Use 3D noise to create caves and overhangs
        double noise = caveNoise.getValue(x * 0.02, y * 0.02, z * 0.02);
        
        // Modify density based on depth (more solid deeper underground)
        double depthFactor = Math.max(0, (64 - y) * 0.01);
        return noise + depthFactor;
    }
    
    private BiomeType getBiomeAt(int x, int z) {
        double biomeNoise = this.biomeNoise.getValue(x * 0.005, 0.0, z * 0.005);
        
        if (biomeNoise < -0.3) {
            return BiomeType.DESERT;
        } else if (biomeNoise > 0.3) {
            return BiomeType.FOREST;
        } else {
            return BiomeType.PLAINS;
        }
    }
    
    private BlockState generateOre(int x, int y, int z) {
        double oreNoise = this.oreNoise.getValue(x * 0.1, y * 0.1, z * 0.1);
        
        if (y < 16 && oreNoise > 0.8) {
            return DIAMOND_ORE;
        } else if (y < 64 && oreNoise > 0.7) {
            return IRON_ORE;
        } else if (oreNoise > 0.6) {
            return COAL_ORE;
        } else {
            return STONE;
        }
    }
    
    private BlockState getSurfaceBlock(BiomeType biome, int y, int waterLevel) {
        switch (biome) {
            case DESERT:
                return y <= waterLevel ? SAND : SAND;
            case FOREST:
                return y <= waterLevel ? DIRT : GRASS_BLOCK;
            case PLAINS:
            default:
                return y <= waterLevel ? DIRT : GRASS_BLOCK;
        }
    }
    
    private byte calculateSkyLight(int worldY) {
        // Simplified sky light calculation
        // In a full implementation, this would trace upward to check for obstructions
        if (worldY > 128) {
            return 15; // Full sky light above certain height
        } else if (worldY > 64) {
            return (byte) (10 + (worldY - 64) / 8); // Gradual increase
        } else {
            return 0; // No sky light underground
        }
    }
    
    private enum BiomeType {
        PLAINS,
        FOREST,
        DESERT
    }
} 