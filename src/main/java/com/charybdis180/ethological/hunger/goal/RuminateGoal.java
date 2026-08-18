/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.ai.goal.Goal
 *  net.minecraft.world.entity.ai.goal.Goal$Flag
 *  net.minecraft.world.entity.animal.Animal
 */
package com.charybdis180.ethological.hunger.goal;

import com.charybdis180.ethological.home.NomadicMigration;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.hunger.HungerAttachments;
import com.charybdis180.ethological.sleep.SleepAttachments;
import java.util.EnumSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;

public class RuminateGoal
extends Goal {
    private final Animal mob;

    public RuminateGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    public boolean canUse() {
        return this.shouldRuminate();
    }

    public boolean canContinueToUse() {
        return this.shouldRuminate();
    }

    public void start() {
        this.mob.getNavigation().stop();
    }

    public void tick() {
        this.mob.getNavigation().stop();
    }

    private boolean shouldRuminate() {
        if (((Boolean)this.mob.getData(SleepAttachments.SLEEPING)).booleanValue() || this.mob.hasData(SleepAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        if (NomadicMigration.isTraveling(this.mob)) {
            return false;
        }
        // A starving mob must eat, not ruminate. A stale RUMINATE_UNTIL stamped on every
        // herd member by beginHerdRuminate kept a 0-hunger cow locked into RuminateGoal
        // (both goals are priority 5 and ruminate registers first), and EatFoodGoal's
        // own isRuminating gate then blocked the urgent eat — the cow starved while
        // standing still. Clear the stale timer so the eat goal can claim it this tick.
        if (Hunger.isUrgentlyHungry((Entity)this.mob)) {
            this.mob.removeData(HungerAttachments.RUMINATE_UNTIL);
            return false;
        }
        return Hunger.isRuminating((Entity)this.mob);
    }
}

