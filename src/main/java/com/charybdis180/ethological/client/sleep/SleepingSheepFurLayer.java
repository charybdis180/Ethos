/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.vertex.PoseStack
 *  com.mojang.blaze3d.vertex.VertexConsumer
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.model.EntityModel
 *  net.minecraft.client.model.geom.EntityModelSet
 *  net.minecraft.client.model.geom.ModelLayers
 *  net.minecraft.client.renderer.MultiBufferSource
 *  net.minecraft.client.renderer.RenderType
 *  net.minecraft.client.renderer.entity.LivingEntityRenderer
 *  net.minecraft.client.renderer.entity.RenderLayerParent
 *  net.minecraft.client.renderer.entity.layers.RenderLayer
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.util.FastColor$ARGB32
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.animal.Sheep
 *  net.minecraft.world.item.DyeColor
 */
package com.charybdis180.ethological.client.sleep;

import com.charybdis180.ethological.client.sleep.BabyModelLayers;
import com.charybdis180.ethological.client.sleep.BabySheepFurModel;
import com.charybdis180.ethological.client.sleep.SleepingSheepFurModel;
import com.charybdis180.ethological.client.sleep.SleepingSheepModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.DyeColor;

public class SleepingSheepFurLayer
extends RenderLayer<Sheep, SleepingSheepModel> {
    private static final ResourceLocation SHEEP_FUR_LOCATION = ResourceLocation.withDefaultNamespace((String)"textures/entity/sheep/sheep_fur.png");
    private static final ResourceLocation BABY_SHEEP_FUR_LOCATION = ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"textures/entity/sheep/sheep_baby_wool.png");
    private final SleepingSheepFurModel adultModel;
    private final BabySheepFurModel babyModel;

    public SleepingSheepFurLayer(RenderLayerParent<Sheep, SleepingSheepModel> renderer, EntityModelSet modelSet) {
        super(renderer);
        this.adultModel = new SleepingSheepFurModel(modelSet.bakeLayer(ModelLayers.SHEEP_FUR));
        this.babyModel = new BabySheepFurModel(modelSet.bakeLayer(BabyModelLayers.BABY_SHEEP_WOOL));
    }

    private SleepingSheepFurModel activeModel(Sheep entity) {
        return entity.isBaby() ? this.babyModel : this.adultModel;
    }

    private ResourceLocation activeTexture(Sheep entity) {
        return entity.isBaby() ? BABY_SHEEP_FUR_LOCATION : SHEEP_FUR_LOCATION;
    }

    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, Sheep livingEntity, float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
        if (!livingEntity.isSheared()) {
            SleepingSheepFurModel model = this.activeModel(livingEntity);
            ResourceLocation texture = this.activeTexture(livingEntity);
            if (livingEntity.isInvisible()) {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.shouldEntityAppearGlowing((Entity)livingEntity)) {
                    ((SleepingSheepModel)this.getParentModel()).copyPropertiesTo((EntityModel)model);
                    model.prepareMobModel(livingEntity, limbSwing, limbSwingAmount, partialTicks);
                    model.setupAnim(livingEntity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
                    VertexConsumer vertexconsumer = buffer.getBuffer(RenderType.outline((ResourceLocation)texture));
                    model.renderToBuffer(poseStack, vertexconsumer, packedLight, LivingEntityRenderer.getOverlayCoords((LivingEntity)livingEntity, (float)0.0f), -16777216);
                }
            } else {
                int color;
                if (livingEntity.hasCustomName() && "jeb_".equals(livingEntity.getName().getString())) {
                    int cycle = livingEntity.tickCount / 25 + livingEntity.getId();
                    int dyeCount = DyeColor.values().length;
                    int from = cycle % dyeCount;
                    int to = (cycle + 1) % dyeCount;
                    float blend = ((float)(livingEntity.tickCount % 25) + partialTicks) / 25.0f;
                    int fromColor = Sheep.getColor((DyeColor)DyeColor.byId((int)from));
                    int toColor = Sheep.getColor((DyeColor)DyeColor.byId((int)to));
                    color = FastColor.ARGB32.lerp((float)blend, (int)fromColor, (int)toColor);
                } else {
                    color = Sheep.getColor((DyeColor)livingEntity.getColor());
                }
                SleepingSheepFurLayer.coloredCutoutModelCopyLayerRender((EntityModel)this.getParentModel(), (EntityModel)model, (ResourceLocation)texture, (PoseStack)poseStack, (MultiBufferSource)buffer, (int)packedLight, (LivingEntity)livingEntity, (float)limbSwing, (float)limbSwingAmount, (float)ageInTicks, (float)netHeadYaw, (float)headPitch, (float)partialTicks, (int)color);
            }
        }
    }
}

