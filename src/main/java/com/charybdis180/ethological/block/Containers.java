package com.charybdis180.ethological.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class Containers {
    private Containers() {
    }

    public static void dropContents(Level level, BlockPos pos, Container container) {
        for (int i = 0; i < container.getContainerSize(); ++i) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) continue;
            ItemEntity entity = new ItemEntity(level, (double)pos.getX() + 0.5, (double)pos.getY() + 0.5, (double)pos.getZ() + 0.5, stack);
            level.addFreshEntity((Entity)entity);
        }
    }
}

