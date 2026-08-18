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
 *  net.minecraft.client.renderer.entity.RenderLayerParent
 *  net.minecraft.client.renderer.entity.layers.RenderLayer
 *  net.minecraft.client.renderer.entity.layers.SaddleLayer
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.animal.Pig
 */
package com.charybdis180.ethological.client.sleep;

import com.charybdis180.ethological.client.sleep.BabyModelLayers;
import com.charybdis180.ethological.client.sleep.BabyPigModel;
import com.charybdis180.ethological.client.sleep.SleepingPigModel;
import com.charybdis180.ethological.sleep.SleepAttachments;
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

public class SleepingPigRenderer
extends MobRenderer<Pig, SleepingPigModel> {
    private static final ResourceLocation AWAKE_TEXTURE = ResourceLocation.withDefaultNamespace((String)"textures/entity/pig/pig.png");
    private static final ResourceLocation ASLEEP_TEXTURE = ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"textures/entity/pig/pig_sleep.png");
    private static final ResourceLocation BABY_TEXTURE = ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"textures/entity/pig/pig_baby.png");
    private static final ResourceLocation BABY_ASLEEP_TEXTURE = ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"textures/entity/pig/pig_baby_sleep.png");
    private final SleepingPigModel adultModel;
    private final BabyPigModel babyModel;

    public SleepingPigRenderer(EntityRendererProvider.Context context) {
        super(context, new SleepingPigModel(context.bakeLayer(ModelLayers.PIG)), 0.7f);
        this.adultModel = (SleepingPigModel)this.model;
        this.babyModel = new BabyPigModel(context.bakeLayer(BabyModelLayers.BABY_PIG));
        this.addLayer((RenderLayer)new SaddleLayer((RenderLayerParent)this, (EntityModel)new SleepingPigModel(context.bakeLayer(ModelLayers.PIG_SADDLE)), ResourceLocation.withDefaultNamespace((String)"textures/entity/pig/pig_saddle.png")));
    }

    public ResourceLocation getTextureLocation(Pig entity) {
        if (entity.isBaby()) {
            return (Boolean)entity.getData(SleepAttachments.SLEEPING) != false ? BABY_ASLEEP_TEXTURE : BABY_TEXTURE;
        }
        return (Boolean)entity.getData(SleepAttachments.SLEEPING) != false ? ASLEEP_TEXTURE : AWAKE_TEXTURE;
    }

    public void render(Pig entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        this.model = entity.isBaby() ? this.babyModel : this.adultModel;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    protected void setupRotations(Pig entity, PoseStack poseStack, float bob, float yBodyRot, float partialTick, float scale) {
        if (((Boolean)entity.getData(SleepAttachments.SLEEPING)).booleanValue()) {
            yBodyRot = ((Float)entity.getData(SleepAttachments.SLEEP_YAW)).floatValue();
        }
        super.setupRotations(entity, poseStack, bob, yBodyRot, partialTick, scale);
    }
}

