package com.charybdis180.ethological.client;

import com.charybdis180.ethological.ModParticles;
import com.charybdis180.ethological.block.ModMenus;
import com.charybdis180.ethological.config.EthologicalClientConfig;
import com.charybdis180.ethological.config.EthologicalClientConfig.FreshAnimationsMode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = "ethological", dist = {Dist.CLIENT})
public class EthologicalClient {
    public EthologicalClient(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, EthologicalClientConfig.SPEC);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(EthologicalClient::onRegisterRenderers);
        modEventBus.addListener(EthologicalClient::onRegisterLayerDefinitions);
        modEventBus.addListener(EthologicalClient::onRegisterParticles);
        modEventBus.addListener(EthologicalClient::onRegisterMenuScreens);
        modEventBus.addListener(EthologicalClient::onRegisterClientReloadListeners);
    }

    private static void onRegisterClientReloadListeners(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new net.minecraft.server.packs.resources.SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(net.minecraft.server.packs.resources.ResourceManager manager,
                                   net.minecraft.util.profiling.ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void value, net.minecraft.server.packs.resources.ResourceManager manager,
                                 net.minecraft.util.profiling.ProfilerFiller profiler) {
                com.charybdis180.ethological.client.sleep.TextureResolver.invalidate();
            }
        });
    }

    private static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.FEEDER.get(), FeederScreen::new);
    }

    private static void onRegisterLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(com.charybdis180.ethological.client.sleep.BabyModelLayers.BABY_CHICKEN,
                com.charybdis180.ethological.client.sleep.BabyChickenModel::createBodyLayer);
        event.registerLayerDefinition(com.charybdis180.ethological.client.sleep.BabyModelLayers.BABY_COW,
                com.charybdis180.ethological.client.sleep.BabyCowModel::createBodyLayer);
        event.registerLayerDefinition(com.charybdis180.ethological.client.sleep.BabyModelLayers.BABY_PIG,
                com.charybdis180.ethological.client.sleep.BabyPigModel::createBodyLayer);
        event.registerLayerDefinition(com.charybdis180.ethological.client.sleep.BabyModelLayers.BABY_SHEEP,
                com.charybdis180.ethological.client.sleep.BabySheepModel::createBodyLayer);
        event.registerLayerDefinition(com.charybdis180.ethological.client.sleep.BabyModelLayers.BABY_SHEEP_WOOL,
                com.charybdis180.ethological.client.sleep.BabySheepFurModel::createBodyLayer);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        FaCompat.registerIfLoaded();
        FreshAnimationsMode mode = EthologicalClientConfig.CONFIG.freshAnimationsMode.get();
        // With FA active (ON/AUTO), FA owns the models and our renderers exist to drive behavior
        // sync + the sleep override. With OFF or no FA pack, our renderers provide the models.
        boolean faActive = isFreshAnimationsPresent() && mode != FreshAnimationsMode.OFF;
        if (!faActive && !EthologicalClientConfig.CONFIG.modernBabyModels.get()) {
            return;
        }
        boolean modernBabies = EthologicalClientConfig.useModernBabyModels();
        event.registerEntityRenderer(EntityType.SHEEP,
                ctx -> new com.charybdis180.ethological.client.sleep.SleepingSheepRenderer(ctx, modernBabies));
        event.registerEntityRenderer(EntityType.COW,
                ctx -> new com.charybdis180.ethological.client.sleep.SleepingCowRenderer(ctx, modernBabies));
        event.registerEntityRenderer(EntityType.PIG,
                ctx -> new com.charybdis180.ethological.client.sleep.SleepingPigRenderer(ctx, modernBabies));
        event.registerEntityRenderer(EntityType.CHICKEN,
                ctx -> new com.charybdis180.ethological.client.sleep.SleepingChickenRenderer(ctx, modernBabies));
    }

    private static void onRegisterParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet((ParticleType) ModParticles.Z.get(), ZParticle.Provider::new);
    }

    public static boolean isFreshAnimationsPresent() {
        for (Pack pack : Minecraft.getInstance().getResourcePackRepository().getSelectedPacks()) {
            String pid = pack.getId().toLowerCase(java.util.Locale.ROOT);
            if (pid.contains("freshanimations") || pid.contains("fresh_animations")) {
                return true;
            }
        }
        return false;
    }
}
