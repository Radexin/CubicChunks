package com.radexin.cubicchunks.gen;

import com.radexin.cubicchunks.chunk.CubeChunk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;

/**
 * Advanced 3D world generation system for cubic chunks.
 * Provides proper 3D terrain generation with biome-aware features and structures.
 */
public class CubicWorldGenerator {
    // Noise generators for different aspects of terrain
    private final PerlinNoise continentalNoise;
    private final PerlinNoise erosionNoise;
    private final PerlinNoise peaksValleysNoise;
    private final PerlinNoise caveNoise;
    private final PerlinNoise oreNoise;
    private final PerlinNoise temperatureNoise;
    private final PerlinNoise humidityNoise;
    private final PerlinNoise weirdnessNoise;
    
    // Ore configurations
    private final Map<OreType, OreConfiguration> oreConfigurations;
    
    // World settings
    private final long seed;
    private final RandomSource random;
    
    // Terrain parameters
    private final int seaLevel = 63;
    private final int minY = -64;
    private final int maxY = 320;
    
    public CubicWorldGenerator(long seed) {
        this.seed = seed;
        this.random = new LegacyRandomSource(seed);
        
        WorldgenRandom worldgenRandom = new WorldgenRandom(new LegacyRandomSource(seed));
        
        // Initialize noise generators with different scales and octaves for varied terrain
        this.continentalNoise = PerlinNoise.create(worldgenRandom, -9, 1.0, 1.0); // Large scale continent shaping
        this.erosionNoise = PerlinNoise.create(worldgenRandom, -7, 1.0, 1.0, 1.0); // Medium scale erosion patterns
        this.peaksValleysNoise = PerlinNoise.create(worldgenRandom, -8, 1.0, 1.0, 1.0); // Mountain and valley formation
        this.caveNoise = PerlinNoise.create(worldgenRandom, -6, 1.0, 1.0, 1.0); // Cave generation
        this.oreNoise = PerlinNoise.create(worldgenRandom, -4, 1.0, 1.0, 1.0); // Ore distribution
        this.temperatureNoise = PerlinNoise.create(worldgenRandom, -10, 1.0, 1.0); // Temperature variation
        this.humidityNoise = PerlinNoise.create(worldgenRandom, -10, 1.0, 1.0); // Humidity variation
        this.weirdnessNoise = PerlinNoise.create(worldgenRandom, -5, 1.0, 1.0); // Special terrain features
        
        // Initialize ore configurations
        this.oreConfigurations = createOreConfigurations();
    }
    
    /**
     * Generates a complete cube using 3D noise and biome-aware generation.
     */
    public void generateCube(CubeChunk cube, Registry<Biome> biomeRegistry) {
        if (cube.isGenerated()) {
            return;
        }
        
        int baseX = cube.getCubeX() * CubeChunk.SIZE;
        int baseY = cube.getCubeY() * CubeChunk.SIZE;
        int baseZ = cube.getCubeZ() * CubeChunk.SIZE;
        
        // First pass: Generate basic terrain
        generateTerrain(cube, baseX, baseY, baseZ);
        
        // Second pass: Apply biome-specific features
        applyBiomeFeatures(cube, baseX, baseY, baseZ, biomeRegistry);
        
        // Third pass: Generate structures and features
        generateFeatures(cube, baseX, baseY, baseZ);
        
        // Fourth pass: Generate ores
        generateOres(cube, baseX, baseY, baseZ);
        
        // Final pass: Initialize lighting
        initializeLighting(cube, baseX, baseY, baseZ);
        
        cube.setGenerated(true);
        cube.setDirty(true);
    }
    
