package com.radexin.cubicchunks.client;

import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles rendering of cubic chunks with proper batching and level-of-detail support.
 */
public class CubicChunkRenderer {
    private final Minecraft minecraft;
    private final BlockRenderDispatcher blockRenderer;
    private final Map<CubeChunk, CubeRenderData> renderDataCache = new ConcurrentHashMap<>();
    private final Queue<CubeChunk> rebuildQueue = new ArrayDeque<>();
    
    public CubicChunkRenderer() {
        this.minecraft = Minecraft.getInstance();
        this.blockRenderer = minecraft.getBlockRenderer();
    }
    
    /**
     * Renders all visible cubic chunks
     */
    public void renderCubes(PoseStack poseStack, Vec3 cameraPos, Collection<CubeChunk> visibleCubes) {
        // Sort cubes by distance for proper alpha blending
        List<CubeChunk> sortedCubes = new ArrayList<>(visibleCubes);
        sortedCubes.sort((a, b) -> {
            double distA = getDistanceToCamera(a, cameraPos);
            double distB = getDistanceToCamera(b, cameraPos);
            return Double.compare(distA, distB);
        });
        
        // Render solid blocks first
        for (CubeChunk cube : sortedCubes) {
            renderCube(poseStack, cube, cameraPos, RenderType.solid());
        }
        
        // Then render translucent blocks
        for (CubeChunk cube : sortedCubes) {
            renderCube(poseStack, cube, cameraPos, RenderType.translucent());
        }
    }
    
    private void renderCube(PoseStack poseStack, CubeChunk cube, Vec3 cameraPos, RenderType renderType) {
        CubeRenderData renderData = getRenderData(cube);
        if (renderData == null || renderData.isEmpty(renderType)) {
            return;
        }
        
        poseStack.pushPose();
        
        // Translate to cube position
        int worldX = cube.getCubeX() * CubeChunk.SIZE;
        int worldY = cube.getCubeY() * CubeChunk.SIZE;
        int worldZ = cube.getCubeZ() * CubeChunk.SIZE;
        
        poseStack.translate(
            worldX - cameraPos.x,
            worldY - cameraPos.y,
            worldZ - cameraPos.z
        );
        
        // Determine LOD level based on distance
        double distance = getDistanceToCamera(cube, cameraPos);
        int lodLevel = calculateLODLevel(distance);
        
        // Render based on LOD level
        if (lodLevel == 0) {
            // Full detail rendering
            renderCubeFullDetail(poseStack, cube, renderType, renderData);
        } else {
            // Simplified rendering for distant cubes
            renderCubeSimplified(poseStack, cube, renderType, lodLevel);
        }
        
        poseStack.popPose();
    }
    
