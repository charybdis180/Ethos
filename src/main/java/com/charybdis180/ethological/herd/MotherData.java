package com.charybdis180.ethological.herd;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record MotherData(UUID motherId, long followUntilGameTime) {
    public static final MotherData DEFAULT = new MotherData(new UUID(0L, 0L), 0L);
    public static final Codec<MotherData> CODEC = RecordCodecBuilder.create(instance -> instance.group(UUIDUtil.CODEC.fieldOf("mother_id").forGetter(MotherData::motherId), Codec.LONG.fieldOf("follow_until").forGetter(MotherData::followUntilGameTime)).apply(instance, MotherData::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, MotherData> STREAM_CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC, MotherData::motherId, ByteBufCodecs.VAR_LONG, MotherData::followUntilGameTime, MotherData::new);

    /** True when this is a real mother link that has not expired yet. */
    public boolean isActive(long gameTime) {
        return this.motherId.getMostSignificantBits() != 0L
                || this.motherId.getLeastSignificantBits() != 0L
                ? gameTime < this.followUntilGameTime
                : false;
    }

    public static MotherData create(UUID motherId, long now, long followTicks) {
        return new MotherData(motherId, now + Math.max(1L, followTicks));
    }
}

