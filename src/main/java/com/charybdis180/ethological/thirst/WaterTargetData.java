package com.charybdis180.ethological.thirst;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public record WaterTargetData(BlockPos pos, BlockPos shore) {
    public static final WaterTargetData DEFAULT = new WaterTargetData(BlockPos.ZERO, BlockPos.ZERO);
    public static final StreamCodec<RegistryFriendlyByteBuf, WaterTargetData> STREAM_CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, WaterTargetData::pos, BlockPos.STREAM_CODEC, WaterTargetData::shore, WaterTargetData::new);

    public WaterTargetData(BlockPos pos) {
        this(pos, pos);
    }
}

