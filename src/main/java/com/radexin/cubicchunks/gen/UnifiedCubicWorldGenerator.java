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
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.core.registries.Registries;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unified 3D world generator combining the best features from all previous generators.
 * Supports advanced terrain generation, 3D biomes, caves, ores, and structures.
 */
public class UnifiedCubicWorldGenerator {
    private final Level level;
    private final Registry<Biome> biomeRegistry;
    private final BiomeSource biomeSource;
    private final ExecutorService generationExecutor;
    private final long seed;
    private final RandomSource random;
    
    // 3D Noise generators for comprehensive terrain generation
    private final PerlinNoise continentalNoise;
    private final PerlinNoise erosionNoise;
    private final PerlinNoise peaksValleysNoise;
    private final PerlinNoise weirdnessNoise;
    private final PerlinNoise temperatureNoise;
    private final PerlinNoise humidityNoise;
    private final PerlinNoise caveNoise;
    private final PerlinNoise caveLayerNoise;
    private final PerlinNoise oreNoise;
    private final PerlinNoise biomeNoise;
    
    // Ore configurations
    private final Map<OreType, OreConfiguration> oreConfigurations;
    
    // Generation configuration
    private final boolean enableAdvancedTerrain = Config.enableAdvancedTerrain;
    private final boolean enableCaves = Config.enableCaves;
    private final boolean enableOres = Config.enableOres;
    private final boolean enableUndergroundStructures = Config.enableUndergroundStructures;
    private final boolean enableVerticalBiomes = Config.enableVerticalBiomes;
    
    // Terrain parameters
    private final int seaLevel = 63;
    private final int minY = -64;
    private final int maxY = 320;
    
    public UnifiedCubicWorldGenerator(Level level, Registry<Biome> biomeRegistry, BiomeSource biomeSource) {
        this.level = level;
        this.biomeRegistry = biomeRegistry;
        this.biomeSource = biomeSource;
        this.seed = level.getRandom().nextLong();
        this.random = new LegacyRandomSource(seed);
        this.generationExecutor = Executors.newFixedThreadPool(Config.cubeGenerationThreads);
        
        // Initialize comprehensive noise generators
        WorldgenRandom worldgenRandom = new WorldgenRandom(new LegacyRandomSource(seed));
        
        // Terrain shaping noise (multi-octave for detail)
        this.continentalNoise = PerlinNoise.create(worldgenRandom, List.of(-9, -8, -7, -6, -5, -4, -3, -2, -1, 0));
        this.erosionNoise = PerlinNoise.create(worldgenRandom, List.of(-7, -6, -5, -4, -3, -2, -1, 0));
        this.peaksValleysNoise = PerlinNoise.create(worldgenRandom, List.of(-8, -7, -6, -5, -4, -3, -2, -1, 0, 1));
        this.weirdnessNoise = PerlinNoise.create(worldgenRandom, List.of(-7, -6, -5, -4, -3, -2, -1, 0, 1));
        
        // Climate noise for biomes
        this.temperatureNoise = PerlinNoise.create(worldgenRandom, List.of(-10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0));
        this.humidityNoise = PerlinNoise.create(worldgenRandom, List.of(-8, -7, -6, -5, -4, -3, -2, -1, 0));
        this.biomeNoise = PerlinNoise.create(worldgenRandom, List.of(-7, -6, -5, -4, -3, -2, -1, 0));
        
        // Feature generation noise
        this.caveNoise = PerlinNoise.create(worldgenRandom, List.of(-6, -5, -4, -3, -2, -1, 0));
        this.caveLayerNoise = PerlinNoise.create(worldgenRandom, List.of(-4, -3, -2, -1, 0));
        this.oreNoise = PerlinNoise.create(worldgenRandom, List.of(-5, -4, -3, -2, -1, 0));
        
        // Initialize ore configurations
        this.oreConfigurations = createOreConfigurations();
    }
    
    /**
     * Generates a cubic chunk asynchronously.
     */
    public CompletableFuture<Void> generateCubeAsync(CubeChunk cube) {
        return CompletableFuture.runAsync(() -> generateCube(cube), generationExecutor);
    }
    
