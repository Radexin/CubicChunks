package com.radexin.cubicchunks.gen;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enhanced 3D biome provider that generates biomes based on 3D coordinates.
 * Features realistic biome transitions, vertical biome layers, and climate-based generation.
 */
public class Enhanced3DBiomeProvider {
    private final Registry<Biome> biomeRegistry;
    private final BiomeSource vanillaBiomeSource;
    private final Enhanced3DWorldGenerator worldGenerator;
    
    // 3D biome noise generators
    private final PerlinNoise temperatureVariation;
    private final PerlinNoise humidityVariation;
    private final PerlinNoise altitudeNoise;
    private final PerlinNoise biomeTransitionNoise;
    
    // Biome cache for performance
    private final Map<Long, Holder<Biome>> biomeCache = new ConcurrentHashMap<>();
    private static final int CACHE_SIZE = 8192;
    
    // Biome layer definitions
    private static final int DEEP_UNDERGROUND_LEVEL = -64;
    private static final int UNDERGROUND_LEVEL = 0;
    private static final int SURFACE_LEVEL = 64;
    private static final int MOUNTAIN_LEVEL = 128;
    private static final int SKY_LEVEL = 256;
    
    public Enhanced3DBiomeProvider(Registry<Biome> biomeRegistry, BiomeSource vanillaBiomeSource, Enhanced3DWorldGenerator worldGenerator) {
        this.biomeRegistry = biomeRegistry;
        this.vanillaBiomeSource = vanillaBiomeSource;
        this.worldGenerator = worldGenerator;
        
        // Initialize 3D biome noise generators
        RandomSource random = RandomSource.create(12345L); // Fixed seed for consistent biomes
        this.temperatureVariation = PerlinNoise.create(random, List.of(-8, -7, -6, -5, -4, -3, -2, -1, 0));
        this.humidityVariation = PerlinNoise.create(random, List.of(-7, -6, -5, -4, -3, -2, -1, 0));
        this.altitudeNoise = PerlinNoise.create(random, List.of(-6, -5, -4, -3, -2, -1, 0));
        this.biomeTransitionNoise = PerlinNoise.create(random, List.of(-5, -4, -3, -2, -1, 0));
    }
    
    /**
     * Gets the biome at a specific 3D coordinate.
     */
    public Holder<Biome> getBiome3D(int x, int y, int z) {
        // Check cache first
        long key = packCoords(x, y, z);
        Holder<Biome> cached = biomeCache.get(key);
        if (cached != null) {
            return cached;
        }
        
        // Generate biome
        Holder<Biome> biome = generateBiome3D(x, y, z);
        
        // Cache result if not full
        if (biomeCache.size() < CACHE_SIZE) {
            biomeCache.put(key, biome);
        }
        
        return biome;
    }
    
    private Holder<Biome> generateBiome3D(int x, int y, int z) {
        // Get base climate from vanilla source
        Holder<Biome> baseBiome = vanillaBiomeSource.getNoiseBiome(x >> 2, 0, z >> 2, null);
        
        // Calculate 3D climate factors
        double temperature = calculateTemperature(x, y, z);
        double humidity = calculateHumidity(x, y, z);
        double altitude = calculateAltitude(x, y, z);
        
        // Determine vertical layer
        BiomeLayer layer = getVerticalLayer(y);
        
        // Generate biome based on layer and climate
        return generateLayeredBiome(baseBiome, layer, temperature, humidity, altitude, x, y, z);
    }
    
    private double calculateTemperature(int x, int y, int z) {
        // Base temperature from world generator
        double baseTemp = worldGenerator.getTemperature(x, z);
        
        // Add 3D variation
        double variation = temperatureVariation.getValue(x * 0.001, y * 0.0005, z * 0.001);
        
        // Altitude affects temperature (colder at higher altitudes)
        double altitudeEffect = Math.max(0, y - SURFACE_LEVEL) * -0.002;
        
        return baseTemp + variation * 0.3 + altitudeEffect;
    }
    
