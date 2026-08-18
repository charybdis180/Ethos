/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.vertex.PoseStack
 *  com.mojang.blaze3d.vertex.VertexConsumer
 *  net.minecraft.client.model.geom.ModelPart
 *  net.minecraft.client.model.geom.PartPose
 *  net.minecraft.client.model.geom.builders.CubeDeformation
 *  net.minecraft.client.model.geom.builders.CubeListBuilder
 *  net.minecraft.client.model.geom.builders.LayerDefinition
 *  net.minecraft.client.model.geom.builders.MeshDefinition
 *  net.minecraft.client.model.geom.builders.PartDefinition
 *  net.minecraft.util.Mth
 *  net.minecraft.world.entity.animal.Sheep
 */
package com.charybdis180.ethological.client.sleep;

import com.charybdis180.ethological.client.sleep.SleepPose;
import com.charybdis180.ethological.client.sleep.SleepingSheepFurModel;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.Sheep;

public class BabySheepFurModel
extends SleepingSheepFurModel {
    private final ModelPart root;
    private final SleepPose.SleepParts parts;
    private final ModelPart head;
    private final ModelPart body;
    private final ModelPart rightFrontLeg;
    private final ModelPart leftFrontLeg;
    private final ModelPart rightHindLeg;
    private final ModelPart leftHindLeg;
    private final float defaultBodyY;
    private final float defaultHeadY;
    private final float defaultFrontLegZ;
    private final float defaultHindLegZ;
    private final float defaultRightFrontX;
    private final float defaultLeftFrontX;
    private final float defaultRightHindX;
    private final float defaultLeftHindX;

    public BabySheepFurModel(ModelPart root) {
        super(root);
        this.root = root;
        this.head = root.getChild("head");
        this.body = root.getChild("body");
        this.rightFrontLeg = root.getChild("right_front_leg");
        this.leftFrontLeg = root.getChild("left_front_leg");
        this.rightHindLeg = root.getChild("right_hind_leg");
        this.leftHindLeg = root.getChild("left_hind_leg");
        this.parts = new SleepPose.SleepParts(this.rightFrontLeg, this.leftFrontLeg, this.rightHindLeg, this.leftHindLeg, this.body, this.head);
        this.defaultBodyY = this.body.y;
        this.defaultHeadY = this.head.y;
        this.defaultFrontLegZ = this.rightFrontLeg.z;
        this.defaultHindLegZ = this.rightHindLeg.z;
        this.defaultRightFrontX = this.rightFrontLeg.x;
        this.defaultLeftFrontX = this.leftFrontLeg.x;
        this.defaultRightHindX = this.rightHindLeg.x;
        this.defaultLeftHindX = this.leftHindLeg.x;
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();
        partdefinition.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-2.5f, -4.5f, -3.5f, 5.0f, 5.0f, 5.0f, new CubeDeformation(0.0f)), PartPose.offset((float)0.0f, (float)15.5f, (float)-2.5f));
        partdefinition.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 10).addBox(-3.0f, -2.0f, -4.5f, 6.0f, 4.0f, 9.0f, new CubeDeformation(0.0f)), PartPose.offset((float)0.0f, (float)17.0f, (float)0.5f));
        partdefinition.addOrReplaceChild("left_hind_leg", CubeListBuilder.create().texOffs(24, 12).addBox(-1.0f, 0.0f, -1.0f, 2.0f, 5.0f, 2.0f, new CubeDeformation(0.0f)), PartPose.offset((float)2.0f, (float)19.0f, (float)3.0f));
        partdefinition.addOrReplaceChild("right_hind_leg", CubeListBuilder.create().texOffs(0, 23).addBox(-1.0f, 0.0f, -1.0f, 2.0f, 5.0f, 2.0f, new CubeDeformation(0.0f)), PartPose.offset((float)-2.0f, (float)19.0f, (float)3.0f));
        partdefinition.addOrReplaceChild("left_front_leg", CubeListBuilder.create().texOffs(24, 5).addBox(-1.0f, 0.0f, -1.0f, 2.0f, 5.0f, 2.0f, new CubeDeformation(0.0f)), PartPose.offset((float)2.0f, (float)19.0f, (float)-2.0f));
        partdefinition.addOrReplaceChild("right_front_leg", CubeListBuilder.create().texOffs(8, 23).addBox(-1.0f, 0.0f, -1.0f, 2.0f, 5.0f, 2.0f, new CubeDeformation(0.0f)), PartPose.offset((float)-2.0f, (float)19.0f, (float)-2.0f));
        return LayerDefinition.create((MeshDefinition)meshdefinition, (int)64, (int)32);
    }

    @Override
    public void setupAnim(Sheep entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        boolean asleep = (Boolean)entity.getData(SleepAttachments.SLEEPING) != false || (Boolean)entity.getData(SleepAttachments.RESTING) != false;
        this.head.xRot = headPitch * ((float)Math.PI / 180);
        this.head.yRot = netHeadYaw * ((float)Math.PI / 180);
        boolean sleeping = (Boolean)entity.getData(SleepAttachments.SLEEPING);
        boolean resting = (Boolean)entity.getData(SleepAttachments.RESTING);
        if (asleep) {
            this.body.y = 20.0f;
            if (sleeping) {
                this.head.y = 23.0f;
                this.head.xRot = 0.25f;
                this.head.yRot = 0.0f;
            }
            this.body.yRot = 0.0f;
            this.body.zRot = 0.0f;
            SleepPose.apply(this.parts, 20.0f, 23.0f, -3.0f, 2.0f, this.defaultRightFrontX, this.defaultLeftFrontX, this.defaultRightHindX, this.defaultLeftHindX, 0.0f, 1.5f, resting, this.defaultBodyY, this.defaultHeadY);
        } else {
            this.body.y = this.defaultBodyY;
            this.head.y = this.defaultHeadY;
            SleepPose.clear(this.parts, this.defaultBodyY, this.defaultHeadY, 19.0f, this.defaultFrontLegZ, this.defaultHindLegZ, this.defaultRightFrontX, this.defaultLeftFrontX, this.defaultRightHindX, this.defaultLeftHindX);
            this.rightHindLeg.xRot = Mth.cos((float)(limbSwing * 0.6662f)) * 1.4f * limbSwingAmount;
            this.leftHindLeg.xRot = Mth.cos((float)(limbSwing * 0.6662f + (float)Math.PI)) * 1.4f * limbSwingAmount;
            this.rightFrontLeg.xRot = Mth.cos((float)(limbSwing * 0.6662f + (float)Math.PI)) * 1.4f * limbSwingAmount;
            this.leftFrontLeg.xRot = Mth.cos((float)(limbSwing * 0.6662f)) * 1.4f * limbSwingAmount;
        }
    }

    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, int color) {
        this.head.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
        this.body.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
        this.rightFrontLeg.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
        this.leftFrontLeg.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
        this.rightHindLeg.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
        this.leftHindLeg.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
    }
}

