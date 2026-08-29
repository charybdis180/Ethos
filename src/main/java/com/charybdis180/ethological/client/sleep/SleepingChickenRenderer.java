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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.Chicken;

public class SleepingChickenRenderer extends MobRenderer<Chicken, EntityModel<Chicken>> {
    private static final ResourceLocation AWAKE_TEXTURE = ResourceLocation.fromNamespaceAndPath("ethological", "textures/entity/chicken/chicken_awake.png");
    private static final ResourceLocation STANDING_TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/chicken.png");
    private static final ResourceLocation ASLEEP_TEXTURE = ResourceLocation.fromNamespaceAndPath("ethological", "textures/entity/chicken/chicken_sleep.png");
    private static final ResourceLocation BABY_TEXTURE = ResourceLocation.fromNamespaceAndPath("ethological", "textures/entity/chicken/chicken_baby.png");
    private static final ResourceLocation BABY_ASLEEP_TEXTURE = ResourceLocation.fromNamespaceAndPath("ethological", "textures/entity/chicken/chicken_baby_sleep.png");
    private final TextureResolver.TexSet textures;
    private final SleepingChickenModel adultModel;
    private final BabyChickenModel babyModel;
    private final boolean useBabyModels;

    public SleepingChickenRenderer(EntityRendererProvider.Context context) {
        this(context, EthologicalClientConfig.useModernBabyModels());
    }

    public SleepingChickenRenderer(EntityRendererProvider.Context context, boolean useBabyModels) {
        super(context, new SleepingChickenModel(context.bakeLayer(ModelLayers.CHICKEN)), 0.3f);
        this.textures = TextureResolver.resolve("chicken", STANDING_TEXTURE, AWAKE_TEXTURE,
                ASLEEP_TEXTURE, BABY_TEXTURE, BABY_ASLEEP_TEXTURE);
        this.adultModel = (SleepingChickenModel) this.model;
        this.babyModel = useBabyModels ? new BabyChickenModel(context.bakeLayer(BabyModelLayers.BABY_CHICKEN)) : null;
        this.useBabyModels = useBabyModels;
    }

    public ResourceLocation getTextureLocation(Chicken entity) {
        boolean sleepVisuals = EthologicalClientConfig.CONFIG.sleepModelsEnabled();
        boolean eyesClosed = sleepVisuals && entity.getData(ModAttachments.SLEEPING);
        if (this.useBabyModels && entity.isBaby()) {
            return eyesClosed ? this.textures.babyAsleep() : this.textures.baby();
        }
        return eyesClosed ? this.textures.asleep()
                : (sleepVisuals && entity.getData(ModAttachments.RESTING) ? this.textures.resting() : this.textures.standing());
    }

    public void render(Chicken entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        boolean sleepingOrResting = entity.getData(ModAttachments.SLEEPING) || entity.getData(ModAttachments.RESTING);
        FaCompat.lockToVanillaModelWhileSleeping(entity, sleepingOrResting);
        this.model = this.useBabyModels && entity.isBaby() ? this.babyModel : this.adultModel;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    protected void setupRotations(Chicken entity, PoseStack poseStack, float bob, float yBodyRot, float partialTick, float scale) {
        if (EthologicalClientConfig.CONFIG.sleepModelsEnabled() && entity.getData(ModAttachments.SLEEPING)) {
            yBodyRot = entity.getData(ModAttachments.SLEEP_YAW);
        }
        super.setupRotations(entity, poseStack, bob, yBodyRot, partialTick, scale);
    }

    protected float getBob(Chicken livingBase, float partialTicks) {
        if (EthologicalClientConfig.CONFIG.sleepModelsEnabled() && livingBase.getData(ModAttachments.SLEEPING)) {
            return 0.0f;
        }
        float f = Mth.lerp(partialTicks, livingBase.oFlap, livingBase.flap);
        float f1 = Mth.lerp(partialTicks, livingBase.oFlapSpeed, livingBase.flapSpeed);
        return (Mth.sin(f) + 1.0f) * f1;
    }
}
