package com.radexin.cubicchunks.world;

import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.chunk.CubeColumn;
import com.radexin.cubicchunks.gen.CubeChunkGenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.registries.Registries;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Manages loading, unloading, and generation of cubic chunks.
 * Handles 3D chunk coordinates and player-based chunk loading priorities.
 */
public class CubicChunkManager {
    private final ServerLevel level;
    private final CubeChunkGenerator generator;
    private final CubicChunkStorage storage;
    private final CubicLightEngine lightEngine;
    
    // Loaded chunks mapped by 3D coordinates
    private final Map<CubePos, CubeChunk> loadedChunks = new ConcurrentHashMap<>();
    private final Map<ChunkPos, CubeColumn> loadedColumns = new ConcurrentHashMap<>();
    
    // Generation and loading queues
    private final PriorityQueue<ChunkLoadRequest> loadQueue = new PriorityQueue<>();
    private final Set<CubePos> generatingChunks = ConcurrentHashMap.newKeySet();
    
    // Player tracking for chunk priorities
    private final Map<UUID, PlayerChunkTracker> playerTrackers = new ConcurrentHashMap<>();
    
    // Configuration
    private int horizontalRenderDistance = 8;
    private int verticalRenderDistance = 4;
    private int maxLoadedChunks = 2048;
    
    public CubicChunkManager(ServerLevel level, CubeChunkGenerator generator, CubicChunkStorage storage) {
        this.level = level;
        this.generator = generator;
        this.storage = storage;
        this.lightEngine = new CubicLightEngine(new CubeWorld(generator));
    }
    
    /**
     * Gets a cube at the specified 3D coordinates, optionally creating/loading it.
     */
    @Nullable
    public CubeChunk getCube(int cubeX, int cubeY, int cubeZ, boolean load) {
        CubePos pos = new CubePos(cubeX, cubeY, cubeZ);
        CubeChunk chunk = loadedChunks.get(pos);
        
        if (chunk == null && load) {
            return loadCubeAsync(pos).join();
        }
        
        return chunk;
    }
    
