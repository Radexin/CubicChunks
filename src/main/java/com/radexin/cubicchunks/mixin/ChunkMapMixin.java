package com.radexin.cubicchunks.mixin;

import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.world.CubeWorld;
import com.radexin.cubicchunks.gen.CubicWorldGenerator;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mixin to ChunkMap to integrate cubic chunk loading with vanilla chunk system.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    
    @Shadow
    public abstract ServerLevel getLevel();
    
    @Unique
    private CubeWorld cubicchunks$cubeWorld;
    
    @Unique
    private final Map<String, CompoundTag> cubicchunks$cubeCache = new ConcurrentHashMap<>();
    
    @Inject(method = "<init>", at = @At("TAIL"))
    private void cubicchunks$initCubeWorld(CallbackInfo ci) {
        ServerLevel level = getLevel();
        var biomeRegistry = level.registryAccess().registry(net.minecraft.core.registries.Registries.BIOME).get();
        CubicWorldGenerator generator = new CubicWorldGenerator(level, biomeRegistry, null);
        this.cubicchunks$cubeWorld = new CubeWorld(generator, null, level);
    }
    
    @Inject(method = "save", at = @At("HEAD"))
    private void cubicchunks$saveCubes(ChunkAccess chunk, CallbackInfo ci) {
        // Save all cubic chunks for this column
        ChunkPos pos = chunk.getPos();
        var column = cubicchunks$cubeWorld.getColumn(pos.x, pos.z, false);
        if (column != null) {
            for (CubeChunk cube : column.getLoadedCubes()) {
                String cubeKey = cubicchunks$getCubeKey(cube.getCubeX(), cube.getCubeY(), cube.getCubeZ());
                cubicchunks$cubeCache.put(cubeKey, cube.toNBT());
            }
        }
    }
    
    @Inject(method = "readChunk", at = @At("HEAD"))
    private void cubicchunks$loadCubes(ChunkPos pos, CallbackInfoReturnable<CompoundTag> cir) {
        // Load cubic chunks for this column position
        var column = cubicchunks$cubeWorld.getColumn(pos.x, pos.z, true);
        
        // Get biome registry from server level
        var biomeRegistry = getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        
        // Load cached cubes for this column
        for (Map.Entry<String, CompoundTag> entry : cubicchunks$cubeCache.entrySet()) {
            String[] coords = entry.getKey().split(",");
            if (coords.length == 3) {
                int cubeX = Integer.parseInt(coords[0]);
                int cubeZ = Integer.parseInt(coords[2]);
                
                if (cubeX == pos.x && cubeZ == pos.z) {
                    CubeChunk cube = CubeChunk.fromNBT(entry.getValue(), biomeRegistry);
                    // Add cube to column (assuming proper integration exists)
                    column.getCube(cube.getCubeY(), true);
                }
            }
        }
    }
    
    @Unique
    private String cubicchunks$getCubeKey(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
    
    @Unique
    public CubeWorld cubicchunks$getCubeWorld() {
        return this.cubicchunks$cubeWorld;
    }
    
    @Unique
    public void cubicchunks$loadCube(int x, int y, int z) {
        // Force load a specific cube
        CubeChunk cube = cubicchunks$cubeWorld.getCube(x, y, z, true);
        if (cube != null) {
            String cubeKey = cubicchunks$getCubeKey(x, y, z);
            CompoundTag cached = cubicchunks$cubeCache.get(cubeKey);
            if (cached != null) {
                // Get biome registry from server level
                var biomeRegistry = getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
                
                // Load from cache
                CubeChunk loaded = CubeChunk.fromNBT(cached, biomeRegistry);
                // Replace cube data
                for (int lx = 0; lx < CubeChunk.SIZE; lx++) {
                    for (int ly = 0; ly < CubeChunk.SIZE; ly++) {
                        for (int lz = 0; lz < CubeChunk.SIZE; lz++) {
                            cube.setBlockState(lx, ly, lz, loaded.getBlockState(lx, ly, lz));
                        }
                    }
                }
            }
        }
    }
} 