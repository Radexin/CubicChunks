package com.radexin.cubicchunks.mixin;

import com.radexin.cubicchunks.world.CubeWorld;
import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.gen.CubeChunkGenerator;
import net.minecraft.world.level.Level;
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

@Mixin(Level.class)
public abstract class LevelMixin {
    
    @Unique
    private CubeWorld cubicchunks$cubeWorld;
    
    @Shadow
    public abstract boolean isClientSide();
    
    @Shadow
    public abstract int getMinBuildHeight();
    
    @Shadow
    public abstract int getMaxBuildHeight();
    
    @Inject(method = "<init>", at = @At("TAIL"))
    private void cubicchunks$initCubeWorld(CallbackInfo ci) {
        this.cubicchunks$cubeWorld = new CubeWorld(new CubeChunkGenerator());
    }
    
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void cubicchunks$getBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        // Allow unlimited Y access by using cubic chunks
        if (pos.getY() < this.getMinBuildHeight() || pos.getY() >= this.getMaxBuildHeight()) {
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
    private void cubicchunks$setBlock(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {
        // Allow unlimited Y access by using cubic chunks
        if (pos.getY() < this.getMinBuildHeight() || pos.getY() >= this.getMaxBuildHeight()) {
            int cubeX = Math.floorDiv(pos.getX(), CubeChunk.SIZE);
            int cubeY = Math.floorDiv(pos.getY(), CubeChunk.SIZE);
            int cubeZ = Math.floorDiv(pos.getZ(), CubeChunk.SIZE);
            
            CubeChunk cube = cubicchunks$cubeWorld.getCube(cubeX, cubeY, cubeZ, true);
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
    
    @Inject(method = "getMinBuildHeight", at = @At("HEAD"), cancellable = true)
    private void cubicchunks$removeMinBuildHeight(CallbackInfoReturnable<Integer> cir) {
        // Remove build height limit - allow infinite vertical building
        cir.setReturnValue(Integer.MIN_VALUE / 2); // Use safe minimum to avoid overflow
    }
    
    @Inject(method = "getMaxBuildHeight", at = @At("HEAD"), cancellable = true)
    private void cubicchunks$removeMaxBuildHeight(CallbackInfoReturnable<Integer> cir) {
        // Remove build height limit - allow infinite vertical building
        cir.setReturnValue(Integer.MAX_VALUE / 2); // Use safe maximum to avoid overflow
    }
    
    @Unique
    public CubeWorld cubicchunks$getCubeWorld() {
        return this.cubicchunks$cubeWorld;
    }
} 