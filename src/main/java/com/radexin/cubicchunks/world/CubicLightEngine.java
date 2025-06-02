package com.radexin.cubicchunks.world;

import com.radexin.cubicchunks.chunk.CubeChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Handles 3D light propagation across cubic chunks.
 * Manages both skylight and blocklight in a cubic chunk system.
 */
public class CubicLightEngine {
    private final CubeWorld cubeWorld;
    private final Queue<LightNode> lightQueue = new ArrayDeque<>();
    private final Queue<LightNode> removalQueue = new ArrayDeque<>();
    
    public CubicLightEngine(CubeWorld cubeWorld) {
        this.cubeWorld = cubeWorld;
    }
    
    /**
     * Updates lighting for a block change at the given position
     */
    public void updateLighting(int worldX, int worldY, int worldZ, BlockState newState, BlockState oldState) {
        // Update block light
        int newBlockLight = newState.getLightEmission();
        int oldBlockLight = oldState.getLightEmission();
        
        if (newBlockLight != oldBlockLight) {
            updateBlockLight(worldX, worldY, worldZ, newBlockLight, oldBlockLight);
        }
        
        // Update sky light if opacity changed
        // Using simplified light block values for now
        int newOpacity = newState.isAir() ? 0 : (newState.canOcclude() ? 15 : 1);
        int oldOpacity = oldState.isAir() ? 0 : (oldState.canOcclude() ? 15 : 1);
        
        if (newOpacity != oldOpacity) {
            updateSkyLight(worldX, worldY, worldZ, newOpacity, oldOpacity);
        }
    }
    
    private void updateBlockLight(int worldX, int worldY, int worldZ, int newLight, int oldLight) {
        int cubeX = Math.floorDiv(worldX, CubeChunk.SIZE);
        int cubeY = Math.floorDiv(worldY, CubeChunk.SIZE);
        int cubeZ = Math.floorDiv(worldZ, CubeChunk.SIZE);
        
        CubeChunk cube = cubeWorld.getCube(cubeX, cubeY, cubeZ, false);
        if (cube == null) return;
        
        int localX = Math.floorMod(worldX, CubeChunk.SIZE);
        int localY = Math.floorMod(worldY, CubeChunk.SIZE);
        int localZ = Math.floorMod(worldZ, CubeChunk.SIZE);
        
        // Remove old light
        if (oldLight > 0) {
            removeLightBFS(worldX, worldY, worldZ, oldLight, true);
        }
        
        // Add new light
        if (newLight > 0) {
            cube.setBlockLight(localX, localY, localZ, (byte) newLight);
            propagateLightBFS(worldX, worldY, worldZ, newLight, true);
        }
    }
    
    private void updateSkyLight(int worldX, int worldY, int worldZ, int newOpacity, int oldOpacity) {
        // Simplified sky light update
        // In a full implementation, this would handle sky light propagation more thoroughly
        
        if (newOpacity > oldOpacity) {
            // Block became more opaque - remove sky light
            removeSkyLightColumn(worldX, worldY, worldZ);
        } else if (newOpacity < oldOpacity) {
            // Block became less opaque - add sky light
            propagateSkyLightColumn(worldX, worldY, worldZ);
        }
    }
    
    private void propagateLightBFS(int startX, int startY, int startZ, int lightLevel, boolean isBlockLight) {
        lightQueue.clear();
        lightQueue.offer(new LightNode(startX, startY, startZ, lightLevel));
        
        while (!lightQueue.isEmpty()) {
            LightNode node = lightQueue.poll();
            
            // Check all 6 neighbors
            for (Direction dir : Direction.values()) {
                int newX = node.x + dir.offsetX;
                int newY = node.y + dir.offsetY;
                int newZ = node.z + dir.offsetZ;
                
                if (node.lightLevel <= 1) continue; // No more light to propagate
                
                int newLightLevel = node.lightLevel - 1;
                
                // Get the cube for this position
                int cubeX = Math.floorDiv(newX, CubeChunk.SIZE);
                int cubeY = Math.floorDiv(newY, CubeChunk.SIZE);
                int cubeZ = Math.floorDiv(newZ, CubeChunk.SIZE);
                
                CubeChunk cube = cubeWorld.getCube(cubeX, cubeY, cubeZ, false);
                if (cube == null) continue;
                
                int localX = Math.floorMod(newX, CubeChunk.SIZE);
                int localY = Math.floorMod(newY, CubeChunk.SIZE);
                int localZ = Math.floorMod(newZ, CubeChunk.SIZE);
                
                BlockState blockState = cube.getBlockState(localX, localY, localZ);
                
                // Check if light can pass through this block
                int opacity = blockState.isAir() ? 0 : (blockState.canOcclude() ? 15 : 1);
                if (opacity >= 15) continue; // Opaque block
                
                newLightLevel -= opacity;
                if (newLightLevel <= 0) continue;
                
                // Get current light level
                int currentLight = isBlockLight ? 
                    cube.getBlockLight(localX, localY, localZ) : 
                    cube.getSkyLight(localX, localY, localZ);
                
                // Only update if new light is brighter
                if (newLightLevel > currentLight) {
                    if (isBlockLight) {
                        cube.setBlockLight(localX, localY, localZ, (byte) newLightLevel);
                    } else {
                        cube.setSkyLight(localX, localY, localZ, (byte) newLightLevel);
                    }
                    
                    lightQueue.offer(new LightNode(newX, newY, newZ, newLightLevel));
                }
            }
        }
    }
    
