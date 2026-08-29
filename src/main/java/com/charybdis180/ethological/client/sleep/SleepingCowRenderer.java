package com.charybdis180.ethological.client.sleep;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.client.FaCompat;
import com.charybdis180.ethological.config.EthologicalClientConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Cow;

public class SleepingCowRenderer extends MobRenderer<Cow, SleepingCowModel> {
    // Local copy of the vanilla texture: FA's pack overrides the minecraft-namespace path with a
    // repaint whose eye whites are erased (FA redraws eyes as CEM overlays, which are gone when we
    // lock sleeping/resting animals to the vanilla model).
    private static final ResourceLocation AWAKE_TEXTURE = ResourceLocation.fromNamespaceAndPath("ethological", "textures/entity/cow/cow_awake.png");
    private static final ResourceLocation STANDING_TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/cow/cow.png");
    private static final ResourceLocation ASLEEP_TEXTURE = ResourceLocation.fromNamespaceAndPath("ethological", "textures/entity/cow/cow_sleep.png");
    private static final ResourceLocation BABY_TEXTURE = ResourceLocation.fromNamespaceAndPath("ethological", "textures/entity/cow/cow_baby.png");
    private static final ResourceLocation BABY_ASLEEP_TEXTURE = ResourceLocation.fromNamespaceAndPath("ethological", "textures/entity/cow/cow_baby_sleep.png");
    private final TextureResolver.TexSet textures;
    private final SleepingCowModel adultModel;
    private final BabyCowModel babyModel;
    private final boolean useBabyModels;

    public SleepingCowRenderer(EntityRendererProvider.Context context) {
        this(context, EthologicalClientConfig.useModernBabyModels());
    }

    public SleepingCowRenderer(EntityRendererProvider.Context context, boolean useBabyModels) {
        super(context, new SleepingCowModel(context.bakeLayer(ModelLayers.COW)), 0.7f);
        this.textures = TextureResolver.resolve("cow", STANDING_TEXTURE, AWAKE_TEXTURE,
                ASLEEP_TEXTURE, BABY_TEXTURE, BABY_ASLEEP_TEXTURE);
        this.adultModel = (SleepingCowModel) this.model;
        this.babyModel = useBabyModels ? new BabyCowModel(context.bakeLayer(BabyModelLayers.BABY_COW)) : null;
        this.useBabyModels = useBabyModels;
    }

    public ResourceLocation getTextureLocation(Cow entity) {
        boolean sleepVisuals = EthologicalClientConfig.CONFIG.sleepModelsEnabled();
        boolean eyesClosed = sleepVisuals && entity.getData(ModAttachments.SLEEPING);
        if (this.useBabyModels && entity.isBaby()) {
            return eyesClosed ? this.textures.babyAsleep() : this.textures.baby();
        }
        // Lying-but-awake uses the namespace-safe vanilla copy (FA's repaint blanks the baked eyes
        // and redraws them via CEM overlays that don't exist on the locked vanilla model).
        return eyesClosed ? this.textures.asleep()
                : (sleepVisuals && entity.getData(ModAttachments.RESTING) ? this.textures.resting() : this.textures.standing());
    }

    public void render(Cow entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        boolean sleepingOrResting = entity.getData(ModAttachments.SLEEPING) || entity.getData(ModAttachments.RESTING);
        // Hand control back to FA/EMF as soon as the animal is up and about again.
        FaCompat.lockToVanillaModelWhileSleeping(entity, sleepingOrResting);
        this.model = this.useBabyModels && entity.isBaby() ? this.babyModel : this.adultModel;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    protected void setupRotations(Cow entity, PoseStack poseStack, float bob, float yBodyRot, float partialTick, float scale) {
        if (EthologicalClientConfig.CONFIG.sleepModelsEnabled() && entity.getData(ModAttachments.SLEEPING)) {
            yBodyRot = entity.getData(ModAttachments.SLEEP_YAW);
        }
        super.setupRotations(entity, poseStack, bob, yBodyRot, partialTick, scale);
    }
}
