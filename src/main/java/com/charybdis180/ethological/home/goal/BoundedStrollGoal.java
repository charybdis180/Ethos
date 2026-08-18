package com.charybdis180.ethological.home.goal;

import com.charybdis180.ethological.avoidance.CliffAvoidance;
import com.charybdis180.ethological.herd.HerdAttachments;
import com.charybdis180.ethological.home.HomeSettingsManager;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.home.SpeciesHomeSettings;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Vanilla random strolling, but destinations beyond the home leash
 * ({@link Homes#allowedRadius}) are rejected. Replaces the vanilla RandomStrollGoal
 * for species with home settings, so idle wandering never carries the animal farther
 * from home than it can return from before sunset.
 */
public class BoundedStrollGoal extends RandomStrollGoal {
    private final Animal animal;

    public BoundedStrollGoal(Animal animal, double speedModifier, int interval) {
        super(animal, speedModifier, interval);
        this.animal = animal;
    }

    @Override
    public boolean canUse() {
        // Babies with a recorded mother follow her instead of wandering (both this
        // and FollowParentGoal sit at priority 6 and would fight over MOVE).
        if (this.animal.isBaby() && this.animal.hasData(HerdAttachments.MOTHER)) {
            return false;
        }
        Optional<SpeciesSleepSettings> sleepSettings = SleepSettingsManager.get(this.animal.getType());
        if (sleepSettings.isPresent()) {
            SpeciesSleepSettings sleep = sleepSettings.get();
            long dayTime = this.animal.level().getDayTime();
            if (sleep.isSleepTime(dayTime) && !Homes.shouldTravelHome(this.animal, dayTime, sleep)) {
                return false;
            }
        }
        // No home: MigrateGoal owns movement at the same priority (nomadic migration
        // or water search). RandomStrollGoal.canUse() would win the slot otherwise.
        if (Homes.effectiveHome(this.animal).isEmpty()) {
            return false;
        }
        return super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        if (Homes.effectiveHome(this.animal).isEmpty()) {
            return false;
        }
        return super.canContinueToUse();
    }

    @Override
    protected Vec3 getPosition() {
        Optional<SpeciesHomeSettings> homeSettings = HomeSettingsManager.get(this.animal.getType());
        Optional<SpeciesSleepSettings> sleepSettings = SleepSettingsManager.get(this.animal.getType());
        Optional<BlockPos> home = Homes.effectiveHome(this.animal);
        if (homeSettings.isEmpty() || sleepSettings.isEmpty() || home.isEmpty()) {
            // No home: MigrateGoal handles movement (directional water search).
            return null;
        }
        double radius = Homes.allowedRadius(this.animal, homeSettings.get(),
                this.animal.level().getDayTime(), sleepSettings.get().sleepStartTick());
        double radiusSqr = radius * radius;
        boolean raining = this.animal.level().isRainingAt(this.animal.blockPosition());
        if (raining) {
            Optional<BlockPos> shelter = Homes.findBetterShelteredHome(
                    this.animal.level(), this.animal.blockPosition(), 8, 0, false);
            if (shelter.isPresent() && shelter.get().distSqr(home.get()) <= radiusSqr) {
                return Vec3.atBottomCenterOf(shelter.get());
            }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            Vec3 candidate = super.getPosition();
            if (candidate == null) {
                return null;
            }
            if (candidate.distanceToSqr(Vec3.atCenterOf(home.get())) > radiusSqr) {
                continue;
            }
            if (!CliffAvoidance.isEdgeSafe(this.animal.level(), BlockPos.containing(candidate))) {
                continue; // never target a cliff lip for an idle wander
            }
            if (raining) {
                BlockPos stand = BlockPos.containing(candidate);
                if (!Homes.isSheltered(this.animal.level(), stand)) {
                    Optional<BlockPos> near = Homes.findBetterShelteredHome(
                            this.animal.level(), stand, 6, 0, false);
                    if (near.isPresent() && near.get().distSqr(home.get()) <= radiusSqr) {
                        return Vec3.atBottomCenterOf(near.get());
                    }
                    continue; // keep looking for a sheltered stroll spot
                }
            }
            return candidate;
        }
        return null; // nothing acceptable nearby: stand still rather than drift too far
    }
}
