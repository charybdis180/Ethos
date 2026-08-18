/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 *  net.minecraft.resources.ResourceLocation
 */
package com.charybdis180.ethological.growth;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public record SpeciesGrowthSettings(ResourceLocation entityId, int minGrowthDays, int maxGrowthDays, float babyScaleExtra, float babyPlayChance, List<String> allowedPlayStyles, boolean babyOnlyPlaysWithBabies) {
    private static final List<String> DEFAULT_PLAY_STYLES = List.of("chase", "headbutt");

    public static SpeciesGrowthSettings fromJson(ResourceLocation fileId, JsonObject json) {
        int maxGrowthDays;
        ResourceLocation entityId = ResourceLocation.parse((String)json.get("entity").getAsString());
        int minGrowthDays = SpeciesGrowthSettings.optInt(json, "min_growth_days", 5);
        if (minGrowthDays > (maxGrowthDays = SpeciesGrowthSettings.optInt(json, "max_growth_days", 7))) {
            minGrowthDays = maxGrowthDays;
        }
        float babyScaleExtra = (float)SpeciesGrowthSettings.optDouble(json, "baby_extra_scale", 0.2);
        float babyPlayChance = (float)SpeciesGrowthSettings.optDouble(json, "baby_play_chance", 0.6);
        List<String> playStyles = SpeciesGrowthSettings.optStringList(json, "play_styles", DEFAULT_PLAY_STYLES);
        boolean babyOnlyPlaysWithBabies = SpeciesGrowthSettings.optBool(json, "baby_only_play_babies", true);
        return new SpeciesGrowthSettings(entityId, minGrowthDays, maxGrowthDays, babyScaleExtra, babyPlayChance, playStyles, babyOnlyPlaysWithBabies);
    }

    public boolean allowsPlay(String style) {
        return this.allowedPlayStyles.contains(style);
    }

    private static int optInt(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }

    private static double optDouble(JsonObject json, String key, double fallback) {
        return json.has(key) ? json.get(key).getAsDouble() : fallback;
    }

    private static boolean optBool(JsonObject json, String key, boolean fallback) {
        return json.has(key) ? json.get(key).getAsBoolean() : fallback;
    }

    private static List<String> optStringList(JsonObject json, String key, List<String> fallback) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return fallback;
        }
        ArrayList<String> styles = new ArrayList<String>();
        json.getAsJsonArray(key).forEach(element -> styles.add(element.getAsString()));
        return styles;
    }
}

