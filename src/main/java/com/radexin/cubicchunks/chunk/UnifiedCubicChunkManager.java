package com.radexin.cubicchunks.chunk;

import com.radexin.cubicchunks.Config;
import com.radexin.cubicchunks.gen.CubicWorldGenerator;
import com.radexin.cubicchunks.gen.CubeChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unified high-performance chunk manager for cubic chunks.
 * Combines advanced spatial partitioning, priority-based loading, player tracking,
 * and optimized memory management from multiple implementations.
 */
public class UnifiedCubicChunkManager {
    // Configuration constants
    private static final int MAX_LOADED_CUBES = Config.maxLoadedCubes;
    private static final int UNLOAD_THRESHOLD = (int)(MAX_LOADED_CUBES * 0.8);
    private static final int GENERATION_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private static final int MAX_QUEUE_SIZE = 1000;
    
    // Core components
    private final ServerLevel level;
    private final CubicWorldGenerator worldGenerator;
    private final CubeChunkGenerator cubeGenerator;
    private final UnifiedCubicChunkStorage storage;
    private final Registry<Biome> biomeRegistry;
    
    // Spatial data structures
    private final Map<CubePos, CubeChunk> loadedCubes = new ConcurrentHashMap<>();
    private final Map<ChunkPos, CubeColumn> loadedColumns = new ConcurrentHashMap<>();
    private final Map<CubePos, CompletableFuture<CubeChunk>> loadingCubes = new ConcurrentHashMap<>();
    
    // Loading and unloading queues
    private final PriorityQueue<ChunkLoadRequest> loadQueue = new PriorityQueue<>();
    private final Set<CubePos> unloadQueue = ConcurrentHashMap.newKeySet();
    private final Set<CubePos> generatingChunks = ConcurrentHashMap.newKeySet();
    
    // Player tracking for intelligent loading
    private final Map<UUID, PlayerChunkTracker> playerTrackers = new ConcurrentHashMap<>();
    
    // Thread pools
    private final ExecutorService generationExecutor = Executors.newFixedThreadPool(GENERATION_THREADS);
    private final ScheduledExecutorService managementExecutor = Executors.newSingleThreadScheduledExecutor();
    
    // Performance tracking and caching
    private final AtomicInteger loadedCount = new AtomicInteger(0);
    private final Map<CubePos, Long> cubeAccessTimes = new ConcurrentHashMap<>();
    private final Map<CubePos, Integer> cubeAccessCounts = new ConcurrentHashMap<>();
    private final Map<CubePos, CubeMetadata> metadataCache = new ConcurrentHashMap<>();
    
    // Configuration
    private int horizontalRenderDistance = 8;
    private int verticalRenderDistance = 4;
    
    public UnifiedCubicChunkManager(ServerLevel level, CubicWorldGenerator worldGenerator, 
                                  CubeChunkGenerator cubeGenerator, UnifiedCubicChunkStorage storage, 
                                  Registry<Biome> biomeRegistry) {
        this.level = level;
        this.worldGenerator = worldGenerator;
        this.cubeGenerator = cubeGenerator;
        this.storage = storage;
        this.biomeRegistry = biomeRegistry;
        
        // Start background management
        managementExecutor.scheduleAtFixedRate(this::manageCubes, 1, 1, TimeUnit.SECONDS);
    }
    
    /**
     * Gets a cube asynchronously with spatial locality optimization.
     */
    public CompletableFuture<CubeChunk> getCubeAsync(int x, int y, int z) {
        CubePos pos = new CubePos(x, y, z);
        
        // Check if already loaded
        CubeChunk existing = loadedCubes.get(pos);
        if (existing != null) {
            updateAccessTime(pos);
            return CompletableFuture.completedFuture(existing);
        }
        
        // Check if currently loading
        CompletableFuture<CubeChunk> loading = loadingCubes.get(pos);
        if (loading != null) {
            return loading;
        }
        
        // Start loading process
        CompletableFuture<CubeChunk> future = CompletableFuture.supplyAsync(() -> {
            try {
                return loadCube(pos);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load cube " + pos, e);
            }
        }, generationExecutor);
        
        loadingCubes.put(pos, future);
        
        // Clean up loading map when complete
        future.whenComplete((cube, throwable) -> {
            loadingCubes.remove(pos);
            if (cube != null) {
                loadedCubes.put(pos, cube);
                loadedCount.incrementAndGet();
                updateAccessTime(pos);
                addToColumn(pos, cube);
            }
        });
        
        return future;
    }
    
