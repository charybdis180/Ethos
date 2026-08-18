/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.world.entity.ai.goal.Goal
 *  net.minecraft.world.entity.ai.goal.Goal$Flag
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.level.block.Block
 *  net.minecraft.world.level.block.Blocks
 *  net.minecraft.world.level.block.CropBlock
 *  net.minecraft.world.level.block.state.BlockState
 */
package com.charybdis180.ethological.trample;

import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.hunger.FoodTargetData;
import com.charybdis180.ethological.hunger.HungerAttachments;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

public class TrampleGoal
extends Goal {
    private final Animal mob;
    private int trampleCooldown;

    public TrampleGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.noneOf(Goal.Flag.class));
    }

    public boolean canUse() {
        return this.isWalkingOverCrop();
    }

    public boolean canContinueToUse() {
        return this.isWalkingOverCrop();
    }

    public void start() {
        this.trampleCooldown = 0;
    }

    public void tick() {
        if (this.trampleCooldown > 0) {
            --this.trampleCooldown;
            return;
        }
        this.trampleCooldown = 20;
        BlockPos cropPos = this.cropUnderfoot();
        if (cropPos != null) {
            Level level = this.mob.level();
            BlockState state = level.getBlockState(cropPos);
            level.levelEvent(2001, cropPos, Block.getId((BlockState)state));
            Block.dropResources(state, level, cropPos, null, this.mob, this.mob.getMainHandItem());
            level.setBlock(cropPos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private boolean isWalkingOverCrop() {
        if (this.mob.getNavigation().isDone()) {
            return false;
        }
        BlockPos cropPos = this.cropUnderfoot();
        if (cropPos == null) {
            return false;
        }
        if (this.isFoodTarget(cropPos)) {
            return false;
        }
        return !HerdManager.foodTargetsOfHerdMates(this.mob).contains(cropPos);
    }

    private BlockPos cropUnderfoot() {
        BlockPos feet = this.mob.blockPosition();
        Level level = this.mob.level();
        if (level.getBlockState(feet).getBlock() instanceof CropBlock) {
            return feet;
        }
        BlockPos above = feet.above();
        if (level.getBlockState(above).getBlock() instanceof CropBlock) {
            return above;
        }
        return null;
    }

    private boolean isFoodTarget(BlockPos cropPos) {
        if (!this.mob.hasData(HungerAttachments.FOOD_TARGET)) {
            return false;
        }
        BlockPos target = ((FoodTargetData)this.mob.getData(HungerAttachments.FOOD_TARGET)).pos();
        return cropPos.equals(target) || cropPos.equals((Object)target.above());
    }
}

