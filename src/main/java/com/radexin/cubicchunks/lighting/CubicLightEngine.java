package com.radexin.cubicchunks.lighting;

import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.chunk.CubicChunkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Advanced 3D lighting engine for cubic chunks.
 * Implements proper light propagation across cube boundaries with performance optimizations.
 */
public class CubicLightEngine {
    private static final int MAX_LIGHT_LEVEL = 15;
    private static final int LIGHT_PROPAGATION_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    
    private final CubicChunkManager chunkManager;
    private final Level level;
    private final ExecutorService lightingExecutor = Executors.newFixedThreadPool(LIGHT_PROPAGATION_THREADS);
    
    // Light update queues for different types
    private final Queue<LightUpdate> skyLightUpdates = new ArrayDeque<>();
    private final Queue<LightUpdate> blockLightUpdates = new ArrayDeque<>();
    
    // Performance optimization: batch updates
    private final Map<Long, Set<BlockPos>> pendingUpdates = new ConcurrentHashMap<>();
    private final Set<CubeChunk> dirtyLightCubes = ConcurrentHashMap.newKeySet();
    
    // Light caching for frequently accessed positions
    private final Map<BlockPos, CachedLightValue> lightCache = new ConcurrentHashMap<>();
    private static final int CACHE_SIZE = 10000;
    private static final long CACHE_EXPIRE_TIME = 5000; // 5 seconds
    
    public CubicLightEngine(CubicChunkManager chunkManager, Level level) {
        this.chunkManager = chunkManager;
        this.level = level;
    }
    
    /**
     * Initializes lighting for a newly generated cube.
     */
    public CompletableFuture<Void> initializeCubeLighting(CubeChunk cube) {
        return CompletableFuture.runAsync(() -> {
            // Initialize sky light
            initializeSkyLight(cube);
            
            // Initialize block light
            initializeBlockLight(cube);
            
            // Propagate light from neighboring cubes
            propagateLightFromNeighbors(cube);
            
            cube.setDirty(true);
        }, lightingExecutor);
    }
    
    /**
     * Updates lighting when a block changes.
     */
    public void updateLightingForBlockChange(BlockPos pos, BlockState oldState, BlockState newState) {
        CubeChunk cube = getCubeForPosition(pos);
        if (cube == null) return;
        
        int localX = pos.getX() & 15;
        int localY = pos.getY() & 15;
        int localZ = pos.getZ() & 15;
        
        // Handle sky light changes
        if (oldState.getLightBlock(level, pos) != newState.getLightBlock(level, pos)) {
            updateSkyLight(cube, localX, localY, localZ, pos);
        }
        
        // Handle block light changes
        if (oldState.getLightEmission() != newState.getLightEmission()) {
            updateBlockLight(cube, localX, localY, localZ, pos, newState);
        }
        
        // Mark cube as dirty for lighting
        dirtyLightCubes.add(cube);
        
        // Invalidate cache
        invalidateLightCache(pos);
    }
    
    /**
     * Gets the combined light level at a position (sky + block light).
     */
    public int getLightLevel(BlockPos pos) {
        // Check cache first
        CachedLightValue cached = lightCache.get(pos);
        if (cached != null && !cached.isExpired()) {
            return cached.lightLevel;
        }
        
        int skyLight = getSkyLight(pos);
        int blockLight = getBlockLight(pos);
        int combined = Math.max(skyLight, blockLight);
        
        // Cache the result
        if (lightCache.size() < CACHE_SIZE) {
            lightCache.put(pos, new CachedLightValue(combined, System.currentTimeMillis()));
        }
        
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
        // Process sky light updates
        processSkyLightUpdates();
        
        // Process block light updates
        processBlockLightUpdates();
        
        // Apply batched updates
        applyBatchedUpdates();
        
        // Clean up expired cache entries
        cleanupLightCache();
    }
    
