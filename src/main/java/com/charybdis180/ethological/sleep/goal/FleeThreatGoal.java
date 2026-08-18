/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.PathfinderMob
 *  net.minecraft.world.entity.ai.goal.Goal
 *  net.minecraft.world.entity.ai.goal.Goal$Flag
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.level.pathfinder.Path
 */
package com.charybdis180.ethological.sleep.goal;

import com.charybdis180.ethological.herd.HerdAttachments;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.MotherData;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.sleep.SleepDisturbance;
import com.charybdis180.ethological.sleep.SleepEvents;
import com.charybdis180.ethological.util.FleePathing;
import java.util.EnumSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;

public class FleeThreatGoal
extends Goal {
    private final Animal mob;
    private LivingEntity threat;
    private double lastSpeed = Double.MAX_VALUE;
    private int repathCooldown;

    public FleeThreatGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    public boolean canUse() {
        this.threat = this.findThreat();
        return this.threat != null;
    }

    public boolean canContinueToUse() {
        this.threat = this.findThreat();
        return this.threat != null;
    }

    public void start() {
        this.repathCooldown = 0;
        this.moveAway();
    }

    public void tick() {
        if (this.threat == null) {
            return;
        }
        double speed = this.currentSpeed();
        if (this.repathCooldown > 0) {
            --this.repathCooldown;
            if (speed > this.lastSpeed) {
                this.repathCooldown = 10;
                this.moveAway();
            }
            return;
        }
        if (this.mob.getNavigation().isDone() || speed > this.lastSpeed) {
            this.repathCooldown = 10;
            this.moveAway();
        }
    }

    public void stop() {
        this.mob.getNavigation().stop();
        this.threat = null;
        this.lastSpeed = Double.MAX_VALUE;
    }

    private double currentSpeed() {
        return this.threat.distanceTo((Entity)this.mob) <= 8.0f ? 1.7 : 1.4;
    }

    private LivingEntity findThreat() {
        LivingEntity living;
        if (HerdManager.panicPhaseOf(this.mob, this.mob.level().getGameTime()) != HerdManager.PanicPhase.NONE) {
            return null;
        }
        Level level = this.mob.level();
        if (!(level instanceof ServerLevel)) {
            return null;
        }
        ServerLevel serverLevel = (ServerLevel)level;
        this.inheritMotherDisturbance(serverLevel);
        if (!this.mob.hasData(SleepAttachments.SLEEP_DISTURBANCE)) {
            return null;
        }
        SleepDisturbance disturbance = (SleepDisturbance)this.mob.getData(SleepAttachments.SLEEP_DISTURBANCE);
        Entity entity = serverLevel.getEntity(disturbance.threatId());
        if (!(entity instanceof LivingEntity) || !(living = (LivingEntity)entity).isAlive()) {
            return null;
        }
        return living.distanceTo((Entity)this.mob) <= 36.0f ? living : null;
    }

    /** Babies copy mother's disturbance so they flee the same threat alongside her. */
    private void inheritMotherDisturbance(ServerLevel serverLevel) {
        if (!this.mob.isBaby() || !this.mob.hasData(HerdAttachments.MOTHER)) {
            return;
        }
        MotherData motherData = (MotherData)this.mob.getData(HerdAttachments.MOTHER);
        if (this.mob.level().getGameTime() >= motherData.followUntilGameTime()) {
            return;
        }
        Entity motherEntity = serverLevel.getEntity(motherData.motherId());
        if (!(motherEntity instanceof Animal mother) || !mother.isAlive() || !mother.hasData(SleepAttachments.SLEEP_DISTURBANCE)) {
            return;
        }
        SleepDisturbance motherDisturbance = (SleepDisturbance)mother.getData(SleepAttachments.SLEEP_DISTURBANCE);
        if (!this.mob.hasData(SleepAttachments.SLEEP_DISTURBANCE)) {
            this.mob.setData(SleepAttachments.SLEEP_DISTURBANCE, motherDisturbance);
            if (((Boolean)this.mob.getData(SleepAttachments.SLEEPING)).booleanValue()) {
                SleepEvents.wake(this.mob);
            }
        }
    }

    private void moveAway() {
        double speed = this.currentSpeed();
        Path path = FleePathing.beelineAway((PathfinderMob)this.mob, this.threat.position(), 16.0, 8);
        if (path != null) {
            this.mob.getNavigation().moveTo(path, speed);
            this.lastSpeed = speed;
        }
    }
}