    /**
     * Gets a cube synchronously with fallback to async loading.
     */
    @Nullable
    public CubeChunk getCube(int x, int y, int z, boolean load) {
        CubePos pos = new CubePos(x, y, z);
        CubeChunk cube = loadedCubes.get(pos);
        
        if (cube == null && load) {
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
            updateAccessTime(pos);
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
                    ChunkLoadRequest request = new ChunkLoadRequest(new CubePos(x, y, z), distance, null);
                    
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
     * Updates chunk loading for a player with intelligent priority management.
     */
    public void updatePlayerChunks(ServerPlayer player) {
        UUID playerId = player.getUUID();
        PlayerChunkTracker tracker = playerTrackers.computeIfAbsent(playerId, 
            id -> new PlayerChunkTracker(player));
        
        BlockPos playerPos = player.blockPosition();
        int playerCubeX = Math.floorDiv(playerPos.getX(), CubeChunk.SIZE);
        int playerCubeY = Math.floorDiv(playerPos.getY(), CubeChunk.SIZE);
        int playerCubeZ = Math.floorDiv(playerPos.getZ(), CubeChunk.SIZE);
        
        tracker.updatePosition(playerCubeX, playerCubeY, playerCubeZ);
        
        // Queue chunks for loading with distance-based priority
        Set<CubePos> chunksInRange = getChunksInRange(playerCubeX, playerCubeY, playerCubeZ);
        
        for (CubePos pos : chunksInRange) {
            if (!loadedCubes.containsKey(pos) && !generatingChunks.contains(pos)) {
                double distance = calculateDistance(playerCubeX, playerCubeY, playerCubeZ, pos.x, pos.y, pos.z);
                loadQueue.offer(new ChunkLoadRequest(pos, distance, playerId));
            }
        }
        
        // Mark distant chunks for unloading
        tracker.unloadFarChunks(this);
    }
    
    /**
     * Unloads a cube and saves it if dirty.
     */
    public void unloadCube(CubePos pos) {
        CubeChunk chunk = loadedCubes.remove(pos);
        if (chunk == null) return;
        
        // Save if dirty
        if (chunk.isDirty()) {
            storage.saveCubeAsync(chunk);
            chunk.setDirty(false);
        }
        
        // Remove from column
        removeFromColumn(pos);
        
        // Update counters
        loadedCount.decrementAndGet();
        cubeAccessTimes.remove(pos);
        cubeAccessCounts.remove(pos);
        metadataCache.remove(pos);
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
                updateAccessTime(new CubePos(cube.getCubeX(), cube.getCubeY(), cube.getCubeZ()));
            }
        }
        
        return result;
    }
    
    /**
     * Processes the chunk loading queue with intelligent batching.
     */
    public void tick() {
        int processed = 0;
        while (!loadQueue.isEmpty() && processed < 4) {
            ChunkLoadRequest request = loadQueue.poll();
            if (request != null) {
                getCubeAsync(request.pos.x, request.pos.y, request.pos.z);
                processed++;
            }
        }
        
        // Process unload queue
        processUnloads();
    }
    
    /**
     * Removes a player from tracking.
     */
    public void removePlayer(ServerPlayer player) {
        playerTrackers.remove(player.getUUID());
    }
    
    private CubeChunk loadCube(CubePos pos) {
        // Try to load from storage first
        CubeChunk cube = storage.loadCube(pos.x, pos.y, pos.z);
        
        if (cube == null) {
            // Generate new cube
            cube = new CubeChunk(pos.x, pos.y, pos.z, biomeRegistry);
            
            // Use appropriate generator
            if (worldGenerator != null) {
                worldGenerator.generateCube(cube, biomeRegistry);
            } else if (cubeGenerator != null) {
                cubeGenerator.generateCube(cube);
            }
        }
        
        return cube;
    }
    
    private void manageCubes() {
        // Memory management
        if (loadedCount.get() > UNLOAD_THRESHOLD) {
            performMemoryCleanup();
        }
        
        // Update metadata cache
        updateMetadataCache();
        
        // Process storage queue
        storage.flush();
    }
    
    private void processUnloads() {
        Iterator<CubePos> iterator = unloadQueue.iterator();
        int processed = 0;
        
        while (iterator.hasNext() && processed < 8) {
            CubePos pos = iterator.next();
            iterator.remove();
            unloadCube(pos);
            processed++;
        }
    }
    
    private void performMemoryCleanup() {
        // Find least recently used cubes
        List<Map.Entry<CubePos, Long>> accessList = new ArrayList<>(cubeAccessTimes.entrySet());
        accessList.sort(Map.Entry.comparingByValue());
        
        int toUnload = loadedCount.get() - MAX_LOADED_CUBES;
        for (int i = 0; i < Math.min(toUnload, accessList.size()); i++) {
            CubePos pos = accessList.get(i).getKey();
            
            // Don't unload if any player is nearby
            if (!isPlayerNearby(pos)) {
                unloadQueue.add(pos);
            }
        }
    }
    
    private boolean isPlayerNearby(CubePos pos) {
        return playerTrackers.values().stream()
            .anyMatch(tracker -> tracker.isChunkInRange(pos));
    }
    
    private void updateMetadataCache() {
        long currentTime = System.currentTimeMillis();
        
        for (Map.Entry<CubePos, CubeChunk> entry : loadedCubes.entrySet()) {
            CubePos pos = entry.getKey();
            CubeChunk cube = entry.getValue();
            
            metadataCache.put(pos, new CubeMetadata(
                pos.x, pos.y, pos.z,
                cube.isEmpty(),
                cube.isDirty(),
                currentTime
            ));
        }
    }
    
