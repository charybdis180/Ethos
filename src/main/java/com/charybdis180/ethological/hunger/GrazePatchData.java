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

public record GrazePatchData(BlockPos center) {
    public static final GrazePatchData DEFAULT = new GrazePatchData(BlockPos.ZERO);
    public static final StreamCodec<RegistryFriendlyByteBuf, GrazePatchData> STREAM_CODEC = StreamCodec.composite((StreamCodec)BlockPos.STREAM_CODEC, GrazePatchData::center, GrazePatchData::new);
}

