/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.vertex.PoseStack
 *  com.mojang.blaze3d.vertex.PoseStack$Pose
 *  com.mojang.blaze3d.vertex.VertexConsumer
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.renderer.LevelRenderer
 *  net.minecraft.client.renderer.MultiBufferSource$BufferSource
 *  net.minecraft.client.renderer.RenderType
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Vec3i
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.item.Items
 *  net.minecraft.world.phys.Vec3
 *  net.neoforged.api.distmarker.Dist
 *  net.neoforged.bus.api.SubscribeEvent
 *  net.neoforged.fml.common.EventBusSubscriber
 *  net.neoforged.neoforge.client.event.RenderLevelStageEvent
 *  net.neoforged.neoforge.client.event.RenderLevelStageEvent$Stage
 *  net.neoforged.neoforge.event.entity.player.PlayerInteractEvent$EntityInteract
 */
package com.charybdis180.ethological.client;

import com.charybdis180.ethological.avoidance.CrowdYield;
import com.charybdis180.ethological.herd.HerdAttachments;
import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.MotherData;
import com.charybdis180.ethological.home.HomeAttachments;
import com.charybdis180.ethological.home.HomeData;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.hunger.FoodTargetData;
import com.charybdis180.ethological.hunger.GrazePatches;
import com.charybdis180.ethological.hunger.HungerAttachments;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.thirst.ThirstAttachments;
import com.charybdis180.ethological.thirst.WaterTargetData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid="ethological", value={Dist.CLIENT})
public final class DebugHighlights {
    private static final Set<UUID> HIGHLIGHTED = new HashSet<UUID>();

