/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.logging.LogUtils
 *  net.neoforged.bus.api.IEventBus
 *  net.neoforged.fml.ModContainer
 *  net.neoforged.fml.common.Mod
 *  net.neoforged.fml.config.IConfigSpec
 *  net.neoforged.fml.config.ModConfig$Type
 *  net.neoforged.neoforge.capabilities.Capabilities$ItemHandler
 *  net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent
 *  net.neoforged.neoforge.common.NeoForge
 *  org.slf4j.Logger
 */
package com.charybdis180.ethological;

import com.charybdis180.ethological.ModParticles;
import com.charybdis180.ethological.ModSounds;
import com.charybdis180.ethological.avoidance.AvoidanceAttachments;
import com.charybdis180.ethological.avoidance.AvoidanceEvents;
import com.charybdis180.ethological.block.ModBlockEntities;
import com.charybdis180.ethological.block.ModBlocks;
import com.charybdis180.ethological.block.ModMenus;
import com.charybdis180.ethological.config.EthologicalConfig;
import com.charybdis180.ethological.debug.DebugAttachments;
import com.charybdis180.ethological.growth.GrowthAttachments;
import com.charybdis180.ethological.growth.GrowthEvents;
import com.charybdis180.ethological.growth.GrowthSettingsManager;
import com.charybdis180.ethological.herd.HerdSettingsManager;
import com.charybdis180.ethological.home.HomeSettingsManager;
import com.charybdis180.ethological.hunger.HungerSettingsManager;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import com.charybdis180.ethological.thirst.ThirstSettingsManager;
import com.charybdis180.ethological.herd.HerdAttachments;
import com.charybdis180.ethological.herd.HerdEvents;
import com.charybdis180.ethological.herd.WolfEvents;
import com.charybdis180.ethological.home.HomeAttachments;
import com.charybdis180.ethological.home.HomeEvents;
import com.charybdis180.ethological.hunger.HungerAttachments;
import com.charybdis180.ethological.hunger.HungerEvents;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.sleep.SleepEvents;
import com.charybdis180.ethological.social.SocialAttachments;
import com.charybdis180.ethological.social.SocialEvents;
import com.charybdis180.ethological.spawn.SpawnEvents;
import com.charybdis180.ethological.thirst.ThirstAttachments;
import com.charybdis180.ethological.thirst.ThirstEvents;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(value="ethological")
public class Ethological {
    public static final String MODID = "ethological";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Ethological(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, (IConfigSpec)EthologicalConfig.SPEC);
        HungerAttachments.ATTACHMENT_TYPES.register(modEventBus);
        SleepAttachments.ATTACHMENT_TYPES.register(modEventBus);
        HomeAttachments.ATTACHMENT_TYPES.register(modEventBus);
        HerdAttachments.ATTACHMENT_TYPES.register(modEventBus);
        ThirstAttachments.ATTACHMENT_TYPES.register(modEventBus);
        DebugAttachments.ATTACHMENT_TYPES.register(modEventBus);
        SocialAttachments.ATTACHMENT_TYPES.register(modEventBus);
        GrowthAttachments.ATTACHMENT_TYPES.register(modEventBus);
        AvoidanceAttachments.ATTACHMENT_TYPES.register(modEventBus);
        ModParticles.PARTICLE_TYPES.register(modEventBus);
        ModSounds.SOUND_EVENTS.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        modEventBus.addListener(RegisterCapabilitiesEvent.class, event -> event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.FEEDER.get(), (feeder, side) -> feeder.getInventory()));
        modEventBus.addListener(ModBlocks::onBuildCreativeTabs);
        modEventBus.addListener(ModConfigEvent.Loading.class, event -> Ethological.onConfigChanged(event.getConfig()));
        modEventBus.addListener(ModConfigEvent.Reloading.class, event -> Ethological.onConfigChanged(event.getConfig()));
        NeoForge.EVENT_BUS.register(HungerEvents.class);
        NeoForge.EVENT_BUS.register(SleepEvents.class);
        NeoForge.EVENT_BUS.register(HomeEvents.class);
        NeoForge.EVENT_BUS.register(HerdEvents.class);
        NeoForge.EVENT_BUS.register(WolfEvents.class);
        NeoForge.EVENT_BUS.register(ThirstEvents.class);
        NeoForge.EVENT_BUS.register(SpawnEvents.class);
        NeoForge.EVENT_BUS.register(SocialEvents.class);
        NeoForge.EVENT_BUS.register(GrowthEvents.class);
        NeoForge.EVENT_BUS.register(AvoidanceEvents.class);
    }

    private static void onConfigChanged(ModConfig config) {
        if (config.getSpec() != EthologicalConfig.SPEC) {
            return;
        }
        HungerSettingsManager.invalidate();
        ThirstSettingsManager.invalidate();
        SleepSettingsManager.invalidate();
        HerdSettingsManager.invalidate();
        HomeSettingsManager.invalidate();
        GrowthSettingsManager.invalidate();
    }
}

