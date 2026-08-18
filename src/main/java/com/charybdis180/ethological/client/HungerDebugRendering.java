/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.vertex.PoseStack
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.Font
 *  net.minecraft.client.gui.Font$DisplayMode
 *  net.minecraft.client.gui.GuiGraphics
 *  net.minecraft.client.renderer.entity.EntityRenderDispatcher
 *  net.minecraft.core.Vec3i
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.chat.FormattedText
 *  net.minecraft.network.chat.MutableComponent
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.entity.animal.Sheep
 *  net.minecraft.world.item.Items
 *  net.minecraft.world.phys.Vec3
 *  net.neoforged.api.distmarker.Dist
 *  net.neoforged.bus.api.SubscribeEvent
 *  net.neoforged.fml.common.EventBusSubscriber
 *  net.neoforged.neoforge.client.event.RenderGuiEvent$Post
 *  net.neoforged.neoforge.client.event.RenderLivingEvent$Post
 *  org.joml.Matrix4f
 */
package com.charybdis180.ethological.client;

import com.charybdis180.ethological.debug.ActiveBehavior;
import com.charybdis180.ethological.debug.DebugAttachments;
import com.charybdis180.ethological.herd.HerdAttachments;
import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.MotherData;
import com.charybdis180.ethological.home.HomeData;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.hunger.HungerAttachments;
import com.charybdis180.ethological.hunger.HungerData;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.thirst.Thirst;
import com.charybdis180.ethological.thirst.ThirstAttachments;
import com.charybdis180.ethological.thirst.ThirstData;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.joml.Matrix4f;

@EventBusSubscriber(modid="ethological", value={Dist.CLIENT})
public final class HungerDebugRendering {
    private HungerDebugRendering() {
    }