    /**
     * Generates a complete cube using unified 3D generation.
     */
    public void generateCube(CubeChunk cube) {
        if (cube.isGenerated()) {
            return;
        }
        
        int baseX = cube.getCubeX() * CubeChunk.SIZE;
        int baseY = cube.getCubeY() * CubeChunk.SIZE;
        int baseZ = cube.getCubeZ() * CubeChunk.SIZE;
        
        // Multi-pass generation for optimal results
        generateBaseTerrain(cube, baseX, baseY, baseZ);
        
        if (enableCaves) {
            generateCaves(cube, baseX, baseY, baseZ);
        }
        
        if (enableOres) {
            generateOres(cube, baseX, baseY, baseZ);
        }
        
        if (enableUndergroundStructures) {
            generateUndergroundStructures(cube, baseX, baseY, baseZ);
        }
        
        applyBiomeFeatures(cube, baseX, baseY, baseZ);
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
                    
                    // Set basic lighting for transparent blocks
                    if (blockState.isAir()) {
                        byte skyLight = calculateSkyLight(worldY);
                        cube.setSkyLight(x, y, z, skyLight);
                    } else {
                        int lightLevel = blockState.getLightEmission();
                        if (lightLevel > 0) {
                            cube.setBlockLight(x, y, z, (byte) lightLevel);
                        }
                    }
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
        // Multi-layer noise sampling for complex terrain
        double continental = getContinentalness(worldX, worldZ);
        double erosion = getErosion(worldX, worldZ);
        double peaksValleys = getPeaksValleys(worldX, worldZ);
        double weirdness = getWeirdness(worldX, worldZ);
        
        // Calculate terrain height and 3D density
        double terrainHeight = calculateTerrainHeight(worldX, worldZ, continental, erosion, peaksValleys);
        double density = calculateDensity3D(worldX, worldY, worldZ, terrainHeight, weirdness);
        
        // Determine block type based on density and biome
        if (density > 0.5) {
            return getTerrainBlock(worldX, worldY, worldZ, density);
        } else if (worldY <= seaLevel) {
            return Blocks.WATER.defaultBlockState();
        } else {
            return Blocks.AIR.defaultBlockState();
        }
    }
    
