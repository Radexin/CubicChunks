package com.radexin.cubicchunks.mixin;

import com.radexin.cubicchunks.world.CubeWorld;
import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.gen.CubeChunkGenerator;
import com.radexin.cubicchunks.client.CubicChunksClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    
    @Inject(method = "<init>", at = @At("TAIL"))
    private void cubicchunks$initClientCubeWorld(CallbackInfo ci) {
        // Ensure client cube world is initialized
        if (CubicChunksClient.getClientCubeWorld() == null) {
            // This will be handled by CubicChunksClient.onClientSetup
        }
    }
    
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void cubicchunks$getBlockStateExtended(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        // Handle extended Y coordinates beyond vanilla limits
        if (pos.getY() < -2048 || pos.getY() >= 2048) {
            CubeWorld cubeWorld = CubicChunksClient.getClientCubeWorld();
            if (cubeWorld != null) {
                int cubeX = Math.floorDiv(pos.getX(), CubeChunk.SIZE);
                int cubeY = Math.floorDiv(pos.getY(), CubeChunk.SIZE);
                int cubeZ = Math.floorDiv(pos.getZ(), CubeChunk.SIZE);
                
                CubeChunk cube = cubeWorld.getCube(cubeX, cubeY, cubeZ, false);
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
    }
    
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", at = @At("HEAD"), cancellable = true)
    private void cubicchunks$setBlockExtended(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {
        // Handle extended Y coordinates beyond vanilla limits
        if (pos.getY() < -2048 || pos.getY() >= 2048) {
            CubeWorld cubeWorld = CubicChunksClient.getClientCubeWorld();
            if (cubeWorld != null) {
                int cubeX = Math.floorDiv(pos.getX(), CubeChunk.SIZE);
                int cubeY = Math.floorDiv(pos.getY(), CubeChunk.SIZE);
                int cubeZ = Math.floorDiv(pos.getZ(), CubeChunk.SIZE);
                
                CubeChunk cube = cubeWorld.getCube(cubeX, cubeY, cubeZ, true);
                if (cube != null) {
                    int localX = Math.floorMod(pos.getX(), CubeChunk.SIZE);
                    int localY = Math.floorMod(pos.getY(), CubeChunk.SIZE);
                    int localZ = Math.floorMod(pos.getZ(), CubeChunk.SIZE);
                    cube.setBlockState(localX, localY, localZ, state);
                    cir.setReturnValue(true);
                } else {
                    cir.setReturnValue(false);
                }
            }
        }
    }
} 