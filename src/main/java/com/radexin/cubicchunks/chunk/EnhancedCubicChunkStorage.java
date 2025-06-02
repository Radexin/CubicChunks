package com.radexin.cubicchunks.chunk;

import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Enhanced cubic chunk storage system with optimized I/O, caching, and compression.
 * Merges the best features of multiple storage approaches with performance improvements.
 */
public class EnhancedCubicChunkStorage {
    // Storage configuration
    private static final int REGION_SIZE = 32; // 32x32x32 cubes per region
    private static final int CACHE_SIZE = 1024;
    private static final int WRITE_BUFFER_SIZE = 64 * 1024; // 64KB buffer
    private static final int COMPRESSION_THRESHOLD = 1024; // Compress chunks larger than 1KB
    
    private final File worldDir;
    private final Registry<Biome> biomeRegistry;
    
    // Region file management
    private final Map<Long, EnhancedRegionFile> regionFiles = new ConcurrentHashMap<>();
    private final Map<Long, CompoundTag> chunkCache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<DirtyChunk> saveQueue = new ConcurrentLinkedQueue<>();
    
    // Async I/O
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService compressionExecutor = Executors.newFixedThreadPool(1);
    
    // Performance metrics
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong saveOperations = new AtomicLong(0);
    private final AtomicLong loadOperations = new AtomicLong(0);
    
    public EnhancedCubicChunkStorage(File worldDir, Registry<Biome> biomeRegistry) {
        this.worldDir = worldDir;
        this.biomeRegistry = biomeRegistry;
        
        // Ensure region directory exists
        File regionDir = new File(worldDir, "cubic_regions");
        if (!regionDir.exists()) {
            regionDir.mkdirs();
        }
    }
    
    /**
     * Asynchronously saves a cube to storage.
     */
    public CompletableFuture<Void> saveCubeAsync(CubeChunk cube) {
        return CompletableFuture.runAsync(() -> {
            try {
                saveCube(cube);
            } catch (IOException e) {
                throw new RuntimeException("Failed to save cube", e);
            }
        }, ioExecutor);
    }
    
    /**
     * Synchronously saves a cube to storage.
     */
    public void saveCube(CubeChunk cube) throws IOException {
        long key = packCubeCoords(cube.getCubeX(), cube.getCubeY(), cube.getCubeZ());
        CompoundTag cubeData = cube.toNBT();
        
        // Update cache
        chunkCache.put(key, cubeData);
        
        // Queue for async disk write
        saveQueue.offer(new DirtyChunk(cube.getCubeX(), cube.getCubeY(), cube.getCubeZ(), cubeData));
        saveOperations.incrementAndGet();
    }
    