    private void addToColumn(CubePos pos, CubeChunk cube) {
        ChunkPos columnPos = new ChunkPos(pos.x, pos.z);
        CubeColumn column = loadedColumns.computeIfAbsent(columnPos, cp -> new CubeColumn(cp.x, cp.z));
        column.loadCube(pos.y, cube);
    }
    
    private void removeFromColumn(CubePos pos) {
        ChunkPos columnPos = new ChunkPos(pos.x, pos.z);
        CubeColumn column = loadedColumns.get(columnPos);
        if (column != null) {
            column.unloadCube(pos.y);
            if (!column.hasLoadedCubes()) {
                loadedColumns.remove(columnPos);
            }
        }
    }
    
    private Set<CubePos> getChunksInRange(int centerX, int centerY, int centerZ) {
        Set<CubePos> chunks = new HashSet<>();
        
        for (int dx = -horizontalRenderDistance; dx <= horizontalRenderDistance; dx++) {
            for (int dy = -verticalRenderDistance; dy <= verticalRenderDistance; dy++) {
                for (int dz = -horizontalRenderDistance; dz <= horizontalRenderDistance; dz++) {
                    chunks.add(new CubePos(centerX + dx, centerY + dy, centerZ + dz));
                }
            }
        }
        
        return chunks;
    }
    
    private double calculateDistance(int x1, int y1, int z1, int x2, int y2, int z2) {
        return Math.sqrt((x1-x2)*(x1-x2) + (y1-y2)*(y1-y2) + (z1-z2)*(z1-z2));
    }
    
    private void updateAccessTime(CubePos pos) {
        cubeAccessTimes.put(pos, System.currentTimeMillis());
        cubeAccessCounts.merge(pos, 1, Integer::sum);
    }
    
    public void shutdown() {
        managementExecutor.shutdown();
        generationExecutor.shutdown();
        storage.saveAll();
    }
    
    // Getters and configuration
    public int getLoadedCubeCount() { return loadedCount.get(); }
    public int getLoadingCubeCount() { return loadingCubes.size(); }
    public int getHorizontalRenderDistance() { return horizontalRenderDistance; }
    public void setHorizontalRenderDistance(int distance) { this.horizontalRenderDistance = distance; }
    public int getVerticalRenderDistance() { return verticalRenderDistance; }
    public void setVerticalRenderDistance(int distance) { this.verticalRenderDistance = distance; }
    public Collection<CubeChunk> getLoadedChunks() { return loadedCubes.values(); }
    
    // Inner classes
    public static class CubePos {
        public final int x, y, z;
        
        public CubePos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof CubePos other)) return false;
            return x == other.x && y == other.y && z == other.z;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(x, y, z);
        }
        
        @Override
        public String toString() {
            return String.format("CubePos[%d, %d, %d]", x, y, z);
        }
    }
    
    private static class ChunkLoadRequest implements Comparable<ChunkLoadRequest> {
        final CubePos pos;
        final double priority;
        final UUID playerId;
        final long timestamp;
        
        ChunkLoadRequest(CubePos pos, double priority, UUID playerId) {
            this.pos = pos;
            this.priority = priority;
            this.playerId = playerId;
            this.timestamp = System.currentTimeMillis();
        }
        
        @Override
        public int compareTo(ChunkLoadRequest other) {
            return Double.compare(this.priority, other.priority);
        }
    }
    
    private static class PlayerChunkTracker {
        final ServerPlayer player;
        int cubeX, cubeY, cubeZ;
        final Set<CubePos> loadedChunks = new HashSet<>();
        
        PlayerChunkTracker(ServerPlayer player) {
            this.player = player;
            BlockPos pos = player.blockPosition();
            this.cubeX = Math.floorDiv(pos.getX(), CubeChunk.SIZE);
            this.cubeY = Math.floorDiv(pos.getY(), CubeChunk.SIZE);
            this.cubeZ = Math.floorDiv(pos.getZ(), CubeChunk.SIZE);
        }
        
        void updatePosition(int newX, int newY, int newZ) {
            this.cubeX = newX;
            this.cubeY = newY;
            this.cubeZ = newZ;
        }
        
        void unloadFarChunks(UnifiedCubicChunkManager manager) {
            Iterator<CubePos> iterator = loadedChunks.iterator();
            while (iterator.hasNext()) {
                CubePos pos = iterator.next();
                double distance = manager.calculateDistance(cubeX, cubeY, cubeZ, pos.x, pos.y, pos.z);
                
                if (distance > Math.max(manager.horizontalRenderDistance, manager.verticalRenderDistance)) {
                    iterator.remove();
                    manager.unloadQueue.add(pos);
                }
            }
        }
        
        boolean isChunkInRange(CubePos pos) {
            double distance = Math.sqrt(
                (cubeX - pos.x) * (cubeX - pos.x) + 
                (cubeY - pos.y) * (cubeY - pos.y) + 
                (cubeZ - pos.z) * (cubeZ - pos.z)
            );
            return distance <= Math.max(8, 4); // Use reasonable defaults
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