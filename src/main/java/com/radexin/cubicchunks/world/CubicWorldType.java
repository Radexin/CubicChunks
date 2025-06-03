package com.radexin.cubicchunks.world;

import com.radexin.cubicchunks.CubicChunks;
import com.radexin.cubicchunks.gen.CubicWorldGenerator;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.storage.WorldData;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.radexin.cubicchunks.chunk.CubicChunkManager;

/**
 * Integrates cubic chunks with Minecraft's world type system.
 * Provides utilities for creating cubic chunk worlds and detecting if a world uses cubic chunks.
 */
public class CubicWorldType {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceLocation CUBIC_WORLD_TYPE_ID = ResourceLocation.fromNamespaceAndPath(CubicChunks.MODID, "cubic");
    
    /**
     * Checks if a level uses cubic chunks.
     */
    public static boolean isCubicWorld(Level level) {
        if (level instanceof ServerLevel serverLevel) {
            // Check if the level has cubic chunks data
            Registry<Biome> biomeRegistry = serverLevel.registryAccess().registry(Registries.BIOME).get();
            CubicWorldGenerator generator = new CubicWorldGenerator(serverLevel, biomeRegistry, null);
            return serverLevel.getDataStorage().get(
                CubicChunksSavedData.factory(generator),
                "cubicchunks"
            ) != null;
        }
        return false;
    }
    
    /**
     * Checks if a world data uses cubic chunks.
     */
    public static boolean isCubicWorld(WorldData worldData) {
        // For now, we'll use a simpler check
        // In a full implementation, this would check the world generation settings
        return false; // Placeholder - would need proper implementation
    }
    
    /**
     * Checks if a dimension uses cubic chunks.
     */
    public static boolean isCubicDimension(LevelStem levelStem) {
        ChunkGenerator generator = levelStem.generator();
        // Check if the chunk generator is our cubic chunk generator
        return generator.getClass().equals(CubicWorldGenerator.class);
    }
    
    /**
     * Creates a cubic chunk generator for a dimension.
     */
    public static CubicWorldGenerator createCubicGenerator(Registry<Biome> biomeRegistry) {
        return new CubicWorldGenerator(null, biomeRegistry, null);
    }
    
    /**
     * Creates a cubic chunk generator with a specific biome source.
     */
    public static CubicWorldGenerator createCubicGenerator(BiomeSource biomeSource) {
        return new CubicWorldGenerator(null, null, biomeSource);
    }
    
    /**
     * Converts a regular world to use cubic chunks.
     * This is a complex operation that should be done carefully.
     */
    public static boolean convertToCubicWorld(ServerLevel level) {
        // This is a placeholder for world conversion functionality
        // In a full implementation, this would:
        // 1. Create cubic chunks data
        // 2. Convert existing chunks to cubic format
        // 3. Update the world's chunk generator
        // 4. Handle entity and block entity migration
        
        try {
            // Create cubic chunks saved data
            Registry<Biome> biomeRegistry = level.registryAccess().registry(Registries.BIOME).get();
            CubicWorldGenerator generator = new CubicWorldGenerator(level, biomeRegistry, null);
            CubicChunksSavedData savedData = CubicChunksSavedData.getOrCreate(level.getServer(), generator);
            
            // Mark as dirty to ensure it gets saved
            savedData.setDirty();
            
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to convert world to cubic chunks: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Gets the cubic chunks manager for a level, if it exists.
     */
    public static CubicChunkManager getCubicChunkManager(Level level) {
        // For now, return null - this would need proper implementation
        // In a full implementation, this would create a manager with proper storage
        return null;
    }
    
    /**
     * Initializes cubic chunks for a newly created world.
     */
    public static void initializeCubicWorld(ServerLevel level) {
        if (!isCubicWorld(level)) {
            Registry<Biome> biomeRegistry = level.registryAccess().registry(Registries.BIOME).get();
            CubicWorldGenerator generator = new CubicWorldGenerator(level, biomeRegistry, null);
            CubicChunksSavedData savedData = CubicChunksSavedData.getOrCreate(level.getServer(), generator);
            savedData.setDirty();
            
            LOGGER.info("Initialized cubic chunks for world: {}", level.dimension().location());
        }
    }
} 