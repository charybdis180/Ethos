package com.charybdis180.ethological.hunger;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public record GrazePatchData(BlockPos center) {
    public static final GrazePatchData DEFAULT = new GrazePatchData(BlockPos.ZERO);
    public static final StreamCodec<RegistryFriendlyByteBuf, GrazePatchData> STREAM_CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, GrazePatchData::center, GrazePatchData::new);
}

