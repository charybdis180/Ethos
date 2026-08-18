/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.Mob
 *  net.minecraft.world.entity.ai.goal.Goal
 *  net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.entity.animal.Chicken
 *  net.minecraft.world.entity.animal.Cow
 *  net.minecraft.world.entity.animal.Pig
 *  net.minecraft.world.entity.animal.Rabbit
 *  net.minecraft.world.entity.animal.Sheep
 *  net.minecraft.world.entity.animal.Wolf
 *  net.neoforged.bus.api.SubscribeEvent
 *  net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
 */
package com.charybdis180.ethological.herd;

import com.charybdis180.ethological.config.EthologicalConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Wolf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

public final class WolfEvents {
    private WolfEvents() {
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof Wolf)) {
            return;
        }
        Wolf wolf = (Wolf)entity;
        if (!((Boolean)EthologicalConfig.CONFIG.comfort.wildWolvesHuntLivestock.get()).booleanValue()) {
            return;
        }
        wolf.targetSelector.addGoal(5, new NearestAttackableTargetGoal<>(wolf, Animal.class, 10, true, false, living -> !wolf.isTame() && WolfEvents.isLivestockPrey(living)));
    }

    private static boolean isLivestockPrey(LivingEntity living) {
        return living instanceof Sheep || living instanceof Cow || living instanceof Chicken || living instanceof Pig || living instanceof Rabbit;
    }
}

