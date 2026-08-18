/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.particles.ParticleType
 *  net.minecraft.world.entity.EntityType
 *  net.neoforged.api.distmarker.Dist
 *  net.neoforged.bus.api.IEventBus
 *  net.neoforged.fml.IExtensionPoint
 *  net.neoforged.fml.ModContainer
 *  net.neoforged.fml.common.Mod
 *  net.neoforged.neoforge.client.event.EntityRenderersEvent$RegisterLayerDefinitions
 *  net.neoforged.neoforge.client.event.EntityRenderersEvent$RegisterRenderers
 *  net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
 *  net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent
 *  net.neoforged.neoforge.client.gui.ConfigurationScreen
 *  net.neoforged.neoforge.client.gui.IConfigScreenFactory
 */
package com.charybdis180.ethological.client;

import com.charybdis180.ethological.ModParticles;
import com.charybdis180.ethological.block.ModMenus;
import com.charybdis180.ethological.client.FeederScreen;
import com.charybdis180.ethological.client.ZParticle;
import com.charybdis180.ethological.client.sleep.BabyChickenModel;
import com.charybdis180.ethological.client.sleep.BabyCowModel;
import com.charybdis180.ethological.client.sleep.BabyModelLayers;
import com.charybdis180.ethological.client.sleep.BabyPigModel;
import com.charybdis180.ethological.client.sleep.BabySheepFurModel;
import com.charybdis180.ethological.client.sleep.BabySheepModel;
import com.charybdis180.ethological.client.sleep.SleepingChickenRenderer;
import com.charybdis180.ethological.client.sleep.SleepingCowRenderer;
import com.charybdis180.ethological.client.sleep.SleepingPigRenderer;
import com.charybdis180.ethological.client.sleep.SleepingSheepRenderer;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value="ethological", dist={Dist.CLIENT})
public class EthologicalClient {
    public EthologicalClient(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(EthologicalClient::onRegisterRenderers);
        modEventBus.addListener(EthologicalClient::onRegisterLayerDefinitions);
        modEventBus.addListener(EthologicalClient::onRegisterParticles);
        modEventBus.addListener(EthologicalClient::onRegisterMenuScreens);
    }

    private static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.FEEDER.get(), FeederScreen::new);
    }

    private static void onRegisterLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(BabyModelLayers.BABY_CHICKEN, BabyChickenModel::createBodyLayer);
        event.registerLayerDefinition(BabyModelLayers.BABY_COW, BabyCowModel::createBodyLayer);
        event.registerLayerDefinition(BabyModelLayers.BABY_PIG, BabyPigModel::createBodyLayer);
        event.registerLayerDefinition(BabyModelLayers.BABY_SHEEP, BabySheepModel::createBodyLayer);
        event.registerLayerDefinition(BabyModelLayers.BABY_SHEEP_WOOL, BabySheepFurModel::createBodyLayer);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(EntityType.SHEEP, SleepingSheepRenderer::new);
        event.registerEntityRenderer(EntityType.COW, SleepingCowRenderer::new);
        event.registerEntityRenderer(EntityType.PIG, SleepingPigRenderer::new);
        event.registerEntityRenderer(EntityType.CHICKEN, SleepingChickenRenderer::new);
    }

    private static void onRegisterParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet((ParticleType)ModParticles.Z.get(), ZParticle.Provider::new);
    }
}