    private void removeLightBFS(int startX, int startY, int startZ, int lightLevel, boolean isBlockLight) {
        removalQueue.clear();
        lightQueue.clear();
        
        removalQueue.offer(new LightNode(startX, startY, startZ, lightLevel));
        
        // First pass: remove light
        while (!removalQueue.isEmpty()) {
            LightNode node = removalQueue.poll();
            
            // Check all 6 neighbors
            for (Direction dir : Direction.values()) {
                int newX = node.x + dir.offsetX;
                int newY = node.y + dir.offsetY;
                int newZ = node.z + dir.offsetZ;
                
                int cubeX = Math.floorDiv(newX, CubeChunk.SIZE);
                int cubeY = Math.floorDiv(newY, CubeChunk.SIZE);
                int cubeZ = Math.floorDiv(newZ, CubeChunk.SIZE);
                
                CubeChunk cube = cubeWorld.getCube(cubeX, cubeY, cubeZ, false);
                if (cube == null) continue;
                
                int localX = Math.floorMod(newX, CubeChunk.SIZE);
                int localY = Math.floorMod(newY, CubeChunk.SIZE);
                int localZ = Math.floorMod(newZ, CubeChunk.SIZE);
                
                int neighborLight = isBlockLight ? 
                    cube.getBlockLight(localX, localY, localZ) : 
                    cube.getSkyLight(localX, localY, localZ);
                
                if (neighborLight != 0 && neighborLight < node.lightLevel) {
                    if (isBlockLight) {
                        cube.setBlockLight(localX, localY, localZ, (byte) 0);
                    } else {
                        cube.setSkyLight(localX, localY, localZ, (byte) 0);
                    }
                    
                    removalQueue.offer(new LightNode(newX, newY, newZ, neighborLight));
                } else if (neighborLight >= node.lightLevel) {
                    lightQueue.offer(new LightNode(newX, newY, newZ, neighborLight));
                }
            }
        }
        
        // Second pass: re-propagate remaining light
        while (!lightQueue.isEmpty()) {
            LightNode node = lightQueue.poll();
            propagateLightBFS(node.x, node.y, node.z, node.lightLevel, isBlockLight);
        }
    }
    
    private void removeSkyLightColumn(int worldX, int worldY, int worldZ) {
        // Remove sky light from this position downward
        for (int y = worldY; y >= worldY - 256; y--) { // Check reasonable range below
            int cubeY = Math.floorDiv(y, CubeChunk.SIZE);
            CubeChunk cube = cubeWorld.getCube(
                Math.floorDiv(worldX, CubeChunk.SIZE), 
                cubeY, 
                Math.floorDiv(worldZ, CubeChunk.SIZE), 
                false
            );
            
            if (cube == null) continue;
            
            int localX = Math.floorMod(worldX, CubeChunk.SIZE);
            int localY = Math.floorMod(y, CubeChunk.SIZE);
            int localZ = Math.floorMod(worldZ, CubeChunk.SIZE);
            
            BlockState blockState = cube.getBlockState(localX, localY, localZ);
            int opacity = blockState.isAir() ? 0 : (blockState.canOcclude() ? 15 : 1);
            if (opacity >= 15) {
                break; // Hit opaque block, stop
            }
            
            cube.setSkyLight(localX, localY, localZ, (byte) 0);
        }
    }
    
    private void propagateSkyLightColumn(int worldX, int worldY, int worldZ) {
        // Propagate sky light from this position downward
        byte currentSkyLight = 15; // Start with full sky light
        
        for (int y = worldY; y >= worldY - 256; y--) { // Check reasonable range below
            int cubeY = Math.floorDiv(y, CubeChunk.SIZE);
            CubeChunk cube = cubeWorld.getCube(
                Math.floorDiv(worldX, CubeChunk.SIZE), 
                cubeY, 
                Math.floorDiv(worldZ, CubeChunk.SIZE), 
                false
            );
            
            if (cube == null) continue;
            
            int localX = Math.floorMod(worldX, CubeChunk.SIZE);
            int localY = Math.floorMod(y, CubeChunk.SIZE);
            int localZ = Math.floorMod(worldZ, CubeChunk.SIZE);
            
            BlockState blockState = cube.getBlockState(localX, localY, localZ);
            int opacity = blockState.isAir() ? 0 : (blockState.canOcclude() ? 15 : 1);
            if (opacity >= 15) {
                break; // Hit opaque block, stop
            }
            
            currentSkyLight -= opacity;
            if (currentSkyLight <= 0) {
                break; // No more light to propagate
            }
            
            cube.setSkyLight(localX, localY, localZ, currentSkyLight);
        }
    }
    
    private static class LightNode {
        final int x, y, z;
        final int lightLevel;
        
        LightNode(int x, int y, int z, int lightLevel) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.lightLevel = lightLevel;
        }
    }
    
    private enum Direction {
        UP(0, 1, 0),
        DOWN(0, -1, 0),
        NORTH(0, 0, -1),
        SOUTH(0, 0, 1),
        WEST(-1, 0, 0),
        EAST(1, 0, 0);
        
        final int offsetX, offsetY, offsetZ;
        
        Direction(int offsetX, int offsetY, int offsetZ) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
        }
    }
} 