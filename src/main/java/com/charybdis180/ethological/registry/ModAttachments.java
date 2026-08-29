package com.charybdis180.ethological.registry;

import com.charybdis180.ethological.avoidance.AvoidanceHazard;
import com.charybdis180.ethological.growth.GrowthData;
import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.herd.MotherData;
import com.charybdis180.ethological.home.HomeData;
import com.charybdis180.ethological.hunger.FoodTargetData;
import com.charybdis180.ethological.hunger.GrazePatchData;
import com.charybdis180.ethological.hunger.HungerData;
import com.charybdis180.ethological.sleep.SleepDisturbance;
import com.charybdis180.ethological.social.FamiliarityData;
import com.charybdis180.ethological.social.PlayData;
import com.charybdis180.ethological.social.StartleData;
import com.charybdis180.ethological.thirst.ThirstData;
import com.charybdis180.ethological.thirst.WaterTargetData;
import com.mojang.serialization.Codec;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Single attachment registry for the whole mod. Registry keys match historical NBT exactly. */
public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "ethological");

    // sleep
    public static final Supplier<AttachmentType<Boolean>> SLEEPING = register("sleeping", () -> AttachmentType.builder(() -> false).serialize(Codec.BOOL).sync(ByteBufCodecs.BOOL).build());
    public static final Supplier<AttachmentType<Boolean>> RESTING = register("resting", () -> AttachmentType.builder(() -> false).sync(ByteBufCodecs.BOOL).build());
    public static final Supplier<AttachmentType<SleepDisturbance>> SLEEP_DISTURBANCE = register("sleep_disturbance", () -> AttachmentType.builder(() -> SleepDisturbance.DEFAULT).build());
    public static final Supplier<AttachmentType<Long>> SLEEP_VIGILANCE = register("sleep_vigilance", () -> AttachmentType.builder(() -> 0L).serialize(Codec.LONG).build());
    public static final Supplier<AttachmentType<Float>> SLEEP_YAW = register("sleep_yaw", () -> AttachmentType.builder(() -> 0.0F).serialize(Codec.FLOAT).sync(ByteBufCodecs.FLOAT).build());
    public static final Supplier<AttachmentType<BlockPos>> SLEEP_POS = register("sleep_pos", () -> AttachmentType.builder(() -> BlockPos.ZERO).serialize(BlockPos.CODEC).build());
    public static final Supplier<AttachmentType<BlockPos>> SLEEP_TARGET = register("sleep_target", () -> AttachmentType.builder(() -> BlockPos.ZERO).serialize(BlockPos.CODEC).sync(BlockPos.STREAM_CODEC).build());

    // hunger
    public static final Supplier<AttachmentType<HungerData>> HUNGER_DATA = register("hunger_data", () -> AttachmentType.builder(() -> HungerData.DEFAULT).serialize(HungerData.CODEC).sync(HungerData.STREAM_CODEC).build());
    public static final Supplier<AttachmentType<FoodTargetData>> FOOD_TARGET = register("food_target", () -> AttachmentType.builder(() -> FoodTargetData.DEFAULT).sync(FoodTargetData.STREAM_CODEC).build());
    public static final Supplier<AttachmentType<GrazePatchData>> GRAZE_PATCH = register("graze_patch", () -> AttachmentType.builder(() -> GrazePatchData.DEFAULT).sync(GrazePatchData.STREAM_CODEC).build());
    public static final Supplier<AttachmentType<Long>> RUMINATE_UNTIL = register("ruminate_until", () -> AttachmentType.builder(() -> 0L).serialize(Codec.LONG).build());
    public static final Supplier<AttachmentType<Long>> EAT_UNTIL = register("eat_until", () -> AttachmentType.builder(() -> 0L).sync(ByteBufCodecs.VAR_LONG).build());

    // home / migration
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

    // herd
    public static final Supplier<AttachmentType<HerdData>> HERD_DATA = register("herd_data", () -> AttachmentType.builder(() -> HerdData.DEFAULT).serialize(HerdData.CODEC).sync(HerdData.STREAM_CODEC).build());
    public static final Supplier<AttachmentType<MotherData>> MOTHER = register("mother", () -> AttachmentType.builder(() -> MotherData.DEFAULT).serialize(MotherData.CODEC).sync(MotherData.STREAM_CODEC).build());

    // thirst
    public static final Supplier<AttachmentType<ThirstData>> THIRST_DATA = register("thirst_data", () -> AttachmentType.builder(() -> ThirstData.DEFAULT).serialize(ThirstData.CODEC).sync(ThirstData.STREAM_CODEC).build());
    public static final Supplier<AttachmentType<WaterTargetData>> WATER_TARGET = register("water_target", () -> AttachmentType.builder(() -> WaterTargetData.DEFAULT).sync(WaterTargetData.STREAM_CODEC).build());
    public static final Supplier<AttachmentType<Long>> HEAD_DIP_UNTIL = register("head_dip_until", () -> AttachmentType.builder(() -> 0L).sync(ByteBufCodecs.VAR_LONG).build());

    // social
    public static final Supplier<AttachmentType<PlayData>> PLAY = register("play", () -> AttachmentType.builder(() -> PlayData.DEFAULT).build());
    public static final Supplier<AttachmentType<StartleData>> STARTLE = register("startle", () -> AttachmentType.builder(() -> StartleData.DEFAULT).build());
    public static final Supplier<AttachmentType<FamiliarityData>> FAMILIARITY = register(
            "familiarity",
            () -> AttachmentType.builder(() -> FamiliarityData.DEFAULT).serialize(FamiliarityData.CODEC).sync(FamiliarityData.STREAM_CODEC).build());

    // growth
    public static final Supplier<AttachmentType<GrowthData>> GROWTH = register("growth", () -> AttachmentType.builder(() -> GrowthData.DEFAULT).serialize(GrowthData.CODEC).sync(GrowthData.STREAM_CODEC).build());

    // rooting (pig root-forage): the crop the pig dug up and is holding in its mouth
    public static final Supplier<AttachmentType<ItemStack>> MOUTH_ITEM = register("mouth_item",
            () -> AttachmentType.builder(() -> ItemStack.EMPTY)
                    .serialize(ItemStack.CODEC)
                    .sync(ItemStack.OPTIONAL_STREAM_CODEC)
                    .build());

    // debug
    public static final Supplier<AttachmentType<String>> ACTIVE_BEHAVIOR = register("active_behavior", () -> AttachmentType.builder(() -> "idle").sync(ByteBufCodecs.STRING_UTF8).build());
    public static final Supplier<AttachmentType<Byte>> PANIC_PHASE = register("panic_phase", () -> AttachmentType.builder(() -> (byte)0).sync(ByteBufCodecs.BYTE).build());

    // avoidance
    public static final Supplier<AttachmentType<AvoidanceHazard>> HAZARD = register(
            "avoidance_hazard",
            () -> AttachmentType.builder(() -> AvoidanceHazard.DEFAULT).build());

    private static <T> Supplier<AttachmentType<T>> register(String name, Supplier<AttachmentType<T>> factory) {
        return ATTACHMENT_TYPES.register(name, factory);
    }

    private ModAttachments() {
    }
}
