package com.charybdis180.ethological.home.goal;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.home.HomeSettingsManager;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.home.SpeciesHomeSettings;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import com.charybdis180.ethological.thirst.Thirst;
import java.util.EnumSet;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;

public class ReturnHomeGoal
extends Goal {
    private final Animal mob;
    private BlockPos homePos;
    private int repathCooldown;

    public ReturnHomeGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    public boolean canUse() {
        Optional<SpeciesHomeSettings> homeSettings = HomeSettingsManager.get(this.mob.getType());
        if (homeSettings.isEmpty()) {
            return false;
        }
        Optional<SpeciesSleepSettings> sleepSettings = SleepSettingsManager.get(this.mob.getType());
        if (sleepSettings.isEmpty()) {
            return false;
        }
        if (this.mob.getData(ModAttachments.SLEEPING) || this.mob.hasData(ModAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        if (Hunger.isUrgentlyHungry(this.mob) || Thirst.isUrgentlyThirsty(this.mob)) {
            return false;
        }
        long dayTime = this.mob.level().getDayTime();
        if (!Homes.shouldTravelHome(this.mob, dayTime, sleepSettings.get())) {
            return false;
        }
        if (!sleepSettings.get().isSleepTime(dayTime)) {
            Optional<BlockPos> home = Homes.effectiveHome(this.mob);
            if (home.isEmpty()) {
                return false;
            }
            double distance = Math.sqrt(this.mob.distanceToSqr(Vec3.atCenterOf(((Vec3i)home.get()))));
            long lead = Math.min(3000L, 200L + (long)(distance / 0.2));
            if (Homes.ticksUntilSleepStart(dayTime, sleepSettings.get().sleepStartTick()) > lead) {
                return false;
            }
        }
        this.homePos = Homes.effectiveHome(this.mob).orElse(null);
        return this.homePos != null;
    }

    public boolean canContinueToUse() {
        return this.canUse();
    }

    public void start() {
        this.repathCooldown = 0;
        this.moveHome();
    }

    public void tick() {
        if (this.repathCooldown > 0) {
            --this.repathCooldown;
        } else if (this.mob.getNavigation().isDone()) {
            this.repathCooldown = 20;
            this.moveHome();
        }
    }

    private void moveHome() {
        BlockPos anchored = Homes.surfaceStandForMove(this.mob.level(), this.homePos, this.mob);
        if (anchored == null) {
            // No usable surface stand: keep the original home aim rather than stalling.
            anchored = this.homePos;
        }
        anchored = Homes.offsetStandFromCorners(this.mob.level(), anchored);
        this.mob.getNavigation().moveTo((double)anchored.getX() + 0.5, (double)anchored.getY(), (double)anchored.getZ() + 0.5, 1.2);
    }
}

