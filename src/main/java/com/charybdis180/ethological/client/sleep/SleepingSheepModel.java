package com.charybdis180.ethological.client.sleep;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.client.sleep.SleepPose;
import net.minecraft.client.model.SheepModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.animal.Sheep;

public class SleepingSheepModel
extends SheepModel<Sheep> {
    private final ModelPart modelRoot;
    private final SleepPose.SleepParts parts;
    private final float defaultBodyY;
    private final float defaultHeadY;
    private final float defaultFrontLegZ;
    private final float defaultHindLegZ;
    private final float defaultRightFrontX;
    private final float defaultLeftFrontX;
    private final float defaultRightHindX;
    private final float defaultLeftHindX;

    public SleepingSheepModel(ModelPart root) {
        super(root);
        this.modelRoot = root;
        this.parts = SleepPose.SleepParts.of(root);
        this.defaultBodyY = root.getChild((String)"body").y;
        this.defaultHeadY = root.getChild((String)"head").y;
        this.defaultFrontLegZ = root.getChild((String)"right_front_leg").z;
        this.defaultHindLegZ = root.getChild((String)"right_hind_leg").z;
        this.defaultRightFrontX = root.getChild((String)"right_front_leg").x;
        this.defaultLeftFrontX = root.getChild((String)"left_front_leg").x;
        this.defaultRightHindX = root.getChild((String)"right_hind_leg").x;
        this.defaultLeftHindX = root.getChild((String)"left_hind_leg").x;
    }

    public void setupAnim(Sheep entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        ModelPart head = this.parts.head();
        ModelPart body = this.parts.body();
        boolean sleeping = (Boolean)entity.getData(ModAttachments.SLEEPING);
        boolean resting = (Boolean)entity.getData(ModAttachments.RESTING);
        if ((sleeping || resting) && SleepPose.lieDownModelsEnabled()) {
            SleepPose.apply(this.parts, 13.5f, SleepPose.headY(20.0f, entity.isBaby(), 10.0f), this.defaultFrontLegZ, this.defaultHindLegZ, this.defaultRightFrontX, this.defaultLeftFrontX, this.defaultRightHindX, this.defaultLeftHindX, 0.0f, 1.5f, resting, this.defaultBodyY, this.defaultHeadY);
            if (sleeping) {
                head.yRot = 0.0f;
            }
            body.yRot = 0.0f;
            body.zRot = 0.0f;
        } else {
            SleepPose.clear(this.parts, this.defaultBodyY, this.defaultHeadY, 12.0f, this.defaultFrontLegZ, this.defaultHindLegZ, this.defaultRightFrontX, this.defaultLeftFrontX, this.defaultRightHindX, this.defaultLeftHindX);
        }
    }
}

