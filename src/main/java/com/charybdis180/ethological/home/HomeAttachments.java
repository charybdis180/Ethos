package com.charybdis180.ethological.home;

import com.charybdis180.ethological.Ethological;
import com.mojang.serialization.Codec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class HomeAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Ethological.MODID);

    public static final Supplier<AttachmentType<HomeData>> HOME = register("home",
            () -> AttachmentType.builder(() -> HomeData.DEFAULT)
                    .serialize(HomeData.CODEC)
                    .sync(HomeData.STREAM_CODEC)
                    .build());

    public static final Supplier<AttachmentType<Float>> LEASH_RADIUS = register("leash_radius",
            () -> AttachmentType.builder(() -> 0.0F)
                    .sync(ByteBufCodecs.FLOAT)
                    .build());

    public static final Supplier<AttachmentType<Double>> NOMAD_HEADING = register("nomad_heading",
            () -> AttachmentType.builder(() -> Double.NaN)
                    .serialize(Codec.DOUBLE)
                    .sync(ByteBufCodecs.DOUBLE)
                    .build());

    /** Game time until which the nomad herd pauses between travel legs (0 = marching). Server-only. */
    public static final Supplier<AttachmentType<Long>> MIGRATION_PAUSE_UNTIL = register("migration_pause_until",
            () -> AttachmentType.builder(() -> 0L)
                    .serialize(Codec.LONG)
                    .build());

    /** Game time until which the heading owner may not commit another large heading turn. Server-only. */
    public static final Supplier<AttachmentType<Long>> NOMAD_TURN_UNTIL = register("nomad_turn_until",
            () -> AttachmentType.builder(() -> 0L)
                    .serialize(Codec.LONG)
                    .build());

    /** Wall-clock cap of the current needs-pause (0 = none active). When reached,
     * the herd ends the pause and resumes marching to search fresh ground. Server-only. */
    public static final Supplier<AttachmentType<Long>> NEEDS_PAUSE_UNTIL = register("needs_pause_until",
            () -> AttachmentType.builder(() -> 0L)
                    .serialize(Codec.LONG)
                    .build());

    /** Game time until which urgent-need migration interrupts are suppressed after a
     * capped needs-pause, so a foodless region is searched instead of pinning the herd
     * to one spot. Server-only. */
    public static final Supplier<AttachmentType<Long>> NEEDS_MARCH_UNTIL = register("needs_march_until",
            () -> AttachmentType.builder(() -> 0L)
                    .serialize(Codec.LONG)
                    .build());

    @SuppressWarnings("unchecked")
    private static <T> Supplier<AttachmentType<T>> register(String name, Supplier<AttachmentType<T>> factory) {
        return (Supplier<AttachmentType<T>>) (Supplier<?>) ATTACHMENT_TYPES.register(name, factory);
    }

    private HomeAttachments() {
    }
}
