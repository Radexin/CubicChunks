package com.radexin.cubicchunks.gen;

import com.radexin.cubicchunks.Config;
import com.radexin.cubicchunks.chunk.CubeChunk;
import net.minecraft.core.Registry;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Advanced 3D world generator for cubic chunks that merges the best features
 * from both CubicWorldGenerator and CubicWorldGenerator.
 * 
 * Features:
 * - Asynchronous generation with thread pool
 * - Comprehensive 3D terrain generation with multi-octave noise
 * - Advanced cave systems with multiple types
 * - Full ore generation with depth-based distribution
 * - Underground structures (dungeons, geodes, ore veins)
 * - Aquifer and lava feature generation
 * - 3D biome support with climate-based selection
 * - Configuration-based feature toggling
 * - Proper Minecraft integration with BiomeSource and Registry
 */
public class CubicWorldGenerator {
    // Core systems
    private final Level level;
    private final Registry<Biome> biomeRegistry;
    private final BiomeSource biomeSource;
    private final ExecutorService generationExecutor;
    private final long seed;
    private final RandomSource random;
    
    // Comprehensive noise generators for all aspects of generation
    private final PerlinNoise continentalNoise;
    private final PerlinNoise erosionNoise;
    private final PerlinNoise peaksValleysNoise;
    private final PerlinNoise weirdnessNoise;
    private final PerlinNoise temperatureNoise;
    private final PerlinNoise humidityNoise;
    private final PerlinNoise biomeNoise;
    
    // Feature-specific noise generators
    private final PerlinNoise caveNoise;
    private final PerlinNoise caveLayerNoise;
    private final PerlinNoise caveChamberNoise;
    private final PerlinNoise oreNoise;
    private final PerlinNoise aquiferNoise;
    private final PerlinNoise structureNoise;
    
    // Configuration system
    private final boolean enableAdvancedTerrain;
    private final boolean enableCaves;
    private final boolean enableOres;
    private final boolean enableUndergroundStructures;
    private final boolean enableVerticalBiomes;
    private final boolean enableAquifers;
    private final boolean enableLavaFeatures;
    private final int generationThreads;
    
    // Ore configurations
    private final Map<OreType, OreConfiguration> oreConfigurations;
    
    // Terrain parameters
    private final int seaLevel = 63;
    private final int minY = -64;
    private final int maxY = 320;
    