    private BlockState generateBasicTerrain(int worldX, int worldY, int worldZ) {
        // Simplified terrain generation
        double height = 64 + continentalNoise.getValue(worldX * 0.01, 0.0, worldZ * 0.01) * 32;
        
        if (worldY <= height) {
            if (worldY <= 0) {
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
        
        // Continental variation
        baseHeight += continental * 50;
        
        // Erosion effects
        double erosionHeight = (1.0 - Math.abs(erosion)) * 30;
        baseHeight += erosionHeight;
        
        // Local peaks and valleys
        baseHeight += peaksValleys * 40;
        
        return Math.max(minY, Math.min(maxY, baseHeight));
    }
    
    private double calculateDensity3D(int worldX, int worldY, int worldZ, double terrainHeight, double weirdness) {
        // Distance from terrain surface
        double distanceFromSurface = terrainHeight - worldY;
        double baseDensity = distanceFromSurface / 32.0;
        
        // 3D noise for overhangs and caves
        double noise3D = continentalNoise.getValue(worldX * 0.02, worldY * 0.01, worldZ * 0.02);
        baseDensity += noise3D * 0.3;
        
        // Weirdness effects for special terrain
        if (Math.abs(weirdness) > 0.7) {
            double weirdnessNoise3D = weirdnessNoise.getValue(worldX * 0.01, worldY * 0.01, worldZ * 0.01);
            baseDensity += weirdnessNoise3D * 0.5;
        }
        
        // Ensure bedrock at bottom
        if (worldY <= minY + 5) {
            baseDensity += (minY + 5 - worldY) * 2.0;
        }
        
        return Math.max(-2.0, Math.min(2.0, baseDensity));
    }
    
    private void generateCaves(CubeChunk cube, int baseX, int baseY, int baseZ) {
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
        // Multi-layer cave generation
        double cave1 = caveNoise.getValue(worldX * 0.03, worldY * 0.03, worldZ * 0.03);
        double cave2 = caveLayerNoise.getValue(worldX * 0.045, worldY * 0.045, worldZ * 0.045);
        
        // Depth-based cave probability
        double depthFactor = Math.max(0, (seaLevel - worldY) / (double)seaLevel);
        
        return (Math.abs(cave1) < 0.08 || Math.abs(cave2) < 0.06) && 
               depthFactor > 0.2 && worldY > minY + 5;
    }
    
    private void generateOres(CubeChunk cube, int baseX, int baseY, int baseZ) {
        for (int y = 0; y < CubeChunk.SIZE; y++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int x = 0; x < CubeChunk.SIZE; x++) {
                    int worldX = baseX + x;
                    int worldY = baseY + y;
                    int worldZ = baseZ + z;
                    
                    BlockState oreBlock = generateOreAt(worldX, worldY, worldZ);
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
    
    private BlockState generateOreAt(int worldX, int worldY, int worldZ) {
        double oreValue = oreNoise.getValue(worldX * 0.05, worldY * 0.05, worldZ * 0.05);
        
        // Height-based ore distribution
        if (worldY < 16 && oreValue > 0.8) {
            return Blocks.DIAMOND_ORE.defaultBlockState();
        } else if (worldY < 32 && oreValue > 0.7) {
            return Blocks.GOLD_ORE.defaultBlockState();
        } else if (worldY < 64 && oreValue > 0.6) {
            return Blocks.IRON_ORE.defaultBlockState();
        } else if (oreValue > 0.5) {
            return Blocks.COAL_ORE.defaultBlockState();
        }
        
        return null;
    }
    
    private void generateUndergroundStructures(CubeChunk cube, int baseX, int baseY, int baseZ) {
        Random structureRandom = new Random(seed + (long)baseX * 341873128712L + (long)baseY * 132897987541L + (long)baseZ * 914744123L);
        
        if (structureRandom.nextDouble() < 0.001) { // 0.1% chance per cube
            int structureType = structureRandom.nextInt(3);
            
            switch (structureType) {
                case 0 -> generateDungeon(cube, structureRandom);
                case 1 -> generateOreVein(cube, structureRandom);
                case 2 -> generateGeode(cube, structureRandom);
            }
        }
    }
    
    private void generateDungeon(CubeChunk cube, Random random) {
        int centerX = random.nextInt(CubeChunk.SIZE);
        int centerY = random.nextInt(CubeChunk.SIZE);
        int centerZ = random.nextInt(CubeChunk.SIZE);
        int size = 3 + random.nextInt(5);
        
        for (int x = Math.max(0, centerX - size); x < Math.min(CubeChunk.SIZE, centerX + size); x++) {
            for (int y = Math.max(0, centerY - size); y < Math.min(CubeChunk.SIZE, centerY + size); y++) {
                for (int z = Math.max(0, centerZ - size); z < Math.min(CubeChunk.SIZE, centerZ + size); z++) {
                    double distance = Math.sqrt((x - centerX) * (x - centerX) + 
                                              (y - centerY) * (y - centerY) + 
                                              (z - centerZ) * (z - centerZ));
                    if (distance < size) {
                        if (distance < size - 1) {
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
        int startX = random.nextInt(CubeChunk.SIZE);
        int startY = random.nextInt(CubeChunk.SIZE);
        int startZ = random.nextInt(CubeChunk.SIZE);
        int length = 8 + random.nextInt(16);
        
        double x = startX, y = startY, z = startZ;
        
        for (int i = 0; i < length; i++) {
            if (x >= 0 && x < CubeChunk.SIZE && y >= 0 && y < CubeChunk.SIZE && z >= 0 && z < CubeChunk.SIZE) {
                cube.setBlockState((int) x, (int) y, (int) z, Blocks.IRON_ORE.defaultBlockState());
            }
            
            x += random.nextGaussian() * 0.5;
            y += random.nextGaussian() * 0.3;
            z += random.nextGaussian() * 0.5;
        }
    }
    
    private void generateGeode(CubeChunk cube, Random random) {
        int centerX = random.nextInt(CubeChunk.SIZE);
        int centerY = random.nextInt(CubeChunk.SIZE);
        int centerZ = random.nextInt(CubeChunk.SIZE);
        int radius = 2 + random.nextInt(4);
        
        for (int x = Math.max(0, centerX - radius); x < Math.min(CubeChunk.SIZE, centerX + radius); x++) {
            for (int y = Math.max(0, centerY - radius); y < Math.min(CubeChunk.SIZE, centerY + radius); y++) {
                for (int z = Math.max(0, centerZ - radius); z < Math.min(CubeChunk.SIZE, centerZ + radius); z++) {
                    double distance = Math.sqrt((x - centerX) * (x - centerX) + 
                                              (y - centerY) * (y - centerY) + 
                                              (z - centerZ) * (z - centerZ));
                    if (distance < radius) {
                        if (distance < radius - 1) {
                            cube.setBlockState(x, y, z, Blocks.AIR.defaultBlockState());
                        } else {
                            cube.setBlockState(x, y, z, Blocks.AMETHYST_BLOCK.defaultBlockState());
                        }
                    }
                }
            }
        }
    }
    
    private void applyBiomeFeatures(CubeChunk cube, int baseX, int baseY, int baseZ) {
        if (!enableVerticalBiomes) return;
        
        for (int y = 0; y < CubeChunk.SIZE; y++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int x = 0; x < CubeChunk.SIZE; x++) {
                    int worldX = baseX + x;
                    int worldY = baseY + y;
                    int worldZ = baseZ + z;
                    
                    BiomeType biome = getBiomeAt(worldX, worldY, worldZ);
                    BlockState currentBlock = cube.getBlockState(x, y, z);
                    
                    // Apply biome-specific surface blocks
                    if (isSurfaceBlock(cube, x, y, z)) {
                        BlockState biomeBlock = getBiomeSpecificBlock(biome, worldY);
                        if (biomeBlock != null) {
                            cube.setBlockState(x, y, z, biomeBlock);
                        }
                    }
                }
            }
        }
    }
    
    private void initializeLighting(CubeChunk cube, int baseX, int baseY, int baseZ) {
        // Basic lighting initialization - full implementation would be more complex
        for (int y = 0; y < CubeChunk.SIZE; y++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int x = 0; x < CubeChunk.SIZE; x++) {
                    BlockState block = cube.getBlockState(x, y, z);
                    if (block.isAir()) {
                        int worldY = baseY + y;
                        byte skyLight = calculateSkyLight(worldY);
                        cube.setSkyLight(x, y, z, skyLight);
                    }
                }
            }
        }
    }
    
    // Utility methods
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
        return temperatureNoise.getValue(x * 0.005, 0.0, z * 0.005);
    }
    
    public double getHumidity(int x, int z) {
        return humidityNoise.getValue(x * 0.005, 0.0, z * 0.005);
    }
    
    private BiomeType getBiomeAt(int x, int y, int z) {
        double biomeValue = biomeNoise.getValue(x * 0.005, y * 0.001, z * 0.005);
        double temperature = getTemperature(x, z);
        double humidity = getHumidity(x, z);
        
        if (temperature < -0.3) {
            return BiomeType.SNOWY;
        } else if (humidity < -0.3) {
            return BiomeType.DESERT;
        } else if (biomeValue > 0.3) {
            return BiomeType.FOREST;
        } else {
            return BiomeType.PLAINS;
        }
    }
    
    private BlockState getTerrainBlock(int worldX, int worldY, int worldZ, double density) {
        if (worldY <= minY + 5) {
            return Blocks.BEDROCK.defaultBlockState();
        } else if (density > 0.8) {
            return Blocks.STONE.defaultBlockState();
        } else if (density > 0.6) {
            return Blocks.DIRT.defaultBlockState();
        } else {
            return Blocks.GRAVEL.defaultBlockState();
        }
    }
    
    private BlockState getSurfaceBlockForBiome(int worldX, int worldY, int worldZ) {
        BiomeType biome = getBiomeAt(worldX, worldY, worldZ);
        return switch (biome) {
            case DESERT -> Blocks.SAND.defaultBlockState();
            case SNOWY -> Blocks.SNOW_BLOCK.defaultBlockState();
            case FOREST -> Blocks.GRASS_BLOCK.defaultBlockState();
            default -> Blocks.GRASS_BLOCK.defaultBlockState();
        };
    }
    
    private BlockState getCaveBlock(int worldX, int worldY, int worldZ) {
        return worldY <= seaLevel ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }
    
    private boolean isSurfaceBlock(CubeChunk cube, int x, int y, int z) {
        // Check if this block is at the surface (has air above)
        if (y + 1 < CubeChunk.SIZE) {
            return cube.getBlockState(x, y + 1, z).isAir();
        }
        return false; // Would need to check adjacent cube
    }
    
    private BlockState getBiomeSpecificBlock(BiomeType biome, int worldY) {
        return switch (biome) {
            case DESERT -> worldY > seaLevel ? Blocks.SAND.defaultBlockState() : null;
            case SNOWY -> worldY > seaLevel ? Blocks.SNOW_BLOCK.defaultBlockState() : null;
            case FOREST -> worldY > seaLevel ? Blocks.GRASS_BLOCK.defaultBlockState() : null;
            default -> null;
        };
    }
    
    private byte calculateSkyLight(int worldY) {
        if (worldY > 128) {
            return 15;
        } else if (worldY > 64) {
            return (byte) (10 + (worldY - 64) / 8);
        } else {
            return 0;
        }
    }
    
    private Map<OreType, OreConfiguration> createOreConfigurations() {
        // Simplified ore configurations
        return new HashMap<>();
    }
    
    public void shutdown() {
        generationExecutor.shutdown();
    }
    
    public enum BiomeType {
        PLAINS, DESERT, FOREST, SNOWY
    }
    
    public enum OreType {
        COAL, IRON, GOLD, DIAMOND, REDSTONE, LAPIS, EMERALD, COPPER
    }
} 