package com.radexin.cubicchunks.mixin;

import com.radexin.cubicchunks.Config;
import com.radexin.cubicchunks.world.CubeWorld;
import com.radexin.cubicchunks.chunk.CubeChunk;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    
    @Shadow
    public abstract ServerLevel serverLevel();
    
    @Shadow
    public abstract BlockPos blockPosition();
    
    @Unique
    private int cubicchunks$lastCubeX = Integer.MAX_VALUE;
    @Unique
    private int cubicchunks$lastCubeY = Integer.MAX_VALUE;
    @Unique
    private int cubicchunks$lastCubeZ = Integer.MAX_VALUE;
    
    @Inject(method = "tick", at = @At("HEAD"))
    private void cubicchunks$trackPlayerMovement(CallbackInfo ci) {
        BlockPos pos = blockPosition();
        int currentCubeX = Math.floorDiv(pos.getX(), CubeChunk.SIZE);
        int currentCubeY = Math.floorDiv(pos.getY(), CubeChunk.SIZE);
        int currentCubeZ = Math.floorDiv(pos.getZ(), CubeChunk.SIZE);
        
        // Check if player moved to a different cube
        if (currentCubeX != cubicchunks$lastCubeX || 
            currentCubeY != cubicchunks$lastCubeY || 
            currentCubeZ != cubicchunks$lastCubeZ) {
            
            cubicchunks$lastCubeX = currentCubeX;
            cubicchunks$lastCubeY = currentCubeY;
            cubicchunks$lastCubeZ = currentCubeZ;
            
            // Load cubes around player
            cubicchunks$loadCubesAroundPlayer();
        }
    }
    
    @Unique
    private void cubicchunks$loadCubesAroundPlayer() {
        ServerLevel level = serverLevel();
        
        // Use reflection to access mixin methods
        try {
            java.lang.reflect.Method loadCubesMethod = level.getClass().getMethod("cubicchunks$loadCubesAroundPlayer", ServerPlayer.class, int.class);
            loadCubesMethod.invoke(level, (ServerPlayer)(Object)this, Config.verticalRenderDistance);
        } catch (Exception e) {
            // Fallback: manually load cubes
            try {
                java.lang.reflect.Method getCubeWorldMethod = level.getClass().getMethod("cubicchunks$getCubeWorld");
                CubeWorld cubeWorld = (CubeWorld) getCubeWorldMethod.invoke(level);
                
                if (cubeWorld != null) {
                    BlockPos pos = blockPosition();
                    int playerCubeX = Math.floorDiv(pos.getX(), CubeChunk.SIZE);
                    int playerCubeY = Math.floorDiv(pos.getY(), CubeChunk.SIZE);
                    int playerCubeZ = Math.floorDiv(pos.getZ(), CubeChunk.SIZE);
                    
                    int renderDistance = ((ServerPlayer)(Object)this).server.getPlayerList().getViewDistance();
                    int verticalDistance = Config.verticalRenderDistance;
                    
                    // Load cubes in a 3D area around the player
                    for (int x = playerCubeX - renderDistance; x <= playerCubeX + renderDistance; x++) {
                        for (int z = playerCubeZ - renderDistance; z <= playerCubeZ + renderDistance; z++) {
                            for (int y = playerCubeY - verticalDistance; y <= playerCubeY + verticalDistance; y++) {
                                CubeChunk cube = cubeWorld.getCube(x, y, z, true);
                                if (cube != null) {
                                    // Send cube to player
                                    com.radexin.cubicchunks.CubicChunks.sendCubeSync((ServerPlayer)(Object)this, cube);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                // Silently fail if reflection doesn't work
            }
        }
    }
} 