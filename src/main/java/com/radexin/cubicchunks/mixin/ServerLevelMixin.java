package com.radexin.cubicchunks.mixin;

import com.radexin.cubicchunks.world.CubeWorld;
import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.gen.UnifiedCubicWorldGenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.server.level.ChunkMap;
import java.util.List;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    
    @Unique
    private CubeWorld cubicchunks$cubeWorld;
    
    @Shadow
    public abstract ChunkMap getChunkSource();
    
    @Shadow
    public abstract List<ServerPlayer> players();
    
    @Inject(method = "<init>", at = @At("TAIL"))
    private void cubicchunks$initCubeWorld(CallbackInfo ci) {
        ServerLevel level = (ServerLevel)(Object)this;
        var biomeRegistry = level.registryAccess().registry(net.minecraft.core.registries.Registries.BIOME).get();
        UnifiedCubicWorldGenerator generator = new UnifiedCubicWorldGenerator(level, biomeRegistry, null);
        // For now we'll create a simple CubeWorld - this should be properly integrated with UnifiedCubicChunkManager later
        this.cubicchunks$cubeWorld = new CubeWorld(generator, null, level);
    }
    
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void cubicchunks$getBlockStateExtended(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        // Handle extended Y coordinates beyond vanilla limits
        if (pos.getY() < -2048 || pos.getY() >= 2048) {
            int cubeX = Math.floorDiv(pos.getX(), CubeChunk.SIZE);
            int cubeY = Math.floorDiv(pos.getY(), CubeChunk.SIZE);
            int cubeZ = Math.floorDiv(pos.getZ(), CubeChunk.SIZE);
            
            CubeChunk cube = cubicchunks$cubeWorld.getCube(cubeX, cubeY, cubeZ, true);
            if (cube != null) {
                int localX = Math.floorMod(pos.getX(), CubeChunk.SIZE);
                int localY = Math.floorMod(pos.getY(), CubeChunk.SIZE);
                int localZ = Math.floorMod(pos.getZ(), CubeChunk.SIZE);
                cir.setReturnValue(cube.getBlockState(localX, localY, localZ));
            } else {
                cir.setReturnValue(Blocks.AIR.defaultBlockState());
            }
        }
    }
    
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", at = @At("HEAD"), cancellable = true)
    private void cubicchunks$setBlockExtended(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {
        // Handle extended Y coordinates beyond vanilla limits
        if (pos.getY() < -2048 || pos.getY() >= 2048) {
            int cubeX = Math.floorDiv(pos.getX(), CubeChunk.SIZE);
            int cubeY = Math.floorDiv(pos.getY(), CubeChunk.SIZE);
            int cubeZ = Math.floorDiv(pos.getZ(), CubeChunk.SIZE);
            
            CubeChunk cube = cubicchunks$cubeWorld.getCube(cubeX, cubeY, cubeZ, true);
            if (cube != null) {
                int localX = Math.floorMod(pos.getX(), CubeChunk.SIZE);
                int localY = Math.floorMod(pos.getY(), CubeChunk.SIZE);
                int localZ = Math.floorMod(pos.getZ(), CubeChunk.SIZE);
                cube.setBlockState(localX, localY, localZ, state);
                
                // Sync cube to nearby players
                for (ServerPlayer player : players()) {
                    if (player.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) < 16384) { // 128 blocks squared
                        com.radexin.cubicchunks.CubicChunks.sendCubeSync(player, cube);
                    }
                }
                
                cir.setReturnValue(true);
            } else {
                cir.setReturnValue(false);
            }
        }
    }
    
    @Unique
    public CubeWorld cubicchunks$getCubeWorld() {
        return this.cubicchunks$cubeWorld;
    }
    
    @Unique
    public void cubicchunks$loadCubesAroundPlayer(ServerPlayer player, int verticalDistance) {
        BlockPos playerPos = player.blockPosition();
        int playerCubeX = Math.floorDiv(playerPos.getX(), CubeChunk.SIZE);
        int playerCubeY = Math.floorDiv(playerPos.getY(), CubeChunk.SIZE);
        int playerCubeZ = Math.floorDiv(playerPos.getZ(), CubeChunk.SIZE);
        
        int renderDistance = player.server.getPlayerList().getViewDistance();
        
        // Load cubes in a 3D area around the player
        for (int x = playerCubeX - renderDistance; x <= playerCubeX + renderDistance; x++) {
            for (int z = playerCubeZ - renderDistance; z <= playerCubeZ + renderDistance; z++) {
                for (int y = playerCubeY - verticalDistance; y <= playerCubeY + verticalDistance; y++) {
                    CubeChunk cube = cubicchunks$cubeWorld.getCube(x, y, z, true);
                    if (cube != null) {
                        // Send cube to player
                        com.radexin.cubicchunks.CubicChunks.sendCubeSync(player, cube);
                    }
                }
            }
        }
    }
} 