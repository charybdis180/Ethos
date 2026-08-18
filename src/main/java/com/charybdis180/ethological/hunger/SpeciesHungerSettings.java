/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonElement
 *  com.google.gson.JsonObject
 *  net.minecraft.core.registries.BuiltInRegistries
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.world.level.block.Block
 *  net.minecraft.world.level.block.Blocks
 */
package com.charybdis180.ethological.hunger;

import com.charybdis180.ethological.Ethological;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public record SpeciesHungerSettings(ResourceLocation entityId, int maxHunger, int depletionIntervalTicks, int starveDamageIntervalTicks, float starveDamage, int eatCooldownTicks, int hungerPerFood, float healPerFood, boolean depleteWhileSleeping, float searchThresholdPercent, Set<Block> foodBlocks, int eatsPerPatch, int patchGrazeRadius, int patchSearchRadius, int grazeLingerTicksMin, int grazeLingerTicksSpan, float shearedDepletionMultiplier, float handFeedMultiplier, float feederMultiplier, float cropMultiplier) {
    public static SpeciesHungerSettings fromJson(ResourceLocation fileId, JsonObject json) {
        ResourceLocation entityId = ResourceLocation.parse((String)json.get("entity").getAsString());
        int maxHunger = SpeciesHungerSettings.optInt(json, "max_hunger", 10);
        int depletionIntervalTicks = SpeciesHungerSettings.optInt(json, "depletion_interval_ticks", 2400);
        int starveDamageIntervalTicks = SpeciesHungerSettings.optInt(json, "starve_damage_interval_ticks", 1200);
        float starveDamage = SpeciesHungerSettings.optFloat(json, "starve_damage", 1.0f);
        int eatCooldownTicks = SpeciesHungerSettings.optInt(json, "eat_cooldown_ticks", 0);
        int hungerPerFood = SpeciesHungerSettings.optInt(json, "hunger_per_food", 1);
        float healPerFood = SpeciesHungerSettings.optFloat(json, "heal_per_food", 2.0f);
        boolean depleteWhileSleeping = json.has("deplete_while_sleeping") && json.get("deplete_while_sleeping").getAsBoolean();
        float searchThresholdPercent = SpeciesHungerSettings.optFloat(json, "search_threshold_percent", 1.0f);
        int eatsPerPatch = SpeciesHungerSettings.optInt(json, "eats_per_patch", 3);
        int patchGrazeRadius = SpeciesHungerSettings.optInt(json, "patch_graze_radius", 8);
        int patchSearchRadius = SpeciesHungerSettings.optInt(json, "patch_search_radius", 32);
        int grazeLingerTicksMin = SpeciesHungerSettings.optInt(json, "graze_linger_ticks_min", 40);
        int grazeLingerTicksSpan = SpeciesHungerSettings.optInt(json, "graze_linger_ticks_span", 40);
        float shearedDepletionMultiplier = SpeciesHungerSettings.optFloat(json, "sheared_depletion_multiplier", 1.0f);
        float handFeedMultiplier = SpeciesHungerSettings.optFloat(json, "hand_feed_multiplier", 2.0f);
        float feederMultiplier = SpeciesHungerSettings.optFloat(json, "feeder_multiplier", 3.0f);
        float cropMultiplier = SpeciesHungerSettings.optFloat(json, "crop_multiplier", 3.0f);
        HashSet<Block> foodBlocks = new HashSet<Block>();
        if (json.has("food_blocks")) {
            for (JsonElement element : json.getAsJsonArray("food_blocks")) {
                ResourceLocation blockId = ResourceLocation.parse((String)element.getAsString());
                if (BuiltInRegistries.BLOCK.containsKey(blockId)) {
                    foodBlocks.add((Block)BuiltInRegistries.BLOCK.get(blockId));
                    continue;
                }
                Ethological.LOGGER.warn("Unknown food block '{}' in hunger settings file '{}'",blockId,fileId);
            }
        }
        return new SpeciesHungerSettings(entityId, maxHunger, depletionIntervalTicks, starveDamageIntervalTicks, starveDamage, eatCooldownTicks, hungerPerFood, healPerFood, depleteWhileSleeping, searchThresholdPercent, Set.copyOf(foodBlocks), eatsPerPatch, patchGrazeRadius, patchSearchRadius, grazeLingerTicksMin, grazeLingerTicksSpan, shearedDepletionMultiplier, handFeedMultiplier, feederMultiplier, cropMultiplier);
    }

    public static boolean isPastureFood(Block block) {
        return block == Blocks.GRASS_BLOCK || block == Blocks.SHORT_GRASS || block == Blocks.TALL_GRASS || block == Blocks.FERN || block == Blocks.LARGE_FERN;
    }

    public static boolean isCropFood(Block block) {
        return block == Blocks.WHEAT || block == Blocks.POTATOES || block == Blocks.CARROTS || block == Blocks.BEETROOTS;
    }

    private static int optInt(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }

    private static float optFloat(JsonObject json, String key, float fallback) {
        return json.has(key) ? json.get(key).getAsFloat() : fallback;
    }
}

