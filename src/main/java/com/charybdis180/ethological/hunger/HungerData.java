package com.charybdis180.ethological.hunger;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record HungerData(int hunger, int maxHunger, long nextDepletionGameTime, long nextStarveGameTime) {
    public static final HungerData DEFAULT = new HungerData(10, 10, 0L, 0L);
    public static final Codec<HungerData> CODEC = RecordCodecBuilder.create(instance -> instance.group(Codec.INT.fieldOf("hunger").forGetter(HungerData::hunger), Codec.INT.fieldOf("max_hunger").forGetter(HungerData::maxHunger), Codec.LONG.fieldOf("next_depletion").forGetter(HungerData::nextDepletionGameTime), Codec.LONG.fieldOf("next_starve").forGetter(HungerData::nextStarveGameTime)).apply(instance, HungerData::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, HungerData> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, HungerData::hunger, ByteBufCodecs.VAR_INT, HungerData::maxHunger, ByteBufCodecs.VAR_LONG, HungerData::nextDepletionGameTime, ByteBufCodecs.VAR_LONG, HungerData::nextStarveGameTime, HungerData::new);

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

