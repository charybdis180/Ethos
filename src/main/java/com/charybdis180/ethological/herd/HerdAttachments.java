/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.Registry
 *  net.neoforged.neoforge.attachment.AttachmentType
 *  net.neoforged.neoforge.registries.DeferredRegister
 *  net.neoforged.neoforge.registries.NeoForgeRegistries
 */
package com.charybdis180.ethological.herd;

import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.herd.MotherData;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class HerdAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create((Registry)NeoForgeRegistries.ATTACHMENT_TYPES, (String)"ethological");
    public static final Supplier<AttachmentType<HerdData>> HERD_DATA = HerdAttachments.register("herd_data", () -> AttachmentType.builder(() -> HerdData.DEFAULT).serialize(HerdData.CODEC).sync(HerdData.STREAM_CODEC).build());
    public static final Supplier<AttachmentType<MotherData>> MOTHER = HerdAttachments.register("mother", () -> AttachmentType.builder(() -> MotherData.DEFAULT).serialize(MotherData.CODEC).sync(MotherData.STREAM_CODEC).build());

    private static <T> Supplier<AttachmentType<T>> register(String name, Supplier<AttachmentType<T>> factory) {
        return ATTACHMENT_TYPES.register(name, factory);
    }

    private HerdAttachments() {
    }
}

