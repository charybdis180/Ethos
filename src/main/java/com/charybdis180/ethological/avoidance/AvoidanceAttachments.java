package com.charybdis180.ethological.avoidance;

import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class AvoidanceAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create((Registry)NeoForgeRegistries.ATTACHMENT_TYPES, "ethological");

    public static final Supplier<AttachmentType<AvoidanceHazard>> HAZARD = register(
            "avoidance_hazard",
            () -> AttachmentType.builder(() -> AvoidanceHazard.DEFAULT).build());

    private static <T> Supplier<AttachmentType<T>> register(String name, Supplier<AttachmentType<T>> factory) {
        return ATTACHMENT_TYPES.register(name, factory);
    }

    private AvoidanceAttachments() {
    }
}
