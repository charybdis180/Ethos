package com.charybdis180.ethological.sleep;

import com.charybdis180.ethological.Ethological;
import com.charybdis180.ethological.config.EthologicalConfig;
import com.charybdis180.ethological.config.SettingsCache;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;

public class SleepSettingsManager
extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new GsonBuilder().create();
    public static final String DIRECTORY = "ethological_sleep";
    private static volatile Map<EntityType<?>, SpeciesSleepSettings> settingsByType = Map.of();
    private static final SettingsCache<SpeciesSleepSettings> CACHE = new SettingsCache<>();

    public SleepSettingsManager() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager resourceManager, ProfilerFiller profiler) {
        HashMap<EntityType<?>, SpeciesSleepSettings> loaded = new HashMap<>();
        resources.forEach((fileId, json) -> {
            try {
                SpeciesSleepSettings settings = SpeciesSleepSettings.fromJson(fileId, json.getAsJsonObject());
                if (!BuiltInRegistries.ENTITY_TYPE.containsKey(settings.entityId())) {
                    Ethological.LOGGER.warn("Sleep settings file '{}' references unknown entity type '{}'", fileId, settings.entityId());
                    return;
                }
                loaded.put(BuiltInRegistries.ENTITY_TYPE.get(settings.entityId()), settings);
            }
            catch (Exception e) {
                Ethological.LOGGER.error("Failed to parse sleep settings file '{}'", fileId, e);
            }
        });
        settingsByType = Map.copyOf(loaded);
        CACHE.invalidate();
        Ethological.LOGGER.info("Loaded Ethological sleep settings for {} species", settingsByType.size());
    }

    public static Optional<SpeciesSleepSettings> get(EntityType<?> type) {
        return CACHE.get(type, () -> Optional.ofNullable(settingsByType.get(type)).map(datapack -> EthologicalConfig.resolveSleep(type, datapack)));
    }

    public static void invalidate() {
        CACHE.invalidate();
    }
}
