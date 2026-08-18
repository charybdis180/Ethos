package com.charybdis180.ethological.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Most-familiar player + trust score. Caps/gains come from comfort config. */
public record FamiliarityData(UUID playerId, int score) {
    public static final FamiliarityData DEFAULT = new FamiliarityData(new UUID(0L, 0L), 0);

    public static final Codec<FamiliarityData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("player_id").forGetter(FamiliarityData::playerId),
            Codec.INT.fieldOf("score").forGetter(FamiliarityData::score)
    ).apply(instance, FamiliarityData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, FamiliarityData> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, FamiliarityData::playerId,
            ByteBufCodecs.VAR_INT, FamiliarityData::score,
            FamiliarityData::new);

    public boolean isEmpty() {
        return this.score <= 0
                || (this.playerId.getMostSignificantBits() == 0L && this.playerId.getLeastSignificantBits() == 0L);
    }

    public FamiliarityData withScore(int value, int maxScore) {
        return new FamiliarityData(this.playerId, Math.max(0, Math.min(maxScore, value)));
    }

    public FamiliarityData bump(UUID player, int gain, int maxScore) {
        if (this.isEmpty() || this.playerId.equals(player)) {
            int base = this.playerId.equals(player) ? this.score : 0;
            return new FamiliarityData(player, Math.min(maxScore, base + gain));
        }
        if (gain >= this.score) {
            return new FamiliarityData(player, Math.min(maxScore, gain));
        }
        return this.withScore(Math.max(0, this.score - 1), maxScore);
    }

    public boolean trusts(UUID player, int threshold) {
        return !this.isEmpty() && this.playerId.equals(player) && this.score >= threshold;
    }
}
