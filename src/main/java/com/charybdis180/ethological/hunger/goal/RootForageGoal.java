package com.charybdis180.ethological.hunger.goal;

import com.charybdis180.ethological.Ethological;
import com.charybdis180.ethological.ModSounds;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.home.FenceDetection;
import com.charybdis180.ethological.home.HomeSettingsManager;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.home.NomadicMigration;
import com.charybdis180.ethological.home.SpeciesHomeSettings;
import com.charybdis180.ethological.hunger.FoodTargetData;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.hunger.HungerSettingsManager;
import com.charybdis180.ethological.hunger.PastureRecovery;
import com.charybdis180.ethological.hunger.SpeciesHungerSettings;
import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Pig root-foraging fallback: when ordinary food scans keep coming up empty nearby, the pig
 * digs into dirt-family ground to unearth a root crop. The dig plays the shared eat animation
 * (varb.etho_eating via FaCompat) with dirt-break particles, converts grass blocks to dirt
 * (PastureRecovery regrows them), feeds via the crop multiplier, rolls a chance of a bonus
 * crop drop for players, and briefly holds the dug-up crop in the pig's mouth (MOUTH_ITEM,
 * rendered fox-style by the client layer).
 *
 * Deliberately a plain Goal with self-managed travel (DrinkWaterGoal style) rather than a
 * MoveToBlockGoal subclass: the vanilla base's arrival/state machine let same-priority goals
 * (rest/follow) steal movement in the tick the pig arrived, so the dig never began. Running
 * at need-behavior priority with explicit arrival checks closes that window.
 */
