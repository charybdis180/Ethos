package com.charybdis180.ethological.thirst;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

public record SpeciesThirstSettings(ResourceLocation entityId, int maxThirst, int depletionIntervalTicks, boolean depleteWhileSleeping, int dehydrateDamageIntervalTicks, float dehydrateDamage, int drinkTicks, int eveningDrinkLeadTicks, int urgentDrinkThreshold) {
    public static SpeciesThirstSettings fromJson(ResourceLocation fileId, JsonObject json) {
        ResourceLocation entityId = ResourceLocation.parse((String)json.get("entity").getAsString());
        int maxThirst = SpeciesThirstSettings.optInt(json, "max_thirst", 10);
        int depletionIntervalTicks = SpeciesThirstSettings.optInt(json, "depletion_interval_ticks", 2400);
        boolean depleteWhileSleeping = !json.has("deplete_while_sleeping") || json.get("deplete_while_sleeping").getAsBoolean();
        int dehydrateDamageIntervalTicks = SpeciesThirstSettings.optInt(json, "dehydrate_damage_interval_ticks", 800);
        float dehydrateDamage = json.has("dehydrate_damage") ? json.get("dehydrate_damage").getAsFloat() : 1.0f;
        int drinkTicks = SpeciesThirstSettings.optInt(json, "drink_ticks", 60);
        int eveningDrinkLeadTicks = SpeciesThirstSettings.optInt(json, "evening_drink_lead_ticks", 1000);
        int urgentDrinkThreshold = SpeciesThirstSettings.optInt(json, "urgent_drink_threshold", 2);
        return new SpeciesThirstSettings(entityId, maxThirst, depletionIntervalTicks, depleteWhileSleeping, dehydrateDamageIntervalTicks, dehydrateDamage, drinkTicks, eveningDrinkLeadTicks, urgentDrinkThreshold);
    }

    private static int optInt(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }
}

