package com.radexin.cubicchunks.network;

import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.world.CubicChunkManager;
import com.radexin.cubicchunks.world.CubicChunkManager.CubePos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages network synchronization for cubic chunks.
 * Handles prioritization, batching, and efficient transmission of 3D chunk data.
 */
public class CubicNetworkManager {
    private final ServerLevel level;
    private final CubicChunkManager chunkManager;
    
    // Player tracking for network synchronization
    private final Map<UUID, PlayerNetworkTracker> playerTrackers = new ConcurrentHashMap<>();
    
    // Pending chunk sends
    private final Map<UUID, PriorityQueue<ChunkSendRequest>> pendingSends = new ConcurrentHashMap<>();
    
    // Network configuration
    private final int maxChunksPerTick = 8;
    private final int maxPacketSize = 1024 * 1024; // 1MB max packet size
    
    public CubicNetworkManager(ServerLevel level, CubicChunkManager chunkManager) {
        this.level = level;
        this.chunkManager = chunkManager;
    }
    
    /**
     * Called when a player joins to start tracking them for chunk updates.
     */
    public void addPlayer(ServerPlayer player) {
        UUID playerId = player.getUUID();
        playerTrackers.put(playerId, new PlayerNetworkTracker(player));
        pendingSends.put(playerId, new PriorityQueue<>());
        
        // Queue initial chunks around player
        queueChunksAroundPlayer(player);
    }
    
    /**
     * Called when a player leaves to stop tracking them.
     */
    public void removePlayer(ServerPlayer player) {
        UUID playerId = player.getUUID();
        playerTrackers.remove(playerId);
        pendingSends.remove(playerId);
    }
    
    /**
     * Called when a player moves to update their chunk loading.
     */
    public void updatePlayerPosition(ServerPlayer player) {
        UUID playerId = player.getUUID();
        PlayerNetworkTracker tracker = playerTrackers.get(playerId);
        
        if (tracker != null) {
            BlockPos oldPos = tracker.lastKnownPosition;
            BlockPos newPos = player.blockPosition();
            
            // Only update if player moved significantly (more than 8 blocks)
            if (oldPos == null || oldPos.distSqr(newPos) > 64) {
                tracker.lastKnownPosition = newPos;
                queueChunksAroundPlayer(player);
                removeDistantChunks(player);
            }
        }
    }
    
    /**
     * Called when a chunk is modified to send updates to nearby players.
     */
    public void onChunkModified(CubePos pos, CubeChunk chunk) {
        for (PlayerNetworkTracker tracker : playerTrackers.values()) {
            if (shouldSendChunkToPlayer(tracker.player, pos)) {
                queueChunkForPlayer(tracker.player, pos, chunk, ChunkSendPriority.UPDATE);
            }
        }
    }
    
    /**
     * Processes pending chunk sends for all players.
     */
    public void tick() {
        for (Map.Entry<UUID, PriorityQueue<ChunkSendRequest>> entry : pendingSends.entrySet()) {
            UUID playerId = entry.getKey();
            PriorityQueue<ChunkSendRequest> queue = entry.getValue();
            PlayerNetworkTracker tracker = playerTrackers.get(playerId);
            
            if (tracker != null && !queue.isEmpty()) {
                processChunkSendsForPlayer(tracker.player, queue);
            }
        }
    }
    
