/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Position
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.LightningBolt
 *  net.minecraft.world.entity.ai.goal.Goal
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.phys.AABB
 *  net.minecraft.world.phys.Vec3
 *  net.neoforged.bus.api.SubscribeEvent
 *  net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
 *  net.neoforged.neoforge.event.entity.ProjectileImpactEvent
 *  net.neoforged.neoforge.event.level.ExplosionEvent$Detonate
 */
package com.charybdis180.ethological.social;

import com.charybdis180.ethological.herd.HerdAttachments;
import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.HerdSettingsManager;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.sleep.SleepEvents;
import com.charybdis180.ethological.social.Familiarity;
import com.charybdis180.ethological.social.SocialAttachments;
import com.charybdis180.ethological.social.StartleData;
import com.charybdis180.ethological.social.goal.CuriousGoal;
import com.charybdis180.ethological.social.goal.PlayGoal;
import com.charybdis180.ethological.social.goal.RestGoal;
import com.charybdis180.ethological.social.goal.StartleGoal;
import com.charybdis180.ethological.social.goal.VigilanceGoal;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

public final class SocialEvents {
    private static final long STARTLE_VIGILANCE_TICKS = 120L;

    private SocialEvents() {
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof Animal)) {
            return;
        }
        Animal animal = (Animal)entity;
        if (HerdSettingsManager.get(animal.getType()).isEmpty()) {
            return;
        }
        animal.goalSelector.addGoal(2, (Goal)new StartleGoal(animal));
        animal.goalSelector.addGoal(4, (Goal)new PlayGoal(animal));
        animal.goalSelector.addGoal(5, (Goal)new RestGoal(animal));
        animal.goalSelector.addGoal(5, (Goal)new CuriousGoal(animal));
        animal.goalSelector.addGoal(7, (Goal)new VigilanceGoal(animal));
    }

    @SubscribeEvent
    public static void onLightningJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof LightningBolt)) {
            return;
        }
        LightningBolt bolt = (LightningBolt)entity;
        SocialEvents.startleAround(bolt.level(), bolt.position(), 12.0, 40L);
    }

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (event.getProjectile().level().isClientSide()) {
            return;
        }
        SocialEvents.startleAround(event.getProjectile().level(), event.getRayTraceResult().getLocation(), 6.0, 25L);
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        SocialEvents.startleAround(event.getLevel(), event.getExplosion().center(), 10.0, 35L);
    }

    private static void startleAround(Level level, Vec3 point, double radius, long startleTicks) {
        if (level.isClientSide()) {
            return;
        }
        long now = level.getGameTime();
        BlockPos from = BlockPos.containing((Position)point);
        long mateTicks = Math.max(10L, startleTicks * 2L / 3L);
        for (Animal animal : level.getEntitiesOfClass(Animal.class, new AABB(point.x - radius, point.y - radius, point.z - radius, point.x + radius, point.y + radius, point.z + radius), a -> HerdSettingsManager.get(a.getType()).isPresent())) {
            SocialEvents.applyStartle(animal, from, now, startleTicks);
            SocialEvents.startleHerdMates(animal, from, now, mateTicks);
        }
    }

    /** Apply STARTLE directly — never re-enter {@link #startleAround} (avoids contagion recursion). */
    private static void applyStartle(Animal animal, BlockPos from, long now, long startleTicks) {
        long scaled = Familiarity.scaleStartleTicks(animal, startleTicks);
        long until = now + scaled;
        if (animal.hasData(SocialAttachments.STARTLE)) {
            StartleData existing = (StartleData)animal.getData(SocialAttachments.STARTLE);
            if (existing.untilGameTime() >= until) {
                return;
            }
        }
        animal.setData(SocialAttachments.STARTLE, new StartleData(from, until));
        animal.setData(SleepAttachments.SLEEP_VIGILANCE, (now + STARTLE_VIGILANCE_TICKS));
        if (((Boolean)animal.getData(SleepAttachments.SLEEPING)).booleanValue()) {
            SleepEvents.wake(animal);
        }
    }

    private static void startleHerdMates(Animal source, BlockPos from, long now, long mateTicks) {
        if (!source.hasData(HerdAttachments.HERD_DATA)) {
            return;
        }
        Level level = source.level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        HerdManager.Herd herd = HerdManager.get(((HerdData)source.getData(HerdAttachments.HERD_DATA)).herdId());
        if (herd == null) {
            return;
        }
        for (UUID memberId : herd.members) {
            if (memberId.equals(source.getUUID())) {
                continue;
            }
            Entity entity = serverLevel.getEntity(memberId);
            if (!(entity instanceof Animal mate) || !mate.isAlive()) {
                continue;
            }
            if (HerdSettingsManager.get(mate.getType()).isEmpty()) {
                continue;
            }
            SocialEvents.applyStartle(mate, from, now, mateTicks);
        }
    }
}

