package com.charybdis180.ethological.sleep;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

public record SleepDisturbance(UUID threatId, long disturbedGameTime) {
    public static final SleepDisturbance DEFAULT = new SleepDisturbance(new UUID(0L, 0L), 0L);
    public static final Codec<SleepDisturbance> CODEC = RecordCodecBuilder.create(instance -> instance.group(UUIDUtil.CODEC.fieldOf("threat").forGetter(SleepDisturbance::threatId), Codec.LONG.fieldOf("disturbed_at").forGetter(SleepDisturbance::disturbedGameTime)).apply(instance, SleepDisturbance::new));
}

