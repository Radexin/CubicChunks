package com.radexin.cubicchunks.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import com.radexin.cubicchunks.CubicChunks;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;

public record CubeSyncPayload(int cubeX, int cubeY, int cubeZ, CompoundTag cubeData) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CubeSyncPayload> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.parse(CubicChunks.MODID + ":cube_sync"));

    public static final StreamCodec<FriendlyByteBuf, CubeSyncPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, CubeSyncPayload::cubeX,
        ByteBufCodecs.INT, CubeSyncPayload::cubeY,
        ByteBufCodecs.INT, CubeSyncPayload::cubeZ,
        ByteBufCodecs.COMPOUND_TAG, CubeSyncPayload::cubeData,
        (x, y, z, data) -> new CubeSyncPayload(x, y, z, data)
    );

    public CubeSyncPayload(FriendlyByteBuf buf) {
        this(buf.readInt(), buf.readInt(), buf.readInt(), buf.readNbt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(cubeX);
        buf.writeInt(cubeY);
        buf.writeInt(cubeZ);
        buf.writeNbt(cubeData);
    }

    @Override
    public CustomPacketPayload.Type<CubeSyncPayload> type() {
        return TYPE;
    }
} 