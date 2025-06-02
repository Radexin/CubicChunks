package com.radexin.cubicchunks.lighting;

import com.radexin.cubicchunks.Config;
import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.chunk.CubicChunkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enhanced 3D lighting engine with full volumetric light propagation.
 * Features adaptive algorithms, efficient caching, and multi-threaded processing.
 */
public class Enhanced3DLightEngine {
    private static final int MAX_LIGHT_LEVEL = 15;
    private static final int LIGHT_PROPAGATION_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private static final int MAX_PROPAGATION_DISTANCE = 16;
    private static final int BATCH_SIZE = 512;
    
    private final CubicChunkManager chunkManager;
    private final Level level;
    
    // Thread management
    private final ExecutorService lightingExecutor = Executors.newFixedThreadPool(LIGHT_PROPAGATION_THREADS);
    private final ScheduledExecutorService maintenanceExecutor = Executors.newSingleThreadScheduledExecutor();
    
    // Light update queues with priority
    private final PriorityBlockingQueue<LightUpdate> skyLightQueue = new PriorityBlockingQueue<>();
    private final PriorityBlockingQueue<LightUpdate> blockLightQueue = new PriorityBlockingQueue<>();
    private final Set<BlockPos> processingPositions = ConcurrentHashMap.newKeySet();
    
    // Advanced caching system
    private final Map<BlockPos, CachedLightValue> lightCache = new ConcurrentHashMap<>();
    private final Map<Long, CubeLight> cubeLightCache = new ConcurrentHashMap<>();
    
    // Performance tracking
    private final AtomicLong lightUpdatesProcessed = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicInteger activePropagations = new AtomicInteger(0);
    
    // Configuration
    private final boolean enableBatchedUpdates = Config.enableBatchedLighting;
    private final int cacheSize = Config.lightCacheSize;
    private final boolean enableSpatialOptimization = true;
    
    public Enhanced3DLightEngine(CubicChunkManager chunkManager, Level level) {
        this.chunkManager = chunkManager;
        this.level = level;
        
        // Start maintenance tasks
        maintenanceExecutor.scheduleAtFixedRate(this::performMaintenance, 5, 5, TimeUnit.SECONDS);
        maintenanceExecutor.scheduleAtFixedRate(this::processBatchedUpdates, 50, 50, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Initializes lighting for a newly generated or loaded cube.
     */
    public CompletableFuture<Void> initializeCubeLighting(CubeChunk cube) {
        return CompletableFuture.runAsync(() -> {
            long start = System.nanoTime();
            
            try {
                // Initialize sky light
                initializeCubeSkyLight(cube);
                
                // Initialize block light
                initializeCubeBlockLight(cube);
                
                // Propagate light from/to neighboring cubes
                propagateWithNeighbors(cube);
                
                // Cache the initialized cube
                cacheCubeLight(cube);
                
                cube.setDirty(true);
                
            } finally {
                long duration = System.nanoTime() - start;
                // Log performance if needed
            }
        }, lightingExecutor);
    }
    
    /**
     * Updates lighting when a block changes.
     */
    public void updateLightingForBlockChange(BlockPos pos, BlockState oldState, BlockState newState) {
        if (processingPositions.contains(pos)) {
            return; // Already processing this position
        }
        
        processingPositions.add(pos);
        
        CompletableFuture.runAsync(() -> {
            try {
                updateLightingAtPosition(pos, oldState, newState);
            } finally {
                processingPositions.remove(pos);
            }
        }, lightingExecutor);
    }
    
    /**
     * Gets the combined light level (sky + block) at a position.
     */
    public int getLightLevel(BlockPos pos) {
        // Check cache first
        CachedLightValue cached = lightCache.get(pos);
        if (cached != null && !cached.isExpired()) {
            cacheHits.incrementAndGet();
            return cached.lightLevel;
        }
        
        cacheMisses.incrementAndGet();
        
        // Calculate light level
        int skyLight = getSkyLight(pos);
        int blockLight = getBlockLight(pos);
        int combined = Math.max(skyLight, blockLight);
        
        // Cache the result
        cacheLight(pos, combined, skyLight, blockLight);
        
        return combined;
    }
    
    /**
     * Gets sky light level at a position.
     */
    public int getSkyLight(BlockPos pos) {
        CubeChunk cube = getCubeForPosition(pos);
        if (cube == null) return 0;
        
        int localX = pos.getX() & 15;
        int localY = pos.getY() & 15;
        int localZ = pos.getZ() & 15;
        
        return cube.getSkyLight(localX, localY, localZ);
    }
    
    /**
     * Gets block light level at a position.
     */
    public int getBlockLight(BlockPos pos) {
        CubeChunk cube = getCubeForPosition(pos);
        if (cube == null) return 0;
        
        int localX = pos.getX() & 15;
        int localY = pos.getY() & 15;
        int localZ = pos.getZ() & 15;
        
        return cube.getBlockLight(localX, localY, localZ);
    }
    
    /**
     * Processes all pending light updates in batches.
     */
    public void processPendingUpdates() {
        if (enableBatchedUpdates) {
            processBatchedUpdates();
        } else {
            processImmediateUpdates();
        }
    }
    
    private void initializeCubeSkyLight(CubeChunk cube) {
        int cubeY = cube.getCubeY();
        int worldMaxY = level.getMaxBuildHeight() / CubeChunk.SIZE;
        
        if (cubeY >= worldMaxY - 1) {
            // Top-level cubes receive full sky light
            initializeTopSkyLight(cube);
        } else {
            // Lower cubes receive propagated sky light
            propagateSkyLightFromAbove(cube);
        }
    }
    
    private void initializeTopSkyLight(CubeChunk cube) {
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                // Start from top and work down
                int currentLight = MAX_LIGHT_LEVEL;
                
                for (int y = CubeChunk.SIZE - 1; y >= 0; y--) {
                    BlockState state = cube.getBlockState(x, y, z);
                    
                    if (state.isAir()) {
                        cube.setSkyLight(x, y, z, (byte) currentLight);
                    } else {
                        int opacity = state.getLightBlock(level, cube.getWorldPos(x, y, z));
                        currentLight = Math.max(0, currentLight - Math.max(1, opacity));
                        cube.setSkyLight(x, y, z, (byte) currentLight);
                    }
                }
            }
        }
    }
    
