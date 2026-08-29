package com.charybdis180.ethological.growth;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record GrowthData(long birthGameTime, long growUpGameTime, float initialScale, float targetScale) {
    public static final GrowthData DEFAULT = new GrowthData(0L, 0L, 1.0f, 1.0f);
    public static final Codec<GrowthData> CODEC = RecordCodecBuilder.create(instance -> instance.group(Codec.LONG.fieldOf("birth_time").forGetter(GrowthData::birthGameTime), Codec.LONG.fieldOf("grow_up_time").forGetter(GrowthData::growUpGameTime), Codec.FLOAT.fieldOf("initial_scale").forGetter(GrowthData::initialScale), Codec.FLOAT.fieldOf("target_scale").forGetter(GrowthData::targetScale)).apply(instance, GrowthData::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, GrowthData> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, GrowthData::birthGameTime, ByteBufCodecs.VAR_LONG, GrowthData::growUpGameTime, ByteBufCodecs.FLOAT, GrowthData::initialScale, ByteBufCodecs.FLOAT, GrowthData::targetScale, GrowthData::new);
}