    private void generateTerrain(CubeChunk cube, int baseX, int baseY, int baseZ) {
        for (int y = 0; y < CubeChunk.SIZE; y++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int x = 0; x < CubeChunk.SIZE; x++) {
                    int worldX = baseX + x;
                    int worldY = baseY + y;
                    int worldZ = baseZ + z;
                    
                    BlockState blockToPlace = generateBlockAt(worldX, worldY, worldZ);
                    cube.setBlockState(x, y, z, blockToPlace);
                }
            }
        }
    }
    
    private BlockState generateBlockAt(int x, int y, int z) {
        // Calculate 3D density using multiple noise layers
        double continentalFactor = getContinentalness(x, z);
        double erosionFactor = getErosion(x, z);
        double peaksValleys = getPeaksValleys(x, z);
        double weirdness = getWeirdness(x, z);
        
        // Calculate terrain height and density
        double baseHeight = calculateTerrainHeight(x, z, continentalFactor, erosionFactor, peaksValleys);
        double density = calculateDensity(x, y, z, baseHeight, weirdness);
        
        // Determine block type based on density and position
        if (density > 0.5) {
            return getTerrainBlock(x, y, z, density);
        } else if (y <= seaLevel) {
            return Blocks.WATER.defaultBlockState();
        } else {
            return Blocks.AIR.defaultBlockState();
        }
    }
    
    private double getContinentalness(int x, int z) {
        return continentalNoise.getValue(x * 0.0001, 0.0, z * 0.0001);
    }
    
    private double getErosion(int x, int z) {
        return erosionNoise.getValue(x * 0.0005, 0.0, z * 0.0005);
    }
    
    private double getPeaksValleys(int x, int z) {
        return peaksValleysNoise.getValue(x * 0.0003, 0.0, z * 0.0003);
    }
    
    private double getWeirdness(int x, int z) {
        return weirdnessNoise.getValue(x * 0.0008, 0.0, z * 0.0008);
    }
    
    private double calculateTerrainHeight(int x, int z, double continentalness, double erosion, double peaksValleys) {
        // Combine different noise factors to create varied terrain
        double baseHeight = seaLevel;
        
        // Continental variation (-20 to +80 blocks from sea level)
        baseHeight += continentalness * 50;
        
        // Erosion creates valleys and plateaus
        double erosionHeight = (1.0 - Math.abs(erosion)) * 30;
        baseHeight += erosionHeight;
        
        // Peaks and valleys add local variation
        baseHeight += peaksValleys * 40;
        
        return Math.max(minY, Math.min(maxY, baseHeight));
    }
    
    private double calculateDensity(int x, int y, int z, double terrainHeight, double weirdness) {
        // Base density from distance to terrain surface
        double distanceFromSurface = terrainHeight - y;
        double baseDensity = distanceFromSurface / 32.0; // Density falls off over 32 blocks
        
        // Add 3D noise for caves and overhangs
        double caveValue = caveNoise.getValue(x * 0.02, y * 0.02, z * 0.02);
        
        // Create caves where cave noise is in certain range
        if (Math.abs(caveValue) < 0.15 && y > minY + 5 && y < terrainHeight - 5) {
            baseDensity -= 1.0; // Create cave
        }
        
        // Add weirdness for special terrain features
        if (Math.abs(weirdness) > 0.7) {
            double weirdnessNoise3D = this.weirdnessNoise.getValue(x * 0.01, y * 0.01, z * 0.01);
            baseDensity += weirdnessNoise3D * 0.5;
        }
        
        // Ensure bedrock at bottom
        if (y <= minY + 5) {
            baseDensity += (minY + 5 - y) * 2.0;
        }
        
        return Math.max(-2.0, Math.min(2.0, baseDensity));
    }
    
    private BlockState getTerrainBlock(int x, int y, int z, double density) {
        // Enhanced terrain block selection based on depth, biome, and local conditions
        
        // Get basic block type from depth
        if (y <= minY + 5) {
            return Blocks.BEDROCK.defaultBlockState();
        }
        
        // Calculate distance from surface for depth-based selection
        double surfaceDistance = calculateSurfaceDistance(x, y, z);
        
        // Stone/dirt/grass selection based on surface proximity
        if (surfaceDistance < -3) {
            return Blocks.STONE.defaultBlockState();
        } else if (surfaceDistance < -1) {
            return Blocks.DIRT.defaultBlockState();
        } else if (surfaceDistance <= 0) {
            if (y > seaLevel) {
                return Blocks.GRASS_BLOCK.defaultBlockState();
            } else {
                return Blocks.DIRT.defaultBlockState();
            }
        }
        
        return Blocks.AIR.defaultBlockState();
    }
    
    private double calculateSurfaceDistance(int x, int y, int z) {
        // Calculate approximate distance to terrain surface using noise
        double continentalness = getContinentalness(x, z);
        double erosion = getErosion(x, z);
        double peaksValleys = getPeaksValleys(x, z);
        
        double terrainHeight = calculateTerrainHeight(x, z, continentalness, erosion, peaksValleys);
        return terrainHeight - y;
    }
    
    private double getTemperature(int x, int z) {
        return temperatureNoise.getValue(x * 0.0003, 0.0, z * 0.0003);
    }
    
    private double getHumidity(int x, int z) {
        return humidityNoise.getValue(x * 0.0004, 0.0, z * 0.0004);
    }
    
    private void applyBiomeFeatures(CubeChunk cube, int baseX, int baseY, int baseZ, Registry<Biome> biomeRegistry) {
        // Implement biome-specific feature generation
        for (int x = 0; x < CubeChunk.SIZE; x += 4) {
            for (int z = 0; z < CubeChunk.SIZE; z += 4) {
                for (int y = 0; y < CubeChunk.SIZE; y += 4) {
                    int worldX = baseX + x;
                    int worldY = baseY + y;
                    int worldZ = baseZ + z;
                    
                    // Sample biome at this position
                    Holder<Biome> biome = getBiomeAt(worldX, worldY, worldZ, biomeRegistry);
                    
                    // Apply biome-specific modifications in a 4x4x4 area
                    applyBiomeFeatureToArea(cube, x, y, z, biome);
                }
            }
        }
    }
    
    private Holder<Biome> getBiomeAt(int x, int y, int z, Registry<Biome> biomeRegistry) {
        // Simplified 3D biome selection based on temperature, humidity, and height
        double temperature = getTemperature(x, z);
        double humidity = getHumidity(x, z);
        
        // Height affects biome selection
        double heightFactor = (y - seaLevel) / 100.0;
        
        if (y > seaLevel + 100) {
            // High altitude - mountains
            return biomeRegistry.getHolderOrThrow(temperature < 0 ? Biomes.JAGGED_PEAKS : Biomes.STONY_PEAKS);
        } else if (temperature < -0.5) {
            // Cold biomes
            if (humidity > 0.2) {
                return biomeRegistry.getHolderOrThrow(Biomes.TAIGA);
            } else {
                return biomeRegistry.getHolderOrThrow(Biomes.SNOWY_PLAINS);
            }
        } else if (temperature > 0.5) {
            // Hot biomes
            if (humidity < -0.3) {
                return biomeRegistry.getHolderOrThrow(Biomes.DESERT);
            } else {
                return biomeRegistry.getHolderOrThrow(Biomes.SAVANNA);
            }
        } else {
            // Temperate biomes
            if (humidity > 0.2) {
                return biomeRegistry.getHolderOrThrow(Biomes.FOREST);
            } else {
                return biomeRegistry.getHolderOrThrow(Biomes.PLAINS);
            }
        }
    }
    
    private void applyBiomeFeatureToArea(CubeChunk cube, int startX, int startY, int startZ, Holder<Biome> biome) {
        // Apply small-scale biome features
        // This is a simplified implementation - real biome features would be more complex
        
        for (int dx = 0; dx < 4 && startX + dx < CubeChunk.SIZE; dx++) {
            for (int dz = 0; dz < 4 && startZ + dz < CubeChunk.SIZE; dz++) {
                for (int dy = 0; dy < 4 && startY + dy < CubeChunk.SIZE; dy++) {
                    int x = startX + dx;
                    int y = startY + dy;
                    int z = startZ + dz;
                    
                    BlockState currentBlock = cube.getBlockState(x, y, z);
                    
                    // Apply biome-specific block changes
                    if (biome.is(Biomes.DESERT)) {
                        if (currentBlock.is(Blocks.GRASS_BLOCK)) {
                            cube.setBlockState(x, y, z, Blocks.SAND.defaultBlockState());
                        }
                    } else if (biome.is(Biomes.TAIGA)) {
                        if (currentBlock.is(Blocks.GRASS_BLOCK) && random.nextFloat() < 0.1f) {
                            cube.setBlockState(x, y, z, Blocks.PODZOL.defaultBlockState());
                        }
                    }
                }
            }
        }
    }
    
    private void generateFeatures(CubeChunk cube, int baseX, int baseY, int baseZ) {
        // Enhanced feature generation with proper 3D distribution
        
        // Cave generation
        generateCaves(cube, baseX, baseY, baseZ);
        
        // Underground structures
        generateUndergroundStructures(cube, baseX, baseY, baseZ);
        
        // Aquifers and water sources
        generateAquifers(cube, baseX, baseY, baseZ);
        
        // Lava lakes in lower regions
        if (baseY < seaLevel - 32) {
            generateLavaFeatures(cube, baseX, baseY, baseZ);
        }
    }
    
    private void generateCaves(CubeChunk cube, int baseX, int baseY, int baseZ) {
        // Advanced 3D cave generation using multiple noise layers
        
        for (int y = 0; y < CubeChunk.SIZE; y++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int x = 0; x < CubeChunk.SIZE; x++) {
                    int worldX = baseX + x;
                    int worldY = baseY + y;
                    int worldZ = baseZ + z;
                    
                    // Skip if too close to surface or bedrock
                    if (worldY < minY + 10 || worldY > seaLevel + 20) continue;
                    
                    // Multiple cave noise layers for variety
                    double caveNoise1 = caveNoise.getValue(worldX * 0.02, worldY * 0.02, worldZ * 0.02);
                    double caveNoise2 = caveNoise.getValue(worldX * 0.05, worldY * 0.05, worldZ * 0.05);
                    double caveNoise3 = caveNoise.getValue(worldX * 0.01, worldY * 0.01, worldZ * 0.01);
                    
                    // Combine noise for complex cave systems
                    double combinedNoise = (caveNoise1 + caveNoise2 * 0.5 + caveNoise3 * 0.3) / 1.8;
                    
                    // Create caves where noise is in specific range
                    if (Math.abs(combinedNoise) < 0.12) {
                        BlockState currentBlock = cube.getBlockState(x, y, z);
                        if (!currentBlock.isAir() && currentBlock != Blocks.BEDROCK.defaultBlockState()) {
                            // Create cave
                            cube.setBlockState(x, y, z, Blocks.AIR.defaultBlockState());
                            
                            // Add water to caves below sea level
                            if (worldY <= seaLevel) {
                                cube.setBlockState(x, y, z, Blocks.WATER.defaultBlockState());
                            }
                        }
                    }
                    
                    // Large caverns using different noise
                    if (Math.abs(caveNoise3) < 0.08 && worldY < seaLevel - 16) {
                        BlockState currentBlock = cube.getBlockState(x, y, z);
                        if (!currentBlock.isAir() && currentBlock != Blocks.BEDROCK.defaultBlockState()) {
                            cube.setBlockState(x, y, z, Blocks.AIR.defaultBlockState());
                        }
                    }
                }
            }
        }
    }
    
    private void generateUndergroundStructures(CubeChunk cube, int baseX, int baseY, int baseZ) {
        // Generate underground structures like dungeons and mineshafts
        
        Random structureRandom = new Random(seed + (long)baseX * 341873128712L + (long)baseY * 132897987541L + (long)baseZ * 914744123L);
        
        // Small chance for underground structures
        if (structureRandom.nextDouble() < 0.001) { // 0.1% chance per cube
            int structureType = structureRandom.nextInt(3);
            
            switch (structureType) {
                case 0:
                    generateSmallDungeon(cube, structureRandom);
                    break;
                case 1:
                    generateOreVein(cube, structureRandom);
                    break;
                case 2:
                    generateGeode(cube, structureRandom);
                    break;
            }
        }
    }
    
    private void generateSmallDungeon(CubeChunk cube, Random random) {
        // Generate a small underground room
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
                            // Hollow interior
                            cube.setBlockState(x, y, z, Blocks.AIR.defaultBlockState());
                        } else {
                            // Walls
                            cube.setBlockState(x, y, z, Blocks.COBBLESTONE.defaultBlockState());
                        }
                    }
                }
            }
        }
    }
    
    private void generateOreVein(CubeChunk cube, Random random) {
        // Generate a concentrated ore vein
        OreType oreType = OreType.values()[random.nextInt(OreType.values().length)];
        BlockState oreBlock = getOreBlock(oreType);
        
        int startX = random.nextInt(CubeChunk.SIZE);
        int startY = random.nextInt(CubeChunk.SIZE);
        int startZ = random.nextInt(CubeChunk.SIZE);
        
        // Generate a winding vein
        double dirX = (random.nextDouble() - 0.5) * 2;
        double dirY = (random.nextDouble() - 0.5) * 2;
        double dirZ = (random.nextDouble() - 0.5) * 2;
        
        double currentX = startX;
        double currentY = startY;
        double currentZ = startZ;
        
        for (int i = 0; i < 20; i++) {
            int x = (int) Math.round(currentX);
            int y = (int) Math.round(currentY);
            int z = (int) Math.round(currentZ);
            
            if (x >= 0 && x < CubeChunk.SIZE && y >= 0 && y < CubeChunk.SIZE && z >= 0 && z < CubeChunk.SIZE) {
                BlockState currentBlock = cube.getBlockState(x, y, z);
                if (currentBlock == Blocks.STONE.defaultBlockState()) {
                    cube.setBlockState(x, y, z, oreBlock);
                }
            }
            
            // Update direction with some randomness
            dirX += (random.nextDouble() - 0.5) * 0.5;
            dirY += (random.nextDouble() - 0.5) * 0.5;
            dirZ += (random.nextDouble() - 0.5) * 0.5;
            
            currentX += dirX;
            currentY += dirY;
            currentZ += dirZ;
        }
    }
    
    private void generateGeode(CubeChunk cube, Random random) {
        // Generate a geode structure
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
                            // Hollow interior with crystals
                            cube.setBlockState(x, y, z, Blocks.AIR.defaultBlockState());
                            if (random.nextDouble() < 0.3) {
                                cube.setBlockState(x, y, z, Blocks.AMETHYST_CLUSTER.defaultBlockState());
                            }
                        } else if (distance <= radius - 1) {
                            // Inner layer
                            cube.setBlockState(x, y, z, Blocks.AMETHYST_BLOCK.defaultBlockState());
                        } else {
                            // Outer shell
                            cube.setBlockState(x, y, z, Blocks.CALCITE.defaultBlockState());
                        }
                    }
                }
            }
        }
    }
    
    private void generateAquifers(CubeChunk cube, int baseX, int baseY, int baseZ) {
        // Generate underground water sources
        if (baseY > seaLevel + 10) return; // Only in lower regions
        
        for (int y = 0; y < CubeChunk.SIZE; y++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int x = 0; x < CubeChunk.SIZE; x++) {
                    int worldX = baseX + x;
                    int worldY = baseY + y;
                    int worldZ = baseZ + z;
                    
                    // Use humidity noise to determine aquifer locations
                    double humidityValue = getHumidity(worldX, worldZ);
                    double aquiferNoise = humidityNoise.getValue(worldX * 0.01, worldY * 0.01, worldZ * 0.01);
                    
                    if (humidityValue > 0.6 && aquiferNoise > 0.7) {
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
        // Generate lava lakes and pockets in deep areas
        for (int y = 0; y < CubeChunk.SIZE; y++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int x = 0; x < CubeChunk.SIZE; x++) {
                    int worldX = baseX + x;
                    int worldY = baseY + y;
                    int worldZ = baseZ + z;
                    
                    // Only in very deep areas
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
    
    private void generateOres(CubeChunk cube, int baseX, int baseY, int baseZ) {
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int y = 0; y < CubeChunk.SIZE; y++) {
                for (int z = 0; z < CubeChunk.SIZE; z++) {
                    int worldX = baseX + x;
                    int worldY = baseY + y;
                    int worldZ = baseZ + z;
                    
                    BlockState currentBlock = cube.getBlockState(x, y, z);
                    
                    // Only replace stone-like blocks with ores
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
        
        // Coal ore - common, higher up
        if (y > -32 && y < 128 && oreValue > 0.8) {
            return isDeepslate ? Blocks.DEEPSLATE_COAL_ORE.defaultBlockState() : Blocks.COAL_ORE.defaultBlockState();
        }
        
        // Iron ore - medium depth
        if (y > -48 && y < 112 && oreValue > 0.85) {
            return isDeepslate ? Blocks.DEEPSLATE_IRON_ORE.defaultBlockState() : Blocks.IRON_ORE.defaultBlockState();
        }
        
        // Gold ore - deeper
        if (y > -64 && y < 32 && oreValue > 0.9) {
            return isDeepslate ? Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState() : Blocks.GOLD_ORE.defaultBlockState();
        }
        
        // Diamond ore - very deep and rare
        if (y > -64 && y < 16 && oreValue > 0.95) {
            return isDeepslate ? Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState() : Blocks.DIAMOND_ORE.defaultBlockState();
        }
        
        return hostBlock;
    }
    
    private void initializeLighting(CubeChunk cube, int baseX, int baseY, int baseZ) {
        // Initialize basic lighting for the chunk
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int y = CubeChunk.SIZE - 1; y >= 0; y--) {
                    BlockState block = cube.getBlockState(x, y, z);
                    
                    if (block.isAir()) {
                        // Set sky light for air blocks
                        int worldY = baseY + y;
                        if (worldY > seaLevel + 32) {
                            cube.setSkyLight(x, y, z, (byte) 15);
                        } else if (worldY > seaLevel) {
                            cube.setSkyLight(x, y, z, (byte) Math.max(0, 15 - (seaLevel + 32 - worldY) / 4));
                        }
                    } else {
                        // Set block light for light-emitting blocks
                        int lightLevel = block.getLightEmission();
                        if (lightLevel > 0) {
                            cube.setBlockLight(x, y, z, (byte) lightLevel);
                        }
                        break; // Stop sky light propagation downward
                    }
                }
            }
        }
    }
    
    private Map<OreType, OreConfiguration> createOreConfigurations() {
        Map<OreType, OreConfiguration> configs = new HashMap<>();
        
        // This would contain proper ore configurations in a real implementation
        // For now, we use simple noise-based generation in generateOreAt()
        
        return configs;
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
    
    /**
     * Enum representing different types of ores that can be generated.
     */
    public enum OreType {
        COAL,
        IRON,
        GOLD,
        DIAMOND,
        REDSTONE,
        LAPIS,
        EMERALD,
        COPPER
    }
} 