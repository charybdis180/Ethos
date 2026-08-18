/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Direction
 *  net.minecraft.core.Direction$Plane
 *  net.minecraft.core.Vec3i
 *  net.minecraft.core.particles.ParticleOptions
 *  net.minecraft.core.particles.ParticleTypes
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.sounds.SoundEvents
 *  net.minecraft.sounds.SoundSource
 *  net.minecraft.tags.FluidTags
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.PathfinderMob
 *  net.minecraft.world.entity.ai.goal.Goal
 *  net.minecraft.world.entity.ai.goal.Goal$Flag
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.entity.animal.Sheep
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.level.LevelReader
 *  net.minecraft.world.phys.Vec3
 */
package com.charybdis180.ethological.thirst.goal;

import com.charybdis180.ethological.config.EthologicalConfig;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.home.HomeSettingsManager;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.home.NomadicMigration;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.thirst.SpeciesThirstSettings;
import com.charybdis180.ethological.thirst.Thirst;
import com.charybdis180.ethological.thirst.ThirstAttachments;
import com.charybdis180.ethological.thirst.ThirstSettingsManager;
import com.charybdis180.ethological.thirst.WaterTargetData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.Vec3;

public class DrinkWaterGoal
extends Goal {
    private static final int HOMELESS_WATER_RADIUS = 96;
    private static final int URGENT_WATER_RADIUS = 160;
    private static final int REACHABILITY_ATTEMPTS = 6;
    private static final int SIP_INTERVAL_TICKS = 15;
    private final Animal mob;
    private BlockPos waterPos;
    private BlockPos shorePos;
    private int travelTicks;
    private int approachStuckTicks;
    private int stationaryTicks;
    private int repathCooldown;
    private Vec3 lastPos;
    /** Ticks spent lapping at the water so far. Drinks continue until thirst is full
     *  (not for a fixed count), so this only drives the animation/sound cadence and the
     *  anti-stall cap. */
    private int elapsedDrinkTicks;
    private boolean finished;
    private boolean startedEffects;
    private long nextSearchGameTime;
    private int failedSearches;
    private BlockPos lastSearchChunk;
    /** Game time of the last actual water scan, used to detect the post-sleep wake burst. */
    private long lastSearchGameTime;
    /** Whether the wake-stagger salt has been consumed (armed again after a real search runs). */
    private boolean wakeStaggerArmed = true;
    /** An animal that has not searched for this long is "waking up" — the whole herd becomes
     * drink-due at the same tick after sleeping, and each member would otherwise run a full
     * 128-radius spiral scan simultaneously. Salt those post-idle searches so members stagger
     * over ~2s instead of bursting in one tick. */
    private static final long WAKE_STAGGER_IDLE_TICKS = 200L;
    /** Set when the last search located water candidates but none were reachable
     * (e.g. a drink-due cow stuck at the bottom of a pit whose rim stands are out
     * of reach). Water exists, so the miss cache never fires, and without this the
     * cow would re-run the full-radius scan every 20-40 ticks. A longer backoff
     * stops the churn while still allowing a genuine re-search. */
    private boolean lastSearchFoundUnreachableWater;

    public DrinkWaterGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private SpeciesThirstSettings settings() {
        return ThirstSettingsManager.get(this.mob.getType()).orElse(null);
    }

    public boolean canUse() {
        SpeciesThirstSettings settings = this.settings();
        if (settings == null || !Thirst.hasThirstData((Entity)this.mob)) {
            return false;
        }
        if (((Boolean)this.mob.getData(SleepAttachments.SLEEPING)).booleanValue() || this.mob.hasData(SleepAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        if (NomadicMigration.blocksNeedsGoals(this.mob)
                && !Thirst.isUrgentlyThirsty((Entity) this.mob)) {
            return false;
        }
        long gameTime = this.mob.level().getGameTime();
        if (!Thirst.isDrinkDue((Entity)this.mob, settings)) {
            return false;
        }
        // A fresh chunk may hold water even though the last one was dry — search it right away
        // instead of waiting out the previous failure's backoff.
        BlockPos chunkPos = new BlockPos(this.mob.blockPosition().getX() >> 4, 0, this.mob.blockPosition().getZ() >> 4);
        if (this.lastSearchChunk != null && !this.lastSearchChunk.equals(chunkPos)) {
            this.nextSearchGameTime = gameTime;
        }
        // A herd that slept the night all becomes drink-due at the same tick, and each member
        // would run a full spiral water scan simultaneously — the wake-up lag spike. When the
        // last scan is old (this animal just woke up), salt the next one per-UUID so members
        // stagger over ~2s. Urgently thirsty animals still search within ~1-2s and a chunk
        // change still searches immediately.
        if (this.wakeStaggerArmed && gameTime - this.lastSearchGameTime >= WAKE_STAGGER_IDLE_TICKS) {
            this.wakeStaggerArmed = false;
            this.nextSearchGameTime = gameTime
                    + com.charybdis180.ethological.util.Personality.salt(this.mob.getUUID(), "drink_wake", 40);
        }
        if (gameTime < this.nextSearchGameTime) {
            return false;
        }
        boolean found = this.findTargetWater();
        this.lastSearchGameTime = gameTime;
        this.wakeStaggerArmed = true;
        if (!found) {
            ++this.failedSearches;
            this.lastSearchChunk = chunkPos;
            long backoff;
            if (this.lastSearchFoundUnreachableWater) {
                // Water exists nearby but none of it is reachable right now (pit/ravine
                // rim out of reach). Re-checking every 20-40 ticks re-runs the full-radius
                // scan each time — with several cows in a pit that is the lag spike. Back
                // off longer; the scan is only worthwhile when the situation could change.
                backoff = 200L + (long)this.mob.getRandom().nextInt(100);
            } else if (Thirst.isUrgentlyThirsty((Entity)this.mob)) {
                // A dehydrated animal must notice water placed right in front of it quickly,
                // but a full spiral scan every tick is far too expensive. Re-check every ~1-2s.
                backoff = 20L + (long)this.mob.getRandom().nextInt(20);
            } else {
                backoff = 100L + (long)this.mob.getRandom().nextInt(100)
                        + (long)Math.min(this.failedSearches, 4) * 100L;
            }
            this.nextSearchGameTime = gameTime + backoff;
        }
        return found;
    }

    private boolean findTargetWater() {
        this.lastSearchFoundUnreachableWater = false;
        Set<BlockPos> herdClaims = HerdManager.waterTargetsOfHerdMates(this.mob);
        Set<BlockPos> shoreClaims = HerdManager.drinkStandsOfHerdMates(this.mob);
        Optional<BlockPos> home = Homes.effectiveHome(this.mob);
        int speciesRadius = HomeSettingsManager.get(this.mob.getType())
                .map(s -> s.waterSearchRadius())
                .orElse(HOMELESS_WATER_RADIUS);
        // Respect the configured radius when a home is present; only escalate to the
        // wider homeless/urgent radii for animals that are actually homeless or urgent.
        int radius = home.isPresent() ? speciesRadius : Math.max(speciesRadius, HOMELESS_WATER_RADIUS);
        // Prefer searching from the animal so distant homes don't miss nearby water.
        BlockPos center = this.mob.blockPosition();
        long now = this.mob.level().getGameTime();
        HerdManager.Herd herd = HerdManager.herdOf(this.mob);
        boolean urgent = Thirst.isUrgentlyThirsty((Entity)this.mob);
        boolean found = false;

        // The alpha searches for the whole herd; a non-alpha member only scans on its own when
        // it is dehydrating (urgent) or when no alpha is loaded to search. This turns a morning
        // herd's water search from N full spiral scans into 1 (the alpha's) + per-member cheap
        // shore/path adoption.
        Optional<Animal> alphaOpt = Homes.herdAlpha(this.mob);
        boolean isAlpha = alphaOpt.isPresent() && alphaOpt.get() == this.mob;
        boolean herdHasLoadedAlpha = alphaOpt.isPresent();

        // 1) Adopt the herd's shared water when a mate (usually the alpha) found it within the
        //    TTL window: one shore check + one reachability path instead of a full ring scan.
        if (herd != null) {
            BlockPos shared = herd.waterShare(now);
            if (shared != null && shared.distSqr(center) <= (double)radius * (double)radius) {
                found = this.tryAdoptWater(shared, shoreClaims);
            }
        }
        if (found) {
            return true;
        }
        // 2) Non-alpha, non-urgent members defer the full scan to the alpha. The alpha (or any
        //    urgently-thirsty member) below runs it and refreshes the herd share; a member that
        //    defers re-checks the share on its next backoff and adopts it then. Defer ONLY when
        //    there is no in-range share to adopt: if the alpha already found water that is in
        //    range but this member could not adopt it (shore unreachable, e.g. it sits in a pit
        //    or across a wall), the alpha's water is not for this member — run its own scan so
        //    it can find a reachable source instead of waiting forever.
        if (herd != null && !isAlpha && !urgent && herdHasLoadedAlpha) {
            BlockPos _share = herd.waterShare(now);
            boolean _shareInRange = _share != null && _share.distSqr(center) <= (double)radius * (double)radius;
            if (_shareInRange) {
            } else {
                return false;
            }
        }
        if (!found) {
            found = this.findReachableWater(center, radius, herdClaims, shoreClaims);
            if (!found && home.isPresent()) {
                found = this.findReachableWater(home.get(), radius, herdClaims, shoreClaims);
            }
            if (!found && Thirst.isUrgentlyThirsty((Entity)this.mob)) {
                found = this.findReachableWater(this.mob.blockPosition(), Math.max(radius, URGENT_WATER_RADIUS), herdClaims, shoreClaims);
            }
            // The herd (e.g. the alpha) has already claimed the nearest water; excluding
            // herd claims means a trailing member can't drink there and skips entirely.
            // Retry allowing the herd's claimed water at the configured radius (not the
            // urgent escalation) so the herd can share the source.
            if (!found && !herdClaims.isEmpty()
                    && (herd == null || !herd.waterShareFresh(now))) {
                found = this.findReachableWater(center, speciesRadius, Set.of(), shoreClaims);
                if (!found && home.isPresent()) {
                    found = this.findReachableWater(home.get(), speciesRadius, Set.of(), shoreClaims);
                }
            }
        }
        // 4) Any successful search refreshes the herd's shared water so mates stop rescaming.
        if (found && herd != null) {
            herd.setWaterShare(this.waterPos, now);
        }
        return found;
    }

    /** Commits a single already-located water surface: resolve a shore stand and confirm it is reachable. */
    private boolean tryAdoptWater(BlockPos surface, Set<BlockPos> shoreClaims) {
        Optional<BlockPos> shoreOpt = Homes.findShoreStandNearWater(this.mob.level(), surface, this.mob.blockPosition(), shoreClaims)
                .or(() -> Homes.findShoreStandNearWater(this.mob.level(), surface, this.mob.blockPosition(), Set.of()));
        if (shoreOpt.isEmpty() || !Homes.isStrictlyReachable(this.mob, shoreOpt.get())) {
            return false;
        }
        this.waterPos = surface;
        this.shorePos = shoreOpt.get();
        this.mob.setData(ThirstAttachments.WATER_TARGET, new WaterTargetData(this.waterPos, this.shorePos));
        return true;
    }

    private boolean findReachableWater(BlockPos center, int radius, Set<BlockPos> herdClaims, Set<BlockPos> shoreClaims) {
        HashSet<BlockPos> excluded = new HashSet<>(herdClaims);
        // One ring scan yields candidates in the same order the old per-attempt rescans would try them.
        List<BlockPos> candidates = Homes.findWaterCandidates(this.mob.level(), center, radius, excluded, REACHABILITY_ATTEMPTS);
        // Prefer water at or near the animal's own Y: reorder the (small) candidate list by a
        // weighted score = 3D distance + dy^2 * yPenaltyWeight before trying to adopt any.
        double yWeight = (Double)EthologicalConfig.CONFIG.comfort.yPenaltyWeight.get();
        double mobX = this.mob.getX();
        double mobY = this.mob.getY();
        double mobZ = this.mob.getZ();
        int mobFeetY = this.mob.blockPosition().getY();
        ArrayList<BlockPos> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingDouble(s -> s.distToCenterSqr(mobX, mobY, mobZ)
                + (double)((s.getY() - mobFeetY) * (s.getY() - mobFeetY)) * yWeight));
        boolean sawWater = false;
        for (BlockPos surface : ordered) {
            if (excluded.contains(surface)) {
                continue;
            }
            sawWater = true;
            if (!this.tryAdoptWater(surface, shoreClaims)) {
                excluded.add(surface);
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    excluded.add(surface.relative(dir));
                }
                continue;
            }
            return true;
        }
        // Candidates existed but none were adoptable — the water is there but unreachable.
        // Let canUse back off longer so a trapped cow stops re-running the scan every 20-40 ticks.
        this.lastSearchFoundUnreachableWater = sawWater;
        return false;
    }

    public boolean canContinueToUse() {
        // Time-box need goals to the pause window so a failing drink target cannot
        // hold a migrating herd still through the travel leg. A nomadic animal that
        // is ALREADY at the water and lapping is allowed to finish the bout to full
        // (otherwise the travel-leg cutoff aborts the drink the moment thirst climbs
        // past the urgent threshold, leaving it ~30% full).
        if (NomadicMigration.blocksNeedsGoals(this.mob)
                && !Thirst.isUrgentlyThirsty((Entity)this.mob)
                && !this.isAtDrinkPosition()) {
            return false;
        }
        return !this.finished && this.settings() != null && (Boolean)this.mob.getData(SleepAttachments.SLEEPING) == false && !this.mob.hasData(SleepAttachments.SLEEP_DISTURBANCE);
    }

    public void start() {
        this.travelTicks = 0;
        this.approachStuckTicks = 0;
        this.stationaryTicks = 0;
        this.repathCooldown = 0;
        this.lastPos = this.mob.position();
        this.failedSearches = 0;
        this.elapsedDrinkTicks = 0;
        this.finished = false;
        this.startedEffects = false;
        this.pathToDrinkSpot();
    }

    public void tick() {
        if (this.finished) {
            return;
        }
        if (!this.mob.level().getFluidState(this.waterPos).is(FluidTags.WATER)) {
            this.finished = true;
            return;
        }
        Vec3 waterCenter = Vec3.atCenterOf((Vec3i)this.waterPos);
        this.mob.getLookControl().setLookAt(waterCenter.x, waterCenter.y, waterCenter.z);
        if (this.isAtDrinkPosition()) {
            this.mob.getNavigation().stop();
            this.approachStuckTicks = 0;
            this.stationaryTicks = 0;
            this.drinkTick();
            return;
        }
        Vec3 now = this.mob.position();
        boolean barelyMoved = this.lastPos != null && now.distanceToSqr(this.lastPos) < 0.04;
        this.lastPos = now;
        if (barelyMoved) {
            if (++this.stationaryTicks >= 40) {
                this.approachStuckTicks += 20;
                this.stationaryTicks = 0;
            }
        } else {
            this.stationaryTicks = 0;
        }
        if (++this.travelTicks > 600 || this.approachStuckTicks > 100) {
            this.finished = true;
            return;
        }
        BlockPos target = this.mob.isInWaterOrBubble() ? this.waterPos : this.shorePos;
        Vec3 targetCenter = Vec3.atCenterOf((Vec3i)target);
        double distSqr = this.mob.distanceToSqr(targetCenter);
        if (this.repathCooldown > 0) {
            --this.repathCooldown;
        }
        if (this.repathCooldown <= 0) {
            if (!Homes.isDryLand((LevelReader)this.mob.level(), this.shorePos)) {
                this.refreshShore();
                if (this.finished) {
                    return;
                }
            }
            this.pathToDrinkSpot();
            this.repathCooldown = 20;
        }
        if (distSqr <= 4.0) {
            this.mob.getMoveControl().setWantedPosition(targetCenter.x, targetCenter.y, targetCenter.z, 1.1);
        }
    }

    private void drinkTick() {
        SpeciesThirstSettings settings = this.settings();
        int total = settings != null ? settings.drinkTicks() : 60;
        int maxThirst = Thirst.getMaxThirst((Entity)this.mob);
        // Loop the head-dip animation while the drink continues (PULSE_INTERVAL = 35).
        if (!this.startedEffects || this.elapsedDrinkTicks % 35 == 0) {
            this.startedEffects = true;
            this.pulseHeadDip();
        }
        if (this.elapsedDrinkTicks % SIP_INTERVAL_TICKS == 0) {
            float pitch = 0.55f + this.mob.getRandom().nextFloat() * 0.1f;
            this.mob.level().playSound(null, this.mob.blockPosition(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 0.85f, pitch);
            Level level = this.mob.level();
            if (level instanceof ServerLevel) {
                ServerLevel serverLevel = (ServerLevel)level;
                serverLevel.sendParticles((ParticleOptions)ParticleTypes.SPLASH, this.mob.getX(), this.mob.getY() + 0.35, this.mob.getZ(), 5, 0.15, 0.1, 0.15, 0.0);
            }
            // Each sip restores a share of the tank. Keep lapping until it is full.
            if (Thirst.getThirst((Entity)this.mob) < maxThirst) {
                int sipAmount = Math.max(1, maxThirst / Math.max(1, total / SIP_INTERVAL_TICKS));
                Thirst.drinkSip((Entity)this.mob, sipAmount);
            }
        }
        ++this.elapsedDrinkTicks;
        // The drink is over once the tank is full (the animation/sound above already
        // looped through the whole bout). The cap only guards a pathological stall.
        if (Thirst.getThirst((Entity)this.mob) >= maxThirst) {
            Thirst.drink((Entity)this.mob);
            this.finished = true;
            return;
        }
        if (this.elapsedDrinkTicks > Math.max(600, total * 10)) {
            this.finished = true;
        }
    }

    private void pulseHeadDip() {
        long until = this.mob.level().getGameTime() + 40L;
        this.mob.setData(ThirstAttachments.HEAD_DIP_UNTIL,until);
        Animal animal = this.mob;
        if (animal instanceof Sheep) {
            Sheep sheep = (Sheep)animal;
            this.mob.level().broadcastEntityEvent((Entity)sheep, (byte)10);
        }
    }

    private void refreshShore() {
        Set<BlockPos> shoreClaims = HerdManager.drinkStandsOfHerdMates(this.mob);
        Optional<BlockPos> shoreOpt = Homes.findShoreStandNearWater(this.mob.level(), this.waterPos, this.mob.blockPosition(), shoreClaims)
                .or(() -> Homes.findShoreStandNearWater(this.mob.level(), this.waterPos, this.mob.blockPosition(), Set.of()));
        if (shoreOpt.isEmpty() || !Homes.isStrictlyReachable(this.mob, shoreOpt.get())) {
            this.finished = true;
            return;
        }
        this.shorePos = shoreOpt.get();
        this.mob.setData(ThirstAttachments.WATER_TARGET, new WaterTargetData(this.waterPos, this.shorePos));
    }

    private void pathToDrinkSpot() {
        BlockPos target = this.mob.isInWaterOrBubble() ? this.waterPos : this.shorePos;
        this.mob.getNavigation().moveTo((double)target.getX() + 0.5, (double)target.getY(), (double)target.getZ() + 0.5, 1.1);
    }

    private boolean isAtDrinkPosition() {
        if (this.mob.isInWaterOrBubble()) {
            return true;
        }
        if (this.hasWaterNearby() || this.hasWaterDirectlyInFront()) {
            return true;
        }
        double shoreHoriz = DrinkWaterGoal.horizontalDistSqr(this.mob.position(), this.shorePos);
        int shoreDy = Math.abs(this.mob.blockPosition().getY() - this.shorePos.getY());
        if (shoreHoriz <= 1.5625 && shoreDy <= 1) {
            return true;
        }
        double waterHoriz = DrinkWaterGoal.horizontalDistSqr(this.mob.position(), this.waterPos);
        int waterDy = Math.abs(this.mob.blockPosition().getY() - this.waterPos.getY());
        if (waterHoriz <= 1.5625 && waterDy <= 2) {
            return true;
        }
        return this.isFacingWater() && this.isWater(this.waterPos) && DrinkWaterGoal.isNearWater(this.mob.blockPosition(), this.waterPos);
    }

    private static double horizontalDistSqr(Vec3 from, BlockPos to) {
        double dx = from.x - ((double)to.getX() + 0.5);
        double dz = from.z - ((double)to.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    private boolean isFacingWater() {
        Vec3 toWater = Vec3.atCenterOf((Vec3i)this.waterPos).subtract(this.mob.position());
        Vec3 toWaterH = new Vec3(toWater.x, 0.0, toWater.z);
        if (toWaterH.lengthSqr() < 1.0E-4) {
            return true;
        }
        toWaterH = toWaterH.normalize();
        Vec3 look = this.mob.getLookAngle();
        Vec3 lookH = new Vec3(look.x, 0.0, look.z);
        if (lookH.lengthSqr() < 1.0E-4) {
            return false;
        }
        return lookH.normalize().dot(toWaterH) >= 0.5;
    }

    private boolean hasWaterDirectlyInFront() {
        Direction facing = this.mob.getDirection();
        BlockPos feet = this.mob.blockPosition();
        BlockPos front = feet.relative(facing);
        return this.isWater(front) || this.isWater(front.below()) || this.isWater(front.above());
    }

    private boolean hasWaterNearby() {
        BlockPos feet = this.mob.blockPosition();
        for (int dx = -1; dx <= 1; ++dx) {
            for (int dz = -1; dz <= 1; ++dz) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = -3; dy <= 1; ++dy) {
                    if (!this.isWater(feet.offset(dx, dy, dz))) continue;
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isNearWater(BlockPos feet, BlockPos water) {
        int dx = Math.abs(feet.getX() - water.getX());
        int dy = Math.abs(feet.getY() - water.getY());
        int dz = Math.abs(feet.getZ() - water.getZ());
        return dx <= 1 && dz <= 1 && dy <= 1 && dx + dz >= 1;
    }

    private boolean isWater(BlockPos pos) {
        return this.mob.level().getFluidState(pos).is(FluidTags.WATER);
    }

    public void stop() {
        this.mob.getNavigation().stop();
        this.mob.removeData(ThirstAttachments.WATER_TARGET);
        this.startedEffects = false;
        this.approachStuckTicks = 0;
        this.stationaryTicks = 0;
        this.repathCooldown = 0;
        this.lastPos = null;
    }
}

