package com.charybdis180.ethological.client.sleep;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.client.sleep.SleepPose;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.Chicken;

public class BabyChickenModel
extends EntityModel<Chicken> {
    private final ModelPart body;
    private final ModelPart leftLeg;
    private final ModelPart rightLeg;
    private final ModelPart rightWing;
    private final ModelPart leftWing;
    private final float defaultBodyY;
    private final float defaultBodyPitch;
    private final float defaultLeftLegY;
    private final float defaultLeftLegZ;
    private final float defaultRightLegY;
    private final float defaultRightLegZ;

    public BabyChickenModel(ModelPart root) {
        this.body = root.getChild("body");
        this.leftLeg = root.getChild("left_leg");
        this.rightLeg = root.getChild("right_leg");
        this.rightWing = root.getChild("right_wing");
        this.leftWing = root.getChild("left_wing");
        this.defaultBodyY = this.body.y;
        this.defaultBodyPitch = this.body.xRot;
        this.defaultLeftLegY = this.leftLeg.y;
        this.defaultLeftLegZ = this.leftLeg.z;
        this.defaultRightLegY = this.rightLeg.y;
        this.defaultRightLegZ = this.rightLeg.z;
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();
        PartDefinition body = partdefinition.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0).addBox(-2.0f, -2.25f, -0.75f, 4.0f, 4.0f, 4.0f, new CubeDeformation(0.0f)).texOffs(10, 8).addBox(-1.0f, -0.25f, -1.75f, 2.0f, 1.0f, 1.0f, new CubeDeformation(0.0f)), PartPose.offset((float)0.0f, (float)20.25f, (float)-1.25f));
        partdefinition.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(2, 2).addBox(-0.5f, 0.0f, 0.0f, 1.0f, 2.0f, 0.0f, new CubeDeformation(0.0f)).texOffs(0, 1).addBox(-0.5f, 2.0f, -1.0f, 1.0f, 0.0f, 1.0f, new CubeDeformation(0.0f)), PartPose.offset((float)1.0f, (float)22.0f, (float)0.5f));
        partdefinition.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(0, 2).addBox(-0.5f, 0.0f, 0.0f, 1.0f, 2.0f, 0.0f, new CubeDeformation(0.0f)).texOffs(0, 0).addBox(-0.5f, 2.0f, -1.0f, 1.0f, 0.0f, 1.0f, new CubeDeformation(0.0f)), PartPose.offset((float)-1.0f, (float)22.0f, (float)0.5f));
        partdefinition.addOrReplaceChild("right_wing", CubeListBuilder.create().texOffs(6, 8).addBox(0.0f, 0.0f, -1.0f, 1.0f, 0.0f, 2.0f, new CubeDeformation(0.0f)), PartPose.offset((float)2.0f, (float)20.0f, (float)0.0f));
        partdefinition.addOrReplaceChild("left_wing", CubeListBuilder.create().texOffs(4, 8).addBox(-1.0f, 0.0f, -1.0f, 1.0f, 0.0f, 2.0f, new CubeDeformation(0.0f)), PartPose.offset((float)-2.0f, (float)20.0f, (float)0.0f));
        return LayerDefinition.create((MeshDefinition)meshdefinition, (int)16, (int)16);
    }

    public void setupAnim(Chicken entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        if ((SleepPose.lieDownModelsEnabled() && (entity.getData(ModAttachments.SLEEPING) || entity.getData(ModAttachments.RESTING)))) {
            this.body.y = 22.25f;
            this.body.xRot = 0.0f;
            this.leftLeg.y = 23.0f;
            this.leftLeg.z = this.defaultLeftLegZ + 0.5f;
            this.rightLeg.y = 23.0f;
            this.rightLeg.z = this.defaultRightLegZ + 0.5f;
            this.leftLeg.xRot = 0.0f;
            this.rightLeg.xRot = 0.0f;
            this.leftWing.zRot = -0.2f;
            this.rightWing.zRot = 0.2f;
            this.body.yRot = 0.0f;
            this.body.zRot = 0.0f;
        } else {
            float flap;
            this.body.y = this.defaultBodyY;
            this.body.xRot = this.defaultBodyPitch;
            this.leftLeg.y = this.defaultLeftLegY;
            this.leftLeg.z = this.defaultLeftLegZ;
            this.rightLeg.y = this.defaultRightLegY;
            this.rightLeg.z = this.defaultRightLegZ;
            this.rightWing.zRot = flap = Mth.sin((float)(ageInTicks * 0.25f)) * 0.1f;
            this.leftWing.zRot = -flap;
        }
    }

    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, int color) {
        this.body.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
        this.leftLeg.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
        this.rightLeg.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
        this.rightWing.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
        this.leftWing.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
    }
}

