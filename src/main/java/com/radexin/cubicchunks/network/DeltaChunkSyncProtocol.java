package com.radexin.cubicchunks.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import com.radexin.cubicchunks.CubicChunks;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;

/**
 * Delta synchronization protocol for efficient chunk updates.
 * Only transmits changed blocks rather than entire chunks.
 */
public record DeltaChunkSyncProtocol(int cubeX, int cubeY, int cubeZ, CompoundTag deltaData) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DeltaChunkSyncProtocol> TYPE = 
        new CustomPacketPayload.Type<>(ResourceLocation.parse(CubicChunks.MODID + ":delta_sync"));

    public static final StreamCodec<FriendlyByteBuf, DeltaChunkSyncProtocol> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, DeltaChunkSyncProtocol::cubeX,
        ByteBufCodecs.INT, DeltaChunkSyncProtocol::cubeY,
        ByteBufCodecs.INT, DeltaChunkSyncProtocol::cubeZ,
        ByteBufCodecs.COMPOUND_TAG, DeltaChunkSyncProtocol::deltaData,
        (x, y, z, data) -> new DeltaChunkSyncProtocol(x, y, z, data)
    );

    @Override
    public CustomPacketPayload.Type<DeltaChunkSyncProtocol> type() {
        return TYPE;
    }
} 