package com.charybdis180.ethological.herd;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

public record SpeciesHerdSettings(
        ResourceLocation entityId,
        int maxSize,
        int joinRadius,
        double followDistance,
        double motherFollowDistance,
        double crossSpeciesAlertRadius,
        double followSpreadPerMemberPercent,
        double resatterDistance,
        double sleepFollowMultiplier,
        int spawnGroupMin,
        int spawnGroupMax,
        int spawnBabiesMin,
        int spawnBabiesMax,
        FollowStyle followStyle) {
    public static final double DEFAULT_MOTHER_FOLLOW_DISTANCE = 3.0;
    /** Fraction of the herd's effective follow size that a member must be within of its
     * alpha to settle/sleep. Default 1.0: a member may sleep once within one follow size
     * of the alpha (cow follow 6 → 6 blocks), which is half of the full herd follow span
     * (~2x follow). Lower it to keep sleeping herds tighter. */
    public static final double DEFAULT_SLEEP_FOLLOW_MULTIPLIER = 1.0;

    public static SpeciesHerdSettings fromJson(ResourceLocation fileId, JsonObject json) {
        ResourceLocation entityId = ResourceLocation.parse(json.get("entity").getAsString());
        int maxSize = optInt(json, "max_size", 15);
        int joinRadius = optInt(json, "join_radius", 24);
        double followDistance = optDouble(json, "follow_distance", 10.0);
        double motherFollowDistance = optDouble(json, "mother_follow_distance", DEFAULT_MOTHER_FOLLOW_DISTANCE);
        double crossSpeciesAlertRadius = optDouble(json, "cross_species_alert_radius", 16.0);
        double followSpreadPerMemberPercent = optDouble(json, "follow_spread_per_member_percent", 0.1);
        double resatterDistance = optDouble(json, "resatter_distance", 14.0);
        double sleepFollowMultiplier = optDouble(json, "sleep_follow_multiplier", DEFAULT_SLEEP_FOLLOW_MULTIPLIER);
        int spawnGroupMin = optInt(json, "spawn_group_min", Math.max(1, maxSize / 2));
        int spawnGroupMax = optInt(json, "spawn_group_max", maxSize);
        if (spawnGroupMin > spawnGroupMax) {
            spawnGroupMin = spawnGroupMax;
        }
        int spawnBabiesMin = optInt(json, "spawn_babies_min", 0);
        int spawnBabiesMax = optInt(json, "spawn_babies_max", Math.min(2, spawnGroupMax));
        if (spawnBabiesMin > spawnBabiesMax) {
            spawnBabiesMin = spawnBabiesMax;
        }
        spawnBabiesMax = Math.min(spawnBabiesMax, spawnGroupMax);
        spawnBabiesMin = Math.min(spawnBabiesMin, spawnBabiesMax);
        FollowStyle followStyle = FollowStyle.fromString(json.has("follow_style") ? json.get("follow_style").getAsString() : "surround");
        return new SpeciesHerdSettings(
                entityId,
                maxSize,
                joinRadius,
                followDistance,
                motherFollowDistance,
                crossSpeciesAlertRadius,
                followSpreadPerMemberPercent,
                resatterDistance,
                sleepFollowMultiplier,
                spawnGroupMin,
                spawnGroupMax,
                spawnBabiesMin,
                spawnBabiesMax,
                followStyle);
    }

    private static int optInt(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }

    private static double optDouble(JsonObject json, String key, double fallback) {
        return json.has(key) ? json.get(key).getAsDouble() : fallback;
    }
}
