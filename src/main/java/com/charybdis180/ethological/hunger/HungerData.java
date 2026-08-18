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
package com.charybdis180.ethological.hunger;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record HungerData(int hunger, int maxHunger, long nextDepletionGameTime, long nextStarveGameTime) {
    public static final HungerData DEFAULT = new HungerData(10, 10, 0L, 0L);
    public static final Codec<HungerData> CODEC = RecordCodecBuilder.create(instance -> instance.group(Codec.INT.fieldOf("hunger").forGetter(HungerData::hunger), Codec.INT.fieldOf("max_hunger").forGetter(HungerData::maxHunger), Codec.LONG.fieldOf("next_depletion").forGetter(HungerData::nextDepletionGameTime), Codec.LONG.fieldOf("next_starve").forGetter(HungerData::nextStarveGameTime)).apply(instance, HungerData::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, HungerData> STREAM_CODEC = StreamCodec.composite((StreamCodec)ByteBufCodecs.VAR_INT, HungerData::hunger, (StreamCodec)ByteBufCodecs.VAR_INT, HungerData::maxHunger, (StreamCodec)ByteBufCodecs.VAR_LONG, HungerData::nextDepletionGameTime, (StreamCodec)ByteBufCodecs.VAR_LONG, HungerData::nextStarveGameTime, HungerData::new);

    public HungerData withHunger(int value) {
        return new HungerData(value, this.maxHunger, this.nextDepletionGameTime, this.nextStarveGameTime);
    }

    public HungerData withMaxHunger(int value) {
        return new HungerData(this.hunger, value, this.nextDepletionGameTime, this.nextStarveGameTime);
    }

    public HungerData withNextDepletion(long gameTime) {
        return new HungerData(this.hunger, this.maxHunger, gameTime, this.nextStarveGameTime);
    }

    public HungerData withNextStarve(long gameTime) {
        return new HungerData(this.hunger, this.maxHunger, this.nextDepletionGameTime, gameTime);
    }
}