    private static boolean holdingDebugStick(Minecraft mc) {
        return mc.player != null && (mc.player.getMainHandItem().is(Items.DEBUG_STICK) || mc.player.getOffhandItem().is(Items.DEBUG_STICK));
    }

    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (!entity.hasData(HungerAttachments.HUNGER_DATA)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!HungerDebugRendering.holdingDebugStick(mc)) {
            return;
        }
        MutableComponent component = Component.literal((String)HungerDebugRendering.statusLine(entity));
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(0.0, (double)entity.getBbHeight() + 0.5, 0.0);
        poseStack.mulPose(dispatcher.cameraOrientation());
        poseStack.scale(-0.025f, -0.025f, 0.025f);
        Matrix4f matrix = poseStack.last().pose();
        Font font = mc.font;
        float x = (float)(-font.width((FormattedText)component)) / 2.0f;
        font.drawInBatch((Component)component, x, 0.0f, -1, false, matrix, event.getMultiBufferSource(), Font.DisplayMode.NORMAL, 0, event.getPackedLight());
        poseStack.popPose();
    }

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!HungerDebugRendering.holdingDebugStick(mc)) {
            return;
        }
        Entity entity = mc.crosshairPickEntity;
        if (!(entity instanceof LivingEntity)) {
            return;
        }
        LivingEntity living = (LivingEntity)entity;
        if (!living.hasData(HungerAttachments.HUNGER_DATA)) {
            return;
        }
        HungerData data = Hunger.data((Entity)living);
        int max = Math.max(1, data.maxHunger());
        float pct = Math.max(0.0f, Math.min(1.0f, (float)data.hunger() / (float)max));
        int barWidth = 40;
        int barHeight = 5;
        int x = (mc.getWindow().getGuiScaledWidth() - barWidth) / 2;
        int y = mc.getWindow().getGuiScaledHeight() / 2 + 12;
        GuiGraphics graphics = event.getGuiGraphics();
        graphics.fill(x - 1, y - 1, x + barWidth + 1, y + barHeight + 1, -15724528);
        int red = (int)(255.0f * (1.0f - pct));
        int green = (int)(255.0f * pct);
        graphics.fill(x, y, x + (int)((float)barWidth * pct), y + barHeight, 0xFF000000 | red << 16 | green << 8);
        String label = (int)(pct * 100.0f) + "%";
        if (((Boolean)living.getData(SleepAttachments.SLEEPING)).booleanValue()) {
            label = label + " (sleeping)";
        }
        graphics.drawString(mc.font, label, x + barWidth + 4, y - 1, -1, true);
        int nextY = y + barHeight + 4;
        if (living.hasData(ThirstAttachments.THIRST_DATA)) {
            ThirstData thirst = Thirst.data((Entity)living);
            int thirstMax = Math.max(1, thirst.maxThirst());
            float thirstPct = Math.max(0.0f, Math.min(1.0f, (float)thirst.thirst() / (float)thirstMax));
            graphics.fill(x - 1, nextY - 1, x + barWidth + 1, nextY + barHeight + 1, -15724528);
            graphics.fill(x, nextY, x + (int)((float)barWidth * thirstPct), nextY + barHeight, -13408564);
            graphics.drawString(mc.font, "T:" + (int)(thirstPct * 100.0f) + "%", x + barWidth + 4, nextY - 1, -1, true);
            nextY += barHeight + 4;
        }
        graphics.drawString(mc.font, HungerDebugRendering.hudExtraLine(living), x, nextY, -3355444, true);
    }

    private static String statusLine(LivingEntity entity) {
        HungerData data = Hunger.data((Entity)entity);
        StringBuilder text = new StringBuilder("F:").append(data.hunger()).append('/').append(data.maxHunger());
        if (entity.hasData(ThirstAttachments.THIRST_DATA)) {
            ThirstData thirst = Thirst.data((Entity)entity);
            text.append(" T:").append(thirst.thirst()).append('/').append(thirst.maxThirst());
        }
        HungerDebugRendering.appendHome(text, entity);
        HungerDebugRendering.appendHerd(text, entity);
        HungerDebugRendering.appendBehavior(text, entity);
        HungerDebugRendering.appendContext(text, entity);
        return text.toString();
    }

    private static String hudExtraLine(LivingEntity entity) {
        Animal animal;
        StringBuilder text = new StringBuilder();
        HungerDebugRendering.appendBehavior(text, entity);
        HungerDebugRendering.appendContext(text, entity);
        if (!text.isEmpty()) {
            text.append(' ');
        }
        HungerDebugRendering.appendHerd(text, entity);
        if (!text.isEmpty() && text.charAt(text.length() - 1) != ' ') {
            text.append(' ');
        }
        HungerDebugRendering.appendHome(text, entity);
        if (entity instanceof Animal && Homes.effectiveHomeData(animal = (Animal)entity).isEmpty() && Homes.effectiveMigrationHeading(animal).isPresent()) {
            text.append(" migrating");
        }
        HungerDebugRendering.appendOccupancy(text, entity);
        return text.toString().trim();
    }

    private static void appendOccupancy(StringBuilder text, LivingEntity entity) {
        BlockPos feet = entity.blockPosition();
        text.append(" occ:").append(feet.getX()).append(',').append(feet.getY()).append(',').append(feet.getZ());
        int crowd = entity.level().getEntitiesOfClass(Animal.class, entity.getBoundingBox().inflate(2.0), other -> other != entity).size();
        text.append(" crowd:").append(crowd);
    }

    private static void appendBehavior(StringBuilder text, LivingEntity entity) {
        if (!(entity instanceof Animal)) {
            return;
        }
        Animal animal = (Animal)entity;
        text.append(" do:").append(ActiveBehavior.current(animal));
    }

    private static void appendContext(StringBuilder text, LivingEntity entity) {
        Sheep sheep;
        if (entity instanceof Sheep && (sheep = (Sheep)entity).isSheared()) {
            text.append(" shorn");
        }
        if (entity.hasData(HerdAttachments.MOTHER)
                && ((MotherData)entity.getData(HerdAttachments.MOTHER)).isActive(entity.level().getGameTime())) {
            text.append(" mother");
        }
        if (entity.hasData(DebugAttachments.PANIC_PHASE)) {
            byte ordinal = (Byte)entity.getData(DebugAttachments.PANIC_PHASE);
            HerdManager.PanicPhase[] phases = HerdManager.PanicPhase.values();
            if (ordinal > 0 && ordinal < phases.length) {
                text.append(" panic:").append(phases[ordinal].name().toLowerCase());
            }
        }
    }

    private static void appendHome(StringBuilder text, LivingEntity entity) {
        if (!(entity instanceof Animal)) {
            text.append(" H:--");
            return;
        }
        Animal animal = (Animal)entity;
        Optional<HomeData> home = Homes.effectiveHomeData(animal);
        if (home.isEmpty()) {
            text.append(" H:--");
            return;
        }
        int dist = (int)Math.sqrt(animal.distanceToSqr(Vec3.atCenterOf((Vec3i)home.get().pos())));
        text.append(" H:").append(dist);
        if (home.get().temporary()) {
            text.append("tmp");
        }
    }

    private static void appendHerd(StringBuilder text, LivingEntity entity) {
        if (!entity.hasData(HerdAttachments.HERD_DATA)) {
            return;
        }
        text.append(((HerdData)entity.getData(HerdAttachments.HERD_DATA)).alpha() ? " a" : " h");
    }
}

