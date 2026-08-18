/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.Vec3i
 *  net.minecraft.world.entity.PathfinderMob
 *  net.minecraft.world.entity.ai.goal.Goal
 *  net.minecraft.world.entity.ai.goal.Goal$Flag
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.level.pathfinder.Path
 *  net.minecraft.world.phys.Vec3
 */
package com.charybdis180.ethological.social.goal;

import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.social.SocialAttachments;
import com.charybdis180.ethological.social.StartleData;
import com.charybdis180.ethological.util.FleePathing;
import java.util.EnumSet;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

public class StartleGoal
extends Goal {
    private final Animal mob;
    private int repathCooldown;

    public StartleGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    private StartleData activeStartle() {
        if (!this.mob.hasData(SocialAttachments.STARTLE)) {
            return null;
        }
        StartleData data = (StartleData)this.mob.getData(SocialAttachments.STARTLE);
        if (this.mob.level().getGameTime() >= data.untilGameTime()) {
            this.mob.removeData(SocialAttachments.STARTLE);
            return null;
        }
        if (HerdManager.panicPhaseOf(this.mob, this.mob.level().getGameTime()) != HerdManager.PanicPhase.NONE) {
            return null;
        }
        return data;
    }

    public boolean canUse() {
        return this.activeStartle() != null;
    }

    public boolean canContinueToUse() {
        return this.activeStartle() != null;
    }

    public void start() {
        this.repathCooldown = 0;
        this.bolt();
    }

    public void tick() {
        if (this.repathCooldown > 0) {
            --this.repathCooldown;
        } else if (this.mob.getNavigation().isDone()) {
            this.repathCooldown = 8;
            this.bolt();
        }
    }

    public void stop() {
        this.mob.getNavigation().stop();
        this.mob.removeData(SocialAttachments.STARTLE);
    }

    private void bolt() {
        StartleData data = (StartleData)this.mob.getData(SocialAttachments.STARTLE);
        Vec3 from = Vec3.atCenterOf((Vec3i)data.fromPos());
        Path path = FleePathing.beelineAway((PathfinderMob)this.mob, from, 10.0, 4);
        if (path != null) {
            this.mob.getNavigation().moveTo(path, 1.5);
        }
    }
}