    private void initializeSkyLight(CubeChunk cube) {
        int cubeY = cube.getCubeY();
        
        // For cubes at the top of the world, initialize with maximum sky light
        if (cubeY >= level.getMaxBuildHeight() / CubeChunk.SIZE - 1) {
            for (int x = 0; x < CubeChunk.SIZE; x++) {
                for (int z = 0; z < CubeChunk.SIZE; z++) {
                    for (int y = 0; y < CubeChunk.SIZE; y++) {
                        BlockState state = cube.getBlockState(x, y, z);
                        if (state.isAir()) {
                            cube.setSkyLight(x, y, z, (byte) MAX_LIGHT_LEVEL);
                        } else {
                            // Calculate light based on opacity
                            int opacity = state.getLightBlock(level, new BlockPos(x, y, z));
                            int lightLevel = Math.max(0, MAX_LIGHT_LEVEL - opacity);
                            cube.setSkyLight(x, y, z, (byte) lightLevel);
                        }
                    }
                }
            }
        } else {
            // For lower cubes, propagate from above
            propagateSkyLightFromAbove(cube);
        }
    }
    
    private void initializeBlockLight(CubeChunk cube) {
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int y = 0; y < CubeChunk.SIZE; y++) {
                for (int z = 0; z < CubeChunk.SIZE; z++) {
                    BlockState state = cube.getBlockState(x, y, z);
                    int emission = state.getLightEmission();
                    
                    if (emission > 0) {
                        cube.setBlockLight(x, y, z, (byte) emission);
                        // Queue for propagation
                        BlockPos worldPos = cube.getWorldPos(x, y, z);
                        blockLightUpdates.offer(new LightUpdate(worldPos, emission, LightType.BLOCK));
                    }
                }
            }
        }
    }
    
    private void propagateLightFromNeighbors(CubeChunk cube) {
        // Check all 6 neighboring cubes and propagate light
        int cubeX = cube.getCubeX();
        int cubeY = cube.getCubeY();
        int cubeZ = cube.getCubeZ();
        
        Direction[] directions = Direction.values();
        for (Direction direction : directions) {
            int neighborX = cubeX + direction.getStepX();
            int neighborY = cubeY + direction.getStepY();
            int neighborZ = cubeZ + direction.getStepZ();
            
            CubeChunk neighbor = chunkManager.getCube(neighborX, neighborY, neighborZ);
            if (neighbor != null && neighbor.isGenerated()) {
                propagateLightBetweenCubes(neighbor, cube, direction);
            }
        }
    }
    
    private void propagateLightBetweenCubes(CubeChunk fromCube, CubeChunk toCube, Direction direction) {
        // Propagate light across cube boundary
        Direction opposite = direction.getOpposite();
        
        for (int u = 0; u < CubeChunk.SIZE; u++) {
            for (int v = 0; v < CubeChunk.SIZE; v++) {
                // Calculate coordinates for both cubes
                int[] fromCoords = getBoundaryCoords(u, v, direction, true);
                int[] toCoords = getBoundaryCoords(u, v, direction, false);
                
                // Propagate sky light
                int fromSkyLight = fromCube.getSkyLight(fromCoords[0], fromCoords[1], fromCoords[2]);
                if (fromSkyLight > 1) {
                    BlockState toState = toCube.getBlockState(toCoords[0], toCoords[1], toCoords[2]);
                    int opacity = toState.getLightBlock(level, new BlockPos(toCoords[0], toCoords[1], toCoords[2]));
                    int newLight = Math.max(0, fromSkyLight - opacity - 1);
                    
                    if (newLight > toCube.getSkyLight(toCoords[0], toCoords[1], toCoords[2])) {
                        toCube.setSkyLight(toCoords[0], toCoords[1], toCoords[2], (byte) newLight);
                    }
                }
                
                // Propagate block light
                int fromBlockLight = fromCube.getBlockLight(fromCoords[0], fromCoords[1], fromCoords[2]);
                if (fromBlockLight > 1) {
                    int newLight = fromBlockLight - 1;
                    if (newLight > toCube.getBlockLight(toCoords[0], toCoords[1], toCoords[2])) {
                        toCube.setBlockLight(toCoords[0], toCoords[1], toCoords[2], (byte) newLight);
                    }
                }
            }
        }
    }
    
    private int[] getBoundaryCoords(int u, int v, Direction direction, boolean isSource) {
        int[] coords = new int[3];
        
        switch (direction) {
            case UP:
                coords[0] = u;
                coords[1] = isSource ? CubeChunk.SIZE - 1 : 0;
                coords[2] = v;
                break;
            case DOWN:
                coords[0] = u;
                coords[1] = isSource ? 0 : CubeChunk.SIZE - 1;
                coords[2] = v;
                break;
            case NORTH:
                coords[0] = u;
                coords[1] = v;
                coords[2] = isSource ? 0 : CubeChunk.SIZE - 1;
                break;
            case SOUTH:
                coords[0] = u;
                coords[1] = v;
                coords[2] = isSource ? CubeChunk.SIZE - 1 : 0;
                break;
            case WEST:
                coords[0] = isSource ? 0 : CubeChunk.SIZE - 1;
                coords[1] = v;
                coords[2] = u;
                break;
            case EAST:
                coords[0] = isSource ? CubeChunk.SIZE - 1 : 0;
                coords[1] = v;
                coords[2] = u;
                break;
        }
        
        return coords;
    }
    
    private void propagateSkyLightFromAbove(CubeChunk cube) {
        int cubeX = cube.getCubeX();
        int cubeY = cube.getCubeY();
        int cubeZ = cube.getCubeZ();
        
        CubeChunk aboveCube = chunkManager.getCube(cubeX, cubeY + 1, cubeZ);
        if (aboveCube == null || !aboveCube.isGenerated()) return;
        
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                int aboveSkyLight = aboveCube.getSkyLight(x, 0, z);
                
                for (int y = CubeChunk.SIZE - 1; y >= 0; y--) {
                    BlockState state = cube.getBlockState(x, y, z);
                    
                    if (state.isAir()) {
                        cube.setSkyLight(x, y, z, (byte) aboveSkyLight);
                    } else {
                        int opacity = state.getLightBlock(level, new BlockPos(x, y, z));
                        aboveSkyLight = Math.max(0, aboveSkyLight - opacity);
                        cube.setSkyLight(x, y, z, (byte) aboveSkyLight);
                    }
                    
                    if (aboveSkyLight <= 0) break;
                }
            }
        }
    }
    
    private void updateSkyLight(CubeChunk cube, int localX, int localY, int localZ, BlockPos worldPos) {
        // Add to update queue for processing
        skyLightUpdates.offer(new LightUpdate(worldPos, 0, LightType.SKY));
    }
    
    private void updateBlockLight(CubeChunk cube, int localX, int localY, int localZ, BlockPos worldPos, BlockState newState) {
        int newEmission = newState.getLightEmission();
        cube.setBlockLight(localX, localY, localZ, (byte) newEmission);
        
        // Add to update queue for propagation
        blockLightUpdates.offer(new LightUpdate(worldPos, newEmission, LightType.BLOCK));
    }
    
    private void processSkyLightUpdates() {
        while (!skyLightUpdates.isEmpty()) {
            LightUpdate update = skyLightUpdates.poll();
            propagateSkyLight(update.pos);
        }
    }
    
    private void processBlockLightUpdates() {
        while (!blockLightUpdates.isEmpty()) {
            LightUpdate update = blockLightUpdates.poll();
            propagateBlockLight(update.pos, update.lightLevel);
        }
    }
    
    private void propagateSkyLight(BlockPos pos) {
        // Implement flood-fill sky light propagation
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        
        queue.offer(pos);
        visited.add(pos);
        
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            CubeChunk cube = getCubeForPosition(current);
            if (cube == null) continue;
            
            int localX = current.getX() & 15;
            int localY = current.getY() & 15;
            int localZ = current.getZ() & 15;
            
            int currentSkyLight = cube.getSkyLight(localX, localY, localZ);
            
            // Propagate to neighbors
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.relative(direction);
                if (visited.contains(neighbor)) continue;
                
                CubeChunk neighborCube = getCubeForPosition(neighbor);
                if (neighborCube == null) continue;
                
                int nLocalX = neighbor.getX() & 15;
                int nLocalY = neighbor.getY() & 15;
                int nLocalZ = neighbor.getZ() & 15;
                
                BlockState neighborState = neighborCube.getBlockState(nLocalX, nLocalY, nLocalZ);
                int opacity = neighborState.getLightBlock(level, neighbor);
                int newLight = Math.max(0, currentSkyLight - opacity - 1);
                
                if (newLight > neighborCube.getSkyLight(nLocalX, nLocalY, nLocalZ)) {
                    neighborCube.setSkyLight(nLocalX, nLocalY, nLocalZ, (byte) newLight);
                    queue.offer(neighbor);
                    visited.add(neighbor);
                }
            }
        }
    }
    
    private void propagateBlockLight(BlockPos pos, int lightLevel) {
        // Implement flood-fill block light propagation
        Queue<LightNode> queue = new PriorityQueue<>((a, b) -> Integer.compare(b.lightLevel, a.lightLevel));
        Set<BlockPos> visited = new HashSet<>();
        
        queue.offer(new LightNode(pos, lightLevel));
        visited.add(pos);
        
        while (!queue.isEmpty()) {
            LightNode current = queue.poll();
            
            // Propagate to neighbors
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.pos.relative(direction);
                if (visited.contains(neighbor)) continue;
                
                CubeChunk neighborCube = getCubeForPosition(neighbor);
                if (neighborCube == null) continue;
                
                int nLocalX = neighbor.getX() & 15;
                int nLocalY = neighbor.getY() & 15;
                int nLocalZ = neighbor.getZ() & 15;
                
                int newLight = Math.max(0, current.lightLevel - 1);
                
                if (newLight > neighborCube.getBlockLight(nLocalX, nLocalY, nLocalZ)) {
                    neighborCube.setBlockLight(nLocalX, nLocalY, nLocalZ, (byte) newLight);
                    queue.offer(new LightNode(neighbor, newLight));
                    visited.add(neighbor);
                }
            }
        }
    }
    
    private void applyBatchedUpdates() {
        for (Map.Entry<Long, Set<BlockPos>> entry : pendingUpdates.entrySet()) {
            for (BlockPos pos : entry.getValue()) {
                // Apply batched updates
                CubeChunk cube = getCubeForPosition(pos);
                if (cube != null) {
                    cube.setDirty(true);
                }
            }
        }
        pendingUpdates.clear();
    }
    
    private void cleanupLightCache() {
        long currentTime = System.currentTimeMillis();
        lightCache.entrySet().removeIf(entry -> entry.getValue().isExpired(currentTime));
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
    
    public void shutdown() {
        lightingExecutor.shutdown();
    }
    
    // Helper classes
    private enum LightType {
        SKY, BLOCK
    }
    
    private static class LightUpdate {
        final BlockPos pos;
        final int lightLevel;
        final LightType type;
        
        LightUpdate(BlockPos pos, int lightLevel, LightType type) {
            this.pos = pos;
            this.lightLevel = lightLevel;
            this.type = type;
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
        final long timestamp;
        
        CachedLightValue(int lightLevel, long timestamp) {
            this.lightLevel = lightLevel;
            this.timestamp = timestamp;
        }
        
        boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }
        
        boolean isExpired(long currentTime) {
            return currentTime - timestamp > CACHE_EXPIRE_TIME;
        }
    }
} 