    private double calculateHumidity(int x, int y, int z) {
        // Base humidity from world generator
        double baseHumidity = worldGenerator.getHumidity(x, z);
        
        // Add 3D variation
        double variation = humidityVariation.getValue(x * 0.0008, y * 0.0003, z * 0.0008);
        
        // Underground tends to be more humid
        double depthEffect = y < SURFACE_LEVEL ? (SURFACE_LEVEL - y) * 0.001 : 0;
        
        return baseHumidity + variation * 0.2 + depthEffect;
    }
    
    private double calculateAltitude(int x, int y, int z) {
        return altitudeNoise.getValue(x * 0.002, y * 0.001, z * 0.002);
    }
    
    private BiomeLayer getVerticalLayer(int y) {
        if (y < DEEP_UNDERGROUND_LEVEL) {
            return BiomeLayer.DEEP_UNDERGROUND;
        } else if (y < UNDERGROUND_LEVEL) {
            return BiomeLayer.UNDERGROUND;
        } else if (y < SURFACE_LEVEL) {
            return BiomeLayer.SURFACE;
        } else if (y < MOUNTAIN_LEVEL) {
            return BiomeLayer.ELEVATED;
        } else if (y < SKY_LEVEL) {
            return BiomeLayer.MOUNTAIN;
        } else {
            return BiomeLayer.SKY;
        }
    }
    
    private Holder<Biome> generateLayeredBiome(Holder<Biome> baseBiome, BiomeLayer layer, 
                                              double temperature, double humidity, double altitude,
                                              int x, int y, int z) {
        switch (layer) {
            case DEEP_UNDERGROUND:
                return generateDeepUndergroundBiome(temperature, humidity, x, y, z);
                
            case UNDERGROUND:
                return generateUndergroundBiome(baseBiome, temperature, humidity, x, y, z);
                
            case SURFACE:
                return generateSurfaceBiome(baseBiome, temperature, humidity, altitude, x, y, z);
                
            case ELEVATED:
                return generateElevatedBiome(baseBiome, temperature, humidity, x, y, z);
                
            case MOUNTAIN:
                return generateMountainBiome(temperature, humidity, x, y, z);
                
            case SKY:
                return generateSkyBiome(temperature, humidity, x, y, z);
                
            default:
                return baseBiome;
        }
    }
    
    private Holder<Biome> generateDeepUndergroundBiome(double temperature, double humidity, int x, int y, int z) {
        // Deep underground biomes based on temperature and special features
        if (temperature < -0.5) {
            return biomeRegistry.getHolderOrThrow(Biomes.DEEP_FROZEN_OCEAN); // Frozen caves
        } else if (temperature > 0.5) {
            return biomeRegistry.getHolderOrThrow(Biomes.BASALT_DELTAS); // Hot underground
        } else {
            return biomeRegistry.getHolderOrThrow(Biomes.DEEP_DARK);
        }
    }
    
    private Holder<Biome> generateUndergroundBiome(Holder<Biome> baseBiome, double temperature, double humidity, int x, int y, int z) {
        // Underground variants of surface biomes
        if (humidity > 0.7) {
            return biomeRegistry.getHolderOrThrow(Biomes.LUSH_CAVES);
        } else if (temperature < -0.3) {
            return biomeRegistry.getHolderOrThrow(Biomes.ICE_SPIKES); // Frozen underground
        } else if (temperature > 0.8) {
            return biomeRegistry.getHolderOrThrow(Biomes.SOUL_SAND_VALLEY); // Hot underground
        } else {
            return biomeRegistry.getHolderOrThrow(Biomes.DRIPSTONE_CAVES);
        }
    }
    
    private Holder<Biome> generateSurfaceBiome(Holder<Biome> baseBiome, double temperature, double humidity, 
                                              double altitude, int x, int y, int z) {
        // Use base biome with some modifications based on local climate
        double transition = biomeTransitionNoise.getValue(x * 0.003, y * 0.001, z * 0.003);
        
        // Create biome transitions based on climate
        if (temperature < -0.5 && humidity < 0.3) {
            return biomeRegistry.getHolderOrThrow(Biomes.FROZEN_PEAKS);
        } else if (temperature > 0.7 && humidity < 0.2) {
            return biomeRegistry.getHolderOrThrow(Biomes.DESERT);
        } else if (humidity > 0.8 && temperature > 0.3) {
            return biomeRegistry.getHolderOrThrow(Biomes.JUNGLE);
        } else if (altitude > 0.5 && transition > 0.3) {
            return biomeRegistry.getHolderOrThrow(Biomes.WINDSWEPT_HILLS);
        }
        
        return baseBiome; // Use vanilla biome as fallback
    }
    
