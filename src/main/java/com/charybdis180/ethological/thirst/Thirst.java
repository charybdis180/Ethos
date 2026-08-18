/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.world.entity.Entity
 */
package com.charybdis180.ethological.thirst;

import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import com.charybdis180.ethological.util.Personality;
import java.util.Optional;
import net.minecraft.world.entity.Entity;

public final class Thirst {
    private Thirst() {
    }

    public static boolean supports(Entity entity) {
        return ThirstSettingsManager.get(entity.getType()).isPresent();
    }

    public static Optional<SpeciesThirstSettings> settingsOf(Entity entity) {
        return ThirstSettingsManager.get(entity.getType());
    }

    public static boolean hasThirstData(Entity entity) {
        return entity.hasData(ThirstAttachments.THIRST_DATA);
    }

    public static ThirstData data(Entity entity) {
        return (ThirstData)entity.getData(ThirstAttachments.THIRST_DATA);
    }

    public static int getThirst(Entity entity) {
        return Thirst.data(entity).thirst();
    }

    public static int getMaxThirst(Entity entity) {
        return Thirst.data(entity).maxThirst();
    }

    public static boolean isDehydrated(Entity entity) {
        return Thirst.hasThirstData(entity) && Thirst.data(entity).thirst() <= 0;
    }

    public static boolean isUrgentlyThirsty(Entity entity) {
        return ThirstSettingsManager.get(entity.getType()).map(s -> Thirst.hasThirstData(entity) && Thirst.getThirst(entity) <= s.urgentDrinkThreshold()).orElse(false);
    }

    /** True when the entity is inside its morning or evening drink window (or urgently thirsty). */
    public static boolean isDrinkDue(Entity entity) {
        return ThirstSettingsManager.get(entity.getType())
                .map(settings -> Thirst.isDrinkDue(entity, settings))
                .orElse(false);
    }

    public static boolean isDrinkDue(Entity entity, SpeciesThirstSettings settings) {
        ThirstData data = Thirst.data(entity);
        if (data.thirst() <= settings.urgentDrinkThreshold()) {
            return true;
        }
        Optional<SpeciesSleepSettings> sleepSettings = SleepSettingsManager.get(entity.getType());
        if (sleepSettings.isEmpty()) {
            return false;
        }
        SpeciesSleepSettings sleep = sleepSettings.get();
        long dayTime = entity.level().getDayTime();
        long t = Homes.dayTick(dayTime);
        if (sleep.isSleepTime(dayTime)) {
            return false;
        }
        long gameTime = entity.level().getGameTime();
        long ticksSinceWake = (t - (long)sleep.sleepEndTick() + 24000L) % 24000L;
        if (ticksSinceWake >= Personality.salt(entity.getUUID(), "thirst_morning", 600)
                && data.lastDrinkGameTime() < gameTime - ticksSinceWake) {
            return true;
        }
        long slotStart = (long)(sleep.sleepStartTick() - settings.eveningDrinkLeadTicks())
                + Personality.salt(entity.getUUID(), "thirst_evening", 400);
        long intoSlot = t - slotStart;
        return intoSlot >= 0L && t < (long)sleep.sleepStartTick() && data.lastDrinkGameTime() < gameTime - intoSlot;
    }

    public static void drink(Entity entity) {
        ThirstData data = Thirst.data(entity);
        Thirst.setData(entity, data.withThirst(data.maxThirst()).withLastDrink(entity.level().getGameTime()));
    }

    /** Partial refill during a drink bout — interrupted drinks still restore some water. */
    public static void drinkSip(Entity entity, int amount) {
        if (amount <= 0 || !Thirst.hasThirstData(entity)) {
            return;
        }
        ThirstData data = Thirst.data(entity);
        int next = Math.min(data.maxThirst(), data.thirst() + amount);
        if (next == data.thirst()) {
            return;
        }
        Thirst.setData(entity, data.withThirst(next).withLastDrink(entity.level().getGameTime()));
    }

    public static void setData(Entity entity, ThirstData data) {
        entity.setData(ThirstAttachments.THIRST_DATA,data);
    }
}

