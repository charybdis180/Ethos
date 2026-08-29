package com.charybdis180.ethological.client.sleep;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.client.HeadDip;
import com.charybdis180.ethological.client.sleep.SleepPose;
import net.minecraft.client.model.PigModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Pig;

public class SleepingPigModel
extends PigModel<Pig> {
    private final ModelPart modelRoot;
    private final SleepPose.SleepParts parts;
    private final float defaultBodyY;
    private final float defaultHeadY;
    private final float defaultHeadZ;
    private final float defaultFrontLegZ;
    private final float defaultHindLegZ;
    private final float defaultRightFrontX;
    private final float defaultLeftFrontX;
    private final float defaultRightHindX;
    private final float defaultLeftHindX;

    public SleepingPigModel(ModelPart root) {
        super(root);
        this.modelRoot = root;
        this.parts = SleepPose.SleepParts.of(root);
        this.defaultBodyY = root.getChild((String)"body").y;
        this.defaultHeadY = root.getChild((String)"head").y;
        this.defaultHeadZ = root.getChild((String)"head").z;
        this.defaultFrontLegZ = root.getChild((String)"right_front_leg").z;
        this.defaultHindLegZ = root.getChild((String)"right_hind_leg").z;
        this.defaultRightFrontX = root.getChild((String)"right_front_leg").x;
        this.defaultLeftFrontX = root.getChild((String)"left_front_leg").x;
        this.defaultRightHindX = root.getChild((String)"right_hind_leg").x;
        this.defaultLeftHindX = root.getChild((String)"left_hind_leg").x;
    }

    public void setupAnim(Pig entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        ModelPart head = this.parts.head();
        ModelPart body = this.parts.body();
        boolean sleeping = (Boolean)entity.getData(ModAttachments.SLEEPING);
        boolean resting = (Boolean)entity.getData(ModAttachments.RESTING);
        if ((sleeping || resting) && SleepPose.lieDownModelsEnabled()) {
            SleepPose.apply(this.parts, 15.0f, SleepPose.headY(18.5f, entity.isBaby()), this.defaultFrontLegZ, this.defaultHindLegZ, this.defaultRightFrontX, this.defaultLeftFrontX, this.defaultRightHindX, this.defaultLeftHindX, 0.5f, 1.0f, resting, this.defaultBodyY, this.defaultHeadY);
            if (sleeping) {
                head.z = -5.0f;
                head.yRot = 0.0f;
            }
            body.yRot = 0.0f;
            body.zRot = 0.0f;
        } else {
            SleepPose.clear(this.parts, this.defaultBodyY, this.defaultHeadY, 18.0f, this.defaultFrontLegZ, this.defaultHindLegZ, this.defaultRightFrontX, this.defaultLeftFrontX, this.defaultRightHindX, this.defaultLeftHindX);
            head.z = this.defaultHeadZ;
            if (HeadDip.active((LivingEntity)entity, 0.0f)) {
                head.y = this.defaultHeadY + HeadDip.positionScale((LivingEntity)entity, 0.0f) * 2.0f;
                head.xRot = HeadDip.angleScale((LivingEntity)entity, 0.0f);
            }
        }
    }
}

