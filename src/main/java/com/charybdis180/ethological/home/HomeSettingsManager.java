/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.Gson
 *  com.google.gson.GsonBuilder
 *  com.google.gson.JsonElement
 *  net.minecraft.core.registries.BuiltInRegistries
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.server.packs.resources.ResourceManager
 *  net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
 *  net.minecraft.util.profiling.ProfilerFiller
 *  net.minecraft.world.entity.EntityType
 */
package com.charybdis180.ethological.home;

import com.charybdis180.ethological.Ethological;
import com.charybdis180.ethological.config.EthologicalConfig;
import com.charybdis180.ethological.home.SpeciesHomeSettings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;

public class HomeSettingsManager
extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new GsonBuilder().create();
    public static final String DIRECTORY = "ethological_home";
    private static volatile Map<EntityType<?>, SpeciesHomeSettings> settingsByType = Map.of();
    private static final Map<EntityType<?>, Optional<SpeciesHomeSettings>> RESOLVED = new ConcurrentHashMap<EntityType<?>, Optional<SpeciesHomeSettings>>();

    public HomeSettingsManager() {
        super(GSON, DIRECTORY);
    }

    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager resourceManager, ProfilerFiller profiler) {
        HashMap loaded = new HashMap();
        resources.forEach((fileId, json) -> {
            try {
                SpeciesHomeSettings settings = SpeciesHomeSettings.fromJson(fileId, json.getAsJsonObject());
                if (!BuiltInRegistries.ENTITY_TYPE.containsKey(settings.entityId())) {
                    Ethological.LOGGER.warn("Home settings file '{}' references unknown entity type '{}'", fileId,settings.entityId());
                    return;
                }
                EntityType type = (EntityType)BuiltInRegistries.ENTITY_TYPE.get(settings.entityId());
                loaded.put(type, settings);
            }
            catch (Exception e) {
                Ethological.LOGGER.error("Failed to parse home settings file '{}'", fileId,e);
            }
        });
        settingsByType = Map.copyOf(loaded);
        RESOLVED.clear();
        Ethological.LOGGER.info("Loaded Ethological home settings for {} species",settingsByType.size());
    }

    public static Optional<SpeciesHomeSettings> get(EntityType<?> type) {
        Optional<SpeciesHomeSettings> cached = RESOLVED.get(type);
        if (cached != null) {
            return cached;
        }
        SpeciesHomeSettings datapack = settingsByType.get(type);
        Optional<SpeciesHomeSettings> resolved = datapack == null ? Optional.empty() : Optional.of(EthologicalConfig.resolveHome(type, datapack));
        RESOLVED.put(type, resolved);
        return resolved;
    }

    public static void invalidate() {
        RESOLVED.clear();
    }
}