    private void queueChunksAroundPlayer(ServerPlayer player) {
        BlockPos playerPos = player.blockPosition();
        int playerCubeX = Math.floorDiv(playerPos.getX(), CubeChunk.SIZE);
        int playerCubeY = Math.floorDiv(playerPos.getY(), CubeChunk.SIZE);
        int playerCubeZ = Math.floorDiv(playerPos.getZ(), CubeChunk.SIZE);
        
        int horizontalRadius = chunkManager.getHorizontalRenderDistance();
        int verticalRadius = chunkManager.getVerticalRenderDistance();
        
        List<ChunkSendRequest> requests = new ArrayList<>();
        
        for (int x = playerCubeX - horizontalRadius; x <= playerCubeX + horizontalRadius; x++) {
            for (int z = playerCubeZ - horizontalRadius; z <= playerCubeZ + horizontalRadius; z++) {
                for (int y = playerCubeY - verticalRadius; y <= playerCubeY + verticalRadius; y++) {
                    CubePos pos = new CubePos(x, y, z);
                    
                    // Calculate 3D distance for prioritization
                    double distance = calculateDistance(playerCubeX, playerCubeY, playerCubeZ, x, y, z);
                    
                    // Only queue chunks within range
                    if (distance <= Math.max(horizontalRadius, verticalRadius)) {
                        CubeChunk chunk = chunkManager.getCube(x, y, z, false);
                        if (chunk != null) {
                            ChunkSendPriority priority = determinePriority(distance, horizontalRadius, verticalRadius);
                            requests.add(new ChunkSendRequest(pos, chunk, priority, distance));
                        }
                    }
                }
            }
        }
        
        // Add all requests to the player's queue
        PriorityQueue<ChunkSendRequest> playerQueue = pendingSends.get(player.getUUID());
        if (playerQueue != null) {
            playerQueue.addAll(requests);
        }
    }
    
    private void removeDistantChunks(ServerPlayer player) {
        UUID playerId = player.getUUID();
        PlayerNetworkTracker tracker = playerTrackers.get(playerId);
        
        if (tracker != null) {
            BlockPos playerPos = player.blockPosition();
            int playerCubeX = Math.floorDiv(playerPos.getX(), CubeChunk.SIZE);
            int playerCubeY = Math.floorDiv(playerPos.getY(), CubeChunk.SIZE);
            int playerCubeZ = Math.floorDiv(playerPos.getZ(), CubeChunk.SIZE);
            
            int maxDistance = Math.max(chunkManager.getHorizontalRenderDistance(), chunkManager.getVerticalRenderDistance()) + 2;
            
            // Remove chunks that are now too far
            tracker.sentChunks.removeIf(pos -> {
                double distance = calculateDistance(playerCubeX, playerCubeY, playerCubeZ, pos.x, pos.y, pos.z);
                if (distance > maxDistance) {
                    // Send unload packet
                    sendChunkUnload(player, pos);
                    return true;
                }
                return false;
            });
        }
    }
    
    private void processChunkSendsForPlayer(ServerPlayer player, PriorityQueue<ChunkSendRequest> queue) {
        int sentThisTick = 0;
        List<ChunkSendRequest> batchRequests = new ArrayList<>();
        int batchSize = 0;
        
        while (!queue.isEmpty() && sentThisTick < maxChunksPerTick) {
            ChunkSendRequest request = queue.poll();
            
            // Verify chunk is still loaded
            CubeChunk chunk = chunkManager.getCube(request.pos.x, request.pos.y, request.pos.z, false);
            if (chunk == null) continue;
            
            // Check if we should still send this chunk
            if (!shouldSendChunkToPlayer(player, request.pos)) continue;
            
            // Estimate packet size (simplified)
            int estimatedSize = estimateChunkPacketSize(chunk);
            
            // Send batch if adding this chunk would exceed max packet size
            if (!batchRequests.isEmpty() && batchSize + estimatedSize > maxPacketSize) {
                sendChunkBatch(player, batchRequests);
                batchRequests.clear();
                batchSize = 0;
            }
            
            batchRequests.add(request);
            batchSize += estimatedSize;
            sentThisTick++;
        }
        
        // Send remaining batch
        if (!batchRequests.isEmpty()) {
            sendChunkBatch(player, batchRequests);
        }
    }
    
