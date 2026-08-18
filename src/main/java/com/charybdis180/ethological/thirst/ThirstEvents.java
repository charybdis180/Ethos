/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.server.packs.resources.PreparableReloadListener
 *  net.minecraft.world.effect.MobEffectInstance
 *  net.minecraft.world.effect.MobEffects
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.ai.goal.Goal
 *  net.minecraft.world.entity.animal.Animal
 *  net.neoforged.bus.api.SubscribeEvent
 *  net.neoforged.neoforge.event.AddReloadListenerEvent
 *  net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
 *  net.neoforged.neoforge.event.tick.EntityTickEvent$Post
 */
package com.charybdis180.ethological.thirst;

import com.charybdis180.ethological.config.EthologicalConfig;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.thirst.SpeciesThirstSettings;
import com.charybdis180.ethological.thirst.Thirst;
import com.charybdis180.ethological.thirst.ThirstAttachments;
import com.charybdis180.ethological.thirst.ThirstData;
import com.charybdis180.ethological.thirst.ThirstSettingsManager;
import com.charybdis180.ethological.thirst.goal.DrinkWaterGoal;
import java.util.Optional;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public final class ThirstEvents {
    private ThirstEvents() {
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener((PreparableReloadListener)new ThirstSettingsManager());
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof Animal)) {
            return;
        }
        Animal animal = (Animal)entity;
        Optional<SpeciesThirstSettings> settingsOpt = ThirstSettingsManager.get(animal.getType());
        if (settingsOpt.isEmpty()) {
            return;
        }
        SpeciesThirstSettings settings = settingsOpt.get();
        long now = animal.level().getGameTime();
        if (!animal.hasData(ThirstAttachments.THIRST_DATA)) {
            Thirst.setData((Entity)animal, new ThirstData(settings.maxThirst(), settings.maxThirst(), now + 1L + (long)animal.getRandom().nextInt(settings.depletionIntervalTicks()), now + (long)settings.dehydrateDamageIntervalTicks(), now));
        } else if (Thirst.getMaxThirst((Entity)animal) != settings.maxThirst()) {
            ThirstData data = Thirst.data((Entity)animal);
            Thirst.setData((Entity)animal, data.withMaxThirst(settings.maxThirst()).withThirst(Math.min(data.thirst(), settings.maxThirst())));
        }
        animal.goalSelector.addGoal(3, (Goal)new DrinkWaterGoal(animal));
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        ThirstData data;
        Entity entity = event.getEntity();
        if (!(entity instanceof Animal)) {
            return;
        }
        Animal animal = (Animal)entity;
        if (animal.level().isClientSide()) {
            return;
        }
        Optional<SpeciesThirstSettings> settingsOpt = ThirstSettingsManager.get(animal.getType());
        if (settingsOpt.isEmpty()) {
            return;
        }
        if (!animal.hasData(ThirstAttachments.THIRST_DATA)) {
            return;
        }
        SpeciesThirstSettings settings = settingsOpt.get();
        long now = animal.level().getGameTime();
        if (now >= (data = Thirst.data((Entity)animal)).nextDepletionGameTime()) {
            int interval = Math.max(1, Math.round((float)settings.depletionIntervalTicks() * ThirstEvents.individualPace(animal)));
            if (Homes.isHotFor(animal)) {
                float mult = ((Double)EthologicalConfig.CONFIG.comfort.heatDepletionMultiplier.get()).floatValue();
                interval = Math.max(1, Math.round((float)interval * mult));
            }
            // Rain outdoors slows thirst drain (hydration opportunity without path spam).
            if (animal.level().isRaining() && animal.level().isRainingAt(animal.blockPosition())) {
                float rainMult = EthologicalConfig.CONFIG.comfort.rainThirstDepletionMultiplier.get().floatValue();
                interval = Math.max(1, Math.round(interval * rainMult));
            }
            if (!settings.depleteWhileSleeping() && ((Boolean)animal.getData(SleepAttachments.SLEEPING)).booleanValue()) {
                Thirst.setData((Entity)animal, data.withNextDepletion(now + (long)interval));
            } else {
                data = data.withThirst(Math.max(0, data.thirst() - 1)).withNextDepletion(now + (long)interval);
                Thirst.setData((Entity)animal, data);
            }
        }
        if (data.thirst() <= 0) {
            // Refresh the 100-tick effect periodically instead of re-applying (and re-syncing) every tick.
            if (!animal.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)
                    || com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "thirst_slow", now, 80L)) {
                animal.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 0, true, false, true));
            }
            if (now >= data.nextDehydrateGameTime()) {
                animal.hurt(animal.damageSources().dryOut(), settings.dehydrateDamage());
                Thirst.setData((Entity)animal, Thirst.data((Entity)animal).withNextDehydrate(now + (long)settings.dehydrateDamageIntervalTicks()));
            }
        }
    }

    private static float individualPace(Animal animal) {
        return com.charybdis180.ethological.util.Personality.clampPace(animal.getUUID(), "thirst_pace");
    }
}

