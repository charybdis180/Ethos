package com.charybdis180.ethological.social.goal;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.herd.HerdManager;
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
        if (!this.mob.hasData(ModAttachments.STARTLE)) {
            return null;
        }
        StartleData data = (StartleData)this.mob.getData(ModAttachments.STARTLE);
        if (this.mob.level().getGameTime() >= data.untilGameTime()) {
            this.mob.removeData(ModAttachments.STARTLE);
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
        this.mob.removeData(ModAttachments.STARTLE);
    }

    private void bolt() {
        StartleData data = (StartleData)this.mob.getData(ModAttachments.STARTLE);
        Vec3 from = Vec3.atCenterOf(data.fromPos());
        Path path = FleePathing.beelineAway(this.mob, from, 10.0, 4);
        if (path != null) {
            this.mob.getNavigation().moveTo(path, 1.5);
        }
    }
}

