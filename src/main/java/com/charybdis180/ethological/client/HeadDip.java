package com.charybdis180.ethological.client;

import com.charybdis180.ethological.registry.ModAttachments;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

public final class HeadDip {
    public static final int PULSE_TICKS = 40;
    public static final int PULSE_INTERVAL = 35;

    private HeadDip() {
    }

    public static float remaining(LivingEntity entity, float partialTick) {
        if (!entity.hasData(ModAttachments.HEAD_DIP_UNTIL)) {
            return 0.0f;
        }
        long until = (Long)entity.getData(ModAttachments.HEAD_DIP_UNTIL);
        long now = entity.level().getGameTime();
        float left = (float)(until - now) - partialTick;
        return Math.max(0.0f, Math.min(40.0f, left));
    }

    public static boolean active(LivingEntity entity, float partialTick) {
        return HeadDip.remaining(entity, partialTick) > 0.0f;
    }

    public static float positionScale(LivingEntity entity, float partialTick) {
        float tick = HeadDip.remaining(entity, partialTick);
        if (tick <= 0.0f) {
            return 0.0f;
        }
        if (tick >= 4.0f && tick <= 36.0f) {
            return 1.0f;
        }
        return tick < 4.0f ? tick / 4.0f : (40.0f - tick) / 4.0f;
    }

    public static float angleScale(LivingEntity entity, float partialTick) {
        float tick = HeadDip.remaining(entity, partialTick);
        if (tick > 4.0f && tick <= 36.0f) {
            float f = (tick - 4.0f) / 32.0f;
            return 0.62831855f + 0.21991149f * Mth.sin((float)(f * 28.7f));
        }
        if (tick > 0.0f) {
            return 0.62831855f;
        }
        return entity.getXRot() * ((float)Math.PI / 180);
    }
}

