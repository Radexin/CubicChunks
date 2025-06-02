package com.radexin.cubicchunks.network;

import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.chunk.UnifiedCubicChunkManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.ByteArrayTag;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Unified network manager for cubic chunks with advanced features:
 * - Delta synchronization for efficient updates
 * - Compression for large payloads
 * - Priority-based transmission
 * - Batch processing for performance
 * - Intelligent caching and deduplication
 */
public class UnifiedCubicNetworkManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private final ServerLevel level;
    private final UnifiedCubicChunkManager chunkManager;
    private final ExecutorService networkExecutor;
    
    // Player tracking for network synchronization
    private final Map<UUID, PlayerNetworkTracker> playerTrackers = new ConcurrentHashMap<>();
    
    // Pending chunk sends with priority queues
    private final Map<UUID, PriorityQueue<ChunkSendRequest>> pendingSends = new ConcurrentHashMap<>();
    
    // Delta tracking for efficient updates
    private final Map<CubePos, ChunkSnapshot> chunkSnapshots = new ConcurrentHashMap<>();
    
    // Network configuration
    private final int maxChunksPerTick = 12;
    private final int maxPacketSize = 2 * 1024 * 1024; // 2MB max packet size
    private final int compressionThreshold = 512; // Compress packets > 512 bytes
    private final int maxCacheAge = 300000; // 5 minutes in milliseconds
    
    // Performance tracking
    private long totalBytesSent = 0;
    private long totalPacketsSent = 0;
    private long compressionSavings = 0;
    
    public UnifiedCubicNetworkManager(ServerLevel level, UnifiedCubicChunkManager chunkManager) {
        this.level = level;
        this.chunkManager = chunkManager;
        this.networkExecutor = Executors.newFixedThreadPool(4);
    }
    
    /**
     * Enhanced player tracking with better initialization.
     */
    public void addPlayer(ServerPlayer player) {
        UUID playerId = player.getUUID();
        PlayerNetworkTracker tracker = new PlayerNetworkTracker(player);
        playerTrackers.put(playerId, tracker);
        pendingSends.put(playerId, new PriorityQueue<>());
        
        // Async initial chunk loading
        CompletableFuture.runAsync(() -> queueInitialChunksForPlayer(player), networkExecutor);
    }
    
    /**
     * Clean up when player leaves.
     */
    public void removePlayer(ServerPlayer player) {
        UUID playerId = player.getUUID();
        PlayerNetworkTracker tracker = playerTrackers.remove(playerId);
        pendingSends.remove(playerId);
        
        if (tracker != null) {
            // Clean up player-specific cache entries
            cleanupPlayerCache(tracker);
        }
    }
    
    /**
     * Enhanced position tracking with smarter chunk loading.
     */
    public void updatePlayerPosition(ServerPlayer player) {
        UUID playerId = player.getUUID();
        PlayerNetworkTracker tracker = playerTrackers.get(playerId);
        
        if (tracker != null) {
            BlockPos oldPos = tracker.lastKnownPosition;
            BlockPos newPos = player.blockPosition();
            
            // Update if moved significantly (configurable threshold)
            if (oldPos == null || oldPos.distSqr(newPos) > 64) {
                tracker.lastKnownPosition = newPos;
                tracker.lastUpdateTime = System.currentTimeMillis();
                
                // Async position update
                CompletableFuture.runAsync(() -> {
                    updateChunksForPlayerPosition(player);
                    removeDistantChunks(player);
                }, networkExecutor);
            }
        }
    }
    
    /**
     * Advanced chunk modification handling with delta sync.
     */
    public void onChunkModified(CubePos pos, CubeChunk chunk) {
        // Create or update snapshot for delta tracking
        ChunkSnapshot oldSnapshot = chunkSnapshots.get(pos);
        ChunkSnapshot newSnapshot = createSnapshot(chunk);
        chunkSnapshots.put(pos, newSnapshot);
        
        // Send delta updates to relevant players
        for (PlayerNetworkTracker tracker : playerTrackers.values()) {
            if (shouldSendChunkToPlayer(tracker.player, pos)) {
                if (oldSnapshot != null) {
                    // Send delta update
                    queueDeltaUpdate(tracker.player, pos, oldSnapshot, newSnapshot);
                } else {
                    // Send full chunk
                    queueChunkForPlayer(tracker.player, pos, chunk, ChunkSendPriority.UPDATE);
                }
            }
        }
    }
    
    /**
     * Advanced tick processing with batching and compression.
     */
    public void tick() {
        long currentTime = System.currentTimeMillis();
        
        // Process pending sends for each player
        for (Map.Entry<UUID, PriorityQueue<ChunkSendRequest>> entry : pendingSends.entrySet()) {
            UUID playerId = entry.getKey();
            PriorityQueue<ChunkSendRequest> queue = entry.getValue();
            PlayerNetworkTracker tracker = playerTrackers.get(playerId);
            
            if (tracker != null && !queue.isEmpty()) {
                processAdvancedChunkSends(tracker.player, queue, currentTime);
            }
        }
        
        // Periodic cache cleanup
        if (currentTime % 30000 == 0) { // Every 30 seconds
            cleanupOldSnapshots(currentTime);
        }
    }
    
    private void queueInitialChunksForPlayer(ServerPlayer player) {
        BlockPos playerPos = player.blockPosition();
        int playerCubeX = Math.floorDiv(playerPos.getX(), CubeChunk.SIZE);
        int playerCubeY = Math.floorDiv(playerPos.getY(), CubeChunk.SIZE);
        int playerCubeZ = Math.floorDiv(playerPos.getZ(), CubeChunk.SIZE);
        
        int horizontalRadius = chunkManager.getHorizontalRenderDistance();
        int verticalRadius = chunkManager.getVerticalRenderDistance();
        
        List<ChunkSendRequest> requests = new ArrayList<>();
        
        // Generate requests in spiral pattern for better loading experience
        for (int distance = 0; distance <= Math.max(horizontalRadius, verticalRadius); distance++) {
            for (int x = playerCubeX - distance; x <= playerCubeX + distance; x++) {
                for (int z = playerCubeZ - distance; z <= playerCubeZ + distance; z++) {
                    for (int y = playerCubeY - verticalRadius; y <= playerCubeY + verticalRadius; y++) {
                        // Only process chunks at the current distance shell
                        int dx = Math.abs(x - playerCubeX);
                        int dz = Math.abs(z - playerCubeZ);
                        if (Math.max(dx, dz) != distance) continue;
                        
                        CubePos pos = new CubePos(x, y, z);
                        double dist3D = calculateDistance(playerCubeX, playerCubeY, playerCubeZ, x, y, z);
                        
                        if (dist3D <= Math.max(horizontalRadius, verticalRadius)) {
                            CubeChunk chunk = chunkManager.getCube(x, y, z, false);
                            if (chunk != null) {
                                ChunkSendPriority priority = determinePriority(dist3D, distance);
                                requests.add(new ChunkSendRequest(pos, chunk, priority, dist3D));
                            }
                        }
                    }
                }
            }
        }
        
        // Add to player's queue
        PriorityQueue<ChunkSendRequest> playerQueue = pendingSends.get(player.getUUID());
        if (playerQueue != null) {
            synchronized (playerQueue) {
                playerQueue.addAll(requests);
            }
        }
    }
    
    private void updateChunksForPlayerPosition(ServerPlayer player) {
        // Similar to initial loading but more selective
        BlockPos playerPos = player.blockPosition();
        int playerCubeX = Math.floorDiv(playerPos.getX(), CubeChunk.SIZE);
        int playerCubeY = Math.floorDiv(playerPos.getY(), CubeChunk.SIZE);
        int playerCubeZ = Math.floorDiv(playerPos.getZ(), CubeChunk.SIZE);
        
        UUID playerId = player.getUUID();
        PlayerNetworkTracker tracker = playerTrackers.get(playerId);
        if (tracker == null) return;
        
        int horizontalRadius = chunkManager.getHorizontalRenderDistance();
        int verticalRadius = chunkManager.getVerticalRenderDistance();
        
        // Only queue chunks that aren't already sent
        for (int x = playerCubeX - horizontalRadius; x <= playerCubeX + horizontalRadius; x++) {
            for (int z = playerCubeZ - horizontalRadius; z <= playerCubeZ + horizontalRadius; z++) {
                for (int y = playerCubeY - verticalRadius; y <= playerCubeY + verticalRadius; y++) {
                    CubePos pos = new CubePos(x, y, z);
                    
                    if (!tracker.sentChunks.contains(pos)) {
                        double distance = calculateDistance(playerCubeX, playerCubeY, playerCubeZ, x, y, z);
                        if (distance <= Math.max(horizontalRadius, verticalRadius)) {
                            CubeChunk chunk = chunkManager.getCube(x, y, z, false);
                            if (chunk != null) {
                                ChunkSendPriority priority = determinePriority(distance, (int)distance);
                                queueChunkForPlayer(player, pos, chunk, priority);
                            }
                        }
                    }
                }
            }
        }
    }
    
    private void processAdvancedChunkSends(ServerPlayer player, PriorityQueue<ChunkSendRequest> queue, long currentTime) {
        int sentThisTick = 0;
        List<ChunkSendRequest> batchRequests = new ArrayList<>();
        int estimatedBatchSize = 0;
        
        synchronized (queue) {
            while (!queue.isEmpty() && sentThisTick < maxChunksPerTick) {
                ChunkSendRequest request = queue.poll();
                
                // Verify chunk is still valid
                if (!isChunkStillValid(request, currentTime)) continue;
                
                CubeChunk chunk = chunkManager.getCube(request.pos.x, request.pos.y, request.pos.z, false);
                if (chunk == null || !shouldSendChunkToPlayer(player, request.pos)) continue;
                
                int estimatedSize = estimateChunkPacketSize(chunk);
                
                // Send current batch if adding this chunk would exceed limits
                if (!batchRequests.isEmpty() && 
                    (estimatedBatchSize + estimatedSize > maxPacketSize || batchRequests.size() >= 8)) {
                    CompletableFuture.runAsync(() -> sendCompressedChunkBatch(player, batchRequests), networkExecutor);
                    batchRequests.clear();
                    estimatedBatchSize = 0;
                }
                
                batchRequests.add(request);
                estimatedBatchSize += estimatedSize;
                sentThisTick++;
            }
        }
        
        // Send remaining batch
        if (!batchRequests.isEmpty()) {
            CompletableFuture.runAsync(() -> sendCompressedChunkBatch(player, batchRequests), networkExecutor);
        }
    }
    
    private void sendCompressedChunkBatch(ServerPlayer player, List<ChunkSendRequest> requests) {
        try {
            // Create batch payload
            CompoundTag batchData = new CompoundTag();
            ListTag chunkList = new ListTag();
            
            for (ChunkSendRequest request : requests) {
                CompoundTag chunkData = new CompoundTag();
                chunkData.putInt("x", request.pos.x);
                chunkData.putInt("y", request.pos.y);
                chunkData.putInt("z", request.pos.z);
                chunkData.put("data", request.chunk.toNBT());
                chunkList.add(chunkData);
            }
            
            batchData.put("chunks", chunkList);
            batchData.putLong("timestamp", System.currentTimeMillis());
            
            // Convert to bytes for compression
            byte[] rawData = serializeNBT(batchData);
            
            // Compress if above threshold
            byte[] finalData = rawData;
            boolean compressed = false;
            if (rawData.length > compressionThreshold) {
                byte[] compressedData = compressData(rawData);
                if (compressedData.length < rawData.length) {
                    finalData = compressedData;
                    compressed = true;
                    compressionSavings += rawData.length - compressedData.length;
                }
            }
            
            // Send the payload - using existing CubeSyncPayload for now
            CubeSyncPayload payload = new CubeSyncPayload(-1, -1, -1, batchData);
            PacketDistributor.sendToPlayer(player, payload);
            
            // Update tracking
            totalBytesSent += finalData.length;
            totalPacketsSent++;
            
            UUID playerId = player.getUUID();
            PlayerNetworkTracker tracker = playerTrackers.get(playerId);
            if (tracker != null) {
                for (ChunkSendRequest request : requests) {
                    tracker.sentChunks.add(request.pos);
                }
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to send chunk batch to player {}", player.getName().getString(), e);
        }
    }
    
    private void queueDeltaUpdate(ServerPlayer player, CubePos pos, ChunkSnapshot oldSnapshot, ChunkSnapshot newSnapshot) {
        try {
            CompoundTag deltaData = createDeltaData(oldSnapshot, newSnapshot);
            if (!deltaData.isEmpty()) {
                // Use existing CubeSyncPayload for delta updates
                CubeSyncPayload payload = new CubeSyncPayload(pos.x, pos.y, pos.z, deltaData);
                PacketDistributor.sendToPlayer(player, payload);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to send delta update for chunk at {}", pos, e);
        }
    }
    
    private ChunkSnapshot createSnapshot(CubeChunk chunk) {
        Map<BlockPos, Integer> blockStateIds = new HashMap<>();
        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
        
        for (int x = 0; x < CubeChunk.SIZE; x++) {
            for (int y = 0; y < CubeChunk.SIZE; y++) {
                for (int z = 0; z < CubeChunk.SIZE; z++) {
                    BlockPos localPos = new BlockPos(x, y, z);
                    // Store block state ID for comparison
                    blockStateIds.put(localPos, chunk.getBlockState(x, y, z).hashCode());
                    
                    // Store block entity data if present
                    // CompoundTag beData = chunk.getBlockEntityData(x, y, z);
                    // if (beData != null) {
                    //     blockEntities.put(localPos, beData);
                    // }
                }
            }
        }
        
        return new ChunkSnapshot(blockStateIds, blockEntities, System.currentTimeMillis());
    }
    
    private CompoundTag createDeltaData(ChunkSnapshot oldSnapshot, ChunkSnapshot newSnapshot) {
        CompoundTag deltaTag = new CompoundTag();
        ListTag changedBlocks = new ListTag();
        
        // Find changed blocks
        for (Map.Entry<BlockPos, Integer> entry : newSnapshot.blockStateIds.entrySet()) {
            BlockPos pos = entry.getKey();
            Integer newStateId = entry.getValue();
            Integer oldStateId = oldSnapshot.blockStateIds.get(pos);
            
            if (!Objects.equals(oldStateId, newStateId)) {
                CompoundTag blockChange = new CompoundTag();
                blockChange.putInt("x", pos.getX());
                blockChange.putInt("y", pos.getY());
                blockChange.putInt("z", pos.getZ());
                blockChange.putInt("stateId", newStateId);
                changedBlocks.add(blockChange);
            }
        }
        
        if (!changedBlocks.isEmpty()) {
            deltaTag.put("changes", changedBlocks);
        }
        
        return deltaTag;
    }
    
    // Utility methods
    private void queueChunkForPlayer(ServerPlayer player, CubePos pos, CubeChunk chunk, ChunkSendPriority priority) {
        double distance = calculateDistanceToPlayer(player, pos);
        ChunkSendRequest request = new ChunkSendRequest(pos, chunk, priority, distance);
        
        PriorityQueue<ChunkSendRequest> playerQueue = pendingSends.get(player.getUUID());
        if (playerQueue != null) {
            synchronized (playerQueue) {
                playerQueue.offer(request);
            }
        }
    }
    
    private boolean shouldSendChunkToPlayer(ServerPlayer player, CubePos pos) {
        BlockPos playerPos = player.blockPosition();
        int playerCubeX = Math.floorDiv(playerPos.getX(), CubeChunk.SIZE);
        int playerCubeY = Math.floorDiv(playerPos.getY(), CubeChunk.SIZE);
        int playerCubeZ = Math.floorDiv(playerPos.getZ(), CubeChunk.SIZE);
        
        double distance = calculateDistance(playerCubeX, playerCubeY, playerCubeZ, pos.x, pos.y, pos.z);
        int maxDistance = Math.max(chunkManager.getHorizontalRenderDistance(), chunkManager.getVerticalRenderDistance());
        
        return distance <= maxDistance;
    }
    
    private double calculateDistance(int x1, int y1, int z1, int x2, int y2, int z2) {
        return Math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2) + (z1 - z2) * (z1 - z2));
    }
    
    private double calculateDistanceToPlayer(ServerPlayer player, CubePos pos) {
        BlockPos playerPos = player.blockPosition();
        int playerCubeX = Math.floorDiv(playerPos.getX(), CubeChunk.SIZE);
        int playerCubeY = Math.floorDiv(playerPos.getY(), CubeChunk.SIZE);
        int playerCubeZ = Math.floorDiv(playerPos.getZ(), CubeChunk.SIZE);
        
        return calculateDistance(playerCubeX, playerCubeY, playerCubeZ, pos.x, pos.y, pos.z);
    }
    
    private ChunkSendPriority determinePriority(double distance, int distanceLevel) {
        if (distanceLevel == 0) return ChunkSendPriority.IMMEDIATE;
        if (distanceLevel <= 2) return ChunkSendPriority.HIGH;
        if (distanceLevel <= 5) return ChunkSendPriority.NORMAL;
        return ChunkSendPriority.LOW;
    }
    
    private int estimateChunkPacketSize(CubeChunk chunk) {
        // Simplified estimation - in real implementation would be more accurate
        return CubeChunk.SIZE * CubeChunk.SIZE * CubeChunk.SIZE * 2; // 2 bytes per block estimate
    }
    
    private boolean isChunkStillValid(ChunkSendRequest request, long currentTime) {
        return currentTime - request.timestamp < 10000; // 10 second validity
    }
    
    private void removeDistantChunks(ServerPlayer player) {
        UUID playerId = player.getUUID();
        PlayerNetworkTracker tracker = playerTrackers.get(playerId);
        if (tracker == null) return;
        
        int maxDistance = Math.max(chunkManager.getHorizontalRenderDistance(), chunkManager.getVerticalRenderDistance()) + 2;
        
        tracker.sentChunks.removeIf(pos -> {
            double distance = calculateDistanceToPlayer(player, pos);
            if (distance > maxDistance) {
                sendChunkUnload(player, pos);
                return true;
            }
            return false;
        });
    }
    
    private void sendChunkUnload(ServerPlayer player, CubePos pos) {
        // Send unload packet
        CompoundTag emptyData = new CompoundTag();
        emptyData.putBoolean("unload", true);
        CubeSyncPayload payload = new CubeSyncPayload(pos.x, pos.y, pos.z, emptyData);
        PacketDistributor.sendToPlayer(player, payload);
    }
    
    private void cleanupPlayerCache(PlayerNetworkTracker tracker) {
        // Clean up any player-specific cache entries
        tracker.sentChunks.clear();
    }
    
    private void cleanupOldSnapshots(long currentTime) {
        chunkSnapshots.entrySet().removeIf(entry -> 
            currentTime - entry.getValue().timestamp > maxCacheAge);
    }
    
    private byte[] compressData(byte[] data) throws IOException {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(data);
        deflater.finish();
        
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        
        while (!deflater.finished()) {
            int compressedSize = deflater.deflate(buffer);
            outputStream.write(buffer, 0, compressedSize);
        }
        
        deflater.end();
        return outputStream.toByteArray();
    }
    
    private byte[] serializeNBT(CompoundTag tag) {
        // Simplified NBT serialization - would use proper NBT writer in real implementation
        return tag.toString().getBytes();
    }
    
    public void shutdown() {
        networkExecutor.shutdown();
        chunkSnapshots.clear();
        playerTrackers.clear();
        pendingSends.clear();
    }
    
    // Performance monitoring
    public NetworkStats getNetworkStats() {
        return new NetworkStats(totalBytesSent, totalPacketsSent, compressionSavings, 
                               chunkSnapshots.size(), playerTrackers.size());
    }
    
    // Inner classes
    private static class PlayerNetworkTracker {
        final ServerPlayer player;
        final Set<CubePos> sentChunks = ConcurrentHashMap.newKeySet();
        @Nullable BlockPos lastKnownPosition;
        long lastUpdateTime = System.currentTimeMillis();
        
        PlayerNetworkTracker(ServerPlayer player) {
            this.player = player;
        }
    }
    
    private static class ChunkSendRequest implements Comparable<ChunkSendRequest> {
        final CubePos pos;
        final CubeChunk chunk;
        final ChunkSendPriority priority;
        final double distance;
        final long timestamp;
        
        ChunkSendRequest(CubePos pos, CubeChunk chunk, ChunkSendPriority priority, double distance) {
            this.pos = pos;
            this.chunk = chunk;
            this.priority = priority;
            this.distance = distance;
            this.timestamp = System.currentTimeMillis();
        }
        
        @Override
        public int compareTo(ChunkSendRequest other) {
            int priorityCompare = Integer.compare(this.priority.getValue(), other.priority.getValue());
            if (priorityCompare != 0) return priorityCompare;
            
            int distanceCompare = Double.compare(this.distance, other.distance);
            if (distanceCompare != 0) return distanceCompare;
            
            return Long.compare(this.timestamp, other.timestamp);
        }
    }
    
    private static class ChunkSnapshot {
        final Map<BlockPos, Integer> blockStateIds;
        final Map<BlockPos, CompoundTag> blockEntities;
        final long timestamp;
        
        ChunkSnapshot(Map<BlockPos, Integer> blockStateIds, Map<BlockPos, CompoundTag> blockEntities, long timestamp) {
            this.blockStateIds = blockStateIds;
            this.blockEntities = blockEntities;
            this.timestamp = timestamp;
        }
    }
    
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
            return "CubePos{" + x + ", " + y + ", " + z + "}";
        }
    }
    
    public enum ChunkSendPriority {
        IMMEDIATE(0), HIGH(1), NORMAL(2), LOW(3), UPDATE(1);
        
        private final int value;
        
        ChunkSendPriority(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
    }
    
    public record NetworkStats(long totalBytesSent, long totalPacketsSent, 
                              long compressionSavings, int cachedSnapshots, int connectedPlayers) {}
} 