    /**
     * Asynchronously loads a cube from storage.
     */
    public CompletableFuture<CubeChunk> loadCubeAsync(int cubeX, int cubeY, int cubeZ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return loadCube(cubeX, cubeY, cubeZ);
            } catch (IOException e) {
                return null;
            }
        }, ioExecutor);
    }
    
    /**
     * Synchronously loads a cube from storage.
     */
    @Nullable
    public CubeChunk loadCube(int cubeX, int cubeY, int cubeZ) throws IOException {
        long key = packCubeCoords(cubeX, cubeY, cubeZ);
        loadOperations.incrementAndGet();
        
        // Check cache first
        CompoundTag cached = chunkCache.get(key);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return CubeChunk.fromNBT(cached, biomeRegistry);
        }
        
        cacheMisses.incrementAndGet();
        
        // Load from disk
        EnhancedRegionFile regionFile = getRegionFile(cubeX, cubeY, cubeZ);
        CompoundTag cubeData = regionFile.loadCubeData(cubeX, cubeY, cubeZ);
        
        if (cubeData != null) {
            // Cache the loaded data
            if (chunkCache.size() < CACHE_SIZE) {
                chunkCache.put(key, cubeData);
            }
            
            return CubeChunk.fromNBT(cubeData, biomeRegistry);
        }
        
        return null;
    }
    
    /**
     * Checks if a cube exists in storage.
     */
    public boolean cubeExists(int cubeX, int cubeY, int cubeZ) {
        long key = packCubeCoords(cubeX, cubeY, cubeZ);
        
        // Check cache
        if (chunkCache.containsKey(key)) {
            return true;
        }
        
        // Check region file
        EnhancedRegionFile regionFile = getRegionFile(cubeX, cubeY, cubeZ);
        return regionFile.cubeExists(cubeX, cubeY, cubeZ);
    }
    
    /**
     * Processes pending saves and flushes data to disk.
     */
    public void flush() {
        // Process save queue
        while (!saveQueue.isEmpty()) {
            DirtyChunk dirty = saveQueue.poll();
            if (dirty != null) {
                try {
                    EnhancedRegionFile regionFile = getRegionFile(dirty.x, dirty.y, dirty.z);
                    regionFile.saveCubeData(dirty.x, dirty.y, dirty.z, dirty.data);
                } catch (IOException e) {
                    // Re-queue for retry
                    saveQueue.offer(dirty);
                    break;
                }
            }
        }
        
        // Flush all region files
        for (EnhancedRegionFile regionFile : regionFiles.values()) {
            regionFile.flush();
        }
    }
    
    /**
     * Saves all pending data and closes the storage system.
     */
    public void saveAll() {
        flush();
        
        for (EnhancedRegionFile regionFile : regionFiles.values()) {
            regionFile.close();
        }
        
        regionFiles.clear();
        chunkCache.clear();
        
        ioExecutor.shutdown();
        compressionExecutor.shutdown();
    }
    
    /**
     * Gets performance statistics.
     */
    public StorageStats getStats() {
        return new StorageStats(
            cacheHits.get(),
            cacheMisses.get(),
            saveOperations.get(),
            loadOperations.get(),
            chunkCache.size(),
            regionFiles.size(),
            saveQueue.size()
        );
    }
    
    /**
     * Clears the cache and optimizes memory usage.
     */
    public void optimizeMemory() {
        // Clear cache if it's getting too large
        if (chunkCache.size() > CACHE_SIZE * 1.2) {
            chunkCache.clear();
        }
        
        // Close unused region files
        regionFiles.entrySet().removeIf(entry -> {
            EnhancedRegionFile regionFile = entry.getValue();
            if (!regionFile.isActive()) {
                regionFile.close();
                return true;
            }
            return false;
        });
    }
    
    private EnhancedRegionFile getRegionFile(int cubeX, int cubeY, int cubeZ) {
        int regionX = Math.floorDiv(cubeX, REGION_SIZE);
        int regionY = Math.floorDiv(cubeY, REGION_SIZE);
        int regionZ = Math.floorDiv(cubeZ, REGION_SIZE);
        
        long regionKey = packRegionCoords(regionX, regionY, regionZ);
        
        return regionFiles.computeIfAbsent(regionKey, k -> {
            File regionFile = new File(worldDir, "cubic_regions" + File.separator + 
                "r." + regionX + "." + regionY + "." + regionZ + ".ccr");
            return new EnhancedRegionFile(regionFile);
        });
    }
    
    private static long packCubeCoords(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) |
               (((long) y & 0x1FFFFF) << 21) |
               (((long) z & 0x1FFFFF) << 42);
    }
    
    private static long packRegionCoords(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) |
               (((long) y & 0x1FFFFF) << 21) |
               (((long) z & 0x1FFFFF) << 42);
    }
    
    /**
     * Enhanced region file with compression and optimized I/O.
     */
    private static class EnhancedRegionFile {
        private final File file;
        private final Map<Long, CompoundTag> pendingWrites = new ConcurrentHashMap<>();
        private RandomAccessFile randomAccessFile;
        private long lastAccessTime;
        
        public EnhancedRegionFile(File file) {
            this.file = file;
            this.lastAccessTime = System.currentTimeMillis();
            
            try {
                if (!file.exists()) {
                    file.getParentFile().mkdirs();
                    file.createNewFile();
                }
                this.randomAccessFile = new RandomAccessFile(file, "rw");
            } catch (IOException e) {
                throw new RuntimeException("Failed to open region file: " + file, e);
            }
        }
        
        public synchronized void saveCubeData(int cubeX, int cubeY, int cubeZ, CompoundTag data) throws IOException {
            this.lastAccessTime = System.currentTimeMillis();
            
            // Serialize and compress data
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            
            try (DataOutputStream dos = new DataOutputStream(
                    data.sizeInBytes() > COMPRESSION_THRESHOLD ? 
                    new GZIPOutputStream(baos) : baos)) {
                NbtIo.write(data, dos);
            }
            
            byte[] serializedData = baos.toByteArray();
            
            // Calculate position in file
            long position = calculatePosition(cubeX, cubeY, cubeZ);
            
            // Write to file
            randomAccessFile.seek(position);
            randomAccessFile.writeInt(serializedData.length);
            randomAccessFile.writeBoolean(data.sizeInBytes() > COMPRESSION_THRESHOLD);
            randomAccessFile.write(serializedData);
        }
        
        @Nullable
        public synchronized CompoundTag loadCubeData(int cubeX, int cubeY, int cubeZ) throws IOException {
            this.lastAccessTime = System.currentTimeMillis();
            
            long position = calculatePosition(cubeX, cubeY, cubeZ);
            
            if (position >= randomAccessFile.length()) {
                return null;
            }
            
            randomAccessFile.seek(position);
            
            try {
                int dataLength = randomAccessFile.readInt();
                boolean isCompressed = randomAccessFile.readBoolean();
                
                if (dataLength <= 0 || dataLength > 1024 * 1024) { // Max 1MB
                    return null;
                }
                
                byte[] data = new byte[dataLength];
                randomAccessFile.readFully(data);
                
                // Decompress and deserialize
                try (DataInputStream dis = new DataInputStream(
                        isCompressed ? 
                        new GZIPInputStream(new ByteArrayInputStream(data)) :
                        new ByteArrayInputStream(data))) {
                    return NbtIo.read(dis);
                }
                
            } catch (IOException e) {
                return null; // Corrupted data
            }
        }
        
        public boolean cubeExists(int cubeX, int cubeY, int cubeZ) {
            try {
                long position = calculatePosition(cubeX, cubeY, cubeZ);
                return position < randomAccessFile.length();
            } catch (IOException e) {
                return false;
            }
        }
        
        public void flush() {
            try {
                if (randomAccessFile != null) {
                    randomAccessFile.getFD().sync();
                }
            } catch (IOException e) {
                // Log error but continue
            }
        }
        
        public void close() {
            try {
                if (randomAccessFile != null) {
                    randomAccessFile.close();
                    randomAccessFile = null;
                }
            } catch (IOException e) {
                // Log error but continue
            }
        }
        
        public boolean isActive() {
            return System.currentTimeMillis() - lastAccessTime < 300000; // 5 minutes
        }
        
        private long calculatePosition(int cubeX, int cubeY, int cubeZ) {
            // Convert to local coordinates within region
            int localX = cubeX & (REGION_SIZE - 1);
            int localY = cubeY & (REGION_SIZE - 1);
            int localZ = cubeZ & (REGION_SIZE - 1);
            
            // Calculate linear index
            int index = localX + localY * REGION_SIZE + localZ * REGION_SIZE * REGION_SIZE;
            
            // Each entry has a fixed size header + variable data
            return index * 8L + REGION_SIZE * REGION_SIZE * REGION_SIZE * 8L;
        }
    }
    
    private static class DirtyChunk {
        final int x, y, z;
        final CompoundTag data;
        
        DirtyChunk(int x, int y, int z, CompoundTag data) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.data = data;
        }
    }
    
    public static class StorageStats {
        public final long cacheHits;
        public final long cacheMisses;
        public final long saveOperations;
        public final long loadOperations;
        public final int cacheSize;
        public final int openRegions;
        public final int pendingSaves;
        
        public StorageStats(long cacheHits, long cacheMisses, long saveOperations, 
                          long loadOperations, int cacheSize, int openRegions, int pendingSaves) {
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.saveOperations = saveOperations;
            this.loadOperations = loadOperations;
            this.cacheSize = cacheSize;
            this.openRegions = openRegions;
            this.pendingSaves = pendingSaves;
        }
        
        public double getCacheHitRate() {
            long total = cacheHits + cacheMisses;
            return total > 0 ? (double) cacheHits / total : 0.0;
        }
    }
} 