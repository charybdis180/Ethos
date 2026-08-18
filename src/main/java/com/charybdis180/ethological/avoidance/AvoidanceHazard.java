package com.charybdis180.ethological.avoidance;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

/** Transient hard-hazard avoidance target (fire / lava block). */
public record AvoidanceHazard(BlockPos pos, long detectedGameTime) {
    public static final AvoidanceHazard DEFAULT = new AvoidanceHazard(BlockPos.ZERO, 0L);
    public static final Codec<AvoidanceHazard> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(AvoidanceHazard::pos),
            Codec.LONG.fieldOf("detected_at").forGetter(AvoidanceHazard::detectedGameTime)
    ).apply(instance, AvoidanceHazard::new));
}