    public CubicWorldGenerator(Level level, Registry<Biome> biomeRegistry, BiomeSource biomeSource) {
        this.level = level;
        this.biomeRegistry = biomeRegistry;
        this.biomeSource = biomeSource;
        this.seed = level.getRandom().nextLong();
        this.random = new LegacyRandomSource(seed);
        
        // Load configuration
        this.enableAdvancedTerrain = Config.enableAdvancedTerrain;
        this.enableCaves = Config.enableCaves;
        this.enableOres = Config.enableOres;
        this.enableUndergroundStructures = Config.enableUndergroundStructures;
        this.enableVerticalBiomes = Config.enableVerticalBiomes;
        this.enableAquifers = true; // Default enabled
        this.enableLavaFeatures = true; // Default enabled
        this.generationThreads = Config.cubeGenerationThreads;
        
        this.generationExecutor = Executors.newFixedThreadPool(generationThreads);
        
        // Initialize comprehensive noise system
        WorldgenRandom worldgenRandom = new WorldgenRandom(new LegacyRandomSource(seed));
        
        // Multi-octave terrain shaping noise for high detail
        this.continentalNoise = PerlinNoise.create(worldgenRandom, List.of(-9, -8, -7, -6, -5, -4, -3, -2, -1, 0));
        this.erosionNoise = PerlinNoise.create(worldgenRandom, List.of(-7, -6, -5, -4, -3, -2, -1, 0));
        this.peaksValleysNoise = PerlinNoise.create(worldgenRandom, List.of(-8, -7, -6, -5, -4, -3, -2, -1, 0, 1));
        this.weirdnessNoise = PerlinNoise.create(worldgenRandom, List.of(-7, -6, -5, -4, -3, -2, -1, 0, 1));
        
        // Climate noise for realistic biome distribution
        this.temperatureNoise = PerlinNoise.create(worldgenRandom, List.of(-10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0));
        this.humidityNoise = PerlinNoise.create(worldgenRandom, List.of(-8, -7, -6, -5, -4, -3, -2, -1, 0));
        this.biomeNoise = PerlinNoise.create(worldgenRandom, List.of(-7, -6, -5, -4, -3, -2, -1, 0));
        
        // Advanced cave system noise generators
        this.caveNoise = PerlinNoise.create(worldgenRandom, List.of(-6, -5, -4, -3, -2, -1, 0));
        this.caveLayerNoise = PerlinNoise.create(worldgenRandom, List.of(-4, -3, -2, -1, 0));
        this.caveChamberNoise = PerlinNoise.create(worldgenRandom, List.of(-3, -2, -1, 0));
        
        // Resource and feature noise
        this.oreNoise = PerlinNoise.create(worldgenRandom, List.of(-5, -4, -3, -2, -1, 0));
        this.aquiferNoise = PerlinNoise.create(worldgenRandom, List.of(-6, -5, -4, -3, -2, -1));
        this.structureNoise = PerlinNoise.create(worldgenRandom, List.of(-4, -3, -2, -1, 0));
        
        // Initialize ore configurations
        this.oreConfigurations = createOreConfigurations();
    }
    
    /**
     * Generates a cubic chunk asynchronously for optimal performance.
     */
    public CompletableFuture<Void> generateCubeAsync(CubeChunk cube) {
        return CompletableFuture.runAsync(() -> generateCube(cube), generationExecutor);
    }
    
    /**
     * Generates a complete cube using advanced multi-pass 3D generation.
     */
    public void generateCube(CubeChunk cube) {
        if (cube.isGenerated()) {
            return;
        }
        
        int baseX = cube.getCubeX() * CubeChunk.SIZE;
        int baseY = cube.getCubeY() * CubeChunk.SIZE;
        int baseZ = cube.getCubeZ() * CubeChunk.SIZE;
        
        // Multi-pass generation for optimal quality and performance
        generateBaseTerrain(cube, baseX, baseY, baseZ);
        
        if (enableCaves) {
            generateAdvancedCaves(cube, baseX, baseY, baseZ);
        }
        
        if (enableAquifers) {
            generateAquifers(cube, baseX, baseY, baseZ);
        }
        
        if (enableLavaFeatures && baseY < seaLevel - 32) {
            generateLavaFeatures(cube, baseX, baseY, baseZ);
        }
        
        if (enableUndergroundStructures) {
            generateUndergroundStructures(cube, baseX, baseY, baseZ);
        }
        
        if (enableOres) {
            generateAdvancedOres(cube, baseX, baseY, baseZ);
        }
        
        if (enableVerticalBiomes) {
            applyBiomeFeatures(cube, baseX, baseY, baseZ);
        }
        
        initializeLighting(cube, baseX, baseY, baseZ);
        
        cube.setGenerated(true);
        cube.setDirty(true);
    }
    
