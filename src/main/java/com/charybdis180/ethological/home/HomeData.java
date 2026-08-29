package com.charybdis180.ethological.home;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record HomeData(BlockPos pos, boolean temporary) {
    public static final HomeData DEFAULT = new HomeData(BlockPos.ZERO, false);
    public static final Codec<HomeData> CODEC = RecordCodecBuilder.create(instance -> instance.group(BlockPos.CODEC.fieldOf("pos").forGetter(HomeData::pos), Codec.BOOL.optionalFieldOf("temporary",false).forGetter(HomeData::temporary)).apply(instance, HomeData::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, HomeData> STREAM_CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, HomeData::pos, ByteBufCodecs.BOOL, HomeData::temporary, HomeData::new);

    public HomeData(BlockPos pos) {
        this(pos, false);
    }
}

