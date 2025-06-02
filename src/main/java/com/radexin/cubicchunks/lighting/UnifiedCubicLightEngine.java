package com.radexin.cubicchunks.lighting;

import com.radexin.cubicchunks.Config;
import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.chunk.UnifiedCubicChunkManager;
import com.radexin.cubicchunks.world.CubeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unified high-performance 3D lighting engine for cubic chunks.
 * Combines advanced algorithms, efficient caching, multi-threaded processing,
 * and cross-cube light propagation from multiple implementations.
 */
public class UnifiedCubicLightEngine {
    private static final int MAX_LIGHT_LEVEL = 15;
    private static final int LIGHT_PROPAGATION_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private static final int MAX_PROPAGATION_DISTANCE = 16;
    private static final int BATCH_SIZE = 512;
    private static final long CACHE_EXPIRE_TIME = 5000; // 5 seconds
    
    // Core components
    private final UnifiedCubicChunkManager chunkManager;
    private final CubeWorld cubeWorld;
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
    
    // Simple BFS queues for immediate processing
    private final Queue<LightNode> lightQueue = new ArrayDeque<>();
    private final Queue<LightNode> removalQueue = new ArrayDeque<>();
    
    // Performance tracking
    private final AtomicLong lightUpdatesProcessed = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicInteger activePropagations = new AtomicInteger(0);
    
    // Configuration
    private final boolean enableBatchedUpdates = Config.enableBatchedLighting;
    private final int cacheSize = Config.lightCacheSize;
    private final boolean enableSpatialOptimization = true;
    