    private void sendChunkBatch(ServerPlayer player, List<ChunkSendRequest> requests) {
        for (ChunkSendRequest request : requests) {
            // Send individual chunk (in a real implementation, you might batch these)
            CubeSyncPayload payload = new CubeSyncPayload(
                request.pos.x, 
                request.pos.y, 
                request.pos.z, 
                request.chunk.toNBT()
            );
            
            PacketDistributor.sendToPlayer(player, payload);
            
            // Mark as sent
            UUID playerId = player.getUUID();
            PlayerNetworkTracker tracker = playerTrackers.get(playerId);
            if (tracker != null) {
                tracker.sentChunks.add(request.pos);
            }
        }
    }
    
    private void sendChunkUnload(ServerPlayer player, CubePos pos) {
        // Create an unload payload (would need to extend CubeSyncPayload or create new packet)
        // For now, just send an empty chunk
        CubeSyncPayload payload = new CubeSyncPayload(pos.x, pos.y, pos.z, new net.minecraft.nbt.CompoundTag());
        PacketDistributor.sendToPlayer(player, payload);
    }
    
    private void queueChunkForPlayer(ServerPlayer player, CubePos pos, CubeChunk chunk, ChunkSendPriority priority) {
        UUID playerId = player.getUUID();
        PriorityQueue<ChunkSendRequest> queue = pendingSends.get(playerId);
        
        if (queue != null) {
            BlockPos playerPos = player.blockPosition();
            int playerCubeX = Math.floorDiv(playerPos.getX(), CubeChunk.SIZE);
            int playerCubeY = Math.floorDiv(playerPos.getY(), CubeChunk.SIZE);
            int playerCubeZ = Math.floorDiv(playerPos.getZ(), CubeChunk.SIZE);
            
            double distance = calculateDistance(playerCubeX, playerCubeY, playerCubeZ, pos.x, pos.y, pos.z);
            queue.offer(new ChunkSendRequest(pos, chunk, priority, distance));
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
        int dx = x1 - x2;
        int dy = y1 - y2;
        int dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    private ChunkSendPriority determinePriority(double distance, int horizontalRadius, int verticalRadius) {
        double maxRadius = Math.max(horizontalRadius, verticalRadius);
        
        if (distance <= maxRadius * 0.3) {
            return ChunkSendPriority.IMMEDIATE;
        } else if (distance <= maxRadius * 0.6) {
            return ChunkSendPriority.HIGH;
        } else if (distance <= maxRadius * 0.8) {
            return ChunkSendPriority.NORMAL;
        } else {
            return ChunkSendPriority.LOW;
        }
    }
    
    private int estimateChunkPacketSize(CubeChunk chunk) {
        // Simplified estimation - in reality you'd want to measure actual NBT size
        if (chunk.isEmpty()) {
            return 256; // Small empty chunk
        } else {
            return 8192; // Typical full chunk
        }
    }
    
    /**
     * Tracks a player's network state for chunk synchronization.
     */
    private static class PlayerNetworkTracker {
        final ServerPlayer player;
        final Set<CubePos> sentChunks = new HashSet<>();
        @Nullable BlockPos lastKnownPosition;
        
        PlayerNetworkTracker(ServerPlayer player) {
            this.player = player;
            this.lastKnownPosition = player.blockPosition();
        }
    }
    
    /**
     * Represents a chunk send request with priority and distance.
     */
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
            // First compare by priority
            int priorityComparison = this.priority.compareTo(other.priority);
            if (priorityComparison != 0) {
                return priorityComparison;
            }
            
            // Then by distance (closer first)
            int distanceComparison = Double.compare(this.distance, other.distance);
            if (distanceComparison != 0) {
                return distanceComparison;
            }
            
            // Finally by timestamp (older first)
            return Long.compare(this.timestamp, other.timestamp);
        }
    }
    
    /**
     * Priority levels for chunk sending.
     */
    public enum ChunkSendPriority {
        IMMEDIATE(0),
        HIGH(1),
        NORMAL(2),
        LOW(3),
        UPDATE(1); // Same as HIGH but for updates
        
        private final int value;
        
        ChunkSendPriority(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
    }
} 