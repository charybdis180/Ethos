package com.charybdis180.ethological.social;

import net.minecraft.core.BlockPos;

public record StartleData(BlockPos fromPos, long untilGameTime) {
    public static final StartleData DEFAULT = new StartleData(BlockPos.ZERO, 0L);
}

