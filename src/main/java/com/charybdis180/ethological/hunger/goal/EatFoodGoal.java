package com.charybdis180.ethological.hunger.goal;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.ModSounds;
import com.charybdis180.ethological.block.ChickenFeederBlock;
import com.charybdis180.ethological.block.FeederBlock;
import com.charybdis180.ethological.block.FeederBlockEntity;
import com.charybdis180.ethological.block.TroughBlock;
import com.charybdis180.ethological.config.EthologicalConfig;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.HerdSettingsManager;
import com.charybdis180.ethological.herd.SpeciesHerdSettings;
import com.charybdis180.ethological.home.HomeSettingsManager;
import com.charybdis180.ethological.home.FenceDetection;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.home.NomadicMigration;
import com.charybdis180.ethological.home.SpeciesHomeSettings;
import com.charybdis180.ethological.hunger.FoodTargetData;
import com.charybdis180.ethological.hunger.GrazePatchData;
import com.charybdis180.ethological.hunger.GrazePatches;
import com.charybdis180.ethological.hunger.PastureRecovery;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.hunger.HungerSettingsManager;
import com.charybdis180.ethological.hunger.SpeciesHungerSettings;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.core.Vec3i;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;

public class EatFoodGoal
extends MoveToBlockGoal {
    private final Animal animal;
    private boolean finished;
    private int failedSearches;
    private boolean foragingFar;
    private BlockPos unreachablePos;
    private long unreachableUntilGameTime;
    private Set<BlockPos> herdClaims = Set.of();
    private int eatsThisPatch;
    private int lingerTicks;
    private BlockPos blacklistedPatch;
    private long blacklistUntilGameTime;
    // patchExhausted's density probe is expensive — cache it ~100 ticks or until this patch's eat count moves.
    private BlockPos cachedPatchDensityPos;
    private int cachedPatchDensity;
    private int cachedPatchDensityEats;
    private long cachedPatchDensityGameTime = Long.MIN_VALUE;
    private boolean travelingToPatch;
    private boolean searchWalking;
    private boolean preferPastureOnly;
    private boolean searchingFeeder;
    private boolean isFeederTarget;
    private boolean nightEating;
    private long nextEatGameTime;
    private int biteCooldown;
    // Per-search invariants hoisted out of isValidTarget (recomputed by prepareSearch before each block scan).
    private SpeciesHungerSettings searchSettings;
    private boolean searchUrgent;
    private boolean searchIsLeader;
    private BlockPos leashHome;
    private double leashRadiusSqr;
    private BlockPos activePatchCenter;
    private BlockPos anchorPos;
    private double anchorRadius;
    private double grazeSpacingSqr;
    private List<Vec3> feedingMatePositions = List.of();
    private ItemEntity seedTarget;
    private long nextSeedScanGameTime;
    // Pen interior flood region (null when not fenced in): while non-null every food
    // target must sit inside it, so penned animals stop fixating on grass across the
    // fence and search within their enclosure instead. Resolved once per scan.
    private LongSet penRegion;
    private int penRefY;
    // herdWantsFood scans the whole herd (getEntity + attachment per member) — cache per-animal with a 20-tick TTL.
    private long herdWantsFoodCheckGameTime = Long.MIN_VALUE;
    private boolean herdWantsFoodCached;
    // Mirror of the horizontal search range passed to super — the merged scan iterates it directly.
    private final int foodSearchRange;
    /** Pig-only: the paired RootForageGoal, notified when our food scans keep failing so it
     *  can take over (rooting), and reset when we find food or the pig digs successfully. */
    private RootForageGoal rootSibling;

    public EatFoodGoal(Animal animal, double speedModifier, int searchRange, int verticalSearchRange) {
        super((PathfinderMob)animal, speedModifier, searchRange, verticalSearchRange);
        this.animal = animal;
        this.foodSearchRange = searchRange;
    }

    /** Pairs this goal with the pig's RootForageGoal for failure/success signaling. */
    public void setRootSibling(RootForageGoal sibling) {
        this.rootSibling = sibling;
    }

    private void noteRootFailure() {
        if (this.rootSibling != null && this.animal instanceof Pig) {
            this.rootSibling.noteSearchFailure();
        }
    }

    /** Rooting succeeded or normal food was found again: clear both goals' failure streaks. */
    public void onSiblingSuccess() {
        this.failedSearches = 0;
        this.foragingFar = false;
        this.nextEatGameTime = Math.max(this.nextEatGameTime,
                this.animal.level().getGameTime());
    }

    /** Lets the rooting goal extend this goal's eat cooldown so pigs alternate behaviors sanely. */
    public void deferUntil(long gameTime) {
        this.nextEatGameTime = Math.max(this.nextEatGameTime, gameTime);
    }

    private SpeciesHungerSettings settings() {
        return HungerSettingsManager.get(this.animal.getType()).orElse(null);
    }

    private boolean shouldSeekFood(SpeciesHungerSettings settings) {
        return (float)Hunger.getHunger(this.animal) < (float)settings.maxHunger() * settings.searchThresholdPercent();
    }

    private boolean isPatchLeader() {
        return GrazePatches.isPatchLeader(this.animal);
    }

    private void clearGrazePatch() {
        this.animal.removeData(ModAttachments.GRAZE_PATCH);
        this.eatsThisPatch = 0;
        this.lingerTicks = 0;
    }

    protected int nextStartTick(PathfinderMob mob) {
        int base = 100 + mob.getRandom().nextInt(100);
        // Back off further while searches keep failing, so a foodless area isn't rescanned every few seconds.
        base += Math.min(this.failedSearches, 4) * 100;
        return EatFoodGoal.reducedTickDelay(base);
    }

    public boolean canUse() {
        if (this.animal.level().getGameTime() < this.nextEatGameTime) {
            return false;
        }
        if (this.nextStartTick > 0) {
            --this.nextStartTick;
            return false;
        }
        SpeciesHungerSettings settings = this.settings();
        if (settings == null) {
            return false;
        }
        if (NomadicMigration.blocksNeedsGoals(this.animal)
                && !Hunger.isUrgentlyHungry((Entity) this.animal)) {
            return false;
        }
        if (this.animal.getData(ModAttachments.SLEEPING) || this.animal.hasData(ModAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        if (Hunger.isRuminating(this.animal)) {
            // A stale herd-ruminate timer (stamped by beginHerdRuminate on every member)
            // must not starve a hungry animal — clear it so the urgent eat proceeds.
            if (Hunger.isUrgentlyHungry(this.animal)) {
                this.animal.removeData(ModAttachments.RUMINATE_UNTIL);
            } else {
                return false;
            }
        }
        boolean daytime = Hunger.isDaytimeGrazeWindow(this.animal);
        boolean urgent = Hunger.isUrgentlyHungry(this.animal);
        if (daytime) {
            this.nightEating = false;
        } else if (urgent) {
            this.nightEating = true;
        }
        if (!daytime && !this.nightEating) {
            return false;
        }
        if (!daytime && !Hunger.wantsFood(this.animal)) {
            this.nightEating = false;
            return false;
        }
        this.nextStartTick = this.nextStartTick(this.animal);
        this.herdClaims = HerdManager.foodTargetsOfHerdMates(this.animal);
        this.finished = false;
        this.travelingToPatch = false;
        this.searchWalking = false;
        if (daytime && this.isPatchLeader()) {
            if (!this.herdWantsFoodCached()) {
                this.clearGrazePatch();
                Hunger.beginHerdRuminate(this.animal);
                return false;
            }
            return this.beginLeaderGraze(settings);
        }
        if (!daytime && !this.nightEating && !this.shouldSeekFood(settings)) {
            return false;
        }
        return this.beginMemberOrUrgentGraze(settings, daytime);
    }

    private boolean beginLeaderGraze(SpeciesHungerSettings settings) {
        Optional<Object> patch;
        Optional<Object> optional = patch = this.animal.hasData(ModAttachments.GRAZE_PATCH) ? Optional.of(((GrazePatchData)this.animal.getData(ModAttachments.GRAZE_PATCH)).center()) : Optional.empty();
        if (patch.isEmpty() || this.patchExhausted((BlockPos)patch.get(), settings)) {
            if (patch.isPresent()) {
                this.retirePatch((BlockPos)patch.get());
            }
            if (!this.pickNewPatch(settings)) {
                this.noteRootFailure();
                if (++this.failedSearches >= 3) {
                    this.foragingFar = true;
                }
                return false;
            }
            patch = Optional.of(((GrazePatchData)this.animal.getData(ModAttachments.GRAZE_PATCH)).center());
            this.eatsThisPatch = 0;
            this.lingerTicks = 0;
        }
        if (Hunger.wantsFood(this.animal) && this.eatsThisPatch < settings.eatsPerPatch()) {
            if (this.tryClaimSeedTarget()) {
                this.start();
                return true;
            }
            boolean found = this.findNearestBlock();
            if (found) {
                this.failedSearches = 0;
                this.animal.setData(ModAttachments.FOOD_TARGET,new FoodTargetData(this.blockPos.immutable()));
                return true;
            }
            this.retirePatch((BlockPos)patch.get());
            return this.pickNewPatch(settings) && this.pathToCurrentPatch();
        }
        if (this.animal.distanceToSqr(Vec3.atCenterOf(((Vec3i)patch.get()))) > 9.0) {
            return this.pathToCurrentPatch();
        }
        this.lingerTicks = settings.grazeLingerTicksMin() + this.animal.getRandom().nextInt(Math.max(1, settings.grazeLingerTicksSpan()));
        this.travelingToPatch = true;
        this.blockPos = (BlockPos)patch.get();
        return true;
    }

    private boolean beginMemberOrUrgentGraze(SpeciesHungerSettings settings, boolean daytime) {
        if (!this.shouldSeekFood(settings) && !Hunger.isUrgentlyHungry(this.animal) && !this.nightEating) {
            return false;
        }
        if (this.tryClaimSeedTarget()) {
            return true;
        }
        boolean found = this.findNearestBlock();
        if (found) {
            this.failedSearches = 0;
            this.animal.setData(ModAttachments.FOOD_TARGET,new FoodTargetData(this.blockPos.immutable()));
            return true;
        }
        this.noteRootFailure();
        if (Hunger.isUrgentlyHungry(this.animal)) {
            return this.beginSearchWalk();
        }
        if (daytime && ++this.failedSearches >= 3) {
            this.foragingFar = true;
        }
        return false;
    }

    private boolean beginSearchWalk() {
        Vec3 target = DefaultRandomPos.getPos(this.animal, (int)16, (int)5);
        // DefaultRandomPos legitimately returns null (no reachable wander point found) —
        // bail before anything dereferences it.
        if (target == null) {
            return false;
        }
        // Penned animals search-walk INSIDE the fence: a random walk target across the
        // wall would pin the starving animal against the fence line. Re-roll a few times;
        // if the pen is too tight for a valid roll, stand pat this attempt instead.
        for (int i = 0; i < 4 && FenceDetection.excludes(this.animal.level(), this.penRegion,
                BlockPos.containing((Position)target), this.penRefY); ++i) {
            target = DefaultRandomPos.getPos(this.animal, (int)16, (int)5);
            if (target == null) {
                return false;
            }
        }
        if (FenceDetection.excludes(this.animal.level(), this.penRegion,
                BlockPos.containing((Position)target), this.penRefY)) {
            return false;
        }
        this.searchWalking = true;
        this.blockPos = BlockPos.containing((Position)target);
        return true;
    }

    private boolean pathToCurrentPatch() {
        if (!this.animal.hasData(ModAttachments.GRAZE_PATCH)) {
            return false;
        }
        this.blockPos = ((GrazePatchData)this.animal.getData(ModAttachments.GRAZE_PATCH)).center();
        this.travelingToPatch = true;
        this.animal.removeData(ModAttachments.FOOD_TARGET);
        return true;
    }

    private boolean pickNewPatch(SpeciesHungerSettings settings) {
        Set<Block> pasture;
        this.prepareSearch(settings);
        BlockPos origin = this.animal.blockPosition();
        int radius = this.searchRadius(settings);
        // Pen-aware allow predicate: inside a fence the patch must sit in the enclosure,
        // regardless of urgency (the urgent bypass below drops the leash, not the pen).
        Predicate<BlockPos> allow = pos -> !FenceDetection.excludes(this.animal.level(), this.penRegion, (BlockPos)pos, this.penRefY)
                && this.withinHomeLeash((BlockPos)pos)
                && !this.isBlacklistedPatch((BlockPos)pos, settings);
        Optional<BlockPos> found = Optional.empty();
        if (!Hunger.isUrgentlyHungry(this.animal) && !(pasture = EatFoodGoal.pastureFoods(settings)).isEmpty()) {
            found = GrazePatches.findPatch(this.animal.level(), origin, radius, pasture, allow);
        }
        if (found.isEmpty()) {
            found = GrazePatches.findPatch(this.animal.level(), origin, radius, settings.foodBlocks(), allow);
        }
        if (found.isEmpty() && this.foragingFar) {
            found = GrazePatches.findPatch(this.animal.level(), origin, radius, settings.foodBlocks(), pos -> !this.isBlacklistedPatch((BlockPos)pos, settings));
        }
        if (found.isEmpty()) {
            this.clearGrazePatch();
            return false;
        }
        if (!Homes.isReachable(this.animal, (BlockPos)found.get())) {
            this.retirePatch((BlockPos)found.get());
            return false;
        }
        this.animal.setData(ModAttachments.GRAZE_PATCH,new GrazePatchData((BlockPos)found.get()));
        this.eatsThisPatch = 0;
        this.lingerTicks = 0;
        this.failedSearches = 0;
        return true;
    }

    private static Set<Block> pastureFoods(SpeciesHungerSettings settings) {
        HashSet<Block> pasture = new HashSet<Block>();
        for (Block block : settings.foodBlocks()) {
            if (!SpeciesHungerSettings.isPastureFood(block)) continue;
            pasture.add(block);
        }
        return pasture;
    }

    private void retirePatch(BlockPos patch) {
        this.blacklistedPatch = patch.immutable();
        this.blacklistUntilGameTime = this.animal.level().getGameTime() + 6000L;
        this.clearGrazePatch();
    }

    private boolean isBlacklistedPatch(BlockPos pos, SpeciesHungerSettings settings) {
        int radius = settings != null ? settings.patchGrazeRadius() : 8;
        return this.blacklistedPatch != null && pos.closerThan(this.blacklistedPatch, (double)radius) && this.animal.level().getGameTime() < this.blacklistUntilGameTime;
    }

    /** {@link Hunger#herdWantsFood} memoized for up to 20 ticks — a grazing herd need not be re-scanned every tick. */
    private boolean herdWantsFoodCached() {
        long now = this.animal.level().getGameTime();
        // Long.MIN_VALUE sentinel must be checked explicitly: now - MIN_VALUE overflows to a
        // negative number, so a bare (now - checkTime >= 20L) never refreshes and the cache
        // would stay false forever, starving patch-leader alphas during the day.
        if (this.herdWantsFoodCheckGameTime == Long.MIN_VALUE || now - this.herdWantsFoodCheckGameTime >= 20L) {
            this.herdWantsFoodCached = Hunger.herdWantsFood(this.animal);
            this.herdWantsFoodCheckGameTime = now;
        }
        return this.herdWantsFoodCached;
    }

    private boolean patchExhausted(BlockPos patch, SpeciesHungerSettings settings) {
        int density = this.patchDensity(patch, settings);
        if (density <= 0) {
            return true;
        }
        return this.eatsThisPatch >= settings.eatsPerPatch() && density <= 2;
    }

    private int patchDensity(BlockPos patch, SpeciesHungerSettings settings) {
        long now = this.animal.level().getGameTime();
        if (patch.equals(this.cachedPatchDensityPos)
                && this.cachedPatchDensityEats == this.eatsThisPatch
                && now - this.cachedPatchDensityGameTime < 100L) {
            return this.cachedPatchDensity;
        }
        this.cachedPatchDensity = GrazePatches.foodDensity((LevelReader)this.animal.level(), patch, settings.foodBlocks(), settings.patchGrazeRadius());
        this.cachedPatchDensityPos = patch;
        this.cachedPatchDensityEats = this.eatsThisPatch;
        this.cachedPatchDensityGameTime = now;
        return this.cachedPatchDensity;
    }

    private int searchRadius(SpeciesHungerSettings settings) {
        Optional<SpeciesHomeSettings> home = HomeSettingsManager.get(this.animal.getType());
        int patchSearch = settings.patchSearchRadius();
        if (home.isPresent() && Homes.effectiveHome(this.animal).isPresent()) {
            return Math.min(patchSearch, home.get().wanderRadius());
        }
        return patchSearch;
    }

    public boolean canContinueToUse() {
        // Time-box need goals to the pause window so a failing/unreachable target
        // cannot hold a migrating herd still through the travel leg.
        if (NomadicMigration.blocksNeedsGoals(this.animal)
                && !Hunger.isUrgentlyHungry((Entity) this.animal)) {
            return false;
        }
        if (this.seedTarget != null) {
            return !this.finished
                    && this.seedTarget.isAlive()
                    && !this.seedTarget.getItem().isEmpty()
                    && !this.animal.getData(ModAttachments.SLEEPING)
                    && !this.animal.hasData(ModAttachments.SLEEP_DISTURBANCE);
        }
        if (this.finished || this.animal.getData(ModAttachments.SLEEPING) || this.animal.hasData(ModAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        if (this.isPatchLeader() && Hunger.isDaytimeGrazeWindow(this.animal) && !this.herdWantsFoodCached()) {
            this.clearGrazePatch();
            Hunger.beginHerdRuminate(this.animal);
            return false;
        }
        if (this.travelingToPatch) {
            return this.lingerTicks > 0 || this.animal.distanceToSqr(Vec3.atCenterOf(this.blockPos)) > 9.0;
        }
        if (this.searchWalking) {
            return this.tryTicks <= 600 && !this.animal.getNavigation().isDone();
        }
        if (this.isFeederTarget) {
            SpeciesHungerSettings settings = this.settings();
            if (settings == null || this.tryTicks > 1200 || this.tryTicks < -1200) {
                return false;
            }
            FeederBlockEntity feeder = this.feederAtTarget();
            return feeder != null && feeder.hasFood();
        }
        SpeciesHungerSettings settings = this.settings();
        return settings != null && this.tryTicks <= 1200 && this.tryTicks >= -1200 && GrazePatches.isStandForFood((LevelReader)this.animal.level(), this.blockPos, settings.foodBlocks());
    }

    public void start() {
        this.finished = false;
        if (this.seedTarget != null) {
            this.animal.removeData(ModAttachments.FOOD_TARGET);
            this.animal.getNavigation().moveTo(this.seedTarget, this.speedModifier);
            return;
        }
        if (!this.travelingToPatch && !this.searchWalking) {
            this.animal.setData(ModAttachments.FOOD_TARGET,new FoodTargetData(this.blockPos.immutable()));
        }
        super.start();
        // Vanilla MoveToBlockGoal aims at blockPos.getY()+1 (the air above the stand) — the
        // classic "bump the block face forever" jump-loop. Re-aim at the true surface stand
        // once, so the very first path respects the ground instead of the air above it.
        if (this.travelingToPatch && this.animal.getNavigation().isDone()) {
            BlockPos anchored = Homes.surfaceStandForMove(this.animal.level(), this.blockPos, this.animal);
            if (anchored != null) {
                anchored = Homes.offsetStandFromCorners(this.animal.level(), anchored);
                this.animal.getNavigation().moveTo(
                        (double)anchored.getX() + 0.5, (double)anchored.getY(), (double)anchored.getZ() + 0.5,
                        this.speedModifier);
            }
        }
    }

    public void stop() {
        this.animal.removeData(ModAttachments.FOOD_TARGET);
        this.travelingToPatch = false;
        this.searchWalking = false;
        this.isFeederTarget = false;
        this.seedTarget = null;
        super.stop();
    }

    /** Computes everything in isValidTarget that doesn't depend on the candidate position, once per block scan. */
    private void prepareSearch(SpeciesHungerSettings settings) {
        this.searchSettings = settings;
        this.searchUrgent = Hunger.isUrgentlyHungry(this.animal);
        this.searchIsLeader = this.isPatchLeader();
        // Pen gate FIRST, so it also constrains the urgent bypass below: a fenced-in
        // animal must search its own enclosure even while starving — grass across the
        // fence is not food it can ever reach. Null (free-roaming) imposes nothing.
        this.penRegion = FenceDetection.pennedRegionOf(this.animal);
        this.penRefY = this.animal.blockPosition().getY();
        this.leashHome = null;
        Optional<SpeciesHomeSettings> homeSettings = HomeSettingsManager.get(this.animal.getType());
        Optional<SpeciesSleepSettings> sleepSettings = SleepSettingsManager.get(this.animal.getType());
        Optional<BlockPos> home = Homes.effectiveHome(this.animal);
        if (homeSettings.isPresent() && sleepSettings.isPresent() && home.isPresent()) {
            this.leashHome = home.get();
            double radius = Homes.allowedRadius(this.animal, homeSettings.get(), this.animal.level().getDayTime(), sleepSettings.get().sleepStartTick());
            this.leashRadiusSqr = radius * radius;
        }
        this.activePatchCenter = null;
        this.anchorPos = null;
        if (this.searchIsLeader) {
            if (this.animal.hasData(ModAttachments.GRAZE_PATCH)) {
                this.activePatchCenter = ((GrazePatchData)this.animal.getData(ModAttachments.GRAZE_PATCH)).center();
            }
        } else {
            Optional<BlockPos> patch = GrazePatches.effectivePatch(this.animal);
            if (patch.isPresent()) {
                this.activePatchCenter = patch.get();
            } else {
                Animal anchor = Homes.herdAlpha(this.animal).orElse(this.animal);
                this.anchorPos = anchor.blockPosition();
                this.anchorRadius = HerdSettingsManager.get(this.animal.getType()).map(SpeciesHerdSettings::followDistance).orElse(10.0) + 4.0;
            }
        }
        double spacing = (Double)EthologicalConfig.CONFIG.comfort.grazeMinSpacing.get();
        this.grazeSpacingSqr = spacing * spacing;
        this.feedingMatePositions = this.searchUrgent ? List.of() : HerdManager.positionsOfFeedingHerdMates(this.animal);
    }

    protected boolean findNearestBlock() {
        SpeciesHungerSettings settings = this.settings();
        if (settings == null) {
            return false;
        }
        this.prepareSearch(settings);
        this.preferPastureOnly = !Hunger.isUrgentlyHungry(this.animal);
        // Single spiral scan (identical order to vanilla MoveToBlockGoal) classifies every candidate:
        // the nearest feeder wins outright, else the nearest pasture food, else the nearest non-pasture
        // food — matching the old feeder-then-pasture-then-any scan sequence without rescanning.
        double yWeight = (Double)EthologicalConfig.CONFIG.comfort.yPenaltyWeight.get();
        int mobY = this.animal.blockPosition().getY();
        double mobX = this.animal.getX();
        double mobZ = this.animal.getZ();
        BlockPos pasture = null;
        double pastureScore = Double.MAX_VALUE;
        BlockPos nonPasture = null;
        double nonPastureScore = Double.MAX_VALUE;
        BlockPos anyFood = null;
        double anyFoodScore = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int j = 0; j <= this.foodSearchRange; j = j > 0 ? -j : 1 - j) {
            for (int k = 0; k < this.foodSearchRange; ++k) {
                for (int l = 0; l <= k; l = l > 0 ? -l : 1 - l) {
                    for (int i1 = l < k && l > -k ? k : 0; i1 <= k; i1 = i1 > 0 ? -i1 : 1 - i1) {
                        cursor.setWithOffset(this.animal.blockPosition(), l, i1 - 1, k);
                        int cls = this.classifyTarget(this.animal.level(), cursor, settings);
                        if (cls == 1) {
                            this.blockPos = cursor.immutable();
                            this.isFeederTarget = true;
                            return true;
                        }
                        if (cls == 2 || cls == 3) {
                            double dy = (double)(cursor.getY() - mobY);
                            double score = cursor.distToCenterSqr(mobX, this.animal.getY(), mobZ) + dy * dy * yWeight;
                            if (cls == 2) {
                                if (score < pastureScore) {
                                    pasture = cursor.immutable();
                                    pastureScore = score;
                                }
                                if (score < anyFoodScore) {
                                    anyFood = pasture;
                                    anyFoodScore = score;
                                }
                            } else {
                                if (score < nonPastureScore) {
                                    nonPasture = cursor.immutable();
                                    nonPastureScore = score;
                                }
                                if (score < anyFoodScore) {
                                    anyFood = nonPasture;
                                    anyFoodScore = score;
                                }
                            }
                        }
                    }
                }
            }
        }
        if (this.preferPastureOnly) {
            if (pasture != null) {
                if (Homes.isReachable(this.animal, pasture)) {
                    this.blockPos = pasture;
                    this.isFeederTarget = false;
                    return true;
                }
                long now = this.animal.level().getGameTime();
                this.unreachablePos = pasture.immutable();
                this.unreachableUntilGameTime = now + 1200L;
            }
            if (nonPasture != null) {
                if (Homes.isReachable(this.animal, nonPasture)) {
                    this.blockPos = nonPasture;
                    this.isFeederTarget = false;
                    return true;
                }
                long now = this.animal.level().getGameTime();
                this.unreachablePos = nonPasture.immutable();
                this.unreachableUntilGameTime = now + 1200L;
            }
            return false;
        }
        if (anyFood != null) {
            if (Homes.isReachable(this.animal, anyFood)) {
                this.blockPos = anyFood;
                this.isFeederTarget = false;
                return true;
            }
            long now = this.animal.level().getGameTime();
            this.unreachablePos = anyFood.immutable();
            this.unreachableUntilGameTime = now + 1200L;
        }
        return false;
    }

    /**
     * Single-scan target classifier: {@code 1} = feeder, {@code 2} = pasture food,
     * {@code 3} = non-pasture food (kept as fallback even during the pasture-only pass),
     * {@code 0} = invalid. {@link #isValidTarget} delegates here.
     */
    private int classifyTarget(LevelReader level, BlockPos pos, SpeciesHungerSettings settings) {
        if (this.unreachablePos != null && pos.equals(this.unreachablePos) && this.animal.level().getGameTime() < this.unreachableUntilGameTime) {
            return 0;
        }
        if (FenceDetection.excludes(this.animal.level(), this.penRegion, pos, this.penRefY)) {
            return 0;
        }
        if (this.herdClaims.contains(pos) || this.herdClaims.contains(pos.above())) {
            return 0;
        }
        if (this.isValidFeederTarget(level, pos, settings)) {
            return 1;
        }
        if (!GrazePatches.isStandForFood(level, pos, settings.foodBlocks())) {
            return 0;
        }
        Block food = GrazePatches.foodBlockAtStand(level, pos, settings.foodBlocks());
        boolean pasture = SpeciesHungerSettings.isPastureFood(food);
        if (food instanceof CropBlock) {
            CropBlock crop = (CropBlock)food;
            BlockState above = level.getBlockState(pos.above());
            BlockState foodState = above.getBlock() == food ? above : level.getBlockState(pos);
            if (!crop.isMaxAge(foodState)) {
                return 0;
            }
        }
        if (!(this.withinHomeLeash(pos) || this.foragingFar || this.searchUrgent)) {
            return 0;
        }
        if (!this.searchUrgent && this.isMateTooCloseToStand(pos)) {
            return 0;
        }
        if (!this.withinActivePatchOrAlpha(pos, settings)) {
            return 0;
        }
        return pasture ? 2 : 3;
    }

    /** Sole target gate: the classifier's verdict (feeder/pasture/other food) is "valid". */
    protected boolean isValidTarget(LevelReader level, BlockPos pos) {
        SpeciesHungerSettings settings = this.searchSettings != null ? this.searchSettings : this.settings();
        return settings != null && this.classifyTarget(level, pos, settings) != 0;
    }

    private boolean isMateTooCloseToStand(BlockPos stand) {
        if (this.feedingMatePositions.isEmpty()) {
            return false;
        }
        Vec3 center = Vec3.atCenterOf(stand);
        for (Vec3 mate : this.feedingMatePositions) {
            if (!(mate.distanceToSqr(center) < this.grazeSpacingSqr)) continue;
            return true;
        }
        return false;
    }

    private boolean isValidFeederTarget(LevelReader level, BlockPos pos, SpeciesHungerSettings settings) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (!switch (this.animal) {
            case Chicken chicken -> block instanceof ChickenFeederBlock;
            case Cow cow -> true;
            case Pig pig -> true;
            case Sheep sheep -> block instanceof TroughBlock
                    // Prefer SIDE stands so animals path beside the trough, not into the CENTER inventory block.
                    && state.getValue(TroughBlock.PART) == TroughBlock.Part.SIDE;
            default -> false;
        }) {
            return false;
        }
        if (!(level instanceof Level realLevel)) return false;
        BlockPos feederPos = EatFoodGoal.resolveFeederEntityPos(realLevel, pos, state);
        if (!(realLevel.getBlockEntity(feederPos) instanceof FeederBlockEntity feeder)) return false;
        return feeder.hasFood() && (this.withinHomeLeash(pos) || this.foragingFar || this.searchUrgent);
    }

    /** SIDE trough parts have no BE — resolve to the CENTER inventory block. */
    private static BlockPos resolveFeederEntityPos(Level level, BlockPos stand, BlockState state) {
        if (state.getBlock() instanceof TroughBlock) {
            BlockPos center = TroughBlock.findCenter(level, stand, state.getValue(FeederBlock.FACING));
            if (center != null) {
                return center;
            }
        }
        return stand;
    }

    private FeederBlockEntity feederAtTarget() {
        Level level = this.animal.level();
        BlockState state = level.getBlockState(this.blockPos);
        BlockPos feederPos = EatFoodGoal.resolveFeederEntityPos(level, this.blockPos, state);
        BlockEntity be = level.getBlockEntity(feederPos);
        return be instanceof FeederBlockEntity feeder ? feeder : null;
    }

    public double acceptedDistance() {
        return this.isFeederTarget ? 3.0 : 1.5;
    }

    private boolean withinActivePatchOrAlpha(BlockPos pos, SpeciesHungerSettings settings) {
        if (this.searchUrgent) {
            return true;
        }
        double grazeRadius = (double)settings.patchGrazeRadius() + 0.5;
        if (this.searchIsLeader) {
            return this.activePatchCenter == null || pos.closerThan(this.activePatchCenter, grazeRadius);
        }
        if (this.activePatchCenter != null) {
            return pos.closerThan(this.activePatchCenter, grazeRadius);
        }
        return this.anchorPos == null || pos.closerThan(this.anchorPos, this.anchorRadius);
    }

    private boolean withinHomeLeash(BlockPos pos) {
        if (this.foragingFar) {
            return true;
        }
        if (this.leashHome == null) {
            return true;
        }
        return pos.distSqr(this.leashHome) <= this.leashRadiusSqr;
    }

    public void tick() {
        if (this.seedTarget != null) {
            this.tickSeedPeck();
            return;
        }
        if (this.travelingToPatch) {
            this.tickTravelOrLinger();
            return;
        }
        if (this.searchWalking) {
            super.tick();
            if (this.animal.getNavigation().isDone()) {
                this.finished = true;
            }
            return;
        }
        super.tick();
        if (this.finished) {
            return;
        }
        if (!this.isReachedTarget()) {
            if (this.tryTicks > 600) {
                this.failTarget();
            }
            return;
        }
        SpeciesHungerSettings settings = this.settings();
        if (settings == null) {
            this.finished = true;
            return;
        }
        if (this.biteCooldown > 0) {
            --this.biteCooldown;
            if (this.isFeederTarget) {
                this.tryTicks = 0;
            }
            return;
        }
        if (this.isFeederTarget) {
            this.eatFromFeeder(settings);
            ++this.eatsThisPatch;
        } else {
            this.eatFoodBlock(settings);
            ++this.eatsThisPatch;
        }
        this.biteCooldown = 25 + this.animal.getRandom().nextInt(15);
        if (this.isPatchLeader()) {
            if (!this.herdWantsFoodCached()) {
                this.clearGrazePatch();
                Hunger.beginHerdRuminate(this.animal);
                this.finished = true;
                return;
            }
            if (!Hunger.wantsFood(this.animal) && this.animal.hasData(ModAttachments.GRAZE_PATCH)) {
                this.lingerTicks = settings.grazeLingerTicksMin() + this.animal.getRandom().nextInt(Math.max(1, settings.grazeLingerTicksSpan()));
                this.travelingToPatch = true;
                this.animal.removeData(ModAttachments.FOOD_TARGET);
                this.blockPos = ((GrazePatchData)this.animal.getData(ModAttachments.GRAZE_PATCH)).center();
                return;
            }
            if (this.eatsThisPatch >= settings.eatsPerPatch()) {
                BlockPos center;
                if (this.animal.hasData(ModAttachments.GRAZE_PATCH) && this.patchExhausted(center = ((GrazePatchData)this.animal.getData(ModAttachments.GRAZE_PATCH)).center(), settings)) {
                    this.retirePatch(center);
                    Hunger.beginRuminate(this.animal);
                }
                this.finished = true;
                return;
            }
        }
        if (Hunger.wantsFood(this.animal) && this.eatsThisPatch < settings.eatsPerPatch()) {
            if (this.isFeederTarget) {
                FeederBlockEntity feeder = this.feederAtTarget();
                if (feeder != null && feeder.hasFood()) {
                    this.tryTicks = 0;
                    return;
                }
                this.isFeederTarget = false;
            }
            this.herdClaims = HerdManager.foodTargetsOfHerdMates(this.animal);
            if (this.findNearestBlock()) {
                this.start();
                return;
            }
            this.noteRootFailure();
            if (this.isPatchLeader() && this.animal.hasData(ModAttachments.GRAZE_PATCH)) {
                this.retirePatch(((GrazePatchData)this.animal.getData(ModAttachments.GRAZE_PATCH)).center());
            }
        }
        if (!Hunger.wantsFood(this.animal)) {
            Hunger.beginRuminate(this.animal);
        }
        this.finished = true;
    }

    private void tickTravelOrLinger() {
        if (!this.animal.hasData(ModAttachments.GRAZE_PATCH) && this.isPatchLeader()) {
            this.finished = true;
            return;
        }
        if (this.isPatchLeader() && !this.herdWantsFoodCached()) {
            this.clearGrazePatch();
            Hunger.beginHerdRuminate(this.animal);
            this.finished = true;
            return;
        }
        BlockPos patch = this.isPatchLeader() && this.animal.hasData(ModAttachments.GRAZE_PATCH) ? ((GrazePatchData)this.animal.getData(ModAttachments.GRAZE_PATCH)).center() : this.blockPos;
        double distSqr = this.animal.distanceToSqr(Vec3.atCenterOf(patch));
        if (distSqr > 9.0) {
            if (this.mob.getNavigation().isDone()) {
                BlockPos anchored = Homes.surfaceStandForMove(this.mob.level(), patch, this.mob);
                if (anchored == null) {
                    anchored = Homes.offsetStandFromCorners(this.mob.level(), patch);
                } else {
                    anchored = Homes.offsetStandFromCorners(this.mob.level(), anchored);
                }
                this.mob.getNavigation().moveTo((double)anchored.getX() + 0.5, (double)anchored.getY(), (double)anchored.getZ() + 0.5, this.speedModifier);
            }
            if (++this.tryTicks > 600) {
                if (this.isPatchLeader()) {
                    this.retirePatch(patch);
                }
                this.finished = true;
            }
            return;
        }
        this.mob.getNavigation().stop();
        if (this.lingerTicks > 0) {
            --this.lingerTicks;
            return;
        }
        if (this.isPatchLeader()) {
            SpeciesHungerSettings settings = this.settings();
            this.retirePatch(patch);
            if (settings != null && this.herdWantsFoodCached() && this.pickNewPatch(settings)) {
                this.pathToCurrentPatch();
                this.tryTicks = 0;
                super.start();
                return;
            }
        }
        this.finished = true;
    }

    private void failTarget() {
        long now = this.animal.level().getGameTime();
        this.unreachablePos = this.blockPos.immutable();
        this.unreachableUntilGameTime = now + 1200L;
        if (++this.failedSearches >= 3) {
            this.foragingFar = true;
        }
        this.finished = true;
    }

    private void eatFoodBlock(SpeciesHungerSettings settings) {
        Level level = this.animal.level();
        BlockPos foodPos = GrazePatches.resolveFoodBlock(level, this.blockPos, settings.foodBlocks());
        BlockState state = level.getBlockState(foodPos);
        Block foodBlock = state.getBlock();
        if (!settings.foodBlocks().contains(foodBlock)) {
            return;
        }
        if (foodBlock instanceof CropBlock && !((CropBlock)foodBlock).isMaxAge(state)) {
            return;
        }
        level.levelEvent(2001, foodPos, Block.getId((BlockState)state));
        if (state.is(Blocks.GRASS_BLOCK)) {
            // Chickens peck at grass blocks without destroying them.
            if (!(this.animal instanceof Chicken)) {
                level.setBlock(foodPos, Blocks.DIRT.defaultBlockState(), 2);
                if (level instanceof ServerLevel serverLevel) {
                    PastureRecovery.schedule(serverLevel, foodPos.immutable());
                }
            }
        } else {
            level.setBlock(foodPos, Blocks.AIR.defaultBlockState(), 3);
            BlockPos upper = foodPos.above();
            if (level.getBlockState(upper).is(state.getBlock())) {
                level.setBlock(upper, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        // Grazing does not fire a NeoForge block event; clear the presence cache so the
        // spawn gate stops treating this chunk region as a guaranteed food source.
        GrazePatches.invalidateFoodCaches();
        level.playSound(null, foodPos, this.eatSound(), SoundSource.NEUTRAL, 1.0f, 1.0f);
        Animal animal = this.animal;
        if (animal instanceof Sheep) {
            Sheep sheep = (Sheep)animal;
            level.broadcastEntityEvent((Entity)sheep, (byte)10);
            sheep.ate();
        }
        float multiplier = SpeciesHungerSettings.isCropFood(state.getBlock()) ? settings.cropMultiplier() : 1.0f;
        int before = Hunger.getHunger(this.animal);
        Hunger.feed(this.animal, EatFoodGoal.scaledFeed(settings, multiplier));
        Hunger.beginEatAnim(this.animal);
        this.animal.heal(settings.healPerFood());
        this.failedSearches = 0;
        this.foragingFar = false;
        int cooldown = settings.eatCooldownTicks();
        if (cooldown > 0) {
            this.nextEatGameTime = this.animal.level().getGameTime() + (long)cooldown;
        }
    }

    private void eatFromFeeder(SpeciesHungerSettings settings) {
        FeederBlockEntity feeder = this.feederAtTarget();
        if (feeder == null || !feeder.consumeItem()) {
            return;
        }
        Level level = this.animal.level();
        level.playSound(null, this.blockPos, this.eatSound(), SoundSource.NEUTRAL, 1.0f, 1.0f);
        Animal animal = this.animal;
        if (animal instanceof Sheep) {
            Sheep sheep = (Sheep)animal;
            level.broadcastEntityEvent((Entity)sheep, (byte)10);
            sheep.ate();
        }
        Hunger.feed(this.animal, EatFoodGoal.scaledFeed(settings, settings.feederMultiplier()));
        Hunger.beginEatAnim(this.animal);
        this.animal.heal(settings.healPerFood());
        this.failedSearches = 0;
        this.foragingFar = false;
        int cooldown = settings.eatCooldownTicks();
        if (cooldown > 0) {
            this.nextEatGameTime = this.animal.level().getGameTime() + (long)cooldown;
        }
    }

    private static int scaledFeed(SpeciesHungerSettings settings, float multiplier) {
        return Math.max(1, Math.round((float)settings.hungerPerFood() * multiplier));
    }

    private SoundEvent eatSound() {
        return switch (this.animal) {
            case Sheep sheep -> ModSounds.SHEEP_EAT.get();
            case Cow cow -> ModSounds.COW_EAT.get();
            case Pig pig -> ModSounds.PIG_EAT.get();
            case Chicken chicken -> ModSounds.CHICKEN_EAT.get();
            default -> SoundEvents.GENERIC_EAT;
        };
    }

    private boolean tryClaimSeedTarget() {
        if (!(this.animal instanceof Chicken)) {
            return false;
        }
        long now = this.animal.level().getGameTime();
        if (now < this.nextSeedScanGameTime) {
            return this.seedTarget != null && this.seedTarget.isAlive() && !this.seedTarget.getItem().isEmpty();
        }
        this.nextSeedScanGameTime = now + 20L + (long)this.animal.getRandom().nextInt(20);
        ItemEntity best = null;
        double bestDist = 8.0 * 8.0;
        AABB box = this.animal.getBoundingBox().inflate(8.0, 3.0, 8.0);
        for (ItemEntity item : this.animal.level().getEntitiesOfClass(ItemEntity.class, box, EatFoodGoal::isPeckableSeed)) {
            // Seeds dropped across the fence are not food for a penned bird.
            if (FenceDetection.excludes(this.animal.level(), this.penRegion, item.blockPosition(), this.penRefY)) {
                continue;
            }
            double dist = this.animal.distanceToSqr(item);
            if (dist < bestDist) {
                bestDist = dist;
                best = item;
            }
        }
        this.seedTarget = best;
        if (best != null) {
            this.blockPos = best.blockPosition();
            this.isFeederTarget = false;
            this.travelingToPatch = false;
            this.searchWalking = false;
            this.failedSearches = 0;
            return true;
        }
        return false;
    }

    private static boolean isPeckableSeed(ItemEntity entity) {
        if (!entity.isAlive() || entity.hasPickUpDelay()) {
            return false;
        }
        Item item = entity.getItem().getItem();
        return item == Items.WHEAT_SEEDS
                || item == Items.BEETROOT_SEEDS
                || item == Items.MELON_SEEDS
                || item == Items.PUMPKIN_SEEDS
                || item == Items.TORCHFLOWER_SEEDS
                || item == Items.PITCHER_POD;
    }

    private void tickSeedPeck() {
        if (this.seedTarget == null || !this.seedTarget.isAlive() || this.seedTarget.getItem().isEmpty()) {
            this.seedTarget = null;
            this.finished = true;
            return;
        }
        SpeciesHungerSettings settings = this.settings();
        if (settings == null) {
            this.finished = true;
            return;
        }
        this.animal.getLookControl().setLookAt(this.seedTarget, 10.0f, (float)this.animal.getMaxHeadXRot());
        if (this.animal.distanceToSqr(this.seedTarget) > 2.25) {
            if (this.animal.tickCount % 10 == 0) {
                this.animal.getNavigation().moveTo(this.seedTarget, this.speedModifier);
            }
            if (this.tryTicks > 600) {
                this.seedTarget = null;
                this.finished = true;
            }
            return;
        }
        this.animal.getNavigation().stop();
        this.seedTarget.getItem().shrink(1);
        if (this.seedTarget.getItem().isEmpty()) {
            this.seedTarget.discard();
        }
        Level level = this.animal.level();
        level.playSound(null, this.animal.blockPosition(), this.eatSound(), SoundSource.NEUTRAL, 1.0f, 1.0f);
        Hunger.feed(this.animal, EatFoodGoal.scaledFeed(settings, 1.0f));
        Hunger.beginEatAnim(this.animal);
        this.animal.heal(settings.healPerFood());
        this.failedSearches = 0;
        this.foragingFar = false;
        this.seedTarget = null;
        int cooldown = settings.eatCooldownTicks();
        if (cooldown > 0) {
            this.nextEatGameTime = level.getGameTime() + (long)cooldown;
        }
        this.finished = true;
    }
}

