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
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.animal.Sheep
 */
package com.charybdis180.ethological.client.sleep;

import com.charybdis180.ethological.client.sleep.BabyModelLayers;
import com.charybdis180.ethological.client.sleep.BabySheepModel;
import com.charybdis180.ethological.client.sleep.SleepingSheepFurLayer;
import com.charybdis180.ethological.client.sleep.SleepingSheepModel;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Sheep;

public class SleepingSheepRenderer
extends MobRenderer<Sheep, SleepingSheepModel> {
    private static final ResourceLocation AWAKE_TEXTURE = ResourceLocation.withDefaultNamespace((String)"textures/entity/sheep/sheep.png");
    private static final ResourceLocation ASLEEP_TEXTURE = ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"textures/entity/sheep/sheep_sleep.png");
    private static final ResourceLocation BABY_TEXTURE = ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"textures/entity/sheep/sheep_baby.png");
    private static final ResourceLocation BABY_ASLEEP_TEXTURE = ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"textures/entity/sheep/sheep_baby_sleep.png");
    private final SleepingSheepModel adultModel;
    private final BabySheepModel babyModel;

    public SleepingSheepRenderer(EntityRendererProvider.Context context) {
        super(context, new SleepingSheepModel(context.bakeLayer(ModelLayers.SHEEP)), 0.7f);
        this.adultModel = (SleepingSheepModel)this.model;
        this.babyModel = new BabySheepModel(context.bakeLayer(BabyModelLayers.BABY_SHEEP));
        this.addLayer(new SleepingSheepFurLayer((RenderLayerParent<Sheep, SleepingSheepModel>)this, context.getModelSet()));
    }

    public ResourceLocation getTextureLocation(Sheep entity) {
        if (entity.isBaby()) {
            return (Boolean)entity.getData(SleepAttachments.SLEEPING) != false ? BABY_ASLEEP_TEXTURE : BABY_TEXTURE;
        }
        return (Boolean)entity.getData(SleepAttachments.SLEEPING) != false ? ASLEEP_TEXTURE : AWAKE_TEXTURE;
    }

    public void render(Sheep entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        this.model = entity.isBaby() ? this.babyModel : this.adultModel;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    protected void setupRotations(Sheep entity, PoseStack poseStack, float bob, float yBodyRot, float partialTick, float scale) {
        if (((Boolean)entity.getData(SleepAttachments.SLEEPING)).booleanValue()) {
            yBodyRot = ((Float)entity.getData(SleepAttachments.SLEEP_YAW)).floatValue();
        }
        super.setupRotations(entity, poseStack, bob, yBodyRot, partialTick, scale);
    }
}

