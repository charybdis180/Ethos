/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.Registry
 *  net.neoforged.neoforge.attachment.AttachmentType
 *  net.neoforged.neoforge.registries.DeferredRegister
 *  net.neoforged.neoforge.registries.NeoForgeRegistries
 */
package com.charybdis180.ethological.social;

import com.charybdis180.ethological.social.FamiliarityData;
import com.charybdis180.ethological.social.PlayData;
import com.charybdis180.ethological.social.StartleData;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class SocialAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create((Registry)NeoForgeRegistries.ATTACHMENT_TYPES, (String)"ethological");
    public static final Supplier<AttachmentType<PlayData>> PLAY = SocialAttachments.register("play", () -> AttachmentType.builder(() -> PlayData.DEFAULT).build());
    public static final Supplier<AttachmentType<StartleData>> STARTLE = SocialAttachments.register("startle", () -> AttachmentType.builder(() -> StartleData.DEFAULT).build());
    public static final Supplier<AttachmentType<FamiliarityData>> FAMILIARITY = SocialAttachments.register(
            "familiarity",
            () -> AttachmentType.builder(() -> FamiliarityData.DEFAULT).serialize(FamiliarityData.CODEC).sync(FamiliarityData.STREAM_CODEC).build());

    private static <T> Supplier<AttachmentType<T>> register(String name, Supplier<AttachmentType<T>> factory) {
        return ATTACHMENT_TYPES.register(name, factory);
    }

    private SocialAttachments() {
    }
}

