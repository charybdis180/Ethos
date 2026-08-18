/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.Registry
 *  net.minecraft.network.codec.ByteBufCodecs
 *  net.neoforged.neoforge.attachment.AttachmentType
 *  net.neoforged.neoforge.registries.DeferredRegister
 *  net.neoforged.neoforge.registries.NeoForgeRegistries
 */
package com.charybdis180.ethological.thirst;

import com.charybdis180.ethological.thirst.ThirstData;
import com.charybdis180.ethological.thirst.WaterTargetData;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class ThirstAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create((Registry)NeoForgeRegistries.ATTACHMENT_TYPES, (String)"ethological");
    public static final Supplier<AttachmentType<ThirstData>> THIRST_DATA = ThirstAttachments.register("thirst_data", () -> AttachmentType.builder(() -> ThirstData.DEFAULT).serialize(ThirstData.CODEC).sync(ThirstData.STREAM_CODEC).build());
    public static final Supplier<AttachmentType<WaterTargetData>> WATER_TARGET = ThirstAttachments.register("water_target", () -> AttachmentType.builder(() -> WaterTargetData.DEFAULT).sync(WaterTargetData.STREAM_CODEC).build());
    public static final Supplier<AttachmentType<Long>> HEAD_DIP_UNTIL = ThirstAttachments.register("head_dip_until", () -> AttachmentType.builder(() -> 0L).sync(ByteBufCodecs.VAR_LONG).build());

    private static <T> Supplier<AttachmentType<T>> register(String name, Supplier<AttachmentType<T>> factory) {
        return ATTACHMENT_TYPES.register(name, factory);
    }

    private ThirstAttachments() {
    }
}

