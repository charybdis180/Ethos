/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.registries.BuiltInRegistries
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.world.entity.EntityType
 *  net.minecraft.world.level.block.Block
 *  net.neoforged.neoforge.common.ModConfigSpec
 *  net.neoforged.neoforge.common.ModConfigSpec$BooleanValue
 *  net.neoforged.neoforge.common.ModConfigSpec$Builder
 *  net.neoforged.neoforge.common.ModConfigSpec$ConfigValue
 *  net.neoforged.neoforge.common.ModConfigSpec$DoubleValue
 *  net.neoforged.neoforge.common.ModConfigSpec$EnumValue
 *  net.neoforged.neoforge.common.ModConfigSpec$IntValue
 *  org.apache.commons.lang3.tuple.Pair
 */
package com.charybdis180.ethological.config;

import com.charybdis180.ethological.Ethological;
import com.charybdis180.ethological.growth.SpeciesGrowthSettings;
import com.charybdis180.ethological.herd.FollowStyle;
import com.charybdis180.ethological.herd.SpeciesHerdSettings;
import com.charybdis180.ethological.home.SpeciesHomeSettings;
import com.charybdis180.ethological.hunger.SpeciesHungerSettings;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import com.charybdis180.ethological.thirst.SpeciesThirstSettings;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public final class EthologicalConfig {
    public static final EthologicalConfig CONFIG;
    public static final ModConfigSpec SPEC;
    private static final List<String> DEFAULT_FOOD_BLOCKS;
    private static final List<String> PIG_FOOD_BLOCKS;
    private static final List<String> CHICKEN_FOOD_BLOCKS;
    public final ComfortSection comfort;
    public final SpeciesBundle sheep;
    public final SpeciesBundle cow;
    public final SpeciesBundle pig;
    public final SpeciesBundle chicken;

    private EthologicalConfig(ModConfigSpec.Builder builder) {
        this.comfort = new ComfortSection(builder);
        this.sheep = new SpeciesBundle(builder, "sheep", new HungerDefaults(10, 2400, 1200, 1.0, 0, 1, 2.0, false, 1.0, DEFAULT_FOOD_BLOCKS, 4, 6, 24, 30, 30, 0.5, 2.0, 3.0, 3.0), new ThirstDefaults(10, 2400, false, 800, 1.0, 60, 1000, 2), new SleepDefaults(13000, 23000, 0, 1200, 4), new HomeDefaults(128, 48, 1000, 200, false, 1.0, 9500), new HerdDefaults(12, 20, 8.0, 3.0, 20.0, 0.08, 12.0, 1.0, 6, 12, 0, 2, FollowStyle.SURROUND), new GrowthDefaults(5, 7, 0.2, 0.6, List.of("chase", "headbutt"), true));
        this.cow = new SpeciesBundle(builder, "cow", new HungerDefaults(12, 2400, 1200, 1.0, 0, 1, 2.0, true, 1.0, DEFAULT_FOOD_BLOCKS, 2, 10, 40, 50, 50, 1.0, 2.0, 3.0, 3.0), new ThirstDefaults(10, 2400, true, 800, 1.0, 60, 1000, 2), new SleepDefaults(13000, 23000, 0, 1200, 4), new HomeDefaults(128, 80, 1000, 200, true, 0.8, 9500), new HerdDefaults(20, 32, 6.0, 3.0, 14.0, 0.08, 16.0, 1.0, 10, 20, 0, 3, FollowStyle.SURROUND), new GrowthDefaults(5, 7, 0.2, 0.6, List.of("chase", "headbutt"), true));
        this.pig = new SpeciesBundle(builder, "pig", new HungerDefaults(10, 2400, 1200, 1.0, 0, 1, 2.0, false, 1.0, PIG_FOOD_BLOCKS, 3, 6, 24, 30, 30, 1.0, 2.0, 3.0, 3.0), new ThirstDefaults(10, 2400, false, 800, 1.0, 60, 1000, 2), new SleepDefaults(13000, 23000, 0, 1200, 4), new HomeDefaults(128, 40, 1000, 200, false, 1.0, 9500), new HerdDefaults(8, 16, 6.0, 3.0, 18.0, 0.15, 12.0, 1.0, 4, 8, 0, 2, FollowStyle.SURROUND), new GrowthDefaults(5, 7, 0.2, 0.6, List.of("chase"), true));
        this.chicken = new SpeciesBundle(builder, "chicken", new HungerDefaults(8, 2000, 1000, 1.0, 0, 1, 1.0, false, 1.0, CHICKEN_FOOD_BLOCKS, 4, 5, 20, 20, 20, 1.0, 2.0, 3.0, 3.0), new ThirstDefaults(8, 2000, false, 700, 1.0, 40, 1000, 2), new SleepDefaults(13000, 23000, 0, 800, 4), new HomeDefaults(96, 24, 800, 200, false, 1.0, 9500), new HerdDefaults(12, 14, 5.0, 3.0, 22.0, 0.1, 10.0, 1.0, 6, 12, 0, 4, FollowStyle.SURROUND), new GrowthDefaults(5, 7, 0.2, 0.6, List.of("chase"), true));
    }

    public static SpeciesHungerSettings resolveHunger(EntityType<?> type, SpeciesHungerSettings datapack) {
        HungerSection section = EthologicalConfig.hungerSection(type);
        return section == null ? datapack : section.toSettings(datapack.entityId());
    }

    public static SpeciesThirstSettings resolveThirst(EntityType<?> type, SpeciesThirstSettings datapack) {
        ThirstSection section = EthologicalConfig.thirstSection(type);
        return section == null ? datapack : section.toSettings(datapack.entityId());
    }

    public static SpeciesSleepSettings resolveSleep(EntityType<?> type, SpeciesSleepSettings datapack) {
        SleepSection section = EthologicalConfig.sleepSection(type);
        return section == null ? datapack : section.toSettings(datapack.entityId());
    }

    public static SpeciesHerdSettings resolveHerd(EntityType<?> type, SpeciesHerdSettings datapack) {
        HerdSection section = EthologicalConfig.herdSection(type);
        return section == null ? datapack : section.toSettings(datapack.entityId());
    }

    public static SpeciesHomeSettings resolveHome(EntityType<?> type, SpeciesHomeSettings datapack) {
        HomeSection section = EthologicalConfig.homeSection(type);
        return section == null ? datapack : section.toSettings(datapack.entityId());
    }

    public static SpeciesGrowthSettings resolveGrowth(EntityType<?> type, SpeciesGrowthSettings datapack) {
        GrowthSection section = EthologicalConfig.growthSection(type);
        return section == null ? datapack : section.toSettings(datapack.entityId());
    }

    private static HungerSection hungerSection(EntityType<?> type) {
        if (type == EntityType.SHEEP) {
            return EthologicalConfig.CONFIG.sheep.hunger;
        }
        if (type == EntityType.COW) {
            return EthologicalConfig.CONFIG.cow.hunger;
        }
        if (type == EntityType.PIG) {
            return EthologicalConfig.CONFIG.pig.hunger;
        }
        if (type == EntityType.CHICKEN) {
            return EthologicalConfig.CONFIG.chicken.hunger;
        }
        return null;
    }

    private static ThirstSection thirstSection(EntityType<?> type) {
        if (type == EntityType.SHEEP) {
            return EthologicalConfig.CONFIG.sheep.thirst;
        }
        if (type == EntityType.COW) {
            return EthologicalConfig.CONFIG.cow.thirst;
        }
        if (type == EntityType.PIG) {
            return EthologicalConfig.CONFIG.pig.thirst;
        }
        if (type == EntityType.CHICKEN) {
            return EthologicalConfig.CONFIG.chicken.thirst;
        }
        return null;
    }

    private static SleepSection sleepSection(EntityType<?> type) {
        if (type == EntityType.SHEEP) {
            return EthologicalConfig.CONFIG.sheep.sleep;
        }
        if (type == EntityType.COW) {
            return EthologicalConfig.CONFIG.cow.sleep;
        }
        if (type == EntityType.PIG) {
            return EthologicalConfig.CONFIG.pig.sleep;
        }
        if (type == EntityType.CHICKEN) {
            return EthologicalConfig.CONFIG.chicken.sleep;
        }
        return null;
    }

    private static HerdSection herdSection(EntityType<?> type) {
        if (type == EntityType.SHEEP) {
            return EthologicalConfig.CONFIG.sheep.herd;
        }
        if (type == EntityType.COW) {
            return EthologicalConfig.CONFIG.cow.herd;
        }
        if (type == EntityType.PIG) {
            return EthologicalConfig.CONFIG.pig.herd;
        }
        if (type == EntityType.CHICKEN) {
            return EthologicalConfig.CONFIG.chicken.herd;
        }
        return null;
    }

    private static HomeSection homeSection(EntityType<?> type) {
        if (type == EntityType.SHEEP) {
            return EthologicalConfig.CONFIG.sheep.home;
        }
        if (type == EntityType.COW) {
            return EthologicalConfig.CONFIG.cow.home;
        }
        if (type == EntityType.PIG) {
            return EthologicalConfig.CONFIG.pig.home;
        }
        if (type == EntityType.CHICKEN) {
            return EthologicalConfig.CONFIG.chicken.home;
        }
        return null;
    }

    private static GrowthSection growthSection(EntityType<?> type) {
        if (type == EntityType.SHEEP) {
            return EthologicalConfig.CONFIG.sheep.growth;
        }
        if (type == EntityType.COW) {
            return EthologicalConfig.CONFIG.cow.growth;
        }
        if (type == EntityType.PIG) {
            return EthologicalConfig.CONFIG.pig.growth;
        }
        if (type == EntityType.CHICKEN) {
            return EthologicalConfig.CONFIG.chicken.growth;
        }
        return null;
    }

    private static String key(String ... parts) {
        return "ethological.configuration." + String.join((CharSequence)".", parts);
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static boolean isResourceLocation(Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String string = (String)value;
        return ResourceLocation.tryParse((String)string) != null;
    }

    private static Set<Block> resolveBlocks(List<? extends String> ids) {
        HashSet<Block> blocks = new HashSet<Block>();
        for (String string : ids) {
            ResourceLocation blockId = ResourceLocation.tryParse((String)string);
            if (blockId == null) {
                Ethological.LOGGER.warn("Invalid food block id '{}' in Ethological config",string);
                continue;
            }
            if (!BuiltInRegistries.BLOCK.containsKey(blockId)) {
                Ethological.LOGGER.warn("Unknown food block '{}' in Ethological config",blockId);
                continue;
            }
            blocks.add((Block)BuiltInRegistries.BLOCK.get(blockId));
        }
        return Set.copyOf(blocks);
    }

    static {
        DEFAULT_FOOD_BLOCKS = List.of("minecraft:grass_block", "minecraft:short_grass", "minecraft:tall_grass", "minecraft:wheat", "minecraft:fern", "minecraft:large_fern");
        PIG_FOOD_BLOCKS = List.of("minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots", "minecraft:short_grass", "minecraft:tall_grass");
        CHICKEN_FOOD_BLOCKS = List.of("minecraft:short_grass", "minecraft:tall_grass", "minecraft:wheat", "minecraft:beetroots");
        Pair pair = new ModConfigSpec.Builder().configure(EthologicalConfig::new);
        CONFIG = (EthologicalConfig)pair.getLeft();
        SPEC = (ModConfigSpec)pair.getRight();
    }

    public static final class ComfortSection {
        public final ModConfigSpec.DoubleValue heatDepletionMultiplier;
        public final ModConfigSpec.DoubleValue hotBiomeTemperature;
        public final ModConfigSpec.DoubleValue warmBiomeTemperature;
        public final ModConfigSpec.IntValue rainShelterSearchBonus;
        public final ModConfigSpec.DoubleValue rainThirstDepletionMultiplier;
        public final ModConfigSpec.IntValue motherFollowTicks;
        public final ModConfigSpec.DoubleValue grazeMinSpacing;
        public final ModConfigSpec.BooleanValue wildWolvesHuntLivestock;
        public final ModConfigSpec.IntValue familiarityHandFeedGain;
        public final ModConfigSpec.IntValue familiarityTrustThreshold;
        public final ModConfigSpec.IntValue familiarityMaxScore;
        public final ModConfigSpec.IntValue nurseFeedAmount;
        public final ModConfigSpec.IntValue nurseMotherDrain;
        public final ModConfigSpec.IntValue nurseCooldownTicks;
        public final ModConfigSpec.IntValue pastureRegrowDelayTicks;
        public final ModConfigSpec.IntValue pastureRegrowMaxPerChunk;
        public final ModConfigSpec.DoubleValue yPenaltyWeight;
        public final ModConfigSpec.IntValue sleepSpotSpacing;
        public final ModConfigSpec.BooleanValue yieldOnBlock;

        private ComfortSection(ModConfigSpec.Builder builder) {
            builder.comment("Comfort and life").translation(EthologicalConfig.key("comfort")).push("comfort");
            this.heatDepletionMultiplier = builder.comment("In heat, multiply thirst depletion interval by this (0.75 = faster thirst).").translation(EthologicalConfig.key("comfort", "heat_depletion_multiplier")).defineInRange("heat_depletion_multiplier", 0.75, 0.05, 1.0);
            this.hotBiomeTemperature = builder.comment("Biome base temperature at or above this always counts as hot.").translation(EthologicalConfig.key("comfort", "hot_biome_temperature")).defineInRange("hot_biome_temperature", 0.95, 0.0, 2.0);
            this.warmBiomeTemperature = builder.comment("Biome base temperature at or above this is hot during midday.").translation(EthologicalConfig.key("comfort", "warm_biome_temperature")).defineInRange("warm_biome_temperature", 0.8, 0.0, 2.0);
            this.rainShelterSearchBonus = builder.comment("Extra blocks of shelter search radius while raining.").translation(EthologicalConfig.key("comfort", "rain_shelter_search_bonus")).defineInRange("rain_shelter_search_bonus", 4, 0, 32);
            this.rainThirstDepletionMultiplier = builder.comment("While raining outdoors, multiply thirst depletion interval by this (above 1.0 = slower thirst).").translation(EthologicalConfig.key("comfort", "rain_thirst_depletion_multiplier")).defineInRange("rain_thirst_depletion_multiplier", 1.35, 1.0, 3.0);
            this.motherFollowTicks = builder.comment("How long babies follow their mother (game ticks; 48000 \u2248 2 days).").translation(EthologicalConfig.key("comfort", "mother_follow_ticks")).defineInRange("mother_follow_ticks", 48000, 0, 240000);
            this.grazeMinSpacing = builder.comment("Minimum distance between grazing herd-mates' food stands.").translation(EthologicalConfig.key("comfort", "graze_min_spacing")).defineInRange("graze_min_spacing", 1.5, 0.0, 8.0);
            this.wildWolvesHuntLivestock = builder.comment("If true, wild wolves hunt sheep, cows, chickens, pigs, and rabbits.").translation(EthologicalConfig.key("comfort", "wild_wolves_hunt_livestock")).define("wild_wolves_hunt_livestock", true);
            this.familiarityHandFeedGain = builder.comment("Trust points gained each successful hand-feed toward the most familiar player.").translation(EthologicalConfig.key("comfort", "familiarity_hand_feed_gain")).defineInRange("familiarity_hand_feed_gain", 2, 1, 20);
            this.familiarityTrustThreshold = builder.comment("Familiarity score required before calmer proximity / curious / startle behavior applies.").translation(EthologicalConfig.key("comfort", "familiarity_trust_threshold")).defineInRange("familiarity_trust_threshold", 4, 1, 20);
            this.familiarityMaxScore = builder.comment("Maximum familiarity score an animal can build toward one player.").translation(EthologicalConfig.key("comfort", "familiarity_max_score")).defineInRange("familiarity_max_score", 20, 1, 100);
            this.nurseFeedAmount = builder.comment("Hunger restored to a baby when nursing from its mother.").translation(EthologicalConfig.key("comfort", "nurse_feed_amount")).defineInRange("nurse_feed_amount", 2, 1, 20);
            this.nurseMotherDrain = builder.comment("Hunger drained from the mother per successful nursing bout.").translation(EthologicalConfig.key("comfort", "nurse_mother_drain")).defineInRange("nurse_mother_drain", 1, 0, 20);
            this.nurseCooldownTicks = builder.comment("Cooldown after nursing before a baby can nurse again.").translation(EthologicalConfig.key("comfort", "nurse_cooldown_ticks")).defineInRange("nurse_cooldown_ticks", 200, 20, 72000);
            this.pastureRegrowDelayTicks = builder.comment("Ticks before grazed dirt may restore to grass (9600 \u2248 8 minutes).").translation(EthologicalConfig.key("comfort", "pasture_regrow_delay_ticks")).defineInRange("pasture_regrow_delay_ticks", 9600, 200, 240000);
            this.pastureRegrowMaxPerChunk = builder.comment("Max pending dirt-to-grass restores queued per chunk.").translation(EthologicalConfig.key("comfort", "pasture_regrow_max_per_chunk")).defineInRange("pasture_regrow_max_per_chunk", 24, 1, 256);
            this.yPenaltyWeight = builder.comment("Y-difference penalty in the food/water target score (score = distanceSqr + dy*dy * weight). Higher strongly prefers targets near the animal's own Y; 0 = pure nearest distance.").translation(EthologicalConfig.key("comfort", "y_penalty_weight")).defineInRange("y_penalty_weight", 16.0, 0.0, 256.0);
            this.sleepSpotSpacing = builder.comment("Horizontal gap between herd members' individual sleep spots (0 = no gap, just never two on the same block).").translation(EthologicalConfig.key("comfort", "sleep_spot_spacing")).defineInRange("sleep_spot_spacing", 0, 0, 8);
            this.yieldOnBlock = builder.comment("If true, a stationary animal steps aside when a pathing herd-mate is stuck against it.").translation(EthologicalConfig.key("comfort", "yield_on_block")).define("yield_on_block", true);
            builder.pop();
        }
    }

    public static final class SpeciesBundle {
        public final HungerSection hunger;
        public final ThirstSection thirst;
        public final SleepSection sleep;
        public final HomeSection home;
        public final HerdSection herd;
        public final GrowthSection growth;

        private SpeciesBundle(ModConfigSpec.Builder builder, String species, HungerDefaults hungerDefaults, ThirstDefaults thirstDefaults, SleepDefaults sleepDefaults, HomeDefaults homeDefaults, HerdDefaults herdDefaults, GrowthDefaults growthDefaults) {
            builder.comment(EthologicalConfig.capitalize(species) + " settings").translation(EthologicalConfig.key(species)).push(species);
            this.hunger = new HungerSection(builder, species, hungerDefaults);
            this.thirst = new ThirstSection(builder, species, thirstDefaults);
            this.sleep = new SleepSection(builder, species, sleepDefaults);
            this.home = new HomeSection(builder, species, homeDefaults);
            this.herd = new HerdSection(builder, species, herdDefaults);
            this.growth = new GrowthSection(builder, species, growthDefaults);
            builder.pop();
        }
    }

    private record HungerDefaults(int maxHunger, int depletionIntervalTicks, int starveDamageIntervalTicks, double starveDamage, int eatCooldownTicks, int hungerPerFood, double healPerFood, boolean depleteWhileSleeping, double searchThresholdPercent, List<String> foodBlocks, int eatsPerPatch, int patchGrazeRadius, int patchSearchRadius, int grazeLingerTicksMin, int grazeLingerTicksSpan, double shearedDepletionMultiplier, double handFeedMultiplier, double feederMultiplier, double cropMultiplier) {
    }

    private record ThirstDefaults(int maxThirst, int depletionIntervalTicks, boolean depleteWhileSleeping, int dehydrateDamageIntervalTicks, double dehydrateDamage, int drinkTicks, int eveningDrinkLeadTicks, int urgentDrinkThreshold) {
    }

    private record SleepDefaults(int sleepStartTick, int sleepEndTick, int regenAmplifier, int maxBedtimeDelayTicks, int sleepReturnRadius) {
    }

    private record HomeDefaults(int waterSearchRadius, int wanderRadius, int leashShrinkTicks, int validationIntervalTicks, boolean nomadic, double migrationSpeed, int campTimeTick) {
    }

    private record HerdDefaults(int maxSize, int joinRadius, double followDistance, double motherFollowDistance, double crossSpeciesAlertRadius, double followSpreadPerMemberPercent, double resatterDistance, double sleepFollowMultiplier, int spawnGroupMin, int spawnGroupMax, int spawnBabiesMin, int spawnBabiesMax, FollowStyle followStyle) {
    }

    private record GrowthDefaults(int minGrowthDays, int maxGrowthDays, double babyExtraScale, double babyPlayChance, List<String> playStyles, boolean babyOnlyPlaysWithBabies) {
    }

    public static final class HungerSection {
        public final ModConfigSpec.IntValue maxHunger;
        public final ModConfigSpec.IntValue depletionIntervalTicks;
        public final ModConfigSpec.IntValue starveDamageIntervalTicks;
        public final ModConfigSpec.DoubleValue starveDamage;
        public final ModConfigSpec.IntValue eatCooldownTicks;
        public final ModConfigSpec.IntValue hungerPerFood;
        public final ModConfigSpec.DoubleValue healPerFood;
        public final ModConfigSpec.BooleanValue depleteWhileSleeping;
        public final ModConfigSpec.DoubleValue searchThresholdPercent;
        public final ModConfigSpec.ConfigValue<List<? extends String>> foodBlocks;
        public final ModConfigSpec.IntValue eatsPerPatch;
        public final ModConfigSpec.IntValue patchGrazeRadius;
        public final ModConfigSpec.IntValue patchSearchRadius;
        public final ModConfigSpec.IntValue grazeLingerTicksMin;
        public final ModConfigSpec.IntValue grazeLingerTicksSpan;
        public final ModConfigSpec.DoubleValue shearedDepletionMultiplier;
        public final ModConfigSpec.DoubleValue handFeedMultiplier;
        public final ModConfigSpec.DoubleValue feederMultiplier;
        public final ModConfigSpec.DoubleValue cropMultiplier;

        private HungerSection(ModConfigSpec.Builder builder, String species, HungerDefaults defaults) {
            builder.comment("Hunger").translation(EthologicalConfig.key(species, "hunger")).push("hunger");
            this.maxHunger = builder.comment("Maximum hunger points.").translation(EthologicalConfig.key(species, "hunger", "max_hunger")).defineInRange("max_hunger", defaults.maxHunger, 1, 100);
            this.depletionIntervalTicks = builder.comment("Ticks between losing 1 hunger.").translation(EthologicalConfig.key(species, "hunger", "depletion_interval_ticks")).defineInRange("depletion_interval_ticks", defaults.depletionIntervalTicks, 1, 72000);
            this.starveDamageIntervalTicks = builder.comment("Ticks between starve damage at 0 hunger.").translation(EthologicalConfig.key(species, "hunger", "starve_damage_interval_ticks")).defineInRange("starve_damage_interval_ticks", defaults.starveDamageIntervalTicks, 1, 72000);
            this.starveDamage = builder.comment("Damage dealt when starving.").translation(EthologicalConfig.key(species, "hunger", "starve_damage")).defineInRange("starve_damage", defaults.starveDamage, 0.0, 20.0);
            this.eatCooldownTicks = builder.comment("Cooldown between successful eats.").translation(EthologicalConfig.key(species, "hunger", "eat_cooldown_ticks")).defineInRange("eat_cooldown_ticks", defaults.eatCooldownTicks, 0, 72000);
            this.hungerPerFood = builder.comment("Hunger restored per food block eaten.").translation(EthologicalConfig.key(species, "hunger", "hunger_per_food")).defineInRange("hunger_per_food", defaults.hungerPerFood, 1, 100);
            this.healPerFood = builder.comment("Health restored per food block eaten.").translation(EthologicalConfig.key(species, "hunger", "heal_per_food")).defineInRange("heal_per_food", defaults.healPerFood, 0.0, 20.0);
            this.depleteWhileSleeping = builder.comment("If true, hunger still depletes while sleeping.").translation(EthologicalConfig.key(species, "hunger", "deplete_while_sleeping")).define("deplete_while_sleeping", defaults.depleteWhileSleeping);
            this.searchThresholdPercent = builder.comment("Seek food when hunger is below max * this percent (1.0 = any deficit).").translation(EthologicalConfig.key(species, "hunger", "search_threshold_percent")).defineInRange("search_threshold_percent", defaults.searchThresholdPercent, 0.0, 1.0);
            this.foodBlocks = builder.comment("Block IDs that count as food.").translation(EthologicalConfig.key(species, "hunger", "food_blocks")).defineListAllowEmpty("food_blocks", defaults.foodBlocks, () -> "minecraft:grass_block", EthologicalConfig::isResourceLocation);
            this.eatsPerPatch = builder.comment("Bites taken at one graze patch before moving on.").translation(EthologicalConfig.key(species, "hunger", "eats_per_patch")).defineInRange("eats_per_patch", defaults.eatsPerPatch, 1, 32);
            this.patchGrazeRadius = builder.comment("Radius around a patch center where members may eat.").translation(EthologicalConfig.key(species, "hunger", "patch_graze_radius")).defineInRange("patch_graze_radius", defaults.patchGrazeRadius, 1, 32);
            this.patchSearchRadius = builder.comment("How far leaders search for the next graze patch.").translation(EthologicalConfig.key(species, "hunger", "patch_search_radius")).defineInRange("patch_search_radius", defaults.patchSearchRadius, 1, 128);
            this.grazeLingerTicksMin = builder.comment("Minimum ticks a full leader lingers at a patch for hungry mates.").translation(EthologicalConfig.key(species, "hunger", "graze_linger_ticks_min")).defineInRange("graze_linger_ticks_min", defaults.grazeLingerTicksMin, 0, 1200);
            this.grazeLingerTicksSpan = builder.comment("Random extra linger ticks added to the minimum.").translation(EthologicalConfig.key(species, "hunger", "graze_linger_ticks_span")).defineInRange("graze_linger_ticks_span", defaults.grazeLingerTicksSpan, 1, 1200);
            this.shearedDepletionMultiplier = builder.comment("When sheared (sheep), multiply depletion interval by this (0.5 = twice as fast).").translation(EthologicalConfig.key(species, "hunger", "sheared_depletion_multiplier")).defineInRange("sheared_depletion_multiplier", defaults.shearedDepletionMultiplier, 0.05, 1.0);
            this.handFeedMultiplier = builder.comment("Multiplier on hunger_per_food when a player hand-feeds this animal.").translation(EthologicalConfig.key(species, "hunger", "hand_feed_multiplier")).defineInRange("hand_feed_multiplier", defaults.handFeedMultiplier, 0.0, 100.0);
            this.feederMultiplier = builder.comment("Multiplier on hunger_per_food when this animal eats from a trough or feeder.").translation(EthologicalConfig.key(species, "hunger", "feeder_multiplier")).defineInRange("feeder_multiplier", defaults.feederMultiplier, 0.0, 100.0);
            this.cropMultiplier = builder.comment("Multiplier on hunger_per_food when this animal eats planted crops (wheat, potatoes, carrots, beetroot).").translation(EthologicalConfig.key(species, "hunger", "crop_multiplier")).defineInRange("crop_multiplier", defaults.cropMultiplier, 0.0, 100.0);
            builder.pop();
        }

        private SpeciesHungerSettings toSettings(ResourceLocation entityId) {
            return new SpeciesHungerSettings(entityId, (Integer)this.maxHunger.get(), (Integer)this.depletionIntervalTicks.get(), (Integer)this.starveDamageIntervalTicks.get(), ((Double)this.starveDamage.get()).floatValue(), (Integer)this.eatCooldownTicks.get(), (Integer)this.hungerPerFood.get(), ((Double)this.healPerFood.get()).floatValue(), (Boolean)this.depleteWhileSleeping.get(), ((Double)this.searchThresholdPercent.get()).floatValue(), EthologicalConfig.resolveBlocks((List)this.foodBlocks.get()), (Integer)this.eatsPerPatch.get(), (Integer)this.patchGrazeRadius.get(), (Integer)this.patchSearchRadius.get(), (Integer)this.grazeLingerTicksMin.get(), (Integer)this.grazeLingerTicksSpan.get(), ((Double)this.shearedDepletionMultiplier.get()).floatValue(), ((Double)this.handFeedMultiplier.get()).floatValue(), ((Double)this.feederMultiplier.get()).floatValue(), ((Double)this.cropMultiplier.get()).floatValue());
        }
    }

    public static final class ThirstSection {
        public final ModConfigSpec.IntValue maxThirst;
        public final ModConfigSpec.IntValue depletionIntervalTicks;
        public final ModConfigSpec.BooleanValue depleteWhileSleeping;
        public final ModConfigSpec.IntValue dehydrateDamageIntervalTicks;
        public final ModConfigSpec.DoubleValue dehydrateDamage;
        public final ModConfigSpec.IntValue drinkTicks;
        public final ModConfigSpec.IntValue eveningDrinkLeadTicks;
        public final ModConfigSpec.IntValue urgentDrinkThreshold;

        private ThirstSection(ModConfigSpec.Builder builder, String species, ThirstDefaults defaults) {
            builder.comment("Thirst").translation(EthologicalConfig.key(species, "thirst")).push("thirst");
            this.maxThirst = builder.comment("Maximum thirst points.").translation(EthologicalConfig.key(species, "thirst", "max_thirst")).defineInRange("max_thirst", defaults.maxThirst, 1, 100);
            this.depletionIntervalTicks = builder.comment("Ticks between losing 1 thirst.").translation(EthologicalConfig.key(species, "thirst", "depletion_interval_ticks")).defineInRange("depletion_interval_ticks", defaults.depletionIntervalTicks, 1, 72000);
            this.depleteWhileSleeping = builder.comment("If true, thirst still depletes while sleeping.").translation(EthologicalConfig.key(species, "thirst", "deplete_while_sleeping")).define("deplete_while_sleeping", defaults.depleteWhileSleeping);
            this.dehydrateDamageIntervalTicks = builder.comment("Ticks between dehydrate damage at 0 thirst.").translation(EthologicalConfig.key(species, "thirst", "dehydrate_damage_interval_ticks")).defineInRange("dehydrate_damage_interval_ticks", defaults.dehydrateDamageIntervalTicks, 1, 72000);
            this.dehydrateDamage = builder.comment("Damage dealt when dehydrated.").translation(EthologicalConfig.key(species, "thirst", "dehydrate_damage")).defineInRange("dehydrate_damage", defaults.dehydrateDamage, 0.0, 20.0);
            this.drinkTicks = builder.comment("Ticks spent drinking once water is reached.").translation(EthologicalConfig.key(species, "thirst", "drink_ticks")).defineInRange("drink_ticks", defaults.drinkTicks, 1, 72000);
            this.eveningDrinkLeadTicks = builder.comment("Ticks before sleep start when evening drinking begins.").translation(EthologicalConfig.key(species, "thirst", "evening_drink_lead_ticks")).defineInRange("evening_drink_lead_ticks", defaults.eveningDrinkLeadTicks, 0, 24000);
            this.urgentDrinkThreshold = builder.comment("At or below this thirst, drink any time while awake.").translation(EthologicalConfig.key(species, "thirst", "urgent_drink_threshold")).defineInRange("urgent_drink_threshold", defaults.urgentDrinkThreshold, 0, 100);
            builder.pop();
        }

        private SpeciesThirstSettings toSettings(ResourceLocation entityId) {
            return new SpeciesThirstSettings(entityId, (Integer)this.maxThirst.get(), (Integer)this.depletionIntervalTicks.get(), (Boolean)this.depleteWhileSleeping.get(), (Integer)this.dehydrateDamageIntervalTicks.get(), ((Double)this.dehydrateDamage.get()).floatValue(), (Integer)this.drinkTicks.get(), (Integer)this.eveningDrinkLeadTicks.get(), (Integer)this.urgentDrinkThreshold.get());
        }
    }

    public static final class SleepSection {
        public final ModConfigSpec.IntValue sleepStartTick;
        public final ModConfigSpec.IntValue sleepEndTick;
        public final ModConfigSpec.IntValue regenAmplifier;
        public final ModConfigSpec.IntValue maxBedtimeDelayTicks;
        public final ModConfigSpec.IntValue sleepReturnRadius;

        private SleepSection(ModConfigSpec.Builder builder, String species, SleepDefaults defaults) {
            builder.comment("Bedtime / sleep").translation(EthologicalConfig.key(species, "sleep")).push("sleep");
            this.sleepStartTick = builder.comment("Day tick when the sleep window opens (0-23999).").translation(EthologicalConfig.key(species, "sleep", "sleep_start_tick")).defineInRange("sleep_start_tick", defaults.sleepStartTick, 0, 23999);
            this.sleepEndTick = builder.comment("Day tick when the sleep window closes (0-23999).").translation(EthologicalConfig.key(species, "sleep", "sleep_end_tick")).defineInRange("sleep_end_tick", defaults.sleepEndTick, 0, 23999);
            this.regenAmplifier = builder.comment("Regeneration amplifier while asleep (0 = Regen I).").translation(EthologicalConfig.key(species, "sleep", "regen_amplifier")).defineInRange("regen_amplifier", defaults.regenAmplifier, 0, 10);
            this.maxBedtimeDelayTicks = builder.comment("Max staggered delay after sleep start before lying down.").translation(EthologicalConfig.key(species, "sleep", "max_bedtime_delay_ticks")).defineInRange("max_bedtime_delay_ticks", defaults.maxBedtimeDelayTicks, 0, 24000);
            this.sleepReturnRadius = builder.comment("Max distance a sleeping animal can be pushed before walking back to its spot.").translation(EthologicalConfig.key(species, "sleep", "sleep_return_radius")).defineInRange("sleep_return_radius", defaults.sleepReturnRadius, 1, 16);
            builder.pop();
        }

        private SpeciesSleepSettings toSettings(ResourceLocation entityId) {
            return new SpeciesSleepSettings(entityId, (Integer)this.sleepStartTick.get(), (Integer)this.sleepEndTick.get(), (Integer)this.regenAmplifier.get(), (Integer)this.maxBedtimeDelayTicks.get(), (Integer)this.sleepReturnRadius.get());
        }
    }

    public static final class HerdSection {
        public final ModConfigSpec.IntValue maxSize;
        public final ModConfigSpec.IntValue joinRadius;
        public final ModConfigSpec.DoubleValue followDistance;
        public final ModConfigSpec.DoubleValue motherFollowDistance;
        public final ModConfigSpec.DoubleValue crossSpeciesAlertRadius;
        public final ModConfigSpec.DoubleValue followSpreadPerMemberPercent;
        public final ModConfigSpec.DoubleValue resatterDistance;
        public final ModConfigSpec.DoubleValue sleepFollowMultiplier;
        public final ModConfigSpec.EnumValue<FollowStyle> followStyle;
        public final ModConfigSpec.IntValue spawnGroupMin;
        public final ModConfigSpec.IntValue spawnGroupMax;
        public final ModConfigSpec.IntValue spawnBabiesMin;
        public final ModConfigSpec.IntValue spawnBabiesMax;

        private HerdSection(ModConfigSpec.Builder builder, String species, HerdDefaults defaults) {
            builder.comment("Herd").translation(EthologicalConfig.key(species, "herd")).push("herd");
            this.maxSize = builder.comment("Maximum herd members.").translation(EthologicalConfig.key(species, "herd", "max_size")).defineInRange("max_size", defaults.maxSize, 1, 64);
            this.joinRadius = builder.comment("Radius to find others when joining or forming a herd.").translation(EthologicalConfig.key(species, "herd", "join_radius")).defineInRange("join_radius", defaults.joinRadius, 1, 128);
            this.followDistance = builder.comment("Base distance for following the alpha.").translation(EthologicalConfig.key(species, "herd", "follow_distance")).defineInRange("follow_distance", defaults.followDistance, 1.0, 64.0);
            this.motherFollowDistance = builder.comment("How close babies try to stay to their mother (blocks).").translation(EthologicalConfig.key(species, "herd", "mother_follow_distance")).defineInRange("mother_follow_distance", defaults.motherFollowDistance, 1.0, 32.0);
            this.crossSpeciesAlertRadius = builder.comment("Radius to alert other animals when hurt.").translation(EthologicalConfig.key(species, "herd", "cross_species_alert_radius")).defineInRange("cross_species_alert_radius", defaults.crossSpeciesAlertRadius, 0.0, 128.0);
            this.followSpreadPerMemberPercent = builder.comment("Extra follow distance per additional member (0.1 = +10% each).").translation(EthologicalConfig.key(species, "herd", "follow_spread_per_member_percent")).defineInRange("follow_spread_per_member_percent", defaults.followSpreadPerMemberPercent, 0.0, 1.0);
            this.resatterDistance = builder.comment("During WARY, re-scatter if the threat comes this close.").translation(EthologicalConfig.key(species, "herd", "resatter_distance")).defineInRange("resatter_distance", defaults.resatterDistance, 1.0, 64.0);
            this.sleepFollowMultiplier = builder.comment("Multiplier on follow distance: pack members may not sleep until within this distance of their alpha (cow 6 * 1.0 = 6 blocks).").translation(EthologicalConfig.key(species, "herd", "sleep_follow_multiplier")).defineInRange("sleep_follow_multiplier", defaults.sleepFollowMultiplier, 0.1, 4.0);
            this.followStyle = builder.comment("How members keep station with the alpha in calm times: FOLLOW trails behind, SURROUND spreads around it.").translation(EthologicalConfig.key(species, "herd", "follow_style")).defineEnum("follow_style", defaults.followStyle);
            this.spawnGroupMin = builder.comment("Minimum animals in a natural spawn pack.").translation(EthologicalConfig.key(species, "herd", "spawn_group_min")).defineInRange("spawn_group_min", defaults.spawnGroupMin, 1, 64);
            this.spawnGroupMax = builder.comment("Maximum animals in a natural spawn pack (also raises cluster cap).").translation(EthologicalConfig.key(species, "herd", "spawn_group_max")).defineInRange("spawn_group_max", defaults.spawnGroupMax, 1, 64);
            this.spawnBabiesMin = builder.comment("Minimum babies in a natural spawn pack.").translation(EthologicalConfig.key(species, "herd", "spawn_babies_min")).defineInRange("spawn_babies_min", defaults.spawnBabiesMin, 0, 64);
            this.spawnBabiesMax = builder.comment("Maximum babies in a natural spawn pack.").translation(EthologicalConfig.key(species, "herd", "spawn_babies_max")).defineInRange("spawn_babies_max", defaults.spawnBabiesMax, 0, 64);
            builder.pop();
        }

        private SpeciesHerdSettings toSettings(ResourceLocation entityId) {
            int max;
            int min = this.spawnGroupMin.get();
            if (min > (max = this.spawnGroupMax.get())) {
                min = max;
            }
            int babiesMax;
            int babiesMin = this.spawnBabiesMin.get();
            if (babiesMin > (babiesMax = this.spawnBabiesMax.get())) {
                babiesMin = babiesMax;
            }
            babiesMax = Math.min(babiesMax, max);
            babiesMin = Math.min(babiesMin, babiesMax);
            return new SpeciesHerdSettings(entityId, this.maxSize.get(), this.joinRadius.get(), this.followDistance.get(), this.motherFollowDistance.get(), this.crossSpeciesAlertRadius.get(), this.followSpreadPerMemberPercent.get(), this.resatterDistance.get(), this.sleepFollowMultiplier.get(), min, max, babiesMin, babiesMax, this.followStyle.get());
        }
    }

    public static final class HomeSection {
        public final ModConfigSpec.IntValue waterSearchRadius;
        public final ModConfigSpec.IntValue wanderRadius;
        public final ModConfigSpec.IntValue leashShrinkTicks;
        public final ModConfigSpec.IntValue validationIntervalTicks;
        public final ModConfigSpec.BooleanValue nomadic;
        public final ModConfigSpec.DoubleValue migrationSpeed;
        public final ModConfigSpec.IntValue campTimeTick;

        private HomeSection(ModConfigSpec.Builder builder, String species, HomeDefaults defaults) {
            builder.comment("Home").translation(EthologicalConfig.key(species, "home")).push("home");
            this.waterSearchRadius = builder.comment("Radius to find or validate water for homes and drinking.").translation(EthologicalConfig.key(species, "home", "water_search_radius")).defineInRange("water_search_radius", defaults.waterSearchRadius, 1, 256);
            this.wanderRadius = builder.comment("Daytime wander leash radius around home.").translation(EthologicalConfig.key(species, "home", "wander_radius")).defineInRange("wander_radius", defaults.wanderRadius, 1, 256);
            this.leashShrinkTicks = builder.comment("Ticks before sleep start over which the leash shrinks.").translation(EthologicalConfig.key(species, "home", "leash_shrink_ticks")).defineInRange("leash_shrink_ticks", defaults.leashShrinkTicks, 0, 24000);
            this.validationIntervalTicks = builder.comment("How often home establish/validate checks run.").translation(EthologicalConfig.key(species, "home", "validation_interval_ticks")).defineInRange("validation_interval_ticks", defaults.validationIntervalTicks, 1, 72000);
            this.nomadic = builder.comment("If true, migrate by day and camp near water instead of a permanent home.").translation(EthologicalConfig.key(species, "home", "nomadic")).define("nomadic", defaults.nomadic);
            this.migrationSpeed = builder.comment("Speed multiplier while migrating (nomadic species).").translation(EthologicalConfig.key(species, "home", "migration_speed")).defineInRange("migration_speed", defaults.migrationSpeed, 0.1, 2.0);
            this.campTimeTick = builder.comment("Day tick when nomads start camping near water until sleep.").translation(EthologicalConfig.key(species, "home", "camp_time_tick")).defineInRange("camp_time_tick", defaults.campTimeTick, 0, 23999);
            builder.pop();
        }

        private SpeciesHomeSettings toSettings(ResourceLocation entityId) {
            return new SpeciesHomeSettings(entityId, (Integer)this.waterSearchRadius.get(), (Integer)this.wanderRadius.get(), (Integer)this.leashShrinkTicks.get(), (Integer)this.validationIntervalTicks.get(), (Boolean)this.nomadic.get(), (Double)this.migrationSpeed.get(), (Integer)this.campTimeTick.get());
        }
    }

    public static final class GrowthSection {
        public final ModConfigSpec.IntValue minGrowthDays;
        public final ModConfigSpec.IntValue maxGrowthDays;
        public final ModConfigSpec.DoubleValue babyExtraScale;
        public final ModConfigSpec.DoubleValue babyPlayChance;
        public final ModConfigSpec.ConfigValue<List<? extends String>> playStyles;
        public final ModConfigSpec.BooleanValue babyOnlyPlaysWithBabies;

        private GrowthSection(ModConfigSpec.Builder builder, String species, GrowthDefaults defaults) {
            builder.comment("Baby growth & play").translation(EthologicalConfig.key(species, "growth")).push("growth");
            this.minGrowthDays = builder.comment("Minimum days for a baby to grow up (1 in-game day = 24000 ticks).").translation(EthologicalConfig.key(species, "growth", "min_growth_days")).defineInRange("min_growth_days", defaults.minGrowthDays, 1, 30);
            this.maxGrowthDays = builder.comment("Maximum days for a baby to grow up (each baby picks a random day in [min, max]).").translation(EthologicalConfig.key(species, "growth", "max_growth_days")).defineInRange("max_growth_days", defaults.maxGrowthDays, 1, 30);
            this.babyExtraScale = builder.comment("Extra SCALE a baby gains by maturity before restoring adult size (0.2 = +20%, about 50% -> 60% of adult).").translation(EthologicalConfig.key(species, "growth", "baby_extra_scale")).defineInRange("baby_extra_scale", defaults.babyExtraScale, 0.0, 0.5);
            this.babyPlayChance = builder.comment("Chance a baby attempts to start a play bout on each roll (0-1).").translation(EthologicalConfig.key(species, "growth", "baby_play_chance")).defineInRange("baby_play_chance", defaults.babyPlayChance, 0.0, 1.0);
            this.playStyles = builder.comment("Allowed play styles for this species: chase, headbutt.").translation(EthologicalConfig.key(species, "growth", "play_styles")).defineListAllowEmpty("play_styles", defaults.playStyles, () -> "chase", value -> {
                String s;
                return value instanceof String && ((s = (String)value).equals("chase") || s.equals("headbutt"));
            });
            this.babyOnlyPlaysWithBabies = builder.comment("If true, babies only play with other babies (never with adults).").translation(EthologicalConfig.key(species, "growth", "baby_only_play_babies")).define("baby_only_play_babies", defaults.babyOnlyPlaysWithBabies);
            builder.pop();
        }

        private SpeciesGrowthSettings toSettings(ResourceLocation entityId) {
            int max;
            int min = (Integer)this.minGrowthDays.get();
            if (min > (max = ((Integer)this.maxGrowthDays.get()).intValue())) {
                min = max;
            }
            return new SpeciesGrowthSettings(entityId, min, max, ((Double)this.babyExtraScale.get()).floatValue(), ((Double)this.babyPlayChance.get()).floatValue(), List.copyOf((Collection)this.playStyles.get()), (Boolean)this.babyOnlyPlaysWithBabies.get());
        }
    }
}

