/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.ai.goal.Goal
 *  net.minecraft.world.entity.ai.goal.Goal$Flag
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.entity.monster.Monster
 *  net.minecraft.world.entity.player.Player
 *  net.minecraft.world.phys.Vec3
 */
package com.charybdis180.ethological.social.goal;

import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.social.SocialAttachments;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class VigilanceGoal
extends Goal {
    private final Animal mob;
    private long nextRollGameTime;
    private int scanTicksLeft;
    private Vec3 lookTarget;

    public VigilanceGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.LOOK));
    }

    private boolean isAlert() {
        if (this.mob.level().getGameTime() < (Long)this.mob.getData(SleepAttachments.SLEEP_VIGILANCE)) {
            return true;
        }
        long t = (this.mob.level().getDayTime() % 24000L + 24000L) % 24000L;
        return t >= 10500L && t <= 13000L;
    }

    public boolean canUse() {
        long now = this.mob.level().getGameTime();
        if (now < this.nextRollGameTime) {
            return false;
        }
        if (((Boolean)this.mob.getData(SleepAttachments.SLEEPING)).booleanValue() || ((Boolean)this.mob.getData(SleepAttachments.RESTING)).booleanValue() || this.mob.hasData(SocialAttachments.PLAY)) {
            return false;
        }
        if (HerdManager.panicPhaseOf(this.mob, now) != HerdManager.PanicPhase.NONE) {
            return false;
        }
        boolean alert = this.isAlert();
        this.nextRollGameTime = now + (long)(alert ? 60 : 120) + (long)this.mob.getRandom().nextInt(120);
        float f = this.mob.getRandom().nextFloat();
        float f2 = alert ? 0.85f : 0.35f;
        if (f >= f2) {
            return false;
        }
        this.lookTarget = this.findLookTarget();
        this.scanTicksLeft = 30 + this.mob.getRandom().nextInt(30);
        return true;
    }

    public boolean canContinueToUse() {
        return this.scanTicksLeft > 0 && (Boolean)this.mob.getData(SleepAttachments.SLEEPING) == false && (Boolean)this.mob.getData(SleepAttachments.RESTING) == false && !this.mob.hasData(SocialAttachments.PLAY) && HerdManager.panicPhaseOf(this.mob, this.mob.level().getGameTime()) == HerdManager.PanicPhase.NONE;
    }

    public void tick() {
        if (this.lookTarget != null) {
            this.mob.getLookControl().setLookAt(this.lookTarget.x, this.lookTarget.y, this.lookTarget.z);
        }
        --this.scanTicksLeft;
    }

    public void stop() {
        this.lookTarget = null;
    }

    private Vec3 findLookTarget() {
        List<Player> players = this.mob.level().getEntitiesOfClass(Player.class, this.mob.getBoundingBox().inflate(16.0), player -> !player.isSpectator());
        if (!players.isEmpty()) {
            players.sort(Comparator.comparingDouble(player -> this.mob.distanceToSqr(player)));
            return players.get(0).getEyePosition();
        }
        List<Monster> monsters = this.mob.level().getEntitiesOfClass(Monster.class, this.mob.getBoundingBox().inflate(16.0), LivingEntity::isAlive);
        if (!monsters.isEmpty()) {
            monsters.sort(Comparator.comparingDouble(monster -> this.mob.distanceToSqr(monster)));
            return ((Monster)monsters.get(0)).getEyePosition();
        }
        double angle = this.mob.getRandom().nextDouble() * Math.PI * 2.0;
        double dist = 6.0 + this.mob.getRandom().nextDouble() * 6.0;
        return this.mob.position().add(Math.cos(angle) * dist, 0.5, Math.sin(angle) * dist);
    }
}