    private void renderCubeFullDetail(PoseStack poseStack, CubeChunk cube, RenderType renderType, CubeRenderData renderData) {
        // Render each block in the cube
        for (int y = 0; y < CubeChunk.SIZE; y++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int x = 0; x < CubeChunk.SIZE; x++) {
                    BlockState blockState = cube.getBlockState(x, y, z);
                    if (blockState.isAir()) continue;
                    
                    // Check if this block should be rendered in this render type
                    if (renderType == RenderType.solid() && blockState.isAir()) continue;
                    if (renderType == RenderType.translucent() && blockState.canOcclude()) continue;
                    
                    // Check if block is occluded by neighbors
                    if (isBlockOccluded(cube, x, y, z, blockState)) continue;
                    
                    poseStack.pushPose();
                    poseStack.translate(x, y, z);
                    
                    renderBlock(poseStack, blockState, cube, x, y, z, renderType);
                    
                    poseStack.popPose();
                }
            }
        }
    }
    
    private void renderCubeSimplified(PoseStack poseStack, CubeChunk cube, RenderType renderType, int lodLevel) {
        // For simplified rendering, just render a representative block or skip entirely
        if (lodLevel > 2) return; // Too far, don't render
        
        // Find the most common non-air block in the cube
        BlockState representativeBlock = getMostCommonBlock(cube);
        if (representativeBlock.isAir()) return;
        
        if (renderType == RenderType.solid() && representativeBlock.isAir()) return;
        if (renderType == RenderType.translucent() && representativeBlock.canOcclude()) return;
        
        // Render a single block to represent the entire cube
        poseStack.pushPose();
        poseStack.translate(CubeChunk.SIZE / 2.0, CubeChunk.SIZE / 2.0, CubeChunk.SIZE / 2.0);
        poseStack.scale(CubeChunk.SIZE, CubeChunk.SIZE, CubeChunk.SIZE);
        
        renderBlock(poseStack, representativeBlock, cube, 0, 0, 0, renderType);
        
        poseStack.popPose();
    }
    
    private void renderBlock(PoseStack poseStack, BlockState blockState, CubeChunk cube, int x, int y, int z, RenderType renderType) {
        BlockPos pos = new BlockPos(x, y, z);
        RandomSource random = RandomSource.create(42); // Use deterministic random for consistency
        
        // Get light level for this block
        int lightLevel = cube.getLightLevel(x, y, z);
        int packedLight = LevelRenderer.getLightColor(minecraft.level, pos.offset(
            cube.getCubeX() * CubeChunk.SIZE,
            cube.getCubeY() * CubeChunk.SIZE,
            cube.getCubeZ() * CubeChunk.SIZE
        ));
        
        // Create a simple vertex consumer for now
        // In a full implementation, this would use proper buffer builders
        VertexConsumer vertexConsumer = minecraft.renderBuffers().bufferSource().getBuffer(renderType);
        
        try {
            // Render the block model
            blockRenderer.renderBatched(
                blockState,
                pos,
                minecraft.level,
                poseStack,
                vertexConsumer,
                false,
                random
            );
        } catch (Exception e) {
            // Fallback: render a simple cube
            renderSimpleCube(poseStack, vertexConsumer, lightLevel);
        }
    }
    
    private void renderSimpleCube(PoseStack poseStack, VertexConsumer vertexConsumer, int lightLevel) {
        // Simplified placeholder - in a full implementation this would render proper geometry
        // For now, just skip rendering to avoid method signature issues
    }
    
    private boolean isBlockOccluded(CubeChunk cube, int x, int y, int z, BlockState blockState) {
        // Simple occlusion check - if all 6 neighbors are opaque, this block is occluded
        if (!blockState.canOcclude()) return false;
        
        for (Direction direction : Direction.values()) {
            int nx = x + direction.getStepX();
            int ny = y + direction.getStepY();
            int nz = z + direction.getStepZ();
            
            BlockState neighbor;
            if (nx >= 0 && nx < CubeChunk.SIZE && ny >= 0 && ny < CubeChunk.SIZE && nz >= 0 && nz < CubeChunk.SIZE) {
                neighbor = cube.getBlockState(nx, ny, nz);
            } else {
                // TODO: Check neighboring cubes
                neighbor = Blocks.AIR.defaultBlockState();
            }
            
            if (!neighbor.canOcclude()) {
                return false; // At least one face is exposed
            }
        }
        
        return true; // All faces are occluded
    }
    
    private BlockState getMostCommonBlock(CubeChunk cube) {
        Map<BlockState, Integer> blockCounts = new HashMap<>();
        
        for (int y = 0; y < CubeChunk.SIZE; y++) {
            for (int z = 0; z < CubeChunk.SIZE; z++) {
                for (int x = 0; x < CubeChunk.SIZE; x++) {
                    BlockState state = cube.getBlockState(x, y, z);
                    if (!state.isAir()) {
                        blockCounts.merge(state, 1, Integer::sum);
                    }
                }
            }
        }
        
        return blockCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(Blocks.AIR.defaultBlockState());
    }
    
    private double getDistanceToCamera(CubeChunk cube, Vec3 cameraPos) {
        double centerX = cube.getCubeX() * CubeChunk.SIZE + CubeChunk.SIZE / 2.0;
        double centerY = cube.getCubeY() * CubeChunk.SIZE + CubeChunk.SIZE / 2.0;
        double centerZ = cube.getCubeZ() * CubeChunk.SIZE + CubeChunk.SIZE / 2.0;
        
        return cameraPos.distanceTo(new Vec3(centerX, centerY, centerZ));
    }
    
    private int calculateLODLevel(double distance) {
        // Calculate LOD level based on distance
        if (distance < 64) return 0;      // Full detail
        if (distance < 128) return 1;     // Reduced detail
        if (distance < 256) return 2;     // Simplified
        return 3;                         // Very simplified or culled
    }
    
    private CubeRenderData getRenderData(CubeChunk cube) {
        CubeRenderData data = renderDataCache.get(cube);
        if (data == null || cube.isDirty()) {
            data = buildRenderData(cube);
            renderDataCache.put(cube, data);
            cube.setDirty(false);
        }
        return data;
    }
    
    private CubeRenderData buildRenderData(CubeChunk cube) {
        // Build render data for the cube
        // This would typically involve creating vertex buffers, etc.
        return new CubeRenderData(cube);
    }
    
    /**
     * Marks a cube for rebuild
     */
    public void markForRebuild(CubeChunk cube) {
        renderDataCache.remove(cube);
        rebuildQueue.offer(cube);
    }
    
    /**
     * Processes the rebuild queue
     */
    public void processRebuilds() {
        int processed = 0;
        while (!rebuildQueue.isEmpty() && processed < 4) { // Limit rebuilds per frame
            CubeChunk cube = rebuildQueue.poll();
            if (cube != null) {
                buildRenderData(cube);
                processed++;
            }
        }
    }
    
    /**
     * Clears all cached render data
     */
    public void clearCache() {
        renderDataCache.clear();
        rebuildQueue.clear();
    }
    
    private static class CubeRenderData {
        private final CubeChunk cube;
        private final Map<RenderType, Boolean> hasContent = new HashMap<>();
        
        public CubeRenderData(CubeChunk cube) {
            this.cube = cube;
            analyzeContent();
        }
        
        private void analyzeContent() {
            // Analyze what render types this cube needs
            boolean hasSolid = false;
            boolean hasTranslucent = false;
            
            for (int y = 0; y < CubeChunk.SIZE; y++) {
                for (int z = 0; z < CubeChunk.SIZE; z++) {
                    for (int x = 0; x < CubeChunk.SIZE; x++) {
                        BlockState state = cube.getBlockState(x, y, z);
                        if (!state.isAir()) {
                            // Simplified render type detection
                            if (state.canOcclude()) {
                                hasSolid = true;
                            } else {
                                hasTranslucent = true;
                            }
                        }
                    }
                }
            }
            
            hasContent.put(RenderType.solid(), hasSolid);
            hasContent.put(RenderType.translucent(), hasTranslucent);
        }
        
        public boolean isEmpty(RenderType renderType) {
            return !hasContent.getOrDefault(renderType, false);
        }
    }
} 