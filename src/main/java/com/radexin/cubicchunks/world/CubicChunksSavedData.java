package com.radexin.cubicchunks.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import com.radexin.cubicchunks.gen.CubeChunkGenerator;
import net.minecraft.core.HolderLookup;

public class CubicChunksSavedData extends SavedData {
    private CubeWorld cubeWorld;
    private final CubeChunkGenerator generator;

    public CubicChunksSavedData(CubeWorld cubeWorld, CubeChunkGenerator generator) {
        this.cubeWorld = cubeWorld;
        this.generator = generator;
    }

    public CubeWorld getCubeWorld() {
        return cubeWorld;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        return cubeWorld.toNBT();
    }

    public static CubicChunksSavedData load(CompoundTag tag, HolderLookup.Provider provider, CubeChunkGenerator generator) {
        // Get biome registry from provider
        var biomeRegistryLookup = provider.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
        // Convert to Registry if needed - for now use null as fallback since Registry interface changed
        net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomeRegistry = null;
        CubeWorld world = CubeWorld.fromNBT(tag, generator, biomeRegistry);
        return new CubicChunksSavedData(world, generator);
    }

    public static SavedData.Factory<CubicChunksSavedData> factory(CubeChunkGenerator generator) {
        return new SavedData.Factory<>(
            () -> new CubicChunksSavedData(new CubeWorld(generator), generator),
            (nbt, provider) -> CubicChunksSavedData.load(nbt, provider, generator),
            null // DataFixTypes, set to null or your type if needed
        );
    }

    public static CubicChunksSavedData getOrCreate(MinecraftServer server, CubeChunkGenerator generator) {
        return server.overworld().getDataStorage().computeIfAbsent(
            CubicChunksSavedData.factory(generator),
            "cubicchunks"
        );
    }
} 