    public UnifiedCubicLightEngine(UnifiedCubicChunkManager chunkManager, CubeWorld cubeWorld, Level level) {
        this.chunkManager = chunkManager;
        this.cubeWorld = cubeWorld;
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
                activePropagations.incrementAndGet();
                
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
                activePropagations.decrementAndGet();
                long duration = System.nanoTime() - start;
                // Performance logging could go here
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
        
        if (enableBatchedUpdates) {
            // Queue for batched processing
            queueLightingUpdate(pos, oldState, newState);
        } else {
            // Process immediately
            CompletableFuture.runAsync(() -> {
                try {
                    updateLightingAtPosition(pos, oldState, newState);
                } finally {
                    processingPositions.remove(pos);
                }
            }, lightingExecutor);
        }
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
     * Processes all pending light updates.
     */
    public void processPendingUpdates() {
        if (enableBatchedUpdates) {
            processBatchedUpdates();
        } else {
            processImmediateUpdates();
        }
    }
    
    // Private implementation methods
    
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
                        int opacity = getOpacity(state, cube.getWorldPos(x, y, z), LightType.SKY);
                        currentLight = Math.max(0, currentLight - Math.max(1, opacity));
                        cube.setSkyLight(x, y, z, (byte) currentLight);
                    }
                }
            }
        }
    }
    
    private void propagateSkyLightFromAbove(CubeChunk cube) {
        // Get cube above
        CubeChunk aboveCube = chunkManager.getCube(cube.getCubeX(), cube.getCubeY() + 1, cube.getCubeZ(), false);
        if (aboveCube == null) return;
        
        // Propagate sky light from above cube
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
                int opacity = getOpacity(state, cube.getWorldPos(x, y, z), LightType.SKY);
                currentLight = Math.max(0, currentLight - Math.max(1, opacity));
                cube.setSkyLight(x, y, z, (byte) currentLight);
            }
        }
    }
    
    private void initializeCubeBlockLight(CubeChunk cube) {
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int y = 0; y < CubeChunk.SIZE; y++) {
                for (int z = 0; z < CubeChunk.SIZE; z++) {
                    BlockState state = cube.getBlockState(x, y, z);
                    int emission = state.getLightEmission();
                    
                    if (emission > 0) {
                        cube.setBlockLight(x, y, z, (byte) emission);
                        // Queue for propagation
                        BlockPos worldPos = cube.getWorldPos(x, y, z);
                        queueLightUpdate(worldPos, emission, LightType.BLOCK, UpdatePriority.IMMEDIATE);
                    }
                }
            }
        }
    }
    
    private void propagateWithNeighbors(CubeChunk cube) {
        // Propagate light to/from all 6 neighboring cubes
        for (Direction direction : Direction.values()) {
            int[] offset = getDirectionOffset(direction);
            CubeChunk neighbor = chunkManager.getCube(
                cube.getCubeX() + offset[0],
                cube.getCubeY() + offset[1],
                cube.getCubeZ() + offset[2],
                false
            );
            
            if (neighbor != null) {
                propagateLightBetweenCubes(cube, neighbor, direction);
            }
        }
    }
    
    private void propagateLightBetweenCubes(CubeChunk fromCube, CubeChunk toCube, Direction direction) {
        // Propagate both sky and block light
        propagateLightAcrossBoundary(fromCube, toCube, direction, LightType.SKY);
        propagateLightAcrossBoundary(fromCube, toCube, direction, LightType.BLOCK);
    }
    
    private void propagateLightAcrossBoundary(CubeChunk fromCube, CubeChunk toCube, 
                                            Direction direction, LightType lightType) {
        // Get the face coordinates for light propagation
        int[] fromFace = getBoundaryFaceCoords(direction, true);
        int[] toFace = getBoundaryFaceCoords(direction, false);
        
        for (int u = 0; u < CubeChunk.SIZE; u++) {
            for (int v = 0; v < CubeChunk.SIZE; v++) {
                int[] fromCoords = mapFaceCoords(fromFace, u, v, direction);
                int[] toCoords = mapFaceCoords(toFace, u, v, direction);
                
                int fromLight = getLightValue(fromCube, fromCoords[0], fromCoords[1], fromCoords[2], lightType);
                int toLight = getLightValue(toCube, toCoords[0], toCoords[1], toCoords[2], lightType);
                
                // Calculate opacity
                BlockState toState = toCube.getBlockState(toCoords[0], toCoords[1], toCoords[2]);
                int opacity = getOpacity(toState, toCube.getWorldPos(toCoords[0], toCoords[1], toCoords[2]), lightType);
                
                int newLight = Math.max(0, fromLight - Math.max(1, opacity));
                
                if (newLight > toLight) {
                    setLightValue(toCube, toCoords[0], toCoords[1], toCoords[2], lightType, newLight);
                    // Queue for further propagation
                    BlockPos worldPos = toCube.getWorldPos(toCoords[0], toCoords[1], toCoords[2]);
                    queueLightUpdate(worldPos, newLight, lightType, UpdatePriority.PROPAGATION);
                }
            }
        }
    }
    
    private void updateLightingAtPosition(BlockPos pos, BlockState oldState, BlockState newState) {
        // Handle block light emission changes
        int oldEmission = oldState.getLightEmission();
        int newEmission = newState.getLightEmission();
        
        if (oldEmission != newEmission) {
            updateBlockLightEmission(pos, oldEmission, newEmission);
        }
        
        // Handle sky light opacity changes
        int oldOpacity = getOpacity(oldState, pos, LightType.SKY);
        int newOpacity = getOpacity(newState, pos, LightType.SKY);
        
        if (oldOpacity != newOpacity) {
            updateSkyLightOpacity(pos, oldOpacity, newOpacity);
        }
        
        // Invalidate cache
        invalidateLightCache(pos);
        lightUpdatesProcessed.incrementAndGet();
    }
    
    private void updateBlockLightEmission(BlockPos pos, int oldEmission, int newEmission) {
        CubeChunk cube = getCubeForPosition(pos);
        if (cube == null) return;
        
        int localX = pos.getX() & 15;
        int localY = pos.getY() & 15;
        int localZ = pos.getZ() & 15;
        
        if (oldEmission > 0) {
            // Remove old light
            removeLightBFS(pos.getX(), pos.getY(), pos.getZ(), oldEmission, LightType.BLOCK);
        }
        
        if (newEmission > 0) {
            // Add new light
            cube.setBlockLight(localX, localY, localZ, (byte) newEmission);
            propagateLightBFS(pos.getX(), pos.getY(), pos.getZ(), newEmission, LightType.BLOCK);
        }
    }
    
    private void updateSkyLightOpacity(BlockPos pos, int oldOpacity, int newOpacity) {
        if (newOpacity > oldOpacity) {
            // Block became more opaque - remove sky light
            recalculateSkyLightColumn(pos);
        } else if (newOpacity < oldOpacity) {
            // Block became less opaque - propagate sky light
            recalculateSkyLightColumn(pos);
        }
    }
    
    private void propagateLightBFS(int startX, int startY, int startZ, int lightLevel, LightType lightType) {
        lightQueue.clear();
        lightQueue.offer(new LightNode(new BlockPos(startX, startY, startZ), lightLevel));
        
        while (!lightQueue.isEmpty()) {
            LightNode node = lightQueue.poll();
            
            // Check all 6 neighbors
            for (Direction dir : Direction.values()) {
                BlockPos newPos = node.pos.relative(dir);
                
                if (node.lightLevel <= 1) continue; // No more light to propagate
                
                CubeChunk cube = getCubeForPosition(newPos);
                if (cube == null) continue;
                
                int localX = newPos.getX() & 15;
                int localY = newPos.getY() & 15;
                int localZ = newPos.getZ() & 15;
                
                BlockState blockState = cube.getBlockState(localX, localY, localZ);
                int opacity = getOpacity(blockState, newPos, lightType);
                
                if (opacity >= 15) continue; // Opaque block
                
                int newLightLevel = Math.max(0, node.lightLevel - Math.max(1, opacity));
                if (newLightLevel <= 0) continue;
                
                int currentLight = getLightValue(cube, localX, localY, localZ, lightType);
                
                // Only update if new light is brighter
                if (newLightLevel > currentLight) {
                    setLightValue(cube, localX, localY, localZ, lightType, newLightLevel);
                    lightQueue.offer(new LightNode(newPos, newLightLevel));
                }
            }
        }
    }
    
    private void removeLightBFS(int startX, int startY, int startZ, int lightLevel, LightType lightType) {
        removalQueue.clear();
        lightQueue.clear();
        
        removalQueue.offer(new LightNode(new BlockPos(startX, startY, startZ), lightLevel));
        
        // First pass: remove light
        while (!removalQueue.isEmpty()) {
            LightNode node = removalQueue.poll();
            
            for (Direction dir : Direction.values()) {
                BlockPos newPos = node.pos.relative(dir);
                
                CubeChunk cube = getCubeForPosition(newPos);
                if (cube == null) continue;
                
                int localX = newPos.getX() & 15;
                int localY = newPos.getY() & 15;
                int localZ = newPos.getZ() & 15;
                
                int neighborLight = getLightValue(cube, localX, localY, localZ, lightType);
                
                if (neighborLight != 0 && neighborLight < node.lightLevel) {
                    setLightValue(cube, localX, localY, localZ, lightType, 0);
                    removalQueue.offer(new LightNode(newPos, neighborLight));
                } else if (neighborLight >= node.lightLevel) {
                    lightQueue.offer(new LightNode(newPos, neighborLight));
                }
            }
        }
        
        // Second pass: re-propagate remaining light
        while (!lightQueue.isEmpty()) {
            LightNode node = lightQueue.poll();
            propagateLightBFS(node.pos.getX(), node.pos.getY(), node.pos.getZ(), node.lightLevel, lightType);
        }
    }
    
    private void recalculateSkyLightColumn(BlockPos pos) {
        // Recalculate sky light for the entire column
        int worldX = pos.getX();
        int worldZ = pos.getZ();
        
        // Start from top of world and work down
        for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
            BlockPos columnPos = new BlockPos(worldX, y, worldZ);
            CubeChunk cube = getCubeForPosition(columnPos);
            if (cube == null) continue;
            
            int localX = worldX & 15;
            int localY = y & 15;
            int localZ = worldZ & 15;
            
            BlockState state = cube.getBlockState(localX, localY, localZ);
            
            // Calculate sky light based on column above
            int lightFromAbove = y < level.getMaxBuildHeight() - 1 ? 
                getSkyLight(new BlockPos(worldX, y + 1, worldZ)) : MAX_LIGHT_LEVEL;
            
            int opacity = getOpacity(state, columnPos, LightType.SKY);
            int newLight = state.isAir() ? lightFromAbove : Math.max(0, lightFromAbove - Math.max(1, opacity));
            
            cube.setSkyLight(localX, localY, localZ, (byte) newLight);
        }
    }
    
    private void queueLightingUpdate(BlockPos pos, BlockState oldState, BlockState newState) {
        // Queue for batched processing
        int oldEmission = oldState.getLightEmission();
        int newEmission = newState.getLightEmission();
        
        if (oldEmission != newEmission) {
            queueLightUpdate(pos, newEmission, LightType.BLOCK, UpdatePriority.HIGH);
        }
        
        int oldOpacity = getOpacity(oldState, pos, LightType.SKY);
        int newOpacity = getOpacity(newState, pos, LightType.SKY);
        
        if (oldOpacity != newOpacity) {
            queueLightUpdate(pos, 0, LightType.SKY, UpdatePriority.HIGH);
        }
    }
    
    private void processBatchedUpdates() {
        // Process sky light updates
        int processed = 0;
        while (!skyLightQueue.isEmpty() && processed < BATCH_SIZE / 2) {
            LightUpdate update = skyLightQueue.poll();
            if (update != null) {
                processLightUpdate(update, LightType.SKY);
                processed++;
            }
        }
        
        // Process block light updates
        processed = 0;
        while (!blockLightQueue.isEmpty() && processed < BATCH_SIZE / 2) {
            LightUpdate update = blockLightQueue.poll();
            if (update != null) {
                processLightUpdate(update, LightType.BLOCK);
                processed++;
            }
        }
    }
    
    private void processImmediateUpdates() {
        // Process all pending updates immediately
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
        processingPositions.remove(update.pos);
        
        if (lightType == LightType.BLOCK) {
            propagateLightBFS(update.pos.getX(), update.pos.getY(), update.pos.getZ(), update.lightLevel, lightType);
        } else {
            recalculateSkyLightColumn(update.pos);
        }
    }
    
    private void performMaintenance() {
        // Clean up expired cache entries
        cleanupLightCache();
        cleanupCubeLightCache();
        
        // Log performance stats if needed
        if (System.currentTimeMillis() % 30000 == 0) { // Every 30 seconds
            logPerformanceStats();
        }
    }
    
    private void cleanupLightCache() {
        long currentTime = System.currentTimeMillis();
        lightCache.entrySet().removeIf(entry -> entry.getValue().isExpired(currentTime));
    }
    
    private void cleanupCubeLightCache() {
        long currentTime = System.currentTimeMillis();
        if (cubeLightCache.size() > cacheSize * 2) {
            cubeLightCache.clear(); // Simple cleanup for now
        }
    }
    
    private void logPerformanceStats() {
        long total = cacheHits.get() + cacheMisses.get();
        double hitRate = total > 0 ? (double) cacheHits.get() / total : 0.0;
        
        // Log stats (could use logger here)
        System.out.println(String.format("Light Engine Stats: Updates=%d, Cache Hit Rate=%.2f%%, Active=%d",
            lightUpdatesProcessed.get(), hitRate * 100, activePropagations.get()));
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
        
        // Also invalidate nearby positions
        for (Direction dir : Direction.values()) {
            lightCache.remove(pos.relative(dir));
        }
    }
    
    private CubeChunk getCubeForPosition(BlockPos pos) {
        int cubeX = Math.floorDiv(pos.getX(), CubeChunk.SIZE);
        int cubeY = Math.floorDiv(pos.getY(), CubeChunk.SIZE);
        int cubeZ = Math.floorDiv(pos.getZ(), CubeChunk.SIZE);
        
        return chunkManager.getCube(cubeX, cubeY, cubeZ, false);
    }
    
    private int[] getDirectionOffset(Direction direction) {
        return switch (direction) {
            case UP -> new int[]{0, 1, 0};
            case DOWN -> new int[]{0, -1, 0};
            case NORTH -> new int[]{0, 0, -1};
            case SOUTH -> new int[]{0, 0, 1};
            case WEST -> new int[]{-1, 0, 0};
            case EAST -> new int[]{1, 0, 0};
        };
    }
    
    private int[] getBoundaryFaceCoords(Direction direction, boolean isSource) {
        return switch (direction) {
            case UP -> new int[]{0, isSource ? CubeChunk.SIZE - 1 : 0, 0};
            case DOWN -> new int[]{0, isSource ? 0 : CubeChunk.SIZE - 1, 0};
            case NORTH -> new int[]{0, 0, isSource ? 0 : CubeChunk.SIZE - 1};
            case SOUTH -> new int[]{0, 0, isSource ? CubeChunk.SIZE - 1 : 0};
            case WEST -> new int[]{isSource ? 0 : CubeChunk.SIZE - 1, 0, 0};
            case EAST -> new int[]{isSource ? CubeChunk.SIZE - 1 : 0, 0, 0};
        };
    }
    
    private int[] mapFaceCoords(int[] faceBase, int u, int v, Direction direction) {
        return switch (direction) {
            case UP, DOWN -> new int[]{faceBase[0] + u, faceBase[1], faceBase[2] + v};
            case NORTH, SOUTH -> new int[]{faceBase[0] + u, faceBase[1] + v, faceBase[2]};
            case WEST, EAST -> new int[]{faceBase[0], faceBase[1] + u, faceBase[2] + v};
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
            return state.isAir() ? 0 : (state.canOcclude() ? 15 : 1);
        }
    }
    
    private static long packCubeCoords(int x, int y, int z) {
        return ((long)(x & 0x1FFFFF)) | 
               (((long)(y & 0x1FFFFF)) << 21) |
               (((long)(z & 0x1FFFFF)) << 42);
    }
    
    public void shutdown() {
        lightingExecutor.shutdown();
        maintenanceExecutor.shutdown();
        
        try {
            if (!lightingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                lightingExecutor.shutdownNow();
            }
            if (!maintenanceExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                maintenanceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            lightingExecutor.shutdownNow();
            maintenanceExecutor.shutdownNow();
        }
    }
    
    // Performance getters
    public long getLightUpdatesProcessed() { return lightUpdatesProcessed.get(); }
    public double getCacheHitRate() {
        long total = cacheHits.get() + cacheMisses.get();
        return total > 0 ? (double) cacheHits.get() / total : 0.0;
    }
    public int getActivePropagations() { return activePropagations.get(); }
    public int getCacheSize() { return lightCache.size(); }
    
    // Enums and inner classes
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
            return priorityCompare != 0 ? priorityCompare : Long.compare(this.timestamp, other.timestamp);
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
            return (currentTime - timestamp) > CACHE_EXPIRE_TIME;
        }
    }
    
    private static class CubeLight {
        final byte[] skyLight;
        final byte[] blockLight;
        final long lastAccess;
        
        CubeLight(CubeChunk cube, long lastAccess) {
            int size = CubeChunk.SIZE * CubeChunk.SIZE * CubeChunk.SIZE;
            this.skyLight = new byte[size];
            this.blockLight = new byte[size];
            this.lastAccess = lastAccess;
            
            // Copy light data from cube
            for (int x = 0; x < CubeChunk.SIZE; x++) {
                for (int y = 0; y < CubeChunk.SIZE; y++) {
                    for (int z = 0; z < CubeChunk.SIZE; z++) {
                        int index = x + y * CubeChunk.SIZE + z * CubeChunk.SIZE * CubeChunk.SIZE;
                        this.skyLight[index] = (byte) cube.getSkyLight(x, y, z);
                        this.blockLight[index] = (byte) cube.getBlockLight(x, y, z);
                    }
                }
            }
        }
    }
} 