    private void propagateSkyLightFromAbove(CubeChunk cube) {
        int cubeX = cube.getCubeX();
        int cubeY = cube.getCubeY();
        int cubeZ = cube.getCubeZ();
        
        // Get cube above
        CubeChunk aboveCube = chunkManager.getCube(cubeX, cubeY + 1, cubeZ);
        if (aboveCube == null) return;
        
        // Copy light from bottom of above cube to top of this cube
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                int lightFromAbove = aboveCube.getSkyLight(x, 0, z);
                propagateSkyLightColumn(cube, x, z, lightFromAbove);
            }
        }
    }
    
    private void propagateSkyLightColumn(CubeChunk cube, int x, int z, int startLight) {
        int currentLight = startLight;
        
        for (int y = CubeChunk.SIZE - 1; y >= 0; y--) {
            BlockState state = cube.getBlockState(x, y, z);
            
            if (state.isAir()) {
                cube.setSkyLight(x, y, z, (byte) currentLight);
            } else {
                int opacity = state.getLightBlock(level, cube.getWorldPos(x, y, z));
                currentLight = Math.max(0, currentLight - Math.max(1, opacity));
                cube.setSkyLight(x, y, z, (byte) currentLight);
            }
        }
    }
    
    private void initializeCubeBlockLight(CubeChunk cube) {
        // First pass: set light emission
        List<BlockPos> lightSources = new ArrayList<>();
        
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int y = 0; y < CubeChunk.SIZE; y++) {
                for (int z = 0; z < CubeChunk.SIZE; z++) {
                    BlockState state = cube.getBlockState(x, y, z);
                    int emission = state.getLightEmission();
                    
                    cube.setBlockLight(x, y, z, (byte) emission);
                    
                    if (emission > 0) {
                        lightSources.add(cube.getWorldPos(x, y, z));
                    }
                }
            }
        }
        
        // Second pass: propagate from light sources
        for (BlockPos source : lightSources) {
            propagateBlockLightFromSource(source, cube.getBlockLight(
                source.getX() & 15, source.getY() & 15, source.getZ() & 15));
        }
    }
    
    private void propagateWithNeighbors(CubeChunk cube) {
        int cubeX = cube.getCubeX();
        int cubeY = cube.getCubeY();
        int cubeZ = cube.getCubeZ();
        
        // Check all 6 neighbors
        for (Direction direction : Direction.values()) {
            int neighborX = cubeX + direction.getStepX();
            int neighborY = cubeY + direction.getStepY();
            int neighborZ = cubeZ + direction.getStepZ();
            
            CubeChunk neighbor = chunkManager.getCube(neighborX, neighborY, neighborZ);
            if (neighbor != null) {
                propagateLightBetweenCubes(cube, neighbor, direction);
            }
        }
    }
    
    private void propagateLightBetweenCubes(CubeChunk fromCube, CubeChunk toCube, Direction direction) {
        Direction opposite = direction.getOpposite();
        
        // Propagate both sky and block light
        propagateLightAcrossBoundary(fromCube, toCube, direction, LightType.SKY);
        propagateLightAcrossBoundary(fromCube, toCube, direction, LightType.BLOCK);
    }
    
    private void propagateLightAcrossBoundary(CubeChunk fromCube, CubeChunk toCube, 
                                            Direction direction, LightType lightType) {
        // Get boundary coordinates for both cubes
        for (int u = 0; u < CubeChunk.SIZE; u++) {
            for (int v = 0; v < CubeChunk.SIZE; v++) {
                int[] fromCoords = getBoundaryCoords(u, v, direction, true);
                int[] toCoords = getBoundaryCoords(u, v, direction, false);
                
                int fromLight = getLightValue(fromCube, fromCoords[0], fromCoords[1], fromCoords[2], lightType);
                int toLight = getLightValue(toCube, toCoords[0], toCoords[1], toCoords[2], lightType);
                
                // Propagate if there's a light difference
                if (fromLight > toLight + 1) {
                    BlockPos toPos = toCube.getWorldPos(toCoords[0], toCoords[1], toCoords[2]);
                    BlockState toState = toCube.getBlockState(toCoords[0], toCoords[1], toCoords[2]);
                    
                    int opacity = getOpacity(toState, toPos, lightType);
                    int newLight = Math.max(0, fromLight - Math.max(1, opacity));
                    
                    if (newLight > toLight) {
                        setLightValue(toCube, toCoords[0], toCoords[1], toCoords[2], lightType, newLight);
                        
                        // Queue for further propagation
                        queueLightUpdate(toPos, newLight, lightType, UpdatePriority.PROPAGATION);
                    }
                }
            }
        }
    }
    
    private void updateLightingAtPosition(BlockPos pos, BlockState oldState, BlockState newState) {
        // Handle emission changes
        int oldEmission = oldState.getLightEmission();
        int newEmission = newState.getLightEmission();
        
        if (oldEmission != newEmission) {
            updateBlockLightEmission(pos, oldEmission, newEmission);
        }
        
        // Handle opacity changes
        int oldOpacity = getOpacity(oldState, pos, LightType.SKY);
        int newOpacity = getOpacity(newState, pos, LightType.SKY);
        
        if (oldOpacity != newOpacity) {
            updateSkyLightOpacity(pos, oldOpacity, newOpacity);
        }
        
        // Invalidate cache for this position and neighbors
        invalidateLightCache(pos);
    }
    
    private void updateBlockLightEmission(BlockPos pos, int oldEmission, int newEmission) {
        CubeChunk cube = getCubeForPosition(pos);
        if (cube == null) return;
        
        int localX = pos.getX() & 15;
        int localY = pos.getY() & 15;
        int localZ = pos.getZ() & 15;
        
        if (newEmission > oldEmission) {
            // Increasing light - propagate outward
            cube.setBlockLight(localX, localY, localZ, (byte) newEmission);
            propagateBlockLightFromSource(pos, newEmission);
        } else if (newEmission < oldEmission) {
            // Decreasing light - remove old light and recalculate
            cube.setBlockLight(localX, localY, localZ, (byte) newEmission);
            removeLightAndRecalculate(pos, oldEmission, LightType.BLOCK);
        }
    }
    
    private void updateSkyLightOpacity(BlockPos pos, int oldOpacity, int newOpacity) {
        if (oldOpacity != newOpacity) {
            // Recalculate sky light column
            recalculateSkyLightColumn(pos);
        }
    }
    
    private void propagateBlockLightFromSource(BlockPos source, int lightLevel) {
        if (lightLevel <= 0) return;
        
        Queue<LightNode> propagationQueue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        
        propagationQueue.offer(new LightNode(source, lightLevel));
        visited.add(source);
        
        while (!propagationQueue.isEmpty()) {
            LightNode node = propagationQueue.poll();
            
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = node.pos.relative(direction);
                
                if (visited.contains(neighborPos)) continue;
                visited.add(neighborPos);
                
                CubeChunk neighborCube = getCubeForPosition(neighborPos);
                if (neighborCube == null) continue;
                
                int localX = neighborPos.getX() & 15;
                int localY = neighborPos.getY() & 15;
                int localZ = neighborPos.getZ() & 15;
                
                BlockState neighborState = neighborCube.getBlockState(localX, localY, localZ);
                int opacity = Math.max(1, neighborState.getLightBlock(level, neighborPos));
                int newLight = Math.max(0, node.lightLevel - opacity);
                int currentLight = neighborCube.getBlockLight(localX, localY, localZ);
                
                if (newLight > currentLight) {
                    neighborCube.setBlockLight(localX, localY, localZ, (byte) newLight);
                    
                    if (newLight > 1) {
                        propagationQueue.offer(new LightNode(neighborPos, newLight));
                    }
                }
            }
        }
    }
    
    private void removeLightAndRecalculate(BlockPos pos, int oldLightLevel, LightType lightType) {
        // Flood-fill removal algorithm
        Queue<LightNode> removalQueue = new ArrayDeque<>();
        Queue<LightNode> recalcQueue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        
        removalQueue.offer(new LightNode(pos, oldLightLevel));
        visited.add(pos);
        
        // Remove old light
        while (!removalQueue.isEmpty()) {
            LightNode node = removalQueue.poll();
            
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = node.pos.relative(direction);
                
                if (visited.contains(neighborPos)) continue;
                visited.add(neighborPos);
                
                CubeChunk neighborCube = getCubeForPosition(neighborPos);
                if (neighborCube == null) continue;
                
                int localX = neighborPos.getX() & 15;
                int localY = neighborPos.getY() & 15;
                int localZ = neighborPos.getZ() & 15;
                
                int neighborLight = getLightValue(neighborCube, localX, localY, localZ, lightType);
                
                if (neighborLight != 0 && neighborLight < node.lightLevel) {
                    setLightValue(neighborCube, localX, localY, localZ, lightType, 0);
                    removalQueue.offer(new LightNode(neighborPos, neighborLight));
                } else if (neighborLight >= node.lightLevel) {
                    recalcQueue.offer(new LightNode(neighborPos, neighborLight));
                }
            }
        }
        
        // Recalculate from remaining sources
        while (!recalcQueue.isEmpty()) {
            LightNode node = recalcQueue.poll();
            propagateLight(node.pos, node.lightLevel, lightType);
        }
    }
    
    private void recalculateSkyLightColumn(BlockPos pos) {
        int worldX = pos.getX();
        int worldZ = pos.getZ();
        
        // Find the highest cube that contains this column
        int topY = level.getMaxBuildHeight();
        int topCubeY = Math.floorDiv(topY, CubeChunk.SIZE);
        
        // Start from top and propagate down
        int currentLight = MAX_LIGHT_LEVEL;
        
        for (int cubeY = topCubeY; cubeY >= level.getMinBuildHeight() / CubeChunk.SIZE; cubeY--) {
            CubeChunk cube = chunkManager.getCube(
                Math.floorDiv(worldX, CubeChunk.SIZE),
                cubeY,
                Math.floorDiv(worldZ, CubeChunk.SIZE)
            );
            
            if (cube == null) continue;
            
            int localX = worldX & 15;
            int localZ = worldZ & 15;
            
            for (int localY = CubeChunk.SIZE - 1; localY >= 0; localY--) {
                int worldY = cubeY * CubeChunk.SIZE + localY;
                if (worldY > topY) continue;
                
                BlockState state = cube.getBlockState(localX, localY, localZ);
                
                if (state.isAir()) {
                    cube.setSkyLight(localX, localY, localZ, (byte) currentLight);
                } else {
                    int opacity = state.getLightBlock(level, new BlockPos(worldX, worldY, worldZ));
                    currentLight = Math.max(0, currentLight - Math.max(1, opacity));
                    cube.setSkyLight(localX, localY, localZ, (byte) currentLight);
                }
            }
        }
    }
    
    private void propagateLight(BlockPos pos, int lightLevel, LightType lightType) {
        if (lightType == LightType.BLOCK) {
            propagateBlockLightFromSource(pos, lightLevel);
        } else {
            // Sky light propagation is more complex and column-based
            recalculateSkyLightColumn(pos);
        }
    }
    
    private void processBatchedUpdates() {
        processBatchedQueue(skyLightQueue, LightType.SKY);
        processBatchedQueue(blockLightQueue, LightType.BLOCK);
    }
    
    private void processBatchedQueue(PriorityBlockingQueue<LightUpdate> queue, LightType lightType) {
        List<LightUpdate> batch = new ArrayList<>();
        queue.drainTo(batch, BATCH_SIZE);
        
        if (!batch.isEmpty()) {
            CompletableFuture.runAsync(() -> {
                activePropagations.incrementAndGet();
                try {
                    for (LightUpdate update : batch) {
                        processLightUpdate(update, lightType);
                    }
                } finally {
                    activePropagations.decrementAndGet();
                }
            }, lightingExecutor);
        }
    }
    
    private void processImmediateUpdates() {
        while (!skyLightQueue.isEmpty()) {
            LightUpdate update = skyLightQueue.poll();
            if (update != null) {
                processLightUpdate(update, LightType.SKY);
            }
        }
        
        while (!blockLightQueue.isEmpty()) {
            LightUpdate update = blockLightQueue.poll();
            if (update != null) {
                processLightUpdate(update, LightType.BLOCK);
            }
        }
    }
    
    private void processLightUpdate(LightUpdate update, LightType lightType) {
        lightUpdatesProcessed.incrementAndGet();
        propagateLight(update.pos, update.lightLevel, lightType);
    }
    
    private void performMaintenance() {
        // Clean up expired cache entries
        cleanupLightCache();
        
        // Clean up cube light cache
        cleanupCubeLightCache();
        
        // Log performance statistics if enabled
        if (Config.enablePerformanceMetrics) {
            logPerformanceStats();
        }
    }
    
    private void cleanupLightCache() {
        if (lightCache.size() > cacheSize * 1.2) {
            long currentTime = System.currentTimeMillis();
            lightCache.entrySet().removeIf(entry -> entry.getValue().isExpired(currentTime));
        }
    }
    
    private void cleanupCubeLightCache() {
        if (cubeLightCache.size() > 256) {
            long currentTime = System.currentTimeMillis();
            cubeLightCache.entrySet().removeIf(entry -> 
                currentTime - entry.getValue().lastAccess > 300000); // 5 minutes
        }
    }
    
    private void logPerformanceStats() {
        long processed = lightUpdatesProcessed.get();
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        int active = activePropagations.get();
        
        // Reset counters
        lightUpdatesProcessed.set(0);
        cacheHits.set(0);
        cacheMisses.set(0);
        
        // Log statistics (implementation depends on logging framework)
        System.out.println(String.format(
            "Light Engine Stats: Updates=%d, Cache Hit Rate=%.2f%%, Active=%d",
            processed, 
            hits + misses > 0 ? (double) hits / (hits + misses) * 100 : 0,
            active
        ));
    }
    
    private void queueLightUpdate(BlockPos pos, int lightLevel, LightType lightType, UpdatePriority priority) {
        LightUpdate update = new LightUpdate(pos, lightLevel, priority);
        
        if (lightType == LightType.SKY) {
            skyLightQueue.offer(update);
        } else {
            blockLightQueue.offer(update);
        }
    }
    
    private void cacheLight(BlockPos pos, int combined, int skyLight, int blockLight) {
        if (lightCache.size() < cacheSize) {
            lightCache.put(pos, new CachedLightValue(combined, skyLight, blockLight, System.currentTimeMillis()));
        }
    }
    
    private void cacheCubeLight(CubeChunk cube) {
        long key = packCubeCoords(cube.getCubeX(), cube.getCubeY(), cube.getCubeZ());
        cubeLightCache.put(key, new CubeLight(cube, System.currentTimeMillis()));
    }
    
    private void invalidateLightCache(BlockPos pos) {
        lightCache.remove(pos);
        
        // Also invalidate neighboring positions
        for (Direction direction : Direction.values()) {
            lightCache.remove(pos.relative(direction));
        }
    }
    
    private CubeChunk getCubeForPosition(BlockPos pos) {
        int cubeX = Math.floorDiv(pos.getX(), CubeChunk.SIZE);
        int cubeY = Math.floorDiv(pos.getY(), CubeChunk.SIZE);
        int cubeZ = Math.floorDiv(pos.getZ(), CubeChunk.SIZE);
        return chunkManager.getCube(cubeX, cubeY, cubeZ);
    }
    
    private int[] getBoundaryCoords(int u, int v, Direction direction, boolean isSource) {
        int coord = isSource ? (CubeChunk.SIZE - 1) : 0;
        
        return switch (direction) {
            case EAST, WEST -> new int[]{coord, u, v};
            case UP, DOWN -> new int[]{u, coord, v};
            case NORTH, SOUTH -> new int[]{u, v, coord};
        };
    }
    
    private int getLightValue(CubeChunk cube, int x, int y, int z, LightType lightType) {
        return lightType == LightType.SKY ? cube.getSkyLight(x, y, z) : cube.getBlockLight(x, y, z);
    }
    
    private void setLightValue(CubeChunk cube, int x, int y, int z, LightType lightType, int value) {
        if (lightType == LightType.SKY) {
            cube.setSkyLight(x, y, z, (byte) value);
        } else {
            cube.setBlockLight(x, y, z, (byte) value);
        }
        cube.setDirty(true);
    }
    
    private int getOpacity(BlockState state, BlockPos pos, LightType lightType) {
        if (lightType == LightType.SKY) {
            return state.getLightBlock(level, pos);
        } else {
            return Math.max(1, state.getLightBlock(level, pos));
        }
    }
    
    private static long packCubeCoords(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) |
               (((long) y & 0x1FFFFF) << 21) |
               (((long) z & 0x1FFFFF) << 42);
    }
    
    public void shutdown() {
        maintenanceExecutor.shutdown();
        lightingExecutor.shutdown();
    }
    
    // Data classes
    private enum LightType {
        SKY, BLOCK
    }
    
    private enum UpdatePriority {
        IMMEDIATE(0),
        HIGH(1),
        PROPAGATION(2),
        MAINTENANCE(3);
        
        final int value;
        
        UpdatePriority(int value) {
            this.value = value;
        }
    }
    
    private static class LightUpdate implements Comparable<LightUpdate> {
        final BlockPos pos;
        final int lightLevel;
        final UpdatePriority priority;
        final long timestamp;
        
        LightUpdate(BlockPos pos, int lightLevel, UpdatePriority priority) {
            this.pos = pos;
            this.lightLevel = lightLevel;
            this.priority = priority;
            this.timestamp = System.currentTimeMillis();
        }
        
        @Override
        public int compareTo(LightUpdate other) {
            int priorityCompare = Integer.compare(this.priority.value, other.priority.value);
            if (priorityCompare != 0) return priorityCompare;
            return Long.compare(this.timestamp, other.timestamp);
        }
    }
    
    private static class LightNode {
        final BlockPos pos;
        final int lightLevel;
        
        LightNode(BlockPos pos, int lightLevel) {
            this.pos = pos;
            this.lightLevel = lightLevel;
        }
    }
    
    private static class CachedLightValue {
        final int lightLevel;
        final int skyLight;
        final int blockLight;
        final long timestamp;
        
        CachedLightValue(int lightLevel, int skyLight, int blockLight, long timestamp) {
            this.lightLevel = lightLevel;
            this.skyLight = skyLight;
            this.blockLight = blockLight;
            this.timestamp = timestamp;
        }
        
        boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }
        
        boolean isExpired(long currentTime) {
            return currentTime - timestamp > 5000; // 5 seconds
        }
    }
    
    private static class CubeLight {
        final byte[] skyLight;
        final byte[] blockLight;
        final long lastAccess;
        
        CubeLight(CubeChunk cube, long lastAccess) {
            this.skyLight = new byte[CubeChunk.SIZE * CubeChunk.SIZE * CubeChunk.SIZE];
            this.blockLight = new byte[CubeChunk.SIZE * CubeChunk.SIZE * CubeChunk.SIZE];
            this.lastAccess = lastAccess;
            
            // Copy light data
            for (int x = 0; x < CubeChunk.SIZE; x++) {
                for (int y = 0; y < CubeChunk.SIZE; y++) {
                    for (int z = 0; z < CubeChunk.SIZE; z++) {
                        int index = (y * CubeChunk.SIZE + z) * CubeChunk.SIZE + x;
                        skyLight[index] = cube.getSkyLight(x, y, z);
                        blockLight[index] = cube.getBlockLight(x, y, z);
                    }
                }
            }
        }
    }
} 