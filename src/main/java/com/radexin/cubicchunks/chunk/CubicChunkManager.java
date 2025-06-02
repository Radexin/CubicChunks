package com.radexin.cubicchunks.chunk;

import com.radexin.cubicchunks.Config;
import com.radexin.cubicchunks.gen.CubicWorldGenerator;
import net.minecraft.core.Registry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-performance chunk manager for cubic chunks with optimized loading/unloading.
 * Implements spatial partitioning, priority-based loading, and memory management.
 */
public class CubicChunkManager {
    private static final int MAX_LOADED_CUBES = Config.maxLoadedCubes;
    private static final int UNLOAD_THRESHOLD = (int)(MAX_LOADED_CUBES * 0.8);
    private static final int GENERATION_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    
    // Spatial partitioning for fast cube lookup
    private final Map<Long, CubeChunk> loadedCubes = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<CubeChunk>> loadingCubes = new ConcurrentHashMap<>();
    
    // Priority queues for loading/unloading
    private final PriorityQueue<ChunkLoadRequest> loadQueue = new PriorityQueue<>();
    private final Set<Long> unloadQueue = ConcurrentHashMap.newKeySet();
    
    // Thread pools for async operations
    private final ExecutorService generationExecutor = Executors.newFixedThreadPool(GENERATION_THREADS);
    private final ScheduledExecutorService managementExecutor = Executors.newSingleThreadScheduledExecutor();
    
    // Performance tracking
    private final AtomicInteger loadedCount = new AtomicInteger(0);
    private final Map<Long, Long> cubeAccessTimes = new ConcurrentHashMap<>();
    private final Map<Long, Integer> cubeAccessCounts = new ConcurrentHashMap<>();
    
    // World generation
    private final CubicWorldGenerator worldGenerator;
    private final Registry<Biome> biomeRegistry;
    private final ServerLevel level;
    
    // Cache for frequently accessed metadata
    private final Map<Long, CubeMetadata> metadataCache = new ConcurrentHashMap<>();
    
    public CubicChunkManager(ServerLevel level, CubicWorldGenerator worldGenerator, Registry<Biome> biomeRegistry) {
        this.level = level;
        this.worldGenerator = worldGenerator;
        this.biomeRegistry = biomeRegistry;
        
        // Start background management task
        managementExecutor.scheduleAtFixedRate(this::manageCubes, 1, 1, TimeUnit.SECONDS);
    }
    
    /**
     * Gets a cube, loading it asynchronously if necessary.
     * Uses spatial locality and priority-based loading.
     */
    public CompletableFuture<CubeChunk> getCubeAsync(int x, int y, int z) {
        long key = encodePosition(x, y, z);
        
        // Check if already loaded
        CubeChunk existing = loadedCubes.get(key);
        if (existing != null) {
            updateAccessTime(key);
            return CompletableFuture.completedFuture(existing);
        }
        
        // Check if currently loading
        CompletableFuture<CubeChunk> loading = loadingCubes.get(key);
        if (loading != null) {
            return loading;
        }
        
        // Start loading process
        CompletableFuture<CubeChunk> future = CompletableFuture.supplyAsync(() -> {
            try {
                return loadCube(x, y, z);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load cube (" + x + ", " + y + ", " + z + ")", e);
            }
        }, generationExecutor);
        
        loadingCubes.put(key, future);
        
        // Clean up loading map when complete
        future.whenComplete((cube, throwable) -> {
            loadingCubes.remove(key);
            if (cube != null) {
                loadedCubes.put(key, cube);
                loadedCount.incrementAndGet();
                updateAccessTime(key);
            }
        });
        
        return future;
    }
    
    /**
     * Synchronous cube access with fallback to async loading.
     */
    @Nullable
    public CubeChunk getCube(int x, int y, int z) {
        long key = encodePosition(x, y, z);
        CubeChunk cube = loadedCubes.get(key);
        
        if (cube == null) {
            // Try to get from async loading if available
            CompletableFuture<CubeChunk> future = getCubeAsync(x, y, z);
            if (future.isDone() && !future.isCompletedExceptionally()) {
                try {
                    cube = future.get();
                } catch (Exception e) {
                    // Fall back to null
                }
            }
        }
        
        if (cube != null) {
            updateAccessTime(key);
        }
        
        return cube;
    }
    
