package com.radexin.cubicchunks.world;

import com.radexin.cubicchunks.world.CubicChunkManager.CubePos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles storage and retrieval of cubic chunk data.
 * Uses a modified file format optimized for 3D chunk coordinates.
 */
public class CubicChunkStorage {
    private final File cubicChunksDir;
    private final ConcurrentHashMap<String, CompoundTag> cache = new ConcurrentHashMap<>();
    private final int cacheSize = 256;
    
    public CubicChunkStorage(LevelStorageSource.LevelStorageAccess levelStorage) {
        File worldDir = levelStorage.getLevelPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();
        this.cubicChunksDir = new File(worldDir, "cubic_chunks");
        
        // Create directory if it doesn't exist
        if (!cubicChunksDir.exists()) {
            cubicChunksDir.mkdirs();
        }
    }
    
    /**
     * Loads chunk data from storage for the given cube position.
     */
    @Nullable
    public CompoundTag loadChunk(CubePos pos) {
        String cacheKey = getCacheKey(pos);
        
        // Check cache first
        CompoundTag cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        
        // Load from file
        File chunkFile = getChunkFile(pos);
        if (!chunkFile.exists()) {
            return null;
        }
        
        try {
            CompoundTag data = NbtIo.read(chunkFile.toPath());
            
            // Cache the data
            if (cache.size() < cacheSize) {
                cache.put(cacheKey, data);
            }
            
            return data;
        } catch (IOException e) {
            System.err.println("Failed to load cubic chunk at " + pos + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Saves chunk data to storage for the given cube position.
     */
    public void saveChunk(CubePos pos, CompoundTag data) {
        String cacheKey = getCacheKey(pos);
        
        // Update cache
        cache.put(cacheKey, data);
        
        // Save to file asynchronously
        File chunkFile = getChunkFile(pos);
        
        // Ensure parent directory exists
        File parentDir = chunkFile.getParentFile();
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }
        
        try {
            NbtIo.write(data, chunkFile.toPath());
        } catch (IOException e) {
            System.err.println("Failed to save cubic chunk at " + pos + ": " + e.getMessage());
        }
    }
    
    /**
     * Checks if chunk data exists for the given position.
     */
    public boolean hasChunk(CubePos pos) {
        String cacheKey = getCacheKey(pos);
        if (cache.containsKey(cacheKey)) {
            return true;
        }
        
        return getChunkFile(pos).exists();
    }
    
    /**
     * Deletes chunk data for the given position.
     */
    public void deleteChunk(CubePos pos) {
        String cacheKey = getCacheKey(pos);
        cache.remove(cacheKey);
        
        File chunkFile = getChunkFile(pos);
        if (chunkFile.exists()) {
            chunkFile.delete();
        }
    }
    
    /**
     * Flushes all cached data to disk.
     */
    public void flush() {
        // All saves are immediate in this implementation
        // In a production system, you might implement batched writes here
    }
    
    /**
     * Clears the cache.
     */
    public void clearCache() {
        cache.clear();
    }
    
    private File getChunkFile(CubePos pos) {
        // Organize chunks into region-like subdirectories for better file system performance
        // Use the format: cubic_chunks/region_X_Z/cube_X_Y_Z.nbt
        int regionX = Math.floorDiv(pos.x, 32);
        int regionZ = Math.floorDiv(pos.z, 32);
        
        File regionDir = new File(cubicChunksDir, String.format("region_%d_%d", regionX, regionZ));
        return new File(regionDir, String.format("cube_%d_%d_%d.nbt", pos.x, pos.y, pos.z));
    }
    
    private String getCacheKey(CubePos pos) {
        return String.format("%d_%d_%d", pos.x, pos.y, pos.z);
    }
    
    /**
     * Gets statistics about the storage system.
     */
    public StorageStats getStats() {
        return new StorageStats(cache.size(), countStoredChunks());
    }
    
    private int countStoredChunks() {
        int count = 0;
        if (cubicChunksDir.exists()) {
            File[] regionDirs = cubicChunksDir.listFiles();
            if (regionDirs != null) {
                for (File regionDir : regionDirs) {
                    if (regionDir.isDirectory()) {
                        File[] chunkFiles = regionDir.listFiles((dir, name) -> name.endsWith(".nbt"));
                        if (chunkFiles != null) {
                            count += chunkFiles.length;
                        }
                    }
                }
            }
        }
        return count;
    }
    
    /**
     * Statistics about the storage system.
     */
    public static class StorageStats {
        public final int cachedChunks;
        public final int storedChunks;
        
        public StorageStats(int cachedChunks, int storedChunks) {
            this.cachedChunks = cachedChunks;
            this.storedChunks = storedChunks;
        }
        
        @Override
        public String toString() {
            return String.format("StorageStats[cached=%d, stored=%d]", cachedChunks, storedChunks);
        }
    }
} 