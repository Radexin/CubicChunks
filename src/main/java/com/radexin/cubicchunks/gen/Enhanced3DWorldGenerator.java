package com.radexin.cubicchunks.gen;

import com.radexin.cubicchunks.Config;
import com.radexin.cubicchunks.chunk.CubeChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enhanced 3D world generator with improved vanilla integration.
 * Supports 3D biomes, advanced terrain features, and proper structure generation.
 */
public class Enhanced3DWorldGenerator {
    private final Level level;
    private final Registry<Biome> biomeRegistry;
    private final BiomeSource biomeSource;
    private final ExecutorService generationExecutor;
    
    // 3D Noise generators for terrain
    private final PerlinNoise continentalNoise;
    private final PerlinNoise erosionNoise;
    private final PerlinNoise peaksValleysNoise;
    private final PerlinNoise weirdnessNoise;
    private final PerlinNoise temperatureNoise;
    private final PerlinNoise humidityNoise;
    
    // Cave and structure generation
    private final PerlinNoise caveNoise;
    private final PerlinNoise caveLayerNoise;
    private final PerlinNoise oreNoise;
    
    // Generation configuration
    private final boolean enableAdvancedTerrain = Config.enableAdvancedTerrain;
    private final boolean enableCaves = Config.enableCaves;
    private final boolean enableOres = Config.enableOres;
    private final boolean enableUndergroundStructures = Config.enableUndergroundStructures;
    private final boolean enableVerticalBiomes = Config.enableVerticalBiomes;
    
    public Enhanced3DWorldGenerator(Level level, Registry<Biome> biomeRegistry, BiomeSource biomeSource) {
        this.level = level;
        this.biomeRegistry = biomeRegistry;
        this.biomeSource = biomeSource;
        this.generationExecutor = Executors.newFixedThreadPool(Config.cubeGenerationThreads);
        
        // Initialize noise generators
        RandomSource random = RandomSource.create(level.getRandom().nextLong());
        
        this.continentalNoise = PerlinNoise.create(random, List.of(-9, -8, -7, -6, -5, -4, -3, -2, -1, 0));
        this.erosionNoise = PerlinNoise.create(random, List.of(-7, -6, -5, -4, -3, -2, -1, 0));
        this.peaksValleysNoise = PerlinNoise.create(random, List.of(-8, -7, -6, -5, -4, -3, -2, -1, 0, 1));
        this.weirdnessNoise = PerlinNoise.create(random, List.of(-7, -6, -5, -4, -3, -2, -1, 0, 1));
        this.temperatureNoise = PerlinNoise.create(random, List.of(-10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0));
        this.humidityNoise = PerlinNoise.create(random, List.of(-8, -7, -6, -5, -4, -3, -2, -1, 0));
        
        // Cave and feature noise
        this.caveNoise = PerlinNoise.create(random, List.of(-6, -5, -4, -3, -2, -1, 0));
        this.caveLayerNoise = PerlinNoise.create(random, List.of(-4, -3, -2, -1, 0));
        this.oreNoise = PerlinNoise.create(random, List.of(-5, -4, -3, -2, -1, 0));
    }
    
    /**
     * Generates a cubic chunk asynchronously.
     */
    public CompletableFuture<Void> generateCubeAsync(CubeChunk cube) {
        return CompletableFuture.runAsync(() -> generateCube(cube), generationExecutor);
    }
    
    /**
     * Generates a cubic chunk synchronously.
     */
    public void generateCube(CubeChunk cube) {
        // Generate base terrain
        generateBaseTerrain(cube);
        
        // Generate caves
        if (enableCaves) {
            generateCaves(cube);
        }
        
        // Generate ores
        if (enableOres) {
            generateOres(cube);
        }
        
        // Generate underground structures
        if (enableUndergroundStructures) {
            generateUndergroundStructures(cube);
        }
        
        cube.setGenerated(true);
    }
    
