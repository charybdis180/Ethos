package com.charybdis180.ethological.client.sleep;

import com.charybdis180.ethological.registry.ModAttachments;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Renders the crop a rooting pig dug up, held at the snout tip fox-style while
 * MOUTH_ITEM is set (set on dig completion, cleared by RootForageGoal when the
 * pig's post-dig eat window ends).
 */
public class PigMouthItemLayer
extends RenderLayer<Pig, SleepingPigModel> {
    private final ItemRenderer itemRenderer;

    public PigMouthItemLayer(RenderLayerParent<Pig, SleepingPigModel> parent, ItemRenderer itemRenderer) {
        super(parent);
        this.itemRenderer = itemRenderer;
    }

    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, Pig entity, float limbSwing,
                       float limbSwingAmount, float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
        ItemStack mouth = (ItemStack)entity.getData(ModAttachments.MOUTH_ITEM);
        if (mouth == null || mouth.isEmpty()) {
            return;
        }
        poseStack.pushPose();
        // Snout-tip mount: forward of the face, slightly above center line. Values are in
        // model space (16 units per block) and were tuned against the vanilla pig proportions.
        poseStack.translate(0.0f, 0.45f, -0.55f);
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90.0f));
        poseStack.scale(0.4f, 0.4f, 0.4f);
        this.itemRenderer.renderStatic(entity, mouth, ItemDisplayContext.GROUND, false,
                poseStack, buffer, entity.level(), packedLight, OverlayTexture.NO_OVERLAY, entity.getId());
        poseStack.popPose();
    }
}
