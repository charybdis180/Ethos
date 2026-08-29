package com.charybdis180.ethological.client.sleep;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.client.sleep.SleepPose;
import com.charybdis180.ethological.client.sleep.SleepingCowModel;
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
import net.minecraft.world.entity.animal.Cow;

public class BabyCowModel
extends SleepingCowModel {
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
    private final float defaultHeadZ;
    private final float defaultFrontLegZ;
    private final float defaultHindLegZ;
    private final float defaultRightFrontX;
    private final float defaultLeftFrontX;
    private final float defaultRightHindX;
    private final float defaultLeftHindX;

    public BabyCowModel(ModelPart root) {
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
        this.defaultHeadZ = this.head.z;
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
        PartDefinition head = partdefinition.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 18).addBox(-3.0f, -4.569f, -4.8333f, 6.0f, 6.0f, 5.0f, new CubeDeformation(0.0f)).texOffs(8, 29).addBox(3.0f, -5.569f, -3.8333f, 1.0f, 2.0f, 1.0f, new CubeDeformation(0.0f)).texOffs(12, 29).addBox(-2.0f, -1.569f, -5.8333f, 4.0f, 3.0f, 1.0f, new CubeDeformation(0.0f)), PartPose.offset((float)0.0f, (float)13.569f, (float)-5.1667f));
        head.addOrReplaceChild("mirror", CubeListBuilder.create().texOffs(4, 29).mirror().addBox(-4.0f, -5.569f, -3.8333f, 1.0f, 2.0f, 1.0f, new CubeDeformation(0.0f)).mirror(false), PartPose.offset((float)0.0f, (float)0.0f, (float)0.0f));
        partdefinition.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0).addBox(-7.0f, -7.0f, -1.0f, 8.0f, 6.0f, 12.0f, new CubeDeformation(0.0f)), PartPose.offset((float)3.0f, (float)19.0f, (float)-5.0f));
        partdefinition.addOrReplaceChild("right_front_leg", CubeListBuilder.create().texOffs(22, 18).addBox(-1.5f, 0.0f, -1.5f, 3.0f, 6.0f, 3.0f, new CubeDeformation(0.0f)), PartPose.offset((float)-2.5f, (float)18.0f, (float)-3.5f));
        partdefinition.addOrReplaceChild("left_front_leg", CubeListBuilder.create().texOffs(34, 18).addBox(-1.5f, 0.0f, -1.5f, 3.0f, 6.0f, 3.0f, new CubeDeformation(0.0f)), PartPose.offset((float)2.5f, (float)18.0f, (float)-3.5f));
        partdefinition.addOrReplaceChild("right_hind_leg", CubeListBuilder.create().texOffs(22, 27).addBox(-1.5f, 0.0f, -1.5f, 3.0f, 6.0f, 3.0f, new CubeDeformation(0.0f)), PartPose.offset((float)-2.5f, (float)18.0f, (float)3.5f));
        partdefinition.addOrReplaceChild("left_hind_leg", CubeListBuilder.create().texOffs(34, 27).addBox(-1.5f, 0.0f, -1.5f, 3.0f, 6.0f, 3.0f, new CubeDeformation(0.0f)), PartPose.offset((float)2.5f, (float)18.0f, (float)3.5f));
        return LayerDefinition.create((MeshDefinition)meshdefinition, (int)64, (int)64);
    }

    @Override
    public void setupAnim(Cow entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        boolean asleep = (Boolean)entity.getData(ModAttachments.SLEEPING) != false || (Boolean)entity.getData(ModAttachments.RESTING) != false;
        this.head.xRot = headPitch * ((float)Math.PI / 180);
        this.head.yRot = netHeadYaw * ((float)Math.PI / 180);
        boolean sleeping = (Boolean)entity.getData(ModAttachments.SLEEPING);
        boolean resting = (Boolean)entity.getData(ModAttachments.RESTING);
        if (asleep && SleepPose.lieDownModelsEnabled()) {
            this.body.y = 23.0f;
            if (sleeping) {
                this.head.y = 22.5f;
                this.head.z = -3.5f;
                this.head.xRot = 0.25f;
                this.head.yRot = 0.0f;
            }
            this.body.yRot = 0.0f;
            this.body.zRot = 0.0f;
            SleepPose.apply(this.parts, 23.0f, 22.5f, -5.75f, 4.75f, this.defaultRightFrontX, this.defaultLeftFrontX, this.defaultRightHindX, this.defaultLeftHindX, 0.75f, 1.5f, resting, this.defaultBodyY, this.defaultHeadY);
        } else {
            this.body.y = this.defaultBodyY;
            this.head.y = this.defaultHeadY;
            this.head.z = this.defaultHeadZ;
            SleepPose.clear(this.parts, this.defaultBodyY, this.defaultHeadY, 18.0f, this.defaultFrontLegZ, this.defaultHindLegZ, this.defaultRightFrontX, this.defaultLeftFrontX, this.defaultRightHindX, this.defaultLeftHindX);
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

