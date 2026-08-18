/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.network.RegistryFriendlyByteBuf
 *  net.minecraft.network.codec.StreamCodec
 */
package com.charybdis180.ethological.hunger;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public record FoodTargetData(BlockPos pos) {
    public static final FoodTargetData DEFAULT = new FoodTargetData(BlockPos.ZERO);
    public static final StreamCodec<RegistryFriendlyByteBuf, FoodTargetData> STREAM_CODEC = StreamCodec.composite((StreamCodec)BlockPos.STREAM_CODEC, FoodTargetData::pos, FoodTargetData::new);
}

