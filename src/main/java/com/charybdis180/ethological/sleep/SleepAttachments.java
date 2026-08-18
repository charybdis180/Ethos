/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.serialization.Codec
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Registry
 *  net.minecraft.network.codec.ByteBufCodecs
 *  net.neoforged.neoforge.attachment.AttachmentType
 *  net.neoforged.neoforge.registries.DeferredRegister
 *  net.neoforged.neoforge.registries.NeoForgeRegistries
 */
package com.charybdis180.ethological.sleep;

import com.charybdis180.ethological.sleep.SleepDisturbance;
import com.mojang.serialization.Codec;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class SleepAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create((Registry)NeoForgeRegistries.ATTACHMENT_TYPES, (String)"ethological");
    public static final Supplier<AttachmentType<Boolean>> SLEEPING = SleepAttachments.register("sleeping", () -> AttachmentType.builder(() -> false).serialize((Codec)Codec.BOOL).sync(ByteBufCodecs.BOOL).build());
    public static final Supplier<AttachmentType<Boolean>> RESTING = SleepAttachments.register("resting", () -> AttachmentType.builder(() -> false).sync(ByteBufCodecs.BOOL).build());
    public static final Supplier<AttachmentType<SleepDisturbance>> SLEEP_DISTURBANCE = SleepAttachments.register("sleep_disturbance", () -> AttachmentType.builder(() -> SleepDisturbance.DEFAULT).build());
    public static final Supplier<AttachmentType<Long>> SLEEP_VIGILANCE = SleepAttachments.register("sleep_vigilance", () -> AttachmentType.builder(() -> 0L).serialize((Codec)Codec.LONG).build());
    public static final Supplier<AttachmentType<Float>> SLEEP_YAW = SleepAttachments.register("sleep_yaw", () -> AttachmentType.builder(() -> Float.valueOf(0.0f)).serialize((Codec)Codec.FLOAT).sync(ByteBufCodecs.FLOAT).build());
    public static final Supplier<AttachmentType<BlockPos>> SLEEP_POS = SleepAttachments.register("sleep_pos", () -> AttachmentType.builder(() -> BlockPos.ZERO).serialize(BlockPos.CODEC).build());
    public static final Supplier<AttachmentType<BlockPos>> SLEEP_TARGET = SleepAttachments.register("sleep_target", () -> AttachmentType.builder(() -> BlockPos.ZERO).serialize(BlockPos.CODEC).sync(BlockPos.STREAM_CODEC).build());

    private static <T> Supplier<AttachmentType<T>> register(String name, Supplier<AttachmentType<T>> factory) {
        return ATTACHMENT_TYPES.register(name, factory);
    }

    private SleepAttachments() {
    }
}

