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
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.animal.Cow
 */
package com.charybdis180.ethological.client.sleep;

import com.charybdis180.ethological.client.sleep.BabyCowModel;
import com.charybdis180.ethological.client.sleep.BabyModelLayers;
import com.charybdis180.ethological.client.sleep.SleepingCowModel;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Cow;

public class SleepingCowRenderer
extends MobRenderer<Cow, SleepingCowModel> {
    private static final ResourceLocation AWAKE_TEXTURE = ResourceLocation.withDefaultNamespace((String)"textures/entity/cow/cow.png");
    private static final ResourceLocation ASLEEP_TEXTURE = ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"textures/entity/cow/cow_sleep.png");
    private static final ResourceLocation BABY_TEXTURE = ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"textures/entity/cow/cow_baby.png");
    private static final ResourceLocation BABY_ASLEEP_TEXTURE = ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"textures/entity/cow/cow_baby_sleep.png");
    private final SleepingCowModel adultModel;
    private final BabyCowModel babyModel;

    public SleepingCowRenderer(EntityRendererProvider.Context context) {
        super(context, new SleepingCowModel(context.bakeLayer(ModelLayers.COW)), 0.7f);
        this.adultModel = (SleepingCowModel)this.model;
        this.babyModel = new BabyCowModel(context.bakeLayer(BabyModelLayers.BABY_COW));
    }

    public ResourceLocation getTextureLocation(Cow entity) {
        if (entity.isBaby()) {
            return (Boolean)entity.getData(SleepAttachments.SLEEPING) != false ? BABY_ASLEEP_TEXTURE : BABY_TEXTURE;
        }
        return (Boolean)entity.getData(SleepAttachments.SLEEPING) != false ? ASLEEP_TEXTURE : AWAKE_TEXTURE;
    }

    public void render(Cow entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        this.model = entity.isBaby() ? this.babyModel : this.adultModel;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    protected void setupRotations(Cow entity, PoseStack poseStack, float bob, float yBodyRot, float partialTick, float scale) {
        if (((Boolean)entity.getData(SleepAttachments.SLEEPING)).booleanValue()) {
            yBodyRot = ((Float)entity.getData(SleepAttachments.SLEEP_YAW)).floatValue();
        }
        super.setupRotations(entity, poseStack, bob, yBodyRot, partialTick, scale);
    }
}