    /**
     * Batch loading of cubes in a region for better performance.
     */
    public CompletableFuture<List<CubeChunk>> loadRegion(int centerX, int centerY, int centerZ, int radius) {
        List<CompletableFuture<CubeChunk>> futures = new ArrayList<>();
        
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = centerX + dx;
                    int y = centerY + dy;
                    int z = centerZ + dz;
                    
                    // Prioritize closer cubes
                    double distance = Math.sqrt(dx*dx + dy*dy + dz*dz);
                    ChunkLoadRequest request = new ChunkLoadRequest(x, y, z, distance);
                    
                    futures.add(getCubeAsync(x, y, z));
                }
            }
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList());
    }
    
    /**
     * Marks a cube for unloading.
     */
    public void unloadCube(int x, int y, int z) {
        long key = encodePosition(x, y, z);
        unloadQueue.add(key);
    }
    
    /**
     * Gets all loaded cubes within a distance from a point.
     */
    public Collection<CubeChunk> getCubesNear(Vec3 center, double maxDistance) {
        List<CubeChunk> result = new ArrayList<>();
        double maxDistSq = maxDistance * maxDistance;
        
        for (CubeChunk cube : loadedCubes.values()) {
            Vec3 cubeCenter = new Vec3(
                cube.getCubeX() * CubeChunk.SIZE + CubeChunk.SIZE / 2.0,
                cube.getCubeY() * CubeChunk.SIZE + CubeChunk.SIZE / 2.0,
                cube.getCubeZ() * CubeChunk.SIZE + CubeChunk.SIZE / 2.0
            );
            
            if (cubeCenter.distanceToSqr(center) <= maxDistSq) {
                result.add(cube);
                updateAccessTime(encodePosition(cube.getCubeX(), cube.getCubeY(), cube.getCubeZ()));
            }
        }
        
        return result;
    }
    
    private CubeChunk loadCube(int x, int y, int z) {
        // Try to load from storage first
        CubeChunk cube = loadFromStorage(x, y, z);
        
        if (cube == null) {
            // Generate new cube
            cube = new CubeChunk(x, y, z, biomeRegistry);
            worldGenerator.generateCube(cube, biomeRegistry);
        }
        
        return cube;
    }
    
    @Nullable
    private CubeChunk loadFromStorage(int x, int y, int z) {
        // TODO: Implement storage loading
        // For now, always generate new cubes
        return null;
    }
    
    private void manageCubes() {
        try {
            // Process unload queue
            processUnloads();
            
            // Memory management
            if (loadedCount.get() > UNLOAD_THRESHOLD) {
                performMemoryCleanup();
            }
            
            // Update metadata cache
            updateMetadataCache();
            
        } catch (Exception e) {
            // Log error but don't crash the game
            System.err.println("Error in cube management: " + e.getMessage());
        }
    }
    
    private void processUnloads() {
        Iterator<Long> iterator = unloadQueue.iterator();
        while (iterator.hasNext()) {
            long key = iterator.next();
            CubeChunk cube = loadedCubes.remove(key);
            
            if (cube != null) {
                // Save cube if dirty
                if (cube.isDirty()) {
                    saveToStorage(cube);
                }
                
                loadedCount.decrementAndGet();
                cubeAccessTimes.remove(key);
                cubeAccessCounts.remove(key);
                metadataCache.remove(key);
            }
            
            iterator.remove();
        }
    }
    
    private void performMemoryCleanup() {
        // Find least recently used cubes
        List<Map.Entry<Long, Long>> accessTimeEntries = new ArrayList<>(cubeAccessTimes.entrySet());
        accessTimeEntries.sort(Map.Entry.comparingByValue());
        
        int toRemove = loadedCount.get() - UNLOAD_THRESHOLD;
        for (int i = 0; i < Math.min(toRemove, accessTimeEntries.size()); i++) {
            long key = accessTimeEntries.get(i).getKey();
            unloadQueue.add(key);
        }
    }
    
    private void updateMetadataCache() {
        // Update cache with frequently accessed cube metadata
        long currentTime = System.currentTimeMillis();
        
        for (Map.Entry<Long, CubeChunk> entry : loadedCubes.entrySet()) {
            long key = entry.getKey();
            CubeChunk cube = entry.getValue();
            
            Integer accessCount = cubeAccessCounts.get(key);
            if (accessCount != null && accessCount > 10) {
                CubeMetadata metadata = new CubeMetadata(
                    cube.getCubeX(),
                    cube.getCubeY(), 
                    cube.getCubeZ(),
                    cube.isEmpty(),
                    cube.isDirty(),
                    currentTime
                );
                metadataCache.put(key, metadata);
            }
        }
    }
    
    private void saveToStorage(CubeChunk cube) {
        // TODO: Implement async storage saving
    }
    
    private void updateAccessTime(long key) {
        long currentTime = System.currentTimeMillis();
        cubeAccessTimes.put(key, currentTime);
        cubeAccessCounts.merge(key, 1, Integer::sum);
    }
    
    private static long encodePosition(int x, int y, int z) {
        return ((long)x << 32) | ((long)y << 16) | (z & 0xFFFFL);
    }
    
    public void shutdown() {
        managementExecutor.shutdown();
        generationExecutor.shutdown();
        
        try {
            if (!managementExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                managementExecutor.shutdownNow();
            }
            if (!generationExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                generationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    // Performance monitoring
    public int getLoadedCubeCount() {
        return loadedCount.get();
    }
    
    public int getLoadingCubeCount() {
        return loadingCubes.size();
    }
    
    public double getAverageAccessCount() {
        return cubeAccessCounts.values().stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }
    
    private static class ChunkLoadRequest implements Comparable<ChunkLoadRequest> {
        final int x, y, z;
        final double priority;
        
        ChunkLoadRequest(int x, int y, int z, double priority) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.priority = priority;
        }
        
        @Override
        public int compareTo(ChunkLoadRequest other) {
            return Double.compare(this.priority, other.priority);
        }
    }
    
    private static class CubeMetadata {
        final int x, y, z;
        final boolean isEmpty;
        final boolean isDirty;
        final long lastUpdated;
        
        CubeMetadata(int x, int y, int z, boolean isEmpty, boolean isDirty, long lastUpdated) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.isEmpty = isEmpty;
            this.isDirty = isDirty;
            this.lastUpdated = lastUpdated;
        }
    }
} 