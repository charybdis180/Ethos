/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.serialization.Codec
 *  net.minecraft.core.Registry
 *  net.neoforged.neoforge.attachment.AttachmentType
 *  net.neoforged.neoforge.registries.DeferredRegister
 *  net.neoforged.neoforge.registries.NeoForgeRegistries
 */
package com.charybdis180.ethological.hunger;

import com.charybdis180.ethological.hunger.FoodTargetData;
import com.charybdis180.ethological.hunger.GrazePatchData;
import com.charybdis180.ethological.hunger.HungerData;
import com.mojang.serialization.Codec;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class HungerAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create((Registry)NeoForgeRegistries.ATTACHMENT_TYPES, (String)"ethological");
    public static final Supplier<AttachmentType<HungerData>> HUNGER_DATA = HungerAttachments.register("hunger_data", () -> AttachmentType.builder(() -> HungerData.DEFAULT).serialize(HungerData.CODEC).sync(HungerData.STREAM_CODEC).build());
    public static final Supplier<AttachmentType<FoodTargetData>> FOOD_TARGET = HungerAttachments.register("food_target", () -> AttachmentType.builder(() -> FoodTargetData.DEFAULT).sync(FoodTargetData.STREAM_CODEC).build());
    public static final Supplier<AttachmentType<GrazePatchData>> GRAZE_PATCH = HungerAttachments.register("graze_patch", () -> AttachmentType.builder(() -> GrazePatchData.DEFAULT).sync(GrazePatchData.STREAM_CODEC).build());
    public static final Supplier<AttachmentType<Long>> RUMINATE_UNTIL = HungerAttachments.register("ruminate_until", () -> AttachmentType.builder(() -> 0L).serialize((Codec)Codec.LONG).build());

    private static <T> Supplier<AttachmentType<T>> register(String name, Supplier<AttachmentType<T>> factory) {
        return ATTACHMENT_TYPES.register(name, factory);
    }

    private HungerAttachments() {
    }
}

