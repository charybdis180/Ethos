/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.model.geom.ModelPart
 */
package com.charybdis180.ethological.client.sleep;

import net.minecraft.client.model.geom.ModelPart;

public final class SleepPose {
    private SleepPose() {
    }

    /** Baked child parts of a model root — resolved once per model instead of per frame. */
    public record SleepParts(ModelPart rightFront, ModelPart leftFront, ModelPart rightHind, ModelPart leftHind, ModelPart body, ModelPart head) {
        public static SleepParts of(ModelPart root) {
            return new SleepParts(
                    root.getChild("right_front_leg"),
                    root.getChild("left_front_leg"),
                    root.getChild("right_hind_leg"),
                    root.getChild("left_hind_leg"),
                    root.getChild("body"),
                    root.getChild("head"));
        }
    }

    public static float headY(float adultSleepHeadY, boolean baby) {
        return SleepPose.headY(adultSleepHeadY, baby, 5.5f);
    }

    public static float headY(float adultSleepHeadY, boolean baby, float raise) {
        return baby ? adultSleepHeadY - raise : adultSleepHeadY;
    }

    public static void apply(SleepParts parts, float bodySleepY, float headSleepY, float frontLegZ, float hindLegZ, float defaultRightFrontX, float defaultLeftFrontX, float defaultRightHindX, float defaultLeftHindX, float frontLegXInset, float hindLegXInset, boolean resting, float defaultBodyY, float defaultHeadY) {
        ModelPart rightFront = parts.rightFront();
        ModelPart leftFront = parts.leftFront();
        SleepPose.poseLeg(rightFront, 1.5707964f, 22.0f, frontLegZ);
        SleepPose.poseLeg(leftFront, 1.5707964f, 22.0f, frontLegZ);
        rightFront.x = defaultRightFrontX + frontLegXInset;
        leftFront.x = defaultLeftFrontX - frontLegXInset;
        ModelPart rightHind = parts.rightHind();
        ModelPart leftHind = parts.leftHind();
        SleepPose.poseLeg(rightHind, -1.5707964f, 21.25f, hindLegZ);
        SleepPose.poseLeg(leftHind, -1.5707964f, 21.25f, hindLegZ);
        rightHind.x = defaultRightHindX + hindLegXInset;
        leftHind.x = defaultLeftHindX - hindLegXInset;
        parts.body().y = bodySleepY;
        ModelPart head = parts.head();
        if (!resting) {
            head.y = headSleepY;
            head.xRot = 0.35f;
        } else {
            head.y = defaultHeadY + (bodySleepY - defaultBodyY);
        }
    }

    public static void clear(SleepParts parts, float defaultBodyY, float defaultHeadY, float defaultLegY, float defaultFrontLegZ, float defaultHindLegZ, float defaultRightFrontX, float defaultLeftFrontX, float defaultRightHindX, float defaultLeftHindX) {
        ModelPart rightHind = parts.rightHind();
        ModelPart leftHind = parts.leftHind();
        SleepPose.resetLeg(rightHind, defaultLegY, defaultHindLegZ);
        SleepPose.resetLeg(leftHind, defaultLegY, defaultHindLegZ);
        rightHind.x = defaultRightHindX;
        leftHind.x = defaultLeftHindX;
        ModelPart rightFront = parts.rightFront();
        ModelPart leftFront = parts.leftFront();
        SleepPose.resetLeg(rightFront, defaultLegY, defaultFrontLegZ);
        SleepPose.resetLeg(leftFront, defaultLegY, defaultFrontLegZ);
        rightFront.x = defaultRightFrontX;
        leftFront.x = defaultLeftFrontX;
        parts.body().y = defaultBodyY;
        parts.head().y = defaultHeadY;
    }

    private static void poseLeg(ModelPart leg, float xRot, float y, float z) {
        leg.xRot = xRot;
        leg.y = y;
        leg.z = z;
    }

    private static void resetLeg(ModelPart leg, float y, float z) {
        leg.y = y;
        leg.z = z;
    }
}

