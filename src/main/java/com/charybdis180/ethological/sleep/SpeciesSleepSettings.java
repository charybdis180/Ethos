package com.charybdis180.ethological.sleep;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

public record SpeciesSleepSettings(ResourceLocation entityId, int sleepStartTick, int sleepEndTick, int regenAmplifier, int maxBedtimeDelayTicks, int sleepReturnRadius) {
    public static SpeciesSleepSettings fromJson(ResourceLocation fileId, JsonObject json) {
        ResourceLocation entityId = ResourceLocation.parse((String)json.get("entity").getAsString());
        int sleepStartTick = SpeciesSleepSettings.optInt(json, "sleep_start_tick", 13000);
        int sleepEndTick = SpeciesSleepSettings.optInt(json, "sleep_end_tick", 23000);
        int regenAmplifier = SpeciesSleepSettings.optInt(json, "regen_amplifier", 0);
        int maxBedtimeDelayTicks = SpeciesSleepSettings.optInt(json, "max_bedtime_delay_ticks", 1200);
        int sleepReturnRadius = SpeciesSleepSettings.optInt(json, "sleep_return_radius", 4);
        return new SpeciesSleepSettings(entityId, sleepStartTick, sleepEndTick, regenAmplifier, maxBedtimeDelayTicks, sleepReturnRadius);
    }

    public boolean isSleepTime(long dayTime) {
        long t = (dayTime % 24000L + 24000L) % 24000L;
        return this.sleepStartTick <= this.sleepEndTick ? t >= (long)this.sleepStartTick && t < (long)this.sleepEndTick : t >= (long)this.sleepStartTick || t < (long)this.sleepEndTick;
    }

    private static int optInt(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }
}

