package com.charybdis180.ethological.social;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.hunger.HungerSettingsManager;
import com.charybdis180.ethological.hunger.SpeciesHungerSettings;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import com.charybdis180.ethological.thirst.Thirst;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;

public final class SocialCalm {
    private SocialCalm() {
    }

    public static boolean canIdle(Animal animal) {
        if (!SocialCalm.stillCalm(animal) || !SocialCalm.hasTimeToIdle(animal)) {
            return false;
        }
        if (Hunger.isRuminating((Entity)animal)) {
            return false;
        }
        if (animal.level().isThundering()) {
            return false;
        }
        Optional<SpeciesHungerSettings> hungerOpt = HungerSettingsManager.get(animal.getType());
        return hungerOpt.isEmpty() || !Hunger.hasHungerData((Entity)animal) || (float)Hunger.getHunger((Entity)animal) >= (float)Hunger.getMaxHunger((Entity)animal) * hungerOpt.get().searchThresholdPercent();
    }

    public static boolean hasTimeToIdle(Animal animal) {
        long dayTime;
        Optional<SpeciesSleepSettings> sleepOpt = SleepSettingsManager.get(animal.getType());
        if (sleepOpt.isEmpty()) {
            return false;
        }
        SpeciesSleepSettings sleep = sleepOpt.get();
        if (sleep.isSleepTime(dayTime = animal.level().getDayTime())) {
            return false;
        }
        Optional<BlockPos> home = Homes.effectiveHome(animal);
        if (home.isPresent()) {
            double distance = Math.sqrt(animal.distanceToSqr(Vec3.atCenterOf(((Vec3i)home.get()))));
            long lead = Math.min(3000L, 200L + (long)(distance / 0.2));
            if (Homes.ticksUntilSleepStart(dayTime, sleep.sleepStartTick()) <= lead) {
                return false;
            }
        }
        return true;
    }

    public static boolean stillCalm(Animal animal) {
        if (animal.getData(ModAttachments.SLEEPING) || animal.hasData(ModAttachments.SLEEP_DISTURBANCE) || animal.hasData(ModAttachments.STARTLE)) {
            return false;
        }
        if (HerdManager.panicPhaseOf(animal, animal.level().getGameTime()) != HerdManager.PanicPhase.NONE) {
            return false;
        }
        return !Hunger.isUrgentlyHungry((Entity)animal) && !SocialCalm.isUrgentlyThirsty(animal);
    }

    public static boolean isUrgentlyThirsty(Animal animal) {
        return Thirst.isUrgentlyThirsty((Entity)animal);
    }
}