    private void generateBaseTerrain(CubeChunk cube) {
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int y = 0; y < CubeChunk.SIZE; y++) {
                for (int z = 0; z < CubeChunk.SIZE; z++) {
                    int worldX = cube.getCubeX() * CubeChunk.SIZE + x;
                    int worldY = cube.getCubeY() * CubeChunk.SIZE + y;
                    int worldZ = cube.getCubeZ() * CubeChunk.SIZE + z;
                    
                    BlockState blockState = generateTerrainBlock(worldX, worldY, worldZ);
                    cube.setBlockState(x, y, z, blockState);
                }
            }
        }
    }
    
    private BlockState generateTerrainBlock(int worldX, int worldY, int worldZ) {
        if (enableAdvancedTerrain) {
            return generateAdvancedTerrain(worldX, worldY, worldZ);
        } else {
            return generateBasicTerrain(worldX, worldY, worldZ);
        }
    }
    
    private BlockState generateAdvancedTerrain(int worldX, int worldY, int worldZ) {
        double scale = 0.01;
        
        // Sample multiple noise layers
        double continental = continentalNoise.getValue(worldX * scale, worldZ * scale, 0.0);
        double erosion = erosionNoise.getValue(worldX * scale * 2, worldZ * scale * 2, 0.0);
        double peaksValleys = peaksValleysNoise.getValue(worldX * scale * 1.5, worldZ * scale * 1.5, 0.0);
        double weirdness = weirdnessNoise.getValue(worldX * scale * 0.5, worldZ * scale * 0.5, 0.0);
        
        // Calculate base height
        double baseHeight = 64 + continental * 40 + erosion * 20 + peaksValleys * 30;
        
        // Add 3D variation
        double density3D = calculateDensity3D(worldX, worldY, worldZ, baseHeight);
        
        // Determine block type based on depth and biome
        if (density3D > 0) {
            return getTerrainBlock(worldX, worldY, worldZ, density3D);
        } else {
            return getFluidBlock(worldX, worldY, worldZ);
        }
    }
    
    private BlockState generateBasicTerrain(int worldX, int worldY, int worldZ) {
        // Simple heightmap-based generation
        double scale = 0.01;
        double height = 64 + continentalNoise.getValue(worldX * scale, worldZ * scale, 0.0) * 32;
        
        if (worldY <= height) {
            if (worldY <= 0) {
                return Blocks.BEDROCK.defaultBlockState();
            } else if (worldY > height - 4) {
                return Blocks.DIRT.defaultBlockState();
            } else {
                return Blocks.STONE.defaultBlockState();
            }
        } else if (worldY <= 63) {
            return Blocks.WATER.defaultBlockState();
        } else {
            return Blocks.AIR.defaultBlockState();
        }
    }
    
    private double calculateDensity3D(int worldX, int worldY, int worldZ, double baseHeight) {
        double scale = 0.02;
        
        // Vertical density function
        double heightFactor = 1.0 - Math.abs(worldY - baseHeight) / 40.0;
        heightFactor = Math.max(0, heightFactor);
        
        // 3D noise for caves and overhangs
        double noise3D = continentalNoise.getValue(worldX * scale, worldY * scale * 0.5, worldZ * scale);
        
        // Combine factors
        return heightFactor + noise3D * 0.3 - 0.1;
    }
    
    private void generateCaves(CubeChunk cube) {
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int y = 0; y < CubeChunk.SIZE; y++) {
                for (int z = 0; z < CubeChunk.SIZE; z++) {
                    int worldX = cube.getCubeX() * CubeChunk.SIZE + x;
                    int worldY = cube.getCubeY() * CubeChunk.SIZE + y;
                    int worldZ = cube.getCubeZ() * CubeChunk.SIZE + z;
                    
                    if (shouldGenerateCave(worldX, worldY, worldZ)) {
                        BlockState currentBlock = cube.getBlockState(x, y, z);
                        if (!currentBlock.isAir() && currentBlock != Blocks.WATER.defaultBlockState()) {
                            BlockState caveBlock = getCaveBlock(worldX, worldY, worldZ);
                            cube.setBlockState(x, y, z, caveBlock);
                        }
                    }
                }
            }
        }
    }
    
    private boolean shouldGenerateCave(int worldX, int worldY, int worldZ) {
        double scale = 0.03;
        
        // Multi-layer cave generation
        double cave1 = caveNoise.getValue(worldX * scale, worldY * scale, worldZ * scale);
        double cave2 = caveLayerNoise.getValue(worldX * scale * 1.5, worldY * scale * 1.5, worldZ * scale * 1.5);
        
        // Depth-based cave probability
        double depthFactor = Math.max(0, (64 - worldY) / 64.0);
        
        return (cave1 > 0.4 || cave2 > 0.6) && depthFactor > 0.2;
    }
    
    private void generateOres(CubeChunk cube) {
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int y = 0; y < CubeChunk.SIZE; y++) {
                for (int z = 0; z < CubeChunk.SIZE; z++) {
                    int worldX = cube.getCubeX() * CubeChunk.SIZE + x;
                    int worldY = cube.getCubeY() * CubeChunk.SIZE + y;
                    int worldZ = cube.getCubeZ() * CubeChunk.SIZE + z;
                    
                    BlockState oreBlock = generateOre(worldX, worldY, worldZ);
                    if (oreBlock != null) {
                        BlockState currentBlock = cube.getBlockState(x, y, z);
                        if (currentBlock == Blocks.STONE.defaultBlockState()) {
                            cube.setBlockState(x, y, z, oreBlock);
                        }
                    }
                }
            }
        }
    }
    
    private BlockState generateOre(int worldX, int worldY, int worldZ) {
        double scale = 0.05;
        double oreValue = oreNoise.getValue(worldX * scale, worldY * scale, worldZ * scale);
        
        // Different ores at different depths
        if (worldY < 16 && oreValue > 0.7) {
            return Blocks.DIAMOND_ORE.defaultBlockState();
        } else if (worldY < 32 && oreValue > 0.6) {
            return Blocks.GOLD_ORE.defaultBlockState();
        } else if (worldY < 48 && oreValue > 0.5) {
            return Blocks.IRON_ORE.defaultBlockState();
        } else if (oreValue > 0.4) {
            return Blocks.COAL_ORE.defaultBlockState();
        }
        
        return null;
    }
    
    private void generateUndergroundStructures(CubeChunk cube) {
        RandomSource random = RandomSource.create();
        // Create a deterministic seed based on cube coordinates
        long seed = ((long) cube.getCubeX() << 32) | ((long) cube.getCubeY() << 16) | cube.getCubeZ();
        random = RandomSource.create(seed);
        
        // Generate various underground structures
        if (random.nextFloat() < 0.001) { // 0.1% chance
            generateDungeon(cube, random);
        }
        
        if (random.nextFloat() < 0.0005) { // 0.05% chance
            generateGeode(cube, random);
        }
        
        if (random.nextFloat() < 0.002) { // 0.2% chance
            generateOreVein(cube, random);
        }
    }
    
    private void generateDungeon(CubeChunk cube, RandomSource random) {
        int centerX = random.nextInt(CubeChunk.SIZE);
        int centerY = random.nextInt(CubeChunk.SIZE);
        int centerZ = random.nextInt(CubeChunk.SIZE);
        
        int radius = 3 + random.nextInt(3);
        
        // Hollow out a spherical area
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int y = 0; y < CubeChunk.SIZE; y++) {
                for (int z = 0; z < CubeChunk.SIZE; z++) {
                    double distance = Math.sqrt(
                        (x - centerX) * (x - centerX) +
                        (y - centerY) * (y - centerY) +
                        (z - centerZ) * (z - centerZ)
                    );
                    
                    if (distance <= radius) {
                        if (distance >= radius - 1) {
                            cube.setBlockState(x, y, z, Blocks.COBBLESTONE.defaultBlockState());
                        } else {
                            cube.setBlockState(x, y, z, Blocks.AIR.defaultBlockState());
                        }
                    }
                }
            }
        }
    }
    
    private void generateGeode(CubeChunk cube, RandomSource random) {
        int centerX = random.nextInt(CubeChunk.SIZE);
        int centerY = random.nextInt(CubeChunk.SIZE);
        int centerZ = random.nextInt(CubeChunk.SIZE);
        
        int radius = 4 + random.nextInt(4);
        
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int y = 0; y < CubeChunk.SIZE; y++) {
                for (int z = 0; z < CubeChunk.SIZE; z++) {
                    double distance = Math.sqrt(
                        (x - centerX) * (x - centerX) +
                        (y - centerY) * (y - centerY) +
                        (z - centerZ) * (z - centerZ)
                    );
                    
                    if (distance <= radius) {
                        if (distance >= radius - 1) {
                            cube.setBlockState(x, y, z, Blocks.CALCITE.defaultBlockState());
                        } else if (distance >= radius - 2) {
                            cube.setBlockState(x, y, z, Blocks.AMETHYST_BLOCK.defaultBlockState());
                        } else {
                            cube.setBlockState(x, y, z, Blocks.AIR.defaultBlockState());
                        }
                    }
                }
            }
        }
    }
    
    private void generateOreVein(CubeChunk cube, RandomSource random) {
        // Generate winding ore veins
        int startX = random.nextInt(CubeChunk.SIZE);
        int startY = random.nextInt(CubeChunk.SIZE);
        int startZ = random.nextInt(CubeChunk.SIZE);
        
        int length = 8 + random.nextInt(16);
        
        double x = startX;
        double y = startY;
        double z = startZ;
        
        for (int i = 0; i < length; i++) {
            if (x >= 0 && x < CubeChunk.SIZE && y >= 0 && y < CubeChunk.SIZE && z >= 0 && z < CubeChunk.SIZE) {
                cube.setBlockState((int) x, (int) y, (int) z, Blocks.IRON_ORE.defaultBlockState());
            }
            
            // Random walk
            x += random.nextGaussian() * 0.5;
            y += random.nextGaussian() * 0.3;
            z += random.nextGaussian() * 0.5;
        }
    }
    
    private BlockState getTerrainBlock(int worldX, int worldY, int worldZ, double density) {
        if (worldY <= 0) {
            return Blocks.BEDROCK.defaultBlockState();
        } else if (density > 0.8) {
            return Blocks.STONE.defaultBlockState();
        } else if (density > 0.4) {
            return Blocks.DIRT.defaultBlockState();
        } else {
            return Blocks.GRAVEL.defaultBlockState();
        }
    }
    
    private BlockState getFluidBlock(int worldX, int worldY, int worldZ) {
        if (worldY <= 63) {
            return Blocks.WATER.defaultBlockState();
        } else if (worldY <= 0) {
            return Blocks.LAVA.defaultBlockState();
        } else {
            return Blocks.AIR.defaultBlockState();
        }
    }
    
    private BlockState getCaveBlock(int worldX, int worldY, int worldZ) {
        if (worldY <= 63) {
            return Blocks.WATER.defaultBlockState();
        } else {
            return Blocks.AIR.defaultBlockState();
        }
    }
    
    public void shutdown() {
        generationExecutor.shutdown();
    }
    
    // Getter methods for noise
    public double getTemperature(int x, int z) {
        return temperatureNoise.getValue(x * 0.005, z * 0.005, 0.0);
    }
    
    public double getHumidity(int x, int z) {
        return humidityNoise.getValue(x * 0.005, z * 0.005, 0.0);
    }
    
    public double getWeirdness(int x, int z) {
        return weirdnessNoise.getValue(x * 0.01, z * 0.01, 0.0);
    }
    
    public double getContinentalness(int x, int z) {
        return continentalNoise.getValue(x * 0.01, z * 0.01, 0.0);
    }
    
    public double getErosion(int x, int z) {
        return erosionNoise.getValue(x * 0.02, z * 0.02, 0.0);
    }
    
    public double getPeaksValleys(int x, int z) {
        return peaksValleysNoise.getValue(x * 0.015, z * 0.015, 0.0);
    }
} 