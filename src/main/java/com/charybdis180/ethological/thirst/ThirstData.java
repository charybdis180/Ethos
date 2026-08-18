/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.datafixers.kinds.App
 *  com.mojang.datafixers.kinds.Applicative
 *  com.mojang.serialization.Codec
 *  com.mojang.serialization.codecs.RecordCodecBuilder
 *  net.minecraft.network.RegistryFriendlyByteBuf
 *  net.minecraft.network.codec.ByteBufCodecs
 *  net.minecraft.network.codec.StreamCodec
 */
package com.charybdis180.ethological.thirst;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record ThirstData(int thirst, int maxThirst, long nextDepletionGameTime, long nextDehydrateGameTime, long lastDrinkGameTime) {
    public static final ThirstData DEFAULT = new ThirstData(10, 10, 0L, 0L, 0L);
    public static final Codec<ThirstData> CODEC = RecordCodecBuilder.create(instance -> instance.group(Codec.INT.fieldOf("thirst").forGetter(ThirstData::thirst), Codec.INT.fieldOf("max_thirst").forGetter(ThirstData::maxThirst), Codec.LONG.fieldOf("next_depletion").forGetter(ThirstData::nextDepletionGameTime), Codec.LONG.fieldOf("next_dehydrate").forGetter(ThirstData::nextDehydrateGameTime), Codec.LONG.fieldOf("last_drink").forGetter(ThirstData::lastDrinkGameTime)).apply(instance, ThirstData::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, ThirstData> STREAM_CODEC = StreamCodec.composite((StreamCodec)ByteBufCodecs.VAR_INT, ThirstData::thirst, (StreamCodec)ByteBufCodecs.VAR_INT, ThirstData::maxThirst, (StreamCodec)ByteBufCodecs.VAR_LONG, ThirstData::nextDepletionGameTime, (StreamCodec)ByteBufCodecs.VAR_LONG, ThirstData::nextDehydrateGameTime, (StreamCodec)ByteBufCodecs.VAR_LONG, ThirstData::lastDrinkGameTime, ThirstData::new);

    public ThirstData withThirst(int value) {
        return new ThirstData(value, this.maxThirst, this.nextDepletionGameTime, this.nextDehydrateGameTime, this.lastDrinkGameTime);
    }

    public ThirstData withMaxThirst(int value) {
        return new ThirstData(this.thirst, value, this.nextDepletionGameTime, this.nextDehydrateGameTime, this.lastDrinkGameTime);
    }

    public ThirstData withNextDepletion(long gameTime) {
        return new ThirstData(this.thirst, this.maxThirst, gameTime, this.nextDehydrateGameTime, this.lastDrinkGameTime);
    }

    public ThirstData withNextDehydrate(long gameTime) {
        return new ThirstData(this.thirst, this.maxThirst, this.nextDepletionGameTime, gameTime, this.lastDrinkGameTime);
    }

    public ThirstData withLastDrink(long gameTime) {
        return new ThirstData(this.thirst, this.maxThirst, this.nextDepletionGameTime, this.nextDehydrateGameTime, gameTime);
    }
}