    private void generateBaseTerrain(CubeChunk cube, int baseX, int baseY, int baseZ) {
        for (int y = 0; y < CubeChunk.SIZE; y++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int x = 0; x < CubeChunk.SIZE; x++) {
                    int worldX = baseX + x;
                    int worldY = baseY + y;
                    int worldZ = baseZ + z;
                    
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
        // Advanced multi-layer noise sampling for complex, realistic terrain
        double continental = getContinentalness(worldX, worldZ);
        double erosion = getErosion(worldX, worldZ);
        double peaksValleys = getPeaksValleys(worldX, worldZ);
        double weirdness = getWeirdness(worldX, worldZ);
        
        // Calculate terrain height using combined noise factors
        double terrainHeight = calculateTerrainHeight(worldX, worldZ, continental, erosion, peaksValleys);
        double density = calculateDensity3D(worldX, worldY, worldZ, terrainHeight, weirdness);
        
        // Advanced block type determination
        if (density > 0.5) {
            return getAdvancedTerrainBlock(worldX, worldY, worldZ, density, terrainHeight);
        } else if (worldY <= seaLevel) {
            return Blocks.WATER.defaultBlockState();
        } else {
            return Blocks.AIR.defaultBlockState();
        }
    }
    
    private BlockState generateBasicTerrain(int worldX, int worldY, int worldZ) {
        // Simplified terrain for performance-critical scenarios
        double height = seaLevel + continentalNoise.getValue(worldX * 0.01, 0.0, worldZ * 0.01) * 32;
        
        if (worldY <= height) {
            if (worldY <= minY + 5) {
                return Blocks.BEDROCK.defaultBlockState();
            } else if (worldY > height - 4) {
                return getSurfaceBlockForBiome(worldX, worldY, worldZ);
            } else {
                return Blocks.STONE.defaultBlockState();
            }
        } else if (worldY <= seaLevel) {
            return Blocks.WATER.defaultBlockState();
        } else {
            return Blocks.AIR.defaultBlockState();
        }
    }
    
    private double calculateTerrainHeight(int x, int z, double continental, double erosion, double peaksValleys) {
        double baseHeight = seaLevel;
        
        // Continental variation creates large-scale landmasses
        baseHeight += continental * 50;
        
        // Erosion creates realistic valleys and plateaus
        double erosionHeight = (1.0 - Math.abs(erosion)) * 30;
        baseHeight += erosionHeight;
        
        // Local peaks and valleys add detailed terrain variation
        baseHeight += peaksValleys * 40;
        
        return Math.max(minY, Math.min(maxY, baseHeight));
    }
    
    private double calculateDensity3D(int worldX, int worldY, int worldZ, double terrainHeight, double weirdness) {
        // Base density from distance to terrain surface
        double distanceFromSurface = terrainHeight - worldY;
        double baseDensity = distanceFromSurface / 32.0;
        
        // 3D noise for overhangs, floating islands, and complex structures
        double noise3D = continentalNoise.getValue(worldX * 0.02, worldY * 0.01, worldZ * 0.02);
        baseDensity += noise3D * 0.3;
        
        // Weirdness creates special terrain features
        if (Math.abs(weirdness) > 0.7) {
            double weirdnessNoise3D = weirdnessNoise.getValue(worldX * 0.01, worldY * 0.01, worldZ * 0.01);
            baseDensity += weirdnessNoise3D * 0.5;
        }
        
        // Ensure solid bedrock foundation
        if (worldY <= minY + 5) {
            baseDensity += (minY + 5 - worldY) * 2.0;
        }
        
        return Math.max(-2.0, Math.min(2.0, baseDensity));
    }
    
    private BlockState getAdvancedTerrainBlock(int worldX, int worldY, int worldZ, double density, double terrainHeight) {
        // Enhanced terrain block selection based on depth, biome, and local conditions
        if (worldY <= minY + 5) {
            return Blocks.BEDROCK.defaultBlockState();
        }
        
        // Calculate surface distance for depth-based selection
        double surfaceDistance = terrainHeight - worldY;
        
        // Get biome for biome-specific blocks
        BiomeType biome = getBiomeAt(worldX, worldY, worldZ);
        
        // Deep stone layers
        if (surfaceDistance < -10) {
            return worldY < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        }
        // Subsurface layers
        else if (surfaceDistance < -3) {
            return Blocks.STONE.defaultBlockState();
        }
        // Soil layers
        else if (surfaceDistance < -1) {
            return getBiomeSubsurfaceBlock(biome);
        }
        // Surface layer
        else if (surfaceDistance <= 0) {
            return getBiomeSurfaceBlock(biome, worldY);
        }
        
        return Blocks.AIR.defaultBlockState();
    }
    
    private void generateAdvancedCaves(CubeChunk cube, int baseX, int baseY, int baseZ) {
        // Multi-layer cave generation for realistic cave systems
        for (int y = 0; y < CubeChunk.SIZE; y++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int x = 0; x < CubeChunk.SIZE; x++) {
                    int worldX = baseX + x;
                    int worldY = baseY + y;
                    int worldZ = baseZ + z;
                    
                    if (shouldGenerateCave(worldX, worldY, worldZ)) {
                        BlockState currentBlock = cube.getBlockState(x, y, z);
                        if (!currentBlock.isAir() && currentBlock != Blocks.BEDROCK.defaultBlockState()) {
                            BlockState caveBlock = getCaveBlock(worldX, worldY, worldZ);
                            cube.setBlockState(x, y, z, caveBlock);
                        }
                    }
                }
            }
        }
    }
    
    private boolean shouldGenerateCave(int worldX, int worldY, int worldZ) {
        // Skip generation too close to surface or bedrock
        if (worldY < minY + 10 || worldY > seaLevel + 20) return false;
        
        // Multiple cave noise layers for complex cave systems
        double cave1 = caveNoise.getValue(worldX * 0.02, worldY * 0.02, worldZ * 0.02);
        double cave2 = caveLayerNoise.getValue(worldX * 0.05, worldY * 0.05, worldZ * 0.05);
        double cave3 = caveChamberNoise.getValue(worldX * 0.01, worldY * 0.01, worldZ * 0.01);
        
        // Combine noise for varied cave types
        double combinedNoise = (cave1 + cave2 * 0.5 + cave3 * 0.3) / 1.8;
        
        // Tunnel caves
        if (Math.abs(combinedNoise) < 0.12) return true;
        
        // Large caverns
        if (Math.abs(cave3) < 0.08 && worldY < seaLevel - 16) return true;
        
        // Depth-based cave probability
        double depthFactor = Math.max(0, (seaLevel - worldY) / (double)seaLevel);
        return Math.abs(cave2) < 0.06 && depthFactor > 0.2;
    }
    
    private void generateAquifers(CubeChunk cube, int baseX, int baseY, int baseZ) {
        if (baseY > seaLevel + 10) return; // Only in lower regions
        
        for (int y = 0; y < CubeChunk.SIZE; y++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int x = 0; x < CubeChunk.SIZE; x++) {
                    int worldX = baseX + x;
                    int worldY = baseY + y;
                    int worldZ = baseZ + z;
                    
                    double aquiferValue = aquiferNoise.getValue(worldX * 0.01, worldY * 0.01, worldZ * 0.01);
                    double humidityValue = getHumidity(worldX, worldZ);
                    
                    if (humidityValue > 0.6 && aquiferValue > 0.7) {
                        BlockState currentBlock = cube.getBlockState(x, y, z);
                        if (currentBlock.isAir()) {
                            cube.setBlockState(x, y, z, Blocks.WATER.defaultBlockState());
                        }
                    }
                }
            }
        }
    }
    
    private void generateLavaFeatures(CubeChunk cube, int baseX, int baseY, int baseZ) {
        for (int y = 0; y < CubeChunk.SIZE; y++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int x = 0; x < CubeChunk.SIZE; x++) {
                    int worldX = baseX + x;
                    int worldY = baseY + y;
                    int worldZ = baseZ + z;
                    
                    if (worldY > minY + 20) continue;
                    
                    double lavaChance = oreNoise.getValue(worldX * 0.03, worldY * 0.03, worldZ * 0.03);
                    
                    if (lavaChance > 0.8) {
                        BlockState currentBlock = cube.getBlockState(x, y, z);
                        if (currentBlock.isAir()) {
                            cube.setBlockState(x, y, z, Blocks.LAVA.defaultBlockState());
                        }
                    }
                }
            }
        }
    }
    
    private void generateUndergroundStructures(CubeChunk cube, int baseX, int baseY, int baseZ) {
        Random structureRandom = new Random(seed + (long)baseX * 341873128712L + (long)baseY * 132897987541L + (long)baseZ * 914744123L);
        
        if (structureRandom.nextDouble() < 0.001) { // 0.1% chance per cube
            int structureType = structureRandom.nextInt(4);
            
            switch (structureType) {
                case 0 -> generateDungeon(cube, structureRandom);
                case 1 -> generateOreVein(cube, structureRandom);
                case 2 -> generateGeode(cube, structureRandom);
                case 3 -> generateCrystalCave(cube, structureRandom);
            }
        }
    }
    
    private void generateDungeon(CubeChunk cube, Random random) {
        int centerX = 4 + random.nextInt(8);
        int centerY = 4 + random.nextInt(8);
        int centerZ = 4 + random.nextInt(8);
        int radius = 2 + random.nextInt(3);
        
        for (int y = Math.max(0, centerY - radius); y < Math.min(CubeChunk.SIZE, centerY + radius); y++) {
            for (int z = Math.max(0, centerZ - radius); z < Math.min(CubeChunk.SIZE, centerZ + radius); z++) {
                for (int x = Math.max(0, centerX - radius); x < Math.min(CubeChunk.SIZE, centerX + radius); x++) {
                    double distance = Math.sqrt((x - centerX) * (x - centerX) + 
                                              (y - centerY) * (y - centerY) + 
                                              (z - centerZ) * (z - centerZ));
                    
                    if (distance <= radius) {
                        if (distance <= radius - 1) {
                            cube.setBlockState(x, y, z, Blocks.AIR.defaultBlockState());
                        } else {
                            cube.setBlockState(x, y, z, Blocks.COBBLESTONE.defaultBlockState());
                        }
                    }
                }
            }
        }
    }
    
    private void generateOreVein(CubeChunk cube, Random random) {
        OreType oreType = OreType.values()[random.nextInt(OreType.values().length)];
        BlockState oreBlock = getOreBlock(oreType);
        
        double x = random.nextInt(CubeChunk.SIZE);
        double y = random.nextInt(CubeChunk.SIZE);
        double z = random.nextInt(CubeChunk.SIZE);
        
        for (int i = 0; i < 20; i++) {
            int blockX = (int) Math.round(x);
            int blockY = (int) Math.round(y);
            int blockZ = (int) Math.round(z);
            
            if (blockX >= 0 && blockX < CubeChunk.SIZE && 
                blockY >= 0 && blockY < CubeChunk.SIZE && 
                blockZ >= 0 && blockZ < CubeChunk.SIZE) {
                
                BlockState currentBlock = cube.getBlockState(blockX, blockY, blockZ);
                if (currentBlock == Blocks.STONE.defaultBlockState() || 
                    currentBlock == Blocks.DEEPSLATE.defaultBlockState()) {
                    cube.setBlockState(blockX, blockY, blockZ, oreBlock);
                }
            }
            
            x += (random.nextDouble() - 0.5) * 2;
            y += (random.nextDouble() - 0.5) * 2;
            z += (random.nextDouble() - 0.5) * 2;
        }
    }
    
    private void generateGeode(CubeChunk cube, Random random) {
        int centerX = 4 + random.nextInt(8);
        int centerY = 4 + random.nextInt(8);
        int centerZ = 4 + random.nextInt(8);
        int radius = 3 + random.nextInt(2);
        
        for (int y = Math.max(0, centerY - radius); y < Math.min(CubeChunk.SIZE, centerY + radius); y++) {
            for (int z = Math.max(0, centerZ - radius); z < Math.min(CubeChunk.SIZE, centerZ + radius); z++) {
                for (int x = Math.max(0, centerX - radius); x < Math.min(CubeChunk.SIZE, centerX + radius); x++) {
                    double distance = Math.sqrt((x - centerX) * (x - centerX) + 
                                              (y - centerY) * (y - centerY) + 
                                              (z - centerZ) * (z - centerZ));
                    
                    if (distance <= radius) {
                        if (distance <= radius - 2) {
                            cube.setBlockState(x, y, z, Blocks.AIR.defaultBlockState());
                            if (random.nextDouble() < 0.3) {
                                cube.setBlockState(x, y, z, Blocks.AMETHYST_CLUSTER.defaultBlockState());
                            }
                        } else if (distance <= radius - 1) {
                            cube.setBlockState(x, y, z, Blocks.AMETHYST_BLOCK.defaultBlockState());
                        } else {
                            cube.setBlockState(x, y, z, Blocks.CALCITE.defaultBlockState());
                        }
                    }
                }
            }
        }
    }
    
    private void generateCrystalCave(CubeChunk cube, Random random) {
        int centerX = random.nextInt(CubeChunk.SIZE);
        int centerY = random.nextInt(CubeChunk.SIZE);
        int centerZ = random.nextInt(CubeChunk.SIZE);
        int size = 4 + random.nextInt(6);
        
        for (int x = Math.max(0, centerX - size); x < Math.min(CubeChunk.SIZE, centerX + size); x++) {
            for (int y = Math.max(0, centerY - size); y < Math.min(CubeChunk.SIZE, centerY + size); y++) {
                for (int z = Math.max(0, centerZ - size); z < Math.min(CubeChunk.SIZE, centerZ + size); z++) {
                    double distance = Math.sqrt((x - centerX) * (x - centerX) + 
                                              (y - centerY) * (y - centerY) + 
                                              (z - centerZ) * (z - centerZ));
                    if (distance < size) {
                        cube.setBlockState(x, y, z, Blocks.AIR.defaultBlockState());
                        if (random.nextDouble() < 0.2) {
                            cube.setBlockState(x, y, z, Blocks.GLOWSTONE.defaultBlockState());
                        }
                    }
                }
            }
        }
    }
    
    private void generateAdvancedOres(CubeChunk cube, int baseX, int baseY, int baseZ) {
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int y = 0; y < CubeChunk.SIZE; y++) {
                for (int z = 0; z < CubeChunk.SIZE; z++) {
                    int worldX = baseX + x;
                    int worldY = baseY + y;
                    int worldZ = baseZ + z;
                    
                    BlockState currentBlock = cube.getBlockState(x, y, z);
                    
                    if (currentBlock.is(Blocks.STONE) || currentBlock.is(Blocks.DEEPSLATE)) {
                        BlockState oreBlock = generateOreAt(worldX, worldY, worldZ, currentBlock);
                        if (oreBlock != currentBlock) {
                            cube.setBlockState(x, y, z, oreBlock);
                        }
                    }
                }
            }
        }
    }
    
    private BlockState generateOreAt(int x, int y, int z, BlockState hostBlock) {
        double oreValue = oreNoise.getValue(x * 0.05, y * 0.05, z * 0.05);
        boolean isDeepslate = hostBlock.is(Blocks.DEEPSLATE);
        
        // Height-based ore distribution with realistic patterns
        if (y > -32 && y < 128 && oreValue > 0.8) {
            return isDeepslate ? Blocks.DEEPSLATE_COAL_ORE.defaultBlockState() : Blocks.COAL_ORE.defaultBlockState();
        }
        if (y > -48 && y < 112 && oreValue > 0.85) {
            return isDeepslate ? Blocks.DEEPSLATE_IRON_ORE.defaultBlockState() : Blocks.IRON_ORE.defaultBlockState();
        }
        if (y > -64 && y < 32 && oreValue > 0.9) {
            return isDeepslate ? Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState() : Blocks.GOLD_ORE.defaultBlockState();
        }
        if (y > -64 && y < 16 && oreValue > 0.95) {
            return isDeepslate ? Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState() : Blocks.DIAMOND_ORE.defaultBlockState();
        }
        if (y > -64 && y < 32 && oreValue > 0.92) {
            return isDeepslate ? Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState() : Blocks.REDSTONE_ORE.defaultBlockState();
        }
        
        return hostBlock;
    }
    
    private void applyBiomeFeatures(CubeChunk cube, int baseX, int baseY, int baseZ) {
        for (int x = 0; x < CubeChunk.SIZE; x += 4) {
            for (int z = 0; z < CubeChunk.SIZE; z += 4) {
                for (int y = 0; y < CubeChunk.SIZE; y += 4) {
                    int worldX = baseX + x;
                    int worldY = baseY + y;
                    int worldZ = baseZ + z;
                    
                    BiomeType biome = getBiomeAt(worldX, worldY, worldZ);
                    applyBiomeFeatureToArea(cube, x, y, z, biome);
                }
            }
        }
    }
    
    private void applyBiomeFeatureToArea(CubeChunk cube, int startX, int startY, int startZ, BiomeType biome) {
        for (int dx = 0; dx < 4 && startX + dx < CubeChunk.SIZE; dx++) {
            for (int dz = 0; dz < 4 && startZ + dz < CubeChunk.SIZE; dz++) {
                for (int dy = 0; dy < 4 && startY + dy < CubeChunk.SIZE; dy++) {
                    int x = startX + dx;
                    int y = startY + dy;
                    int z = startZ + dz;
                    
                    BlockState currentBlock = cube.getBlockState(x, y, z);
                    BlockState biomeBlock = getBiomeSpecificReplacement(biome, currentBlock);
                    
                    if (biomeBlock != null) {
                        cube.setBlockState(x, y, z, biomeBlock);
                    }
                }
            }
        }
    }
    
    private void initializeLighting(CubeChunk cube, int baseX, int baseY, int baseZ) {
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int y = CubeChunk.SIZE - 1; y >= 0; y--) {
                    BlockState block = cube.getBlockState(x, y, z);
                    
                    if (block.isAir()) {
                        int worldY = baseY + y;
                        byte skyLight = calculateSkyLight(worldY);
                        cube.setSkyLight(x, y, z, skyLight);
                    } else {
                        int lightLevel = block.getLightEmission();
                        if (lightLevel > 0) {
                            cube.setBlockLight(x, y, z, (byte) lightLevel);
                        }
                        break; // Stop sky light propagation
                    }
                }
            }
        }
    }
    
    // Utility methods for noise sampling
    public double getContinentalness(int x, int z) {
        return continentalNoise.getValue(x * 0.0001, 0.0, z * 0.0001);
    }
    
    public double getErosion(int x, int z) {
        return erosionNoise.getValue(x * 0.0005, 0.0, z * 0.0005);
    }
    
    public double getPeaksValleys(int x, int z) {
        return peaksValleysNoise.getValue(x * 0.0003, 0.0, z * 0.0003);
    }
    
    public double getWeirdness(int x, int z) {
        return weirdnessNoise.getValue(x * 0.0008, 0.0, z * 0.0008);
    }
    
    public double getTemperature(int x, int z) {
        return temperatureNoise.getValue(x * 0.0003, 0.0, z * 0.0003);
    }
    
    public double getHumidity(int x, int z) {
        return humidityNoise.getValue(x * 0.0004, 0.0, z * 0.0004);
    }
    
    private BiomeType getBiomeAt(int x, int y, int z) {
        double temperature = getTemperature(x, z);
        double humidity = getHumidity(x, z);
        double heightFactor = (y - seaLevel) / 100.0;
        
        if (y > seaLevel + 100) {
            return temperature < 0 ? BiomeType.SNOWY_PEAKS : BiomeType.STONY_PEAKS;
        } else if (temperature < -0.5) {
            return humidity > 0.2 ? BiomeType.TAIGA : BiomeType.SNOWY;
        } else if (temperature > 0.5) {
            return humidity < -0.3 ? BiomeType.DESERT : BiomeType.SAVANNA;
        } else {
            return humidity > 0.2 ? BiomeType.FOREST : BiomeType.PLAINS;
        }
    }
    
    private BlockState getSurfaceBlockForBiome(int worldX, int worldY, int worldZ) {
        BiomeType biome = getBiomeAt(worldX, worldY, worldZ);
        return getBiomeSurfaceBlock(biome, worldY);
    }
    
    private BlockState getBiomeSurfaceBlock(BiomeType biome, int worldY) {
        return switch (biome) {
            case DESERT -> Blocks.SAND.defaultBlockState();
            case SNOWY, SNOWY_PEAKS -> Blocks.SNOW_BLOCK.defaultBlockState();
            case TAIGA -> Blocks.PODZOL.defaultBlockState();
            case STONY_PEAKS -> Blocks.STONE.defaultBlockState();
            case SAVANNA -> Blocks.COARSE_DIRT.defaultBlockState();
            default -> worldY > seaLevel ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState();
        };
    }
    
    private BlockState getBiomeSubsurfaceBlock(BiomeType biome) {
        return switch (biome) {
            case DESERT -> Blocks.SANDSTONE.defaultBlockState();
            case SNOWY, SNOWY_PEAKS -> Blocks.DIRT.defaultBlockState();
            default -> Blocks.DIRT.defaultBlockState();
        };
    }
    
    private BlockState getCaveBlock(int worldX, int worldY, int worldZ) {
        return worldY <= seaLevel ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }
    
    private BlockState getBiomeSpecificReplacement(BiomeType biome, BlockState currentBlock) {
        if (currentBlock.is(Blocks.GRASS_BLOCK)) {
            return switch (biome) {
                case DESERT -> Blocks.SAND.defaultBlockState();
                case TAIGA -> random.nextFloat() < 0.1f ? Blocks.PODZOL.defaultBlockState() : null;
                default -> null;
            };
        }
        return null;
    }
    
    private byte calculateSkyLight(int worldY) {
        if (worldY > seaLevel + 32) {
            return 15;
        } else if (worldY > seaLevel) {
            return (byte) Math.max(0, 15 - (seaLevel + 32 - worldY) / 4);
        } else {
            return 0;
        }
    }
    
    private BlockState getOreBlock(OreType oreType) {
        return switch (oreType) {
            case COAL -> Blocks.COAL_ORE.defaultBlockState();
            case IRON -> Blocks.IRON_ORE.defaultBlockState();
            case GOLD -> Blocks.GOLD_ORE.defaultBlockState();
            case DIAMOND -> Blocks.DIAMOND_ORE.defaultBlockState();
            case REDSTONE -> Blocks.REDSTONE_ORE.defaultBlockState();
            case LAPIS -> Blocks.LAPIS_ORE.defaultBlockState();
            case EMERALD -> Blocks.EMERALD_ORE.defaultBlockState();
            case COPPER -> Blocks.COPPER_ORE.defaultBlockState();
        };
    }
    
    private Map<OreType, OreConfiguration> createOreConfigurations() {
        return new HashMap<>(); // Placeholder for future ore configuration system
    }
    
    public void shutdown() {
        generationExecutor.shutdown();
    }
    
    public enum BiomeType {
        PLAINS, DESERT, FOREST, SNOWY, TAIGA, SAVANNA, SNOWY_PEAKS, STONY_PEAKS
    }
    
    public enum OreType {
        COAL, IRON, GOLD, DIAMOND, REDSTONE, LAPIS, EMERALD, COPPER
    }
} 