    /**
     * Asynchronously loads a cube, either from storage or by generating it.
     */
    public CompletableFuture<CubeChunk> loadCubeAsync(CubePos pos) {
        // Check if already loaded
        CubeChunk existing = loadedChunks.get(pos);
        if (existing != null) {
            return CompletableFuture.completedFuture(existing);
        }
        
        // Check if already being generated
        if (generatingChunks.contains(pos)) {
            return CompletableFuture.supplyAsync(() -> {
                // Wait for generation to complete
                while (generatingChunks.contains(pos)) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return loadedChunks.get(pos);
            });
        }
        
        generatingChunks.add(pos);
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Try to load from storage first
                CompoundTag chunkData = storage.loadChunk(pos);
                CubeChunk chunk;
                
                if (chunkData != null) {
                    // Load from saved data
                    Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
                    chunk = CubeChunk.fromNBT(chunkData, biomeRegistry);
                } else {
                    // Generate new chunk
                    Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
                    chunk = new CubeChunk(pos.x, pos.y, pos.z, biomeRegistry);
                    generator.generateCube(chunk);
                    
                    // Initialize lighting
                    initializeLighting(chunk);
                }
                
                // Add to loaded chunks
                loadedChunks.put(pos, chunk);
                
                // Add to column
                ChunkPos columnPos = new ChunkPos(pos.x, pos.z);
                CubeColumn column = loadedColumns.computeIfAbsent(columnPos, cp -> new CubeColumn(cp.x, cp.z));
                column.loadCube(pos.y, chunk);
                
                return chunk;
            } finally {
                generatingChunks.remove(pos);
            }
        });
    }
    
    /**
     * Unloads a cube and saves it to storage if dirty.
     */
    public void unloadCube(CubePos pos) {
        CubeChunk chunk = loadedChunks.remove(pos);
        if (chunk == null) return;
        
        // Save if dirty
        if (chunk.isDirty()) {
            storage.saveChunk(pos, chunk.toNBT());
            chunk.setDirty(false);
        }
        
        // Remove from column
        ChunkPos columnPos = new ChunkPos(pos.x, pos.z);
        CubeColumn column = loadedColumns.get(columnPos);
        if (column != null) {
            column.unloadCube(pos.y);
            if (!column.hasLoadedCubes()) {
                loadedColumns.remove(columnPos);
            }
        }
    }
    
    /**
     * Updates chunk loading for a player based on their position and render distance.
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
        
        // Queue chunks for loading based on distance
        Set<CubePos> chunksInRange = getChunksInRange(playerCubeX, playerCubeY, playerCubeZ);
        
        for (CubePos pos : chunksInRange) {
            if (!loadedChunks.containsKey(pos) && !generatingChunks.contains(pos)) {
                double distance = calculateDistance(playerCubeX, playerCubeY, playerCubeZ, pos.x, pos.y, pos.z);
                loadQueue.offer(new ChunkLoadRequest(pos, distance, playerId));
            }
        }
        
        // Remove far chunks
        tracker.unloadFarChunks(this);
    }
    
    /**
     * Removes a player from chunk tracking when they disconnect.
     */
    public void removePlayer(ServerPlayer player) {
        playerTrackers.remove(player.getUUID());
    }
    
    /**
     * Processes the chunk loading queue.
     */
    public void tick() {
        // Process chunk loading queue
        int processed = 0;
        while (!loadQueue.isEmpty() && processed < 4) { // Limit chunks per tick
            ChunkLoadRequest request = loadQueue.poll();
            if (!loadedChunks.containsKey(request.pos)) {
                loadCubeAsync(request.pos);
                processed++;
            }
        }
        
        // Unload chunks if we have too many loaded
        if (loadedChunks.size() > maxLoadedChunks) {
            unloadDistantChunks();
        }
        
        // Tick loaded chunks
        for (CubeChunk chunk : loadedChunks.values()) {
            chunk.tick(level);
        }
    }
    
    private Set<CubePos> getChunksInRange(int centerX, int centerY, int centerZ) {
        Set<CubePos> chunks = new HashSet<>();
        
        for (int x = centerX - horizontalRenderDistance; x <= centerX + horizontalRenderDistance; x++) {
            for (int z = centerZ - horizontalRenderDistance; z <= centerZ + horizontalRenderDistance; z++) {
                for (int y = centerY - verticalRenderDistance; y <= centerY + verticalRenderDistance; y++) {
                    double distance = calculateDistance(centerX, centerY, centerZ, x, y, z);
                    if (distance <= Math.max(horizontalRenderDistance, verticalRenderDistance)) {
                        chunks.add(new CubePos(x, y, z));
                    }
                }
            }
        }
        
        return chunks;
    }
    
    private double calculateDistance(int x1, int y1, int z1, int x2, int y2, int z2) {
        int dx = x1 - x2;
        int dy = y1 - y2;
        int dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    private void unloadDistantChunks() {
        // Find chunks to unload based on player distances
        List<CubePos> chunksToUnload = new ArrayList<>();
        
        for (Map.Entry<CubePos, CubeChunk> entry : loadedChunks.entrySet()) {
            CubePos pos = entry.getKey();
            boolean shouldKeep = false;
            
            // Check if any player is close enough to this chunk
            for (PlayerChunkTracker tracker : playerTrackers.values()) {
                double distance = calculateDistance(tracker.cubeX, tracker.cubeY, tracker.cubeZ, pos.x, pos.y, pos.z);
                if (distance <= Math.max(horizontalRenderDistance, verticalRenderDistance) + 2) { // Extra margin
                    shouldKeep = true;
                    break;
                }
            }
            
            if (!shouldKeep) {
                chunksToUnload.add(pos);
            }
        }
        
        // Unload oldest chunks first
        chunksToUnload.sort((a, b) -> {
            CubeChunk chunkA = loadedChunks.get(a);
            CubeChunk chunkB = loadedChunks.get(b);
            return Long.compare(chunkA.getInhabitedTime(), chunkB.getInhabitedTime());
        });
        
        // Unload up to 25% of excess chunks per tick
        int toUnload = Math.min(chunksToUnload.size(), (loadedChunks.size() - maxLoadedChunks + 3) / 4);
        for (int i = 0; i < toUnload; i++) {
            unloadCube(chunksToUnload.get(i));
        }
    }
    
    private void initializeLighting(CubeChunk chunk) {
        // Initialize basic lighting for the chunk
        // This is a simplified implementation - full lighting would require proper sky light calculation
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int y = 0; y < CubeChunk.SIZE; y++) {
                    if (chunk.getBlockState(x, y, z).isAir()) {
                        // Set sky light for air blocks (simplified)
                        int worldY = chunk.getCubeY() * CubeChunk.SIZE + y;
                        if (worldY > 64) {
                            chunk.setSkyLight(x, y, z, (byte) 15);
                        }
                    }
                }
            }
        }
    }
    
    // Getters and setters
    public int getHorizontalRenderDistance() { return horizontalRenderDistance; }
    public void setHorizontalRenderDistance(int distance) { this.horizontalRenderDistance = distance; }
    
    public int getVerticalRenderDistance() { return verticalRenderDistance; }
    public void setVerticalRenderDistance(int distance) { this.verticalRenderDistance = distance; }
    
    public Collection<CubeChunk> getLoadedChunks() { return loadedChunks.values(); }
    public int getLoadedChunkCount() { return loadedChunks.size(); }
    
    /**
     * Represents a 3D chunk position.
     */
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
    
    /**
     * Represents a chunk loading request with priority.
     */
    private static class ChunkLoadRequest implements Comparable<ChunkLoadRequest> {
        final CubePos pos;
        final double distance;
        final UUID playerId;
        final long timestamp;
        
        ChunkLoadRequest(CubePos pos, double distance, UUID playerId) {
            this.pos = pos;
            this.distance = distance;
            this.playerId = playerId;
            this.timestamp = System.currentTimeMillis();
        }
        
        @Override
        public int compareTo(ChunkLoadRequest other) {
            // Prioritize by distance (closer chunks first)
            return Double.compare(this.distance, other.distance);
        }
    }
    
    /**
     * Tracks a player's position and loaded chunks.
     */
    private static class PlayerChunkTracker {
        final ServerPlayer player;
        int cubeX, cubeY, cubeZ;
        final Set<CubePos> loadedChunks = new HashSet<>();
        
        PlayerChunkTracker(ServerPlayer player) {
            this.player = player;
            BlockPos pos = player.blockPosition();
            updatePosition(
                Math.floorDiv(pos.getX(), CubeChunk.SIZE),
                Math.floorDiv(pos.getY(), CubeChunk.SIZE),
                Math.floorDiv(pos.getZ(), CubeChunk.SIZE)
            );
        }
        
        void updatePosition(int newX, int newY, int newZ) {
            this.cubeX = newX;
            this.cubeY = newY;
            this.cubeZ = newZ;
        }
        
        void unloadFarChunks(CubicChunkManager manager) {
            loadedChunks.removeIf(pos -> {
                double distance = manager.calculateDistance(cubeX, cubeY, cubeZ, pos.x, pos.y, pos.z);
                return distance > Math.max(manager.horizontalRenderDistance, manager.verticalRenderDistance) + 1;
            });
        }
    }
} 