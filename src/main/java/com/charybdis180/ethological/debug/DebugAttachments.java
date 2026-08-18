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
package com.charybdis180.ethological.debug;

import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class DebugAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create((Registry)NeoForgeRegistries.ATTACHMENT_TYPES, (String)"ethological");
    public static final Supplier<AttachmentType<String>> ACTIVE_BEHAVIOR = DebugAttachments.register("active_behavior", () -> AttachmentType.builder(() -> "idle").sync(ByteBufCodecs.STRING_UTF8).build());
    public static final Supplier<AttachmentType<Byte>> PANIC_PHASE = DebugAttachments.register("panic_phase", () -> AttachmentType.builder(() -> (byte)0).sync(ByteBufCodecs.BYTE).build());

    private static <T> Supplier<AttachmentType<T>> register(String name, Supplier<AttachmentType<T>> factory) {
        return ATTACHMENT_TYPES.register(name, factory);
    }

    private DebugAttachments() {
    }
}

