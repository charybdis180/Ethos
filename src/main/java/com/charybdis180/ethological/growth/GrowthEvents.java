package com.charybdis180.ethological.growth;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.growth.GrowthData;
import com.charybdis180.ethological.growth.GrowthSettingsManager;
import com.charybdis180.ethological.growth.SpeciesGrowthSettings;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public final class GrowthEvents {
    private static final long MAINTENANCE_INTERVAL = 100L;

    private GrowthEvents() {
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new GrowthSettingsManager());
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        GrowthData data;
        Entity entity = event.getEntity();
        if (!(entity instanceof Animal)) {
            return;
        }
        Animal animal = (Animal)entity;
        Level level = animal.level();
        if (!(level instanceof ServerLevel)) {
            return;
        }
        ServerLevel serverLevel = (ServerLevel)level;
        if (!animal.isBaby()) {
            return;
        }
        Optional<SpeciesGrowthSettings> settingsOpt = GrowthSettingsManager.get(animal.getType());
        if (settingsOpt.isEmpty()) {
            return;
        }
        if (animal.hasData(ModAttachments.GROWTH)) {
            data = (GrowthData)animal.getData(ModAttachments.GROWTH);
        } else {
            data = GrowthEvents.initialize(animal, settingsOpt.get(), serverLevel.getGameTime());
            animal.setData(ModAttachments.GROWTH,data);
        }
        long now = serverLevel.getGameTime();
        if (now >= data.growUpGameTime()) {
            AttributeInstance scale = animal.getAttribute(Attributes.SCALE);
            if (scale != null) {
                scale.setBaseValue((double)data.initialScale());
            }
            animal.removeData(ModAttachments.GROWTH);
            animal.setAge(0);
            return;
        }
        // Maintenance (age re-pin + scale lerp) runs every ~100 ticks; the visual difference is imperceptible
        // and this avoids per-tick attribute updates and age churn.
        if (!com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "growth", now, MAINTENANCE_INTERVAL)) {
            return;
        }
        // Preserve vanilla ageUp boosts (e.g. hand-feeding): any age gained beyond natural ticking since the
        // last re-pin is converted into an equivalent fraction of the modded growth span.
        int ageBoost = animal.getAge() - (-24000 + (int)MAINTENANCE_INTERVAL);
        if (ageBoost > 0) {
            long span = data.growUpGameTime() - data.birthGameTime();
            long shift = (long)((double)ageBoost * (double)span / 24000.0);
            if (shift > 0L) {
                data = new GrowthData(data.birthGameTime(), data.growUpGameTime() - shift, data.initialScale(), data.targetScale());
                animal.setData(ModAttachments.GROWTH,data);
            }
        }
        animal.setBaby(true);
        AttributeInstance scale = animal.getAttribute(Attributes.SCALE);
        if (scale != null) {
            float span = data.growUpGameTime() - data.birthGameTime();
            float progress = span > 0.0f ? GrowthEvents.clamp01((float)(now - data.birthGameTime()) / span) : 1.0f;
            double target = GrowthEvents.lerp(data.initialScale(), data.targetScale(), progress);
            if (Math.abs(scale.getBaseValue() - target) > 1.0E-4) {
                scale.setBaseValue(target);
            }
        }
    }

    private static GrowthData initialize(Animal animal, SpeciesGrowthSettings settings, long now) {
        int minDays = Math.min(settings.minGrowthDays(), settings.maxGrowthDays());
        int maxDays = Math.max(settings.minGrowthDays(), settings.maxGrowthDays());
        int days = minDays + (maxDays > minDays ? animal.getRandom().nextInt(maxDays - minDays + 1) : 0);
        long growUp = now + (long)days * 24000L;
        float initialScale = 1.0f;
        AttributeInstance scale = animal.getAttribute(Attributes.SCALE);
        if (scale != null) {
            initialScale = (float)scale.getBaseValue();
        }
        float targetScale = initialScale + settings.babyScaleExtra();
        return new GrowthData(now, growUp, initialScale, targetScale);
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private static float clamp01(float value) {
        return value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
    }
}

