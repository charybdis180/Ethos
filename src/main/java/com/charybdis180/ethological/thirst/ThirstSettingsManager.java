package com.charybdis180.ethological.thirst;

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

public class ThirstSettingsManager
extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new GsonBuilder().create();
    public static final String DIRECTORY = "ethological_thirst";
    private static volatile Map<EntityType<?>, SpeciesThirstSettings> settingsByType = Map.of();
    private static final SettingsCache<SpeciesThirstSettings> CACHE = new SettingsCache<>();

    public ThirstSettingsManager() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager resourceManager, ProfilerFiller profiler) {
        HashMap<EntityType<?>, SpeciesThirstSettings> loaded = new HashMap<>();
        resources.forEach((fileId, json) -> {
            try {
                SpeciesThirstSettings settings = SpeciesThirstSettings.fromJson(fileId, json.getAsJsonObject());
                if (!BuiltInRegistries.ENTITY_TYPE.containsKey(settings.entityId())) {
                    Ethological.LOGGER.warn("Thirst settings file '{}' references unknown entity type '{}'", fileId, settings.entityId());
                    return;
                }
                loaded.put(BuiltInRegistries.ENTITY_TYPE.get(settings.entityId()), settings);
            }
            catch (Exception e) {
                Ethological.LOGGER.error("Failed to parse thirst settings file '{}'", fileId, e);
            }
        });
        settingsByType = Map.copyOf(loaded);
        CACHE.invalidate();
        Ethological.LOGGER.info("Loaded Ethological thirst settings for {} species", settingsByType.size());
    }

    public static Optional<SpeciesThirstSettings> get(EntityType<?> type) {
        return CACHE.get(type, () -> Optional.ofNullable(settingsByType.get(type)).map(datapack -> EthologicalConfig.resolveThirst(type, datapack)));
    }

    public static void invalidate() {
        CACHE.invalidate();
    }
}
