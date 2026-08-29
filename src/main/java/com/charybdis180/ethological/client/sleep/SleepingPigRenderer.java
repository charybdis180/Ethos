package com.charybdis180.ethological.client.sleep;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.client.FaCompat;
import com.charybdis180.ethological.config.EthologicalClientConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.layers.SaddleLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Pig;

public class SleepingPigRenderer extends MobRenderer<Pig, SleepingPigModel> {
    private static final ResourceLocation AWAKE_TEXTURE = ResourceLocation.fromNamespaceAndPath("ethological", "textures/entity/pig/pig_awake.png");
    private static final ResourceLocation STANDING_TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/pig/pig.png");
    private static final ResourceLocation ASLEEP_TEXTURE = ResourceLocation.fromNamespaceAndPath("ethological", "textures/entity/pig/pig_sleep.png");
    private static final ResourceLocation BABY_TEXTURE = ResourceLocation.fromNamespaceAndPath("ethological", "textures/entity/pig/pig_baby.png");
    private static final ResourceLocation BABY_ASLEEP_TEXTURE = ResourceLocation.fromNamespaceAndPath("ethological", "textures/entity/pig/pig_baby_sleep.png");
    private final TextureResolver.TexSet textures;
    private final SleepingPigModel adultModel;
    private final BabyPigModel babyModel;
    private final boolean useBabyModels;

    public SleepingPigRenderer(EntityRendererProvider.Context context) {
        this(context, EthologicalClientConfig.useModernBabyModels());
    }

    public SleepingPigRenderer(EntityRendererProvider.Context context, boolean useBabyModels) {
        super(context, new SleepingPigModel(context.bakeLayer(ModelLayers.PIG)), 0.7f);
        this.textures = TextureResolver.resolve("pig", STANDING_TEXTURE, AWAKE_TEXTURE,
                ASLEEP_TEXTURE, BABY_TEXTURE, BABY_ASLEEP_TEXTURE);
        this.adultModel = (SleepingPigModel) this.model;
        this.babyModel = useBabyModels ? new BabyPigModel(context.bakeLayer(BabyModelLayers.BABY_PIG)) : null;
        this.useBabyModels = useBabyModels;
        this.addLayer((RenderLayer) new SaddleLayer((RenderLayerParent) this,
                (EntityModel) new SleepingPigModel(context.bakeLayer(ModelLayers.PIG_SADDLE)),
                ResourceLocation.withDefaultNamespace("textures/entity/pig/pig_saddle.png")));
        this.addLayer((RenderLayer) new PigMouthItemLayer((RenderLayerParent<Pig, SleepingPigModel>) this,
                context.getItemRenderer()));
    }

    public ResourceLocation getTextureLocation(Pig entity) {
        boolean sleepVisuals = EthologicalClientConfig.CONFIG.sleepModelsEnabled();
        boolean eyesClosed = sleepVisuals && entity.getData(ModAttachments.SLEEPING);
        if (this.useBabyModels && entity.isBaby()) {
            return eyesClosed ? this.textures.babyAsleep() : this.textures.baby();
        }
        return eyesClosed ? this.textures.asleep()
                : (sleepVisuals && entity.getData(ModAttachments.RESTING) ? this.textures.resting() : this.textures.standing());
    }

    public void render(Pig entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        boolean sleepingOrResting = entity.getData(ModAttachments.SLEEPING) || entity.getData(ModAttachments.RESTING);
        FaCompat.lockToVanillaModelWhileSleeping(entity, sleepingOrResting);
        this.model = this.useBabyModels && entity.isBaby() ? this.babyModel : this.adultModel;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    protected void setupRotations(Pig entity, PoseStack poseStack, float bob, float yBodyRot, float partialTick, float scale) {
        if (EthologicalClientConfig.CONFIG.sleepModelsEnabled() && entity.getData(ModAttachments.SLEEPING)) {
            yBodyRot = entity.getData(ModAttachments.SLEEP_YAW);
        }
        super.setupRotations(entity, poseStack, bob, yBodyRot, partialTick, scale);
    }
}
