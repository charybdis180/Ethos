package com.charybdis180.ethological.hunger;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public record FoodTargetData(BlockPos pos) {
    public static final FoodTargetData DEFAULT = new FoodTargetData(BlockPos.ZERO);
    public static final StreamCodec<RegistryFriendlyByteBuf, FoodTargetData> STREAM_CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, FoodTargetData::pos, FoodTargetData::new);
}