    private DebugHighlights() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        if (!event.getItemStack().is(Items.DEBUG_STICK)) {
            return;
        }
        if (!(event.getTarget() instanceof LivingEntity)) {
            return;
        }
        UUID id = event.getTarget().getUUID();
        if (!HIGHLIGHTED.remove(id)) {
            HIGHLIGHTED.add(id);
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !DebugHighlights.holdingDebugStick(mc)) {
            return;
        }
        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        // Index every rendered entity by UUID once — the mother lookup below used to rescan the
        // full entity list per highlighted animal.
        Map<UUID, Entity> entitiesById = new HashMap<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            entitiesById.put(entity.getUUID(), entity);
        }
        // Dim occupied-block indicator for every rendered animal while holding a debug stick.
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Animal) {
                DebugHighlights.drawFaceY(poseStack, lines, camera, ((Animal)entity).blockPosition(), 0.85f, 0.45f, 0.10f);
            }
        }
        block0: for (Entity entity : mc.level.entitiesForRendering()) {
            if (!HIGHLIGHTED.contains(entity.getUUID()) || !(entity instanceof Animal)) continue;
            Animal animal = (Animal)entity;
            // Bright own-cell marker, separation radius, and the occupied cells its pathing steers around.
            BlockPos feet = animal.blockPosition();
            DebugHighlights.drawBox(poseStack, lines, camera, feet, 1.0f, 0.9f, 0.2f);
            DebugHighlights.drawRingY(poseStack, lines, camera, animal.getX(), feet.getY() + 0.1, animal.getZ(), 2.0f, 1.0f, 0.9f, 0.2f);
            // A live crowd-yield request: the blocker is being asked to step aside. Draw a
            // bright ring at its feet and a line in the stuck mover's heading direction.
            if (CrowdYield.hasActiveRequest(animal, animal.level().getGameTime())) {
                double heading = CrowdYield.moverHeadingOf(animal);
                DebugHighlights.drawRingY(poseStack, lines, camera, animal.getX(), feet.getY() + 0.1, animal.getZ(), 1.0f, 0.4f, 0.95f, 0.85f);
                Vec3 from = animal.getEyePosition(1.0f);
                Vec3 to = from.add(Math.cos(heading) * 3.0, 0.0, Math.sin(heading) * 3.0);
                DebugHighlights.drawLine(poseStack, lines, camera, from, to, 0.4f, 0.95f, 0.85f);
            }
            if (animal.hasData(SleepAttachments.SLEEP_TARGET)) {
                BlockPos sleepTarget = (BlockPos)animal.getData(SleepAttachments.SLEEP_TARGET);
                DebugHighlights.drawBox(poseStack, lines, camera, sleepTarget, 0.95f, 0.35f, 0.85f);
                DebugHighlights.drawLine(poseStack, lines, camera, animal.getEyePosition(1.0f), Vec3.atCenterOf((Vec3i)sleepTarget), 0.95f, 0.35f, 0.85f);
            }
            List<Animal> neighbors = mc.level.getEntitiesOfClass(Animal.class, animal.getBoundingBox().inflate(6.0));
            int shown = 0;
            for (Animal other : neighbors) {
                if (other == animal || shown >= 12) continue;
                DebugHighlights.drawBox(poseStack, lines, camera, other.blockPosition(), 1.0f, 0.5f, 0.1f);
                ++shown;
            }
            Optional<HomeData> homeData = Homes.effectiveHomeData(animal);
            if (homeData.isPresent()) {
                BlockPos home = homeData.get().pos();
                if (homeData.get().temporary()) {
                    DebugHighlights.drawBox(poseStack, lines, camera, home, 1.0f, 0.55f, 0.15f);
                } else {
                    DebugHighlights.drawBox(poseStack, lines, camera, home, 1.0f, 0.25f, 0.25f);
                }
                float radius = ((Float)animal.getData(HomeAttachments.LEASH_RADIUS)).floatValue();
                if (radius > 0.5f) {
                    DebugHighlights.drawRing(poseStack, lines, camera, home, radius, 0.25f, 0.45f, 1.0f);
                }
            }
            if (animal.hasData(HungerAttachments.FOOD_TARGET)) {
                BlockPos target = ((FoodTargetData)animal.getData(HungerAttachments.FOOD_TARGET)).pos();
                DebugHighlights.drawBox(poseStack, lines, camera, target, 1.0f, 0.9f, 0.2f);
                DebugHighlights.drawLine(poseStack, lines, camera, animal.getEyePosition(1.0f), Vec3.atCenterOf((Vec3i)target), 1.0f, 0.9f, 0.2f);
            }
            GrazePatches.effectivePatch(animal).ifPresent(patch -> {
                DebugHighlights.drawBox(poseStack, lines, camera, patch, 0.45f, 0.95f, 0.25f);
                DebugHighlights.drawRing(poseStack, lines, camera, patch, 8.0f, 0.35f, 0.85f, 0.2f);
            });
            if (animal.hasData(ThirstAttachments.WATER_TARGET)) {
                WaterTargetData water = (WaterTargetData)animal.getData(ThirstAttachments.WATER_TARGET);
                DebugHighlights.drawBox(poseStack, lines, camera, water.pos(), 0.2f, 0.85f, 1.0f);
                DebugHighlights.drawBox(poseStack, lines, camera, water.shore(), 0.15f, 0.75f, 0.65f);
                DebugHighlights.drawLine(poseStack, lines, camera, animal.getEyePosition(1.0f), Vec3.atCenterOf((Vec3i)water.shore()), 0.15f, 0.75f, 0.65f);
            }
            if (homeData.isEmpty()) {
                Homes.effectiveMigrationHeading(animal).ifPresent(heading -> {
                    Vec3 from = animal.getEyePosition(1.0f);
                    Vec3 to = from.add(Math.cos(heading) * 20.0, 0.0, Math.sin(heading) * 20.0);
                    DebugHighlights.drawLine(poseStack, lines, camera, from, to, 1.0f, 0.2f, 0.85f);
                });
            }
            if (animal.hasData(HerdAttachments.HERD_DATA)) {
                if (((HerdData)animal.getData(HerdAttachments.HERD_DATA)).alpha()) {
                    // Alpha highlighted: fan out lines to every rendered herd member.
                    HerdManager.Herd herd = HerdManager.herdOf(animal);
                    if (herd != null) {
                        for (UUID memberId : herd.members) {
                            if (memberId.equals(animal.getUUID())) continue;
                            Entity member = entitiesById.get(memberId);
                            if (member != null) {
                                DebugHighlights.drawLine(poseStack, lines, camera, animal.getEyePosition(1.0f), member.getEyePosition(1.0f), 0.75f, 0.75f, 0.8f);
                            }
                        }
                    }
                } else {
                    Homes.herdAlpha(animal).ifPresent(alpha -> {
                        if (alpha != animal) {
                            DebugHighlights.drawLine(poseStack, lines, camera, animal.getEyePosition(1.0f), alpha.getEyePosition(1.0f), 0.75f, 0.75f, 0.8f);
                        }
                    });
                }
            }
            if (!animal.hasData(HerdAttachments.MOTHER)) continue;
            UUID motherId = ((MotherData)animal.getData(HerdAttachments.MOTHER)).motherId();
            Entity mother = entitiesById.get(motherId);
            if (mother != null) {
                DebugHighlights.drawLine(poseStack, lines, camera, animal.getEyePosition(1.0f), mother.getEyePosition(1.0f), 1.0f, 0.45f, 0.75f);
                continue block0;
            }
        }
        buffers.endBatch(RenderType.lines());
    }

    private static boolean holdingDebugStick(Minecraft mc) {
        return mc.player != null && (mc.player.getMainHandItem().is(Items.DEBUG_STICK) || mc.player.getOffhandItem().is(Items.DEBUG_STICK));
    }

    private static void drawBox(PoseStack poseStack, VertexConsumer lines, Vec3 camera, BlockPos pos, float r, float g, float b) {
        LevelRenderer.renderLineBox((PoseStack)poseStack, (VertexConsumer)lines, (double)((double)pos.getX() - camera.x - 0.05), (double)((double)pos.getY() - camera.y - 0.05), (double)((double)pos.getZ() - camera.z - 0.05), (double)((double)pos.getX() + 1.05 - camera.x), (double)((double)pos.getY() + 1.05 - camera.y), (double)((double)pos.getZ() + 1.05 - camera.z), (float)r, (float)g, (float)b, (float)1.0f);
    }

    private static void drawFaceY(PoseStack poseStack, VertexConsumer lines, Vec3 camera, BlockPos pos, float r, float g, float b) {
        double y = (double)pos.getY() + 0.02;
        double pad = 0.05;
        Vec3 a = new Vec3((double)pos.getX() + pad, y, (double)pos.getZ() + pad);
        Vec3 bVec = new Vec3((double)pos.getX() + 1.0 - pad, y, (double)pos.getZ() + pad);
        Vec3 c = new Vec3((double)pos.getX() + 1.0 - pad, y, (double)pos.getZ() + 1.0 - pad);
        Vec3 d = new Vec3((double)pos.getX() + pad, y, (double)pos.getZ() + 1.0 - pad);
        DebugHighlights.drawLine(poseStack, lines, camera, a, bVec, r, g, b);
        DebugHighlights.drawLine(poseStack, lines, camera, bVec, c, r, g, b);
        DebugHighlights.drawLine(poseStack, lines, camera, c, d, r, g, b);
        DebugHighlights.drawLine(poseStack, lines, camera, d, a, r, g, b);
    }

    private static void drawLine(PoseStack poseStack, VertexConsumer lines, Vec3 camera, Vec3 from, Vec3 to, float r, float g, float b) {
        PoseStack.Pose pose = poseStack.last();
        Vec3 a = from.subtract(camera);
        Vec3 bVec = to.subtract(camera);
        Vec3 delta = bVec.subtract(a);
        double len = delta.length();
        float nx = len < 1.0E-4 ? 0.0f : (float)(delta.x / len);
        float ny = len < 1.0E-4 ? 1.0f : (float)(delta.y / len);
        float nz = len < 1.0E-4 ? 0.0f : (float)(delta.z / len);
        lines.addVertex(pose, (float)a.x, (float)a.y, (float)a.z).setColor(r, g, b, 1.0f).setNormal(pose, nx, ny, nz);
        lines.addVertex(pose, (float)bVec.x, (float)bVec.y, (float)bVec.z).setColor(r, g, b, 1.0f).setNormal(pose, nx, ny, nz);
    }

    private static void drawRing(PoseStack poseStack, VertexConsumer lines, Vec3 camera, BlockPos home, float radius, float r, float g, float b) {
        DebugHighlights.drawRingY(poseStack, lines, camera, (double)home.getX() + 0.5, (double)home.getY() + 1.0, (double)home.getZ() + 0.5, radius, r, g, b);
    }

    private static void drawRingY(PoseStack poseStack, VertexConsumer lines, Vec3 camera, double cx, double cy, double cz, float radius, float r, float g, float b) {
        int segments = 64;
        double y = cy - camera.y;
        for (int i = 0; i < segments; ++i) {
            double a1 = (double)i * (Math.PI * 2 / (double)segments);
            double a2 = (double)(i + 1) * (Math.PI * 2 / (double)segments);
            double x1 = cx + Math.cos(a1) * (double)radius - camera.x;
            double z1 = cz + Math.sin(a1) * (double)radius - camera.z;
            double x2 = cx + Math.cos(a2) * (double)radius - camera.x;
            double z2 = cz + Math.sin(a2) * (double)radius - camera.z;
            double pad = 0.02;
            LevelRenderer.renderLineBox((PoseStack)poseStack, (VertexConsumer)lines, (double)(Math.min(x1, x2) - pad), (double)(y - pad), (double)(Math.min(z1, z2) - pad), (double)(Math.max(x1, x2) + pad), (double)(y + pad), (double)(Math.max(z1, z2) + pad), (float)r, (float)g, (float)b, (float)1.0f);
        }
    }
}

