/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.level.Level
 */
package com.charybdis180.ethological.hunger;

import com.charybdis180.ethological.herd.HerdAttachments;
import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.hunger.HungerAttachments;
import com.charybdis180.ethological.hunger.HungerData;
import com.charybdis180.ethological.hunger.HungerSettingsManager;
import com.charybdis180.ethological.hunger.SpeciesHungerSettings;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import com.charybdis180.ethological.thirst.SpeciesThirstSettings;
import com.charybdis180.ethological.thirst.Thirst;
import com.charybdis180.ethological.thirst.ThirstSettingsManager;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;

public final class Hunger {
    public static final int RUMINATE_TICKS_MIN = 40;
    public static final int RUMINATE_TICKS_SPAN = 41;
    public static final long MORNING_DRINK_GRACE_TICKS = 4000L;

    private Hunger() {
    }

    public static boolean supports(Entity entity) {
        return HungerSettingsManager.get(entity.getType()).isPresent();
    }

    public static Optional<SpeciesHungerSettings> settingsOf(Entity entity) {
        return HungerSettingsManager.get(entity.getType());
    }

    public static boolean hasHungerData(Entity entity) {
        return entity.hasData(HungerAttachments.HUNGER_DATA);
    }

    public static HungerData data(Entity entity) {
        return (HungerData)entity.getData(HungerAttachments.HUNGER_DATA);
    }

    public static int getHunger(Entity entity) {
        return Hunger.data(entity).hunger();
    }

    public static int getMaxHunger(Entity entity) {
        return Hunger.data(entity).maxHunger();
    }

    public static boolean isStarving(Entity entity) {
        return Hunger.hasHungerData(entity) && Hunger.data(entity).hunger() <= 0;
    }

    public static boolean isFull(Entity entity) {
        return Hunger.hasHungerData(entity) && Hunger.getHunger(entity) >= Hunger.getMaxHunger(entity);
    }

    public static boolean wantsFood(Entity entity) {
        return Hunger.hasHungerData(entity) && Hunger.getHunger(entity) < Hunger.getMaxHunger(entity);
    }

    public static boolean herdWantsFood(Animal animal) {
        Level level;
        if (Hunger.wantsFood((Entity)animal)) {
            return true;
        }
        if (!animal.hasData(HerdAttachments.HERD_DATA) || !((level = animal.level()) instanceof ServerLevel)) {
            return false;
        }
        ServerLevel serverLevel = (ServerLevel)level;
        HerdManager.Herd herd = HerdManager.get(((HerdData)animal.getData(HerdAttachments.HERD_DATA)).herdId());
        if (herd == null) {
            return false;
        }
        for (UUID memberId : herd.members) {
            Animal mate;
            Entity member;
            if (memberId.equals(animal.getUUID()) || !((member = serverLevel.getEntity(memberId)) instanceof Animal) || !Hunger.wantsFood((Entity)(mate = (Animal)member))) continue;
            return true;
        }
        return false;
    }

    public static boolean isUrgentlyHungry(Entity entity) {
        return Hunger.hasHungerData(entity) && Hunger.getHunger(entity) <= Math.max(1, (int)((float)Hunger.getMaxHunger(entity) * 0.3f));
    }

    public static boolean isRuminating(Entity entity) {
        return entity.hasData(HungerAttachments.RUMINATE_UNTIL) && entity.level().getGameTime() < (Long)entity.getData(HungerAttachments.RUMINATE_UNTIL);
    }

    public static void beginRuminate(Animal animal) {
        long until = animal.level().getGameTime() + 40L + (long)animal.getRandom().nextInt(41);
        animal.setData(HungerAttachments.RUMINATE_UNTIL,until);
    }

    public static void beginHerdRuminate(Animal animal) {
        Level level;
        Hunger.beginRuminate(animal);
        if (!animal.hasData(HerdAttachments.HERD_DATA) || !((level = animal.level()) instanceof ServerLevel)) {
            return;
        }
        ServerLevel serverLevel = (ServerLevel)level;
        HerdManager.Herd herd = HerdManager.get(((HerdData)animal.getData(HerdAttachments.HERD_DATA)).herdId());
        if (herd == null) {
            return;
        }
        for (UUID memberId : herd.members) {
            Entity member;
            if (memberId.equals(animal.getUUID()) || !((member = serverLevel.getEntity(memberId)) instanceof Animal)) continue;
            Animal mate = (Animal)member;
            Hunger.beginRuminate(mate);
        }
    }

    public static boolean isDaytimeGrazeWindow(Animal animal) {
        long dayTime;
        Optional<SpeciesSleepSettings> sleepOpt = SleepSettingsManager.get(animal.getType());
        if (sleepOpt.isEmpty()) {
            return true;
        }
        SpeciesSleepSettings sleep = sleepOpt.get();
        if (sleep.isSleepTime(dayTime = animal.level().getDayTime())) {
            return false;
        }
        long t = Homes.dayTick(dayTime);
        long gameTime = animal.level().getGameTime();
        Optional<SpeciesThirstSettings> thirstOpt = ThirstSettingsManager.get(animal.getType());
        if (thirstOpt.isPresent()) {
            long ticksSinceWake;
            SpeciesThirstSettings thirst = thirstOpt.get();
            long eveningStart = sleep.sleepStartTick() - thirst.eveningDrinkLeadTicks();
            if (t >= eveningStart) {
                return false;
            }
            if (Thirst.hasThirstData((Entity)animal) && (ticksSinceWake = (t - (long)sleep.sleepEndTick() + 24000L) % 24000L) <= 4000L && Thirst.data((Entity)animal).lastDrinkGameTime() < gameTime - ticksSinceWake) {
                return false;
            }
        } else if (t >= (long)sleep.sleepStartTick()) {
            return false;
        }
        return true;
    }

    public static void feed(Entity entity, int amount) {
        HungerData data = Hunger.data(entity);
        Hunger.setData(entity, data.withHunger(Math.min(data.maxHunger(), data.hunger() + amount)));
    }

    public static void setData(Entity entity, HungerData data) {
        entity.setData(HungerAttachments.HUNGER_DATA,data);
    }
}

