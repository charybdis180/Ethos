/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 *  net.minecraft.resources.ResourceLocation
 */
package com.charybdis180.ethological.home;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

public record SpeciesHomeSettings(ResourceLocation entityId, int waterSearchRadius, int wanderRadius, int leashShrinkTicks, int validationIntervalTicks, boolean nomadic, double migrationSpeed, int campTimeTick) {
    public static SpeciesHomeSettings fromJson(ResourceLocation fileId, JsonObject json) {
        ResourceLocation entityId = ResourceLocation.parse((String)json.get("entity").getAsString());
        int waterSearchRadius = SpeciesHomeSettings.optInt(json, "water_search_radius", 128);
        int wanderRadius = SpeciesHomeSettings.optInt(json, "wander_radius", 64);
        int leashShrinkTicks = SpeciesHomeSettings.optInt(json, "leash_shrink_ticks", 1000);
        int validationIntervalTicks = SpeciesHomeSettings.optInt(json, "validation_interval_ticks", 200);
        boolean nomadic = json.has("nomadic") && json.get("nomadic").getAsBoolean();
        double migrationSpeed = SpeciesHomeSettings.optDouble(json, "migration_speed", 1.0);
        int campTimeTick = SpeciesHomeSettings.optInt(json, "camp_time_tick", 9500);
        return new SpeciesHomeSettings(entityId, waterSearchRadius, wanderRadius, leashShrinkTicks, validationIntervalTicks, nomadic, migrationSpeed, campTimeTick);
    }

    private static int optInt(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }

    private static double optDouble(JsonObject json, String key, double fallback) {
        return json.has(key) ? json.get(key).getAsDouble() : fallback;
    }
}

