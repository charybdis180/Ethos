/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.vertex.PoseStack
 *  net.minecraft.client.model.EntityModel
 *  net.minecraft.client.model.geom.ModelLayers
 *  net.minecraft.client.renderer.MultiBufferSource
 *  net.minecraft.client.renderer.entity.EntityRendererProvider$Context
 *  net.minecraft.client.renderer.entity.MobRenderer
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.util.Mth
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.animal.Chicken
 */
package com.charybdis180.ethological.client.sleep;

import com.charybdis180.ethological.client.sleep.BabyChickenModel;
import com.charybdis180.ethological.client.sleep.BabyModelLayers;
import com.charybdis180.ethological.client.sleep.SleepingChickenModel;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.Chicken;

public class SleepingChickenRenderer
extends MobRenderer<Chicken, EntityModel<Chicken>> {
    private static final ResourceLocation AWAKE_TEXTURE = ResourceLocation.withDefaultNamespace((String)"textures/entity/chicken.png");
    private static final ResourceLocation ASLEEP_TEXTURE = ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"textures/entity/chicken/chicken_sleep.png");
    private static final ResourceLocation BABY_TEXTURE = ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"textures/entity/chicken/chicken_baby.png");
    private static final ResourceLocation BABY_ASLEEP_TEXTURE = ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"textures/entity/chicken/chicken_baby_sleep.png");
    private final SleepingChickenModel adultModel;
    private final BabyChickenModel babyModel;

    public SleepingChickenRenderer(EntityRendererProvider.Context context) {
        super(context, new SleepingChickenModel(context.bakeLayer(ModelLayers.CHICKEN)), 0.3f);
        this.adultModel = (SleepingChickenModel)this.model;
        this.babyModel = new BabyChickenModel(context.bakeLayer(BabyModelLayers.BABY_CHICKEN));
    }

    public ResourceLocation getTextureLocation(Chicken entity) {
        if (entity.isBaby()) {
            return (Boolean)entity.getData(SleepAttachments.SLEEPING) != false ? BABY_ASLEEP_TEXTURE : BABY_TEXTURE;
        }
        return (Boolean)entity.getData(SleepAttachments.SLEEPING) != false ? ASLEEP_TEXTURE : AWAKE_TEXTURE;
    }

    public void render(Chicken entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        this.model = entity.isBaby() ? this.babyModel : this.adultModel;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    protected void setupRotations(Chicken entity, PoseStack poseStack, float bob, float yBodyRot, float partialTick, float scale) {
        if (((Boolean)entity.getData(SleepAttachments.SLEEPING)).booleanValue()) {
            yBodyRot = ((Float)entity.getData(SleepAttachments.SLEEP_YAW)).floatValue();
        }
        super.setupRotations(entity, poseStack, bob, yBodyRot, partialTick, scale);
    }

    protected float getBob(Chicken livingBase, float partialTicks) {
        if (((Boolean)livingBase.getData(SleepAttachments.SLEEPING)).booleanValue()) {
            return 0.0f;
        }
        float f = Mth.lerp((float)partialTicks, (float)livingBase.oFlap, (float)livingBase.flap);
        float f1 = Mth.lerp((float)partialTicks, (float)livingBase.oFlapSpeed, (float)livingBase.flapSpeed);
        return (Mth.sin((float)f) + 1.0f) * f1;
    }
}