    private Holder<Biome> generateElevatedBiome(Holder<Biome> baseBiome, double temperature, double humidity, int x, int y, int z) {
        // Elevated biomes - transition to mountain variants
        if (temperature < -0.2) {
            return biomeRegistry.getHolderOrThrow(Biomes.SNOWY_SLOPES);
        } else if (temperature > 0.5) {
            return biomeRegistry.getHolderOrThrow(Biomes.SAVANNA_PLATEAU);
        } else {
            return biomeRegistry.getHolderOrThrow(Biomes.MEADOW);
        }
    }
    
    private Holder<Biome> generateMountainBiome(double temperature, double humidity, int x, int y, int z) {
        // High altitude mountain biomes
        if (temperature < -0.3) {
            return biomeRegistry.getHolderOrThrow(Biomes.FROZEN_PEAKS);
        } else if (temperature < 0.2) {
            return biomeRegistry.getHolderOrThrow(Biomes.STONY_PEAKS);
        } else {
            return biomeRegistry.getHolderOrThrow(Biomes.JAGGED_PEAKS);
        }
    }
    
    private Holder<Biome> generateSkyBiome(double temperature, double humidity, int x, int y, int z) {
        // Sky biomes for very high altitudes
        if (temperature < -0.5) {
            return biomeRegistry.getHolderOrThrow(Biomes.FROZEN_OCEAN); // Frozen sky
        } else {
            return biomeRegistry.getHolderOrThrow(Biomes.THE_VOID); // Normal sky
        }
    }
    
    /**
     * Gets the surface biome for a given X,Z coordinate.
     */
    public Holder<Biome> getSurfaceBiome(int x, int z) {
        return getBiome3D(x, SURFACE_LEVEL, z);
    }
    
    /**
     * Checks if a biome transition should occur at the given coordinates.
     */
    public boolean shouldTransitionBiome(int x, int y, int z) {
        double transition = biomeTransitionNoise.getValue(x * 0.005, y * 0.002, z * 0.005);
        return Math.abs(transition) > 0.6;
    }
    
    /**
     * Gets biomes in a radius around a point for smooth transitions.
     */
    public Holder<Biome>[] getBiomesInRadius(int centerX, int centerY, int centerZ, int radius) {
        int size = (radius * 2 + 1);
        @SuppressWarnings("unchecked")
        Holder<Biome>[] biomes = new Holder[size * size * size];
        
        int index = 0;
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int y = centerY - radius; y <= centerY + radius; y++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    biomes[index++] = getBiome3D(x, y, z);
                }
            }
        }
        
        return biomes;
    }
    
    /**
     * Clears the biome cache to free memory.
     */
    public void clearCache() {
        biomeCache.clear();
    }
    
    /**
     * Gets cache statistics.
     */
    public BiomeCacheStats getCacheStats() {
        return new BiomeCacheStats(biomeCache.size(), CACHE_SIZE);
    }
    
    private static long packCoords(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) |
               (((long) y & 0x1FFFFF) << 21) |
               (((long) z & 0x1FFFFF) << 42);
    }
    
    // Enums and data classes
    private enum BiomeLayer {
        DEEP_UNDERGROUND,
        UNDERGROUND,
        SURFACE,
        ELEVATED,
        MOUNTAIN,
        SKY
    }
    
    public static class BiomeCacheStats {
        public final int currentSize;
        public final int maxSize;
        
        public BiomeCacheStats(int currentSize, int maxSize) {
            this.currentSize = currentSize;
            this.maxSize = maxSize;
        }
        
        public double getCacheUsage() {
            return (double) currentSize / maxSize;
        }
    }
} 