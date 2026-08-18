/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Vec3i
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.ai.goal.Goal
 *  net.minecraft.world.entity.ai.goal.Goal$Flag
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.phys.Vec3
 */
package com.charybdis180.ethological.sleep.goal;

import com.charybdis180.ethological.config.EthologicalConfig;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.herd.goal.FollowPathing;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.sleep.SleepEvents;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import com.charybdis180.ethological.thirst.Thirst;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;

public class SettleForSleepGoal
extends Goal {
    private static final int SHELTER_SEARCH_RADIUS = 8;
    private final Animal mob;
    private BlockPos settleTarget;

    public SettleForSleepGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    public boolean canUse() {
        return this.shouldSettle();
    }

    public boolean canContinueToUse() {
        return this.shouldSettle();
    }

    public void start() {
        BlockPos here = this.mob.blockPosition();
        int spacing = (Integer)EthologicalConfig.CONFIG.comfort.sleepSpotSpacing.get();
        Set<BlockPos> occupied = new HashSet<BlockPos>();
        HerdManager.Herd herd = HerdManager.herdOf(this.mob);
        if (herd != null && this.mob.level() instanceof ServerLevel) {
            ServerLevel serverLevel = (ServerLevel)this.mob.level();
            occupied.addAll(herd.sleepSpotClaimsOf(this.mob, serverLevel.getGameTime()));
            for (UUID id : herd.members) {
                if (id.equals(this.mob.getUUID())) {
                    continue;
                }
                Entity mate = serverLevel.getEntity(id);
                if (mate != null) {
                    occupied.add(mate.blockPosition());
                }
            }
        }
        this.settleTarget = Homes.findUnoccupiedStand(this.mob.level(), here, SHELTER_SEARCH_RADIUS, occupied, spacing)
                .orElseGet(() -> Homes.findBetterShelteredHome(this.mob.level(), here, SHELTER_SEARCH_RADIUS, 0, false)
                        .orElseGet(() -> Homes.findCliffSafeStand(this.mob.level(), here, SHELTER_SEARCH_RADIUS).orElse(here)));
        if (herd != null) {
            herd.setSleepSpotClaim(this.mob.getUUID(), this.settleTarget, this.mob.level().getGameTime());
        }
        this.mob.setData(SleepAttachments.SLEEP_TARGET,this.settleTarget.immutable());
        if (this.settleTarget.closerThan((Vec3i)here, 1.5)) {
            this.mob.getNavigation().stop();
        } else {
            this.mob.getNavigation().moveTo((double)this.settleTarget.getX() + 0.5, (double)this.settleTarget.getY(), (double)this.settleTarget.getZ() + 0.5, 1.0);
        }
    }

    public void stop() {
        HerdManager.Herd herd = HerdManager.herdOf(this.mob);
        if (herd != null) {
            herd.clearSleepSpotClaim(this.mob.getUUID());
        }
        // Keep the sleep target visible while the animal is asleep — the morning wake
        // (SleepEvents.wake) is what clears it. Only drop it here if the animal was
        // interrupted before actually falling asleep.
        if (!((Boolean)this.mob.getData(SleepAttachments.SLEEPING)).booleanValue()) {
            this.mob.removeData(SleepAttachments.SLEEP_TARGET);
        }
        this.settleTarget = null;
    }

    public void tick() {
        if (this.settleTarget == null) {
            this.mob.getNavigation().stop();
            return;
        }
        double distSqr = this.mob.distanceToSqr(Vec3.atBottomCenterOf((Vec3i)this.settleTarget));
        if (distSqr <= 2.25) {
            this.mob.getNavigation().stop();
            return;
        }
        if (this.mob.getNavigation().isDone()) {
            this.mob.getNavigation().moveTo((double)this.settleTarget.getX() + 0.5, (double)this.settleTarget.getY(), (double)this.settleTarget.getZ() + 0.5, 1.0);
        }
    }

    private boolean shouldSettle() {
        long dayTime;
        if (((Boolean)this.mob.getData(SleepAttachments.SLEEPING)).booleanValue()) {
            return false;
        }
        if (this.mob.hasData(SleepAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        if (Hunger.isUrgentlyHungry((Entity)this.mob) || Thirst.isUrgentlyThirsty((Entity)this.mob)) {
            return false;
        }
        // A herd member farther than its species' configurable sleep radius from its alpha
        // must rejoin the herd first — settling away from the alpha both splits the
        // sleeping herd and, because this goal outranks FollowAlphaGoal, cancels the escape
        // path that would have walked it back (the "escaping but standing still" freeze).
        double _sleepR = FollowPathing.sleepRadius(this.mob);
        if (FollowPathing.distanceToAlpha(this.mob) > _sleepR) {
            return false;
        }
        Optional<SpeciesSleepSettings> settingsOpt = SleepSettingsManager.get(this.mob.getType());
        if (settingsOpt.isEmpty()) {
            return false;
        }
        SpeciesSleepSettings settings = settingsOpt.get();
        if (!settings.isSleepTime(dayTime = this.mob.level().getDayTime())) {
            return false;
        }
        if (Homes.shouldTravelHome(this.mob, dayTime, settings)) {
            return false;
        }
        return Homes.ticksIntoSleepWindow(dayTime, settings) < SleepEvents.bedtimeDelay(this.mob, settings);
    }
}

