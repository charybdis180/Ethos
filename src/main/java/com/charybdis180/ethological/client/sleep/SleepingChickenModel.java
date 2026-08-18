/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.model.ChickenModel
 *  net.minecraft.client.model.geom.ModelPart
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.animal.Chicken
 */
package com.charybdis180.ethological.client.sleep;

import com.charybdis180.ethological.client.HeadDip;
import com.charybdis180.ethological.sleep.SleepAttachments;
import net.minecraft.client.model.ChickenModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Chicken;

public class SleepingChickenModel
extends ChickenModel<Chicken> {
    private final ModelPart head;
    private final ModelPart beak;
    private final ModelPart redThing;
    private final ModelPart body;
    private final ModelPart rightWing;
    private final ModelPart leftWing;
    private final float defaultHeadY;
    private final float defaultBeakY;
    private final float defaultCombY;
    private final float defaultBodyY;
    private final float defaultWingY;

    public SleepingChickenModel(ModelPart root) {
        super(root);
        this.head = root.getChild("head");
        this.beak = root.getChild("beak");
        this.redThing = root.getChild("red_thing");
        this.body = root.getChild("body");
        this.rightWing = root.getChild("right_wing");
        this.leftWing = root.getChild("left_wing");
        this.defaultHeadY = this.head.y;
        this.defaultBeakY = this.beak.y;
        this.defaultCombY = this.redThing.y;
        this.defaultBodyY = this.body.y;
        this.defaultWingY = this.rightWing.y;
    }

    public void setupAnim(Chicken entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        boolean sleeping = (Boolean)entity.getData(SleepAttachments.SLEEPING);
        boolean resting = (Boolean)entity.getData(SleepAttachments.RESTING);
        if (sleeping || resting) {
            this.body.y = 21.0f;
            if (sleeping) {
                float headY = entity.isBaby() ? 17.5f : 20.5f;
                float beakY = entity.isBaby() ? 17.5f : 20.5f;
                float combY = entity.isBaby() ? 17.5f : 20.5f;
                this.head.y = headY;
                this.beak.y = beakY;
                this.redThing.y = combY;
                this.head.xRot = 0.55f;
                this.head.yRot = 0.0f;
                this.beak.xRot = 0.55f;
                this.beak.yRot = 0.0f;
                this.redThing.xRot = 0.55f;
                this.redThing.yRot = 0.0f;
            } else {
                float delta = 21.0f - this.defaultBodyY;
                this.head.y = this.defaultHeadY + delta;
                this.beak.y = this.defaultBeakY + delta;
                this.redThing.y = this.defaultCombY + delta;
            }
            this.rightWing.y = 20.0f;
            this.leftWing.y = 20.0f;
            this.rightWing.zRot = 0.0f;
            this.leftWing.zRot = 0.0f;
            this.rightWing.xRot = 0.0f;
            this.leftWing.xRot = 0.0f;
            this.body.yRot = 0.0f;
            this.body.zRot = 0.0f;
        } else {
            this.body.y = this.defaultBodyY;
            this.head.y = this.defaultHeadY;
            this.beak.y = this.defaultBeakY;
            this.redThing.y = this.defaultCombY;
            this.rightWing.y = this.defaultWingY;
            this.leftWing.y = this.defaultWingY;
            if (HeadDip.active((LivingEntity)entity, 0.0f)) {
                float dipY = HeadDip.positionScale((LivingEntity)entity, 0.0f) * 1.0f;
                float dipRot = HeadDip.angleScale((LivingEntity)entity, 0.0f);
                this.head.y = this.defaultHeadY + dipY;
                this.beak.y = this.defaultBeakY + dipY;
                this.redThing.y = this.defaultCombY + dipY;
                this.head.xRot = dipRot;
                this.beak.xRot = dipRot;
                this.redThing.xRot = dipRot;
            }
        }
    }
}

