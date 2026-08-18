/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.datafixers.kinds.App
 *  com.mojang.datafixers.kinds.Applicative
 *  com.mojang.serialization.Codec
 *  com.mojang.serialization.codecs.RecordCodecBuilder
 *  net.minecraft.core.UUIDUtil
 *  net.minecraft.network.RegistryFriendlyByteBuf
 *  net.minecraft.network.codec.ByteBufCodecs
 *  net.minecraft.network.codec.StreamCodec
 */
package com.charybdis180.ethological.herd;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record HerdData(UUID herdId, boolean alpha) {
    public static final HerdData DEFAULT = new HerdData(new UUID(0L, 0L), false);
    public static final Codec<HerdData> CODEC = RecordCodecBuilder.create(instance -> instance.group(UUIDUtil.CODEC.fieldOf("herd_id").forGetter(HerdData::herdId), Codec.BOOL.optionalFieldOf("alpha",false).forGetter(HerdData::alpha)).apply(instance, HerdData::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, HerdData> STREAM_CODEC = StreamCodec.composite((StreamCodec)UUIDUtil.STREAM_CODEC, HerdData::herdId, (StreamCodec)ByteBufCodecs.BOOL, HerdData::alpha, HerdData::new);
}

