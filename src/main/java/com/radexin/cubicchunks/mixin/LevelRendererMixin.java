package com.radexin.cubicchunks.mixin;

import com.radexin.cubicchunks.Config;
import com.radexin.cubicchunks.world.CubeWorld;
import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.client.CubicChunksClient;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.client.Camera;
import java.util.Set;
import java.util.HashSet;
import com.radexin.cubicchunks.client.CubicRenderer;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;

import java.util.Collection;
import com.radexin.cubicchunks.chunk.CubicChunkManager;

/**
 * Mixin to integrate cubic chunk rendering with Minecraft's level renderer.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    
    @Shadow
    private int lastViewDistance;
    
    @Unique
    private final Set<CubeChunk> cubicchunks$renderedCubes = new HashSet<>();
    
    @Shadow private Minecraft minecraft;
    
    private CubicRenderer cubicChunkRenderer;
    
    @Inject(method = "<init>", at = @At("TAIL"))
    private void initCubicChunkRenderer(CallbackInfo ci) {
        this.cubicChunkRenderer = new CubicRenderer();
    }
    
    @Inject(method = "setupRender", at = @At("HEAD"))
    private void cubicchunks$setupVerticalRender(Camera camera, Entity entity, boolean spectator, boolean capturedFrustum, float partialTick, CallbackInfo ci) {
        // Clear previously rendered cubes
        cubicchunks$renderedCubes.clear();
        
        // Get player position
        Vec3 cameraPos = camera.getPosition();
        int playerCubeX = Mth.floor(cameraPos.x / CubeChunk.SIZE);
        int playerCubeY = Mth.floor(cameraPos.y / CubeChunk.SIZE);
        int playerCubeZ = Mth.floor(cameraPos.z / CubeChunk.SIZE);
        
        // Get client cube world
        CubeWorld cubeWorld = CubicChunksClient.getClientCubeWorld();
        if (cubeWorld == null) return;
        
        // Calculate vertical render distance
        int verticalRenderDistance = Config.verticalRenderDistance;
        int horizontalRenderDistance = this.lastViewDistance;
        
        // Queue cubic chunks for rendering based on vertical render distance
        for (int x = playerCubeX - horizontalRenderDistance; x <= playerCubeX + horizontalRenderDistance; x++) {
            for (int z = playerCubeZ - horizontalRenderDistance; z <= playerCubeZ + horizontalRenderDistance; z++) {
                for (int y = playerCubeY - verticalRenderDistance; y <= playerCubeY + verticalRenderDistance; y++) {
                    // Check if cube is within render distance
                    double distSq = Math.pow(x - playerCubeX, 2) + Math.pow(z - playerCubeZ, 2);
                    if (distSq <= horizontalRenderDistance * horizontalRenderDistance) {
                        CubeChunk cube = cubeWorld.getCube(x, y, z, false);
                        if (cube != null) {
                            cubicchunks$renderedCubes.add(cube);
                        }
                    }
                }
            }
        }
    }
    
    @Unique
    public Set<CubeChunk> cubicchunks$getRenderedCubes() {
        return cubicchunks$renderedCubes;
    }
    
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", 
           target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;DZLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V", 
           ordinal = 0))
    private void beforeRenderChunks(PoseStack poseStack, float partialTick, long finishNanoTime, 
                                   boolean renderBlockOutline, Camera camera, CallbackInfo ci) {
        
        // Check if we're in a world that uses cubic chunks
        if (minecraft.level == null) return;
        
        try {
            // Get cubic chunk manager if available
            CubicChunkManager chunkManager = getCubicChunkManager();
            if (chunkManager == null) return;
            
            Vec3 cameraPos = camera.getPosition();
            
            // Get visible cubic chunks within render distance
            Collection<CubeChunk> visibleCubes = getVisibleCubesFromManager(
                chunkManager, cameraPos, 
                minecraft.options.renderDistance().get(),
                getVerticalRenderDistance()
            );
            
            if (!visibleCubes.isEmpty()) {
                // Render cubic chunks
                poseStack.pushPose();
                cubicChunkRenderer.renderCubes(poseStack, cameraPos, visibleCubes);
                poseStack.popPose();
            }
            
        } catch (Exception e) {
            // Log error but don't crash the game
            System.err.println("Error rendering cubic chunks: " + e.getMessage());
        }
    }
    
    @Inject(method = "allChanged", at = @At("TAIL"))
    private void onAllChanged(CallbackInfo ci) {
        if (cubicChunkRenderer != null) {
            cubicChunkRenderer.clearCache();
        }
    }
    
    /**
     * Gets the cubic chunk manager from the current level.
     * Returns null if not available or not a cubic world.
     */
    private CubicChunkManager getCubicChunkManager() {
        if (minecraft.level == null) return null;
        
        // Use the CubicWorldType utility to get the manager
        return com.radexin.cubicchunks.world.CubicWorldType.getCubicChunkManager(minecraft.level);
    }
    
    /**
     * Gets the vertical render distance setting.
     */
    private int getVerticalRenderDistance() {
        // This would get the setting from configuration
        // For now, use a reasonable default
        return Math.min(minecraft.options.renderDistance().get(), 8);
    }
    
    /**
     * Invalidates chunk rendering data when chunks are updated.
     */
    @Inject(method = "setBlockDirty(III)V", at = @At("HEAD"))
    private void onBlockChanged(int x, int y, int z, CallbackInfo ci) {
        if (cubicChunkRenderer != null) {
            // Calculate which cube this block belongs to
            int cubeX = Math.floorDiv(x, CubeChunk.SIZE);
            int cubeY = Math.floorDiv(y, CubeChunk.SIZE);
            int cubeZ = Math.floorDiv(z, CubeChunk.SIZE);
            
            // Mark the cube for rebuilding
            CubicChunkManager chunkManager = getCubicChunkManager();
            if (chunkManager != null) {
                CubeChunk cube = chunkManager.getCube(cubeX, cubeY, cubeZ, false);
                if (cube != null) {
                    cubicChunkRenderer.markForRebuild(cube);
                }
            }
        }
    }
    
    /**
     * Gets visible cubic chunks from the chunk manager within render distance.
     */
    private Collection<CubeChunk> getVisibleCubesFromManager(CubicChunkManager chunkManager, Vec3 cameraPos, Integer renderDistance, int verticalRenderDistance) {
        java.util.List<CubeChunk> visibleCubes = new java.util.ArrayList<>();
        
        int centerCubeX = (int) Math.floor(cameraPos.x / CubeChunk.SIZE);
        int centerCubeY = (int) Math.floor(cameraPos.y / CubeChunk.SIZE);
        int centerCubeZ = (int) Math.floor(cameraPos.z / CubeChunk.SIZE);
        
        // Get cubes within render distance
        for (int x = centerCubeX - renderDistance; x <= centerCubeX + renderDistance; x++) {
            for (int z = centerCubeZ - renderDistance; z <= centerCubeZ + renderDistance; z++) {
                for (int y = centerCubeY - verticalRenderDistance; y <= centerCubeY + verticalRenderDistance; y++) {
                    // Check if cube is within circular horizontal distance
                    double distSq = Math.pow(x - centerCubeX, 2) + Math.pow(z - centerCubeZ, 2);
                    if (distSq <= renderDistance * renderDistance) {
                        CubeChunk cube = chunkManager.getCube(x, y, z, false);
                        if (cube != null && !cube.isEmpty()) {
                            visibleCubes.add(cube);
                        }
                    }
                }
            }
        }
        
        return visibleCubes;
    }
    
    /**
     * Process chunk rebuild queue during the render tick.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        if (cubicChunkRenderer != null) {
            cubicChunkRenderer.processRebuilds();
        }
    }
} 