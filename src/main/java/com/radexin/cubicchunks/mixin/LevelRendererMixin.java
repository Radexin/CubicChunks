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

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    
    @Shadow
    private int lastViewDistance;
    
    @Unique
    private final Set<CubeChunk> cubicchunks$renderedCubes = new HashSet<>();
    
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
} 