public class RootForageGoal
extends Goal {
    /** Failed EatFoodGoal scans before a pig considers digging. */
    static final int ROOT_FORAGE_THRESHOLD = 2;
    private static final Set<Block> DIGGABLE = Set.of(
            Blocks.DIRT,
            Blocks.COARSE_DIRT,
            Blocks.ROOTED_DIRT,
            Blocks.GRASS_BLOCK,
            Blocks.PODZOL,
            Blocks.MYCELIUM);
    private static final Item[] ROOT_CROPS = {Items.CARROT, Items.POTATO, Items.BEETROOT};
    private static final float BONUS_DROP_CHANCE = 0.25f;
    private static final int SNUFFLE_TICKS_TOTAL = 60;
    private static final int TRAVEL_TIMEOUT_TICKS = 600;

    private final Pig pig;
    private final EatFoodGoal sibling;
    private boolean finished;
    private boolean digging;
    private int snuffleTicks;
    private int travelTicks;
    private long nextEatGameTime;
    private int failedSearches;
    private LongSet penRegion;
    private int penRefY;
    private BlockPos leashHome;
    private double leashRadiusSqr;
    /** Column the pig stands ON while digging; the floor beneath it is what gets rooted. */
    private BlockPos standPos;
    private Set<BlockPos> herdClaims = Set.of();

    public RootForageGoal(Pig pig, EatFoodGoal sibling) {
        this.pig = pig;
        this.sibling = sibling;
        this.setFlags(java.util.EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** Called by the sibling EatFoodGoal whenever its own nearby food scan comes up empty. */
    void noteSearchFailure() {
        ++this.failedSearches;
    }

    private SpeciesHungerSettings settings() {
        return HungerSettingsManager.get(this.pig.getType()).orElse(null);
    }

    public boolean canUse() {
        if (this.nextEatGameTime > 0L && this.pig.level().getGameTime() < this.nextEatGameTime) {
            return false;
        }
        if (this.failedSearches < ROOT_FORAGE_THRESHOLD || this.digging) {
            return false;
        }
        if (this.settings() == null) {
            return false;
        }
        if (this.pig.getData(ModAttachments.SLEEPING) || this.pig.hasData(ModAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        if (NomadicMigration.blocksNeedsGoals(this.pig)) {
            return false;
        }
        if (!Hunger.wantsFood(this.pig)) {
            return false;
        }
        if (Hunger.isRuminating(this.pig)) {
            // Same override as EatFoodGoal: a stale herd-ruminate timer must not starve an animal.
            if (!Hunger.isUrgentlyHungry(this.pig)) {
                return false;
            }
            this.pig.removeData(ModAttachments.RUMINATE_UNTIL);
        }
        return this.findDigSite();
    }

    public boolean canContinueToUse() {
        if (this.digging) {
            return !this.finished && this.snuffleTicks > 0;
        }
        if (this.finished || this.pig.getData(ModAttachments.SLEEPING) || this.pig.hasData(ModAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        if (this.travelTicks > TRAVEL_TIMEOUT_TICKS || this.standPos == null) {
            return false;
        }
        // Site still valid mid-walk (another animal may have rooted it first, block broken, etc).
        return DIGGABLE.contains(this.pig.level().getBlockState(this.standPos.below()).getBlock());
    }

    public void start() {
        this.finished = false;
        this.digging = false;
        this.snuffleTicks = 0;
        this.travelTicks = 0;
        this.pig.setData(ModAttachments.FOOD_TARGET, new FoodTargetData(this.standPos.immutable()));
        this.pathToSite();
        Ethological.LOGGER.debug("Ethological: {} rooting toward {}", this.pig, this.standPos);
    }

    public void stop() {
        this.pig.removeData(ModAttachments.FOOD_TARGET);
        this.digging = false;
        this.snuffleTicks = 0;
        this.travelTicks = 0;
        if (!this.finished) {
            this.standPos = null;
        }
    }

    public void tick() {
        if (this.finished) {
            return;
        }
        if (!this.digging) {
            ++this.travelTicks;
            if (this.arrived()) {
                this.beginDigging();
                return;
            }
            if (this.travelTicks > TRAVEL_TIMEOUT_TICKS) {
                this.finished = true;
                return;
            }
            // Repath periodically: the first leg may have been interrupted by a shove or
            // a same-priority goal grabbing the navigator for a tick.
            if (this.pig.getNavigation().isDone() && this.pig.tickCount % 20 == 0) {
                this.pathToSite();
            }
            return;
        }
        // Dig sequence: stationary snuffling with periodic dirt-break particle pulses.
        this.pig.getNavigation().stop();
        this.pig.getLookControl().setLookAt(
                (double)this.standPos.getX() + 0.5,
                (double)this.standPos.getY(),
                (double)this.standPos.getZ() + 0.5);
        --this.snuffleTicks;
        if (this.snuffleTicks == SNUFFLE_TICKS_TOTAL - 20 || this.snuffleTicks == 20) {
            this.playSnufflePulse();
        }
        if (this.snuffleTicks <= 0) {
            this.completeDig();
        }
    }

    private boolean arrived() {
        return this.standPos != null
                && this.pig.blockPosition().distSqr(this.standPos) <= 2.25;
    }

    private void pathToSite() {
        BlockPos anchored = Homes.surfaceStandForMove(this.pig.level(), this.standPos, this.pig);
        if (anchored == null) {
            anchored = this.standPos;
        }
        anchored = Homes.offsetStandFromCorners(this.pig.level(), anchored);
        this.pig.getNavigation().moveTo(
                (double)anchored.getX() + 0.5,
                (double)anchored.getY(),
                (double)anchored.getZ() + 0.5,
                1.0);
    }

    private void beginDigging() {
        this.digging = true;
        this.snuffleTicks = SNUFFLE_TICKS_TOTAL;
        this.pig.getNavigation().stop();
        Level level = this.pig.level();
        level.playSound(null, this.standPos, ModSounds.PIG_EAT.get(), SoundSource.NEUTRAL, 1.0f, 0.85f);
        this.playSnufflePulse();
    }

    private void playSnufflePulse() {
        Level level = this.pig.level();
        BlockState floor = level.getBlockState(this.standPos.below());
        level.levelEvent(2001, this.standPos.below(), Block.getId(floor));
        Hunger.beginEatAnim(this.pig);
    }

    private void completeDig() {
        Level level = this.pig.level();
        BlockPos floorPos = this.standPos.below().immutable();
        BlockState floor = level.getBlockState(floorPos);
        Block floorBlock = floor.getBlock();
        this.finished = true;
        this.digging = false;
        this.standPos = null;
        if (!DIGGABLE.contains(floorBlock)) {
            return;
        }
        if (floorBlock == Blocks.GRASS_BLOCK) {
            // Chosen terrain cost (user decision): rooted grass becomes dirt; PastureRecovery
            // slowly regrows it, matching how cow/sheep grazing already trades grass for time.
            level.setBlock(floorPos, Blocks.DIRT.defaultBlockState(), 2);
            if (level instanceof ServerLevel serverLevel) {
                PastureRecovery.schedule(serverLevel, floorPos);
            }
        }
        level.playSound(null, floorPos, ModSounds.PIG_EAT.get(), SoundSource.NEUTRAL, 1.0f, 1.0f);
        SpeciesHungerSettings settings = this.settings();
        if (settings != null) {
            int fed = Math.max(1, Math.round((float)settings.hungerPerFood() * settings.cropMultiplier()));
            Hunger.feed(this.pig, fed);
            this.pig.heal(settings.healPerFood());
            int cooldown = settings.eatCooldownTicks();
            if (cooldown > 0) {
                this.nextEatGameTime = level.getGameTime() + (long)cooldown;
            }
        } else {
            Hunger.feed(this.pig, 1);
        }
        Hunger.beginEatAnim(this.pig);
        Item crop = ROOT_CROPS[this.pig.getRandom().nextInt(ROOT_CROPS.length)];
        ItemStack stack = new ItemStack(crop);
        this.pig.setData(ModAttachments.MOUTH_ITEM, stack);
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(
                    new ItemParticleOption(ParticleTypes.ITEM, stack),
                    this.pig.getX(), this.pig.getY() + 0.6, this.pig.getZ(),
                    8, 0.3, 0.2, 0.3, 0.02);
            if (this.pig.getRandom().nextFloat() < BONUS_DROP_CHANCE) {
                ItemEntity drop = new ItemEntity(serverLevel,
                        this.pig.getX(), this.pig.getY() + 0.5, this.pig.getZ(), new ItemStack(crop));
                serverLevel.addFreshEntity(drop);
            }
        }
        this.failedSearches = 0;
        if (this.sibling != null) {
            this.sibling.onSiblingSuccess();
            // Hold the eat goal off through its own cooldown so pig and eat goal don't fight
            // over the next bite window.
            int cooldown = settings != null ? settings.eatCooldownTicks() : 0;
            this.sibling.deferUntil(level.getGameTime() + (long)Math.max(1, cooldown));
        }
    }

    /**
     * Nearest standable column inside the pen/leash whose floor block is diggable and which no
     * herdmate has claimed. Reachability is validated only for the winner — one path check per
     * attempt instead of one per candidate.
     */
    private boolean findDigSite() {
        SpeciesHungerSettings settings = this.settings();
        if (settings == null) {
            return false;
        }
        this.penRegion = FenceDetection.pennedRegionOf(this.pig);
        this.penRefY = this.pig.blockPosition().getY();
        this.leashHome = null;
        Optional<SpeciesHomeSettings> homeSettings = HomeSettingsManager.get(this.pig.getType());
        Optional<SpeciesSleepSettings> sleepSettings = SleepSettingsManager.get(this.pig.getType());
        Optional<BlockPos> home = Homes.effectiveHome(this.pig);
        if (homeSettings.isPresent() && sleepSettings.isPresent() && home.isPresent()) {
            this.leashHome = home.get();
            double radius = Homes.allowedRadius(this.pig, homeSettings.get(), this.pig.level().getDayTime(), sleepSettings.get().sleepStartTick());
            this.leashRadiusSqr = radius * radius;
        }
        this.herdClaims = HerdManager.foodTargetsOfHerdMates(this.pig);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        double yWeight = 16.0;
        int mobY = this.pig.blockPosition().getY();
        double mobX = this.pig.getX();
        double mobZ = this.pig.getZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int j = 0; j <= 3; j = j > 0 ? -j : 1 - j) {
            for (int k = 0; k < 12; ++k) {
                for (int l = 0; l <= k; l = l > 0 ? -l : 1 - l) {
                    for (int i1 = l < k && l > -k ? k : 0; i1 <= k; i1 = i1 > 0 ? -i1 : 1 - i1) {
                        cursor.setWithOffset(this.pig.blockPosition(), l, j, k);
                        if (!DIGGABLE.contains(this.pig.level().getBlockState(cursor.below()).getBlock())) {
                            continue;
                        }
                        if (!Homes.isDryLand(this.pig.level(), cursor)) {
                            continue;
                        }
                        if (FenceDetection.excludes(this.pig.level(), this.penRegion, cursor, this.penRefY)) {
                            continue;
                        }
                        if (this.herdClaims.contains(cursor) || this.herdClaims.contains(cursor.above())) {
                            continue;
                        }
                        if (this.leashHome != null
                                && cursor.distSqr(this.leashHome) > this.leashRadiusSqr) {
                            continue;
                        }
                        double dy = (double)(cursor.getY() - mobY);
                        double score = cursor.distToCenterSqr(mobX, this.pig.getY(), mobZ) + dy * dy * yWeight;
                        if (score < bestScore) {
                            bestScore = score;
                            best = cursor.immutable();
                        }
                    }
                }
            }
        }
        if (best != null && Homes.isReachable(this.pig, best)) {
            this.standPos = best;
            return true;
        }
        return false;
    }
}
