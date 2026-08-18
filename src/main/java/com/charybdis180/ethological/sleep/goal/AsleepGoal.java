/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.world.entity.ai.goal.Goal
 *  net.minecraft.world.entity.ai.goal.Goal$Flag
 *  net.minecraft.world.entity.animal.Animal
 */
package com.charybdis180.ethological.sleep.goal;

import com.charybdis180.ethological.sleep.SleepAttachments;
import java.util.EnumSet;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;

public class AsleepGoal
extends Goal {
    private final Animal mob;

    public AsleepGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    public boolean canUse() {
        return (Boolean)this.mob.getData(SleepAttachments.SLEEPING);
    }

    public boolean canContinueToUse() {
        return this.canUse();
    }

    public void start() {
    }

    public void stop() {
    }

    public void tick() {
    }
}

