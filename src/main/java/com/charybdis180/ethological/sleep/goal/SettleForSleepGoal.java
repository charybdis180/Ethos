package com.charybdis180.ethological.sleep.goal;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.config.EthologicalConfig;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.home.FenceDetection;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.herd.goal.FollowPathing;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.sleep.SleepEvents;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import com.charybdis180.ethological.thirst.Thirst;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;

public class SettleForSleepGoal
extends Goal {
    private static final int SHELTER_SEARCH_RADIUS = 8;
    /** Flat-bed probe: how wide the alpha's flat-spot scan reaches, and how much the flat
     *  preference may bias the search center away from the alpha's current feet. */
    private static final int FLAT_SEARCH_RADIUS = 8;
    private static final int FLAT_CENTER_MAX_DIST = 4;
    /** Followers keep this many blocks clear of the alpha's claimed bed (the alpha "moat"),
     *  so a big huddle rings the alpha instead of packing against it and pinning it in place.
     *  Also the min spacing fallbacks enforce around any body, so they can never stack onto
     *  a crowd. */
    private static final int ALPHA_MOAT = 2;
    private final Animal mob;
    private BlockPos settleTarget;
    /** Cooldown after a settle attempt finds no dry bed anywhere nearby — standing awake
     *  beats wading into a pond to sleep, and the search shouldn't re-run every tick. */
    private long nextSettleRetryGameTime;
    /** Memo for {@link #standingOnDryBed()}: result + game time of the last check. */
    private long dryBedCheckGameTime = Long.MIN_VALUE;
    private boolean dryBedCheckResult;

    public SettleForSleepGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    public boolean canUse() {
        return this.shouldSettle();
    }

    public boolean canContinueToUse() {
        return this.shouldSettle();
    }

    public void start() {
        BlockPos here = this.mob.blockPosition();
        int spacing = (Integer)EthologicalConfig.CONFIG.comfort.sleepSpotSpacing.get();
        Set<BlockPos> occupied = new HashSet<BlockPos>();
        BlockPos alphaBed = null;
        HerdManager.Herd herd = HerdManager.herdOf(this.mob);
        if (herd != null && this.mob.level() instanceof ServerLevel) {
            ServerLevel serverLevel = (ServerLevel)this.mob.level();
            occupied.addAll(herd.sleepSpotClaimsOf(this.mob, serverLevel.getGameTime()));
            for (UUID id : herd.members) {
                if (id.equals(this.mob.getUUID())) {
                    continue;
                }
                Entity mate = serverLevel.getEntity(id);
                if (mate != null) {
                    occupied.add(mate.blockPosition());
                    // The alpha's committed bed gets a moat: followers pick slots that keep
                    // ALPHA_MOAT clear of it, so the huddle never packs the alpha in.
                    if (id.equals(herd.alphaId) && mate.hasData(ModAttachments.SLEEP_TARGET)) {
                        alphaBed = (BlockPos)mate.getData(ModAttachments.SLEEP_TARGET);
                    }
                }
            }
        }
        final BlockPos moatCenter = alphaBed;
        java.util.function.Predicate<BlockPos> tooCloseToProtected = t -> {
            if (moatCenter != null
                    && Math.abs(t.getX() - moatCenter.getX()) <= ALPHA_MOAT
                    && Math.abs(t.getZ() - moatCenter.getZ()) <= ALPHA_MOAT
                    && Math.abs(t.getY() - moatCenter.getY()) <= 2) {
                return true;
            }
            for (BlockPos occ : occupied) {
                if (Math.abs(t.getX() - occ.getX()) <= ALPHA_MOAT
                        && Math.abs(t.getZ() - occ.getZ()) <= ALPHA_MOAT
                        && Math.abs(t.getY() - occ.getY()) <= 2) {
                    return true;
                }
            }
            return false;
        };
        // A penned alpha beds at the enclosure's most open column instead of wherever it
        // happens to stand — corners (or a lopsided arm, where a mean centroid lands) would
        // drag the whole huddle into a wall. The search still validates dry/cliff-safe/
        // unoccupied ground, and the winner is rejected if the pathfinder can't reach it or
        // it falls outside the pen, so this is only ever a preference, never a trap.
        // Followers keep settling near themselves around the alpha.
        BlockPos searchCenter = here;
        boolean pennedAlpha = false;
        LongSet penRegion = null;
        if (herd != null && herd.alphaId != null && herd.alphaId.equals(this.mob.getUUID())) {
            LongSet region = FenceDetection.pennedRegionOf(this.mob);
            if (region != null) {
                BlockPos openCenter = FenceDetection.penMostOpenColumnOf(this.mob);
                if (openCenter != null && !openCenter.equals(here)) {
                    searchCenter = openCenter;
                    pennedAlpha = true;
                    penRegion = region;
                }
            }
        }
        final BlockPos center = searchCenter;
        final boolean alphaCentred = pennedAlpha;
        final LongSet pen = penRegion;
        // Free-roaming alpha: steer the settle search toward flat, stable ground so the
        // growing huddle can ring it without any member being pushed off a cliff edge.
        BlockPos settleCenter = center;
        if (!alphaCentred && herd != null && herd.alphaId != null && herd.alphaId.equals(this.mob.getUUID())) {
            Optional<BlockPos> flat = Homes.flatStandNear(this.mob.level(), this.mob, here, FLAT_SEARCH_RADIUS, occupied, spacing, 3);
            if (flat.isPresent() && flat.get().distSqr(here) <= (double)(FLAT_CENTER_MAX_DIST * FLAT_CENTER_MAX_DIST)) {
                settleCenter = flat.get();
            }
        }
        this.settleTarget = Homes.findUnoccupiedStand(this.mob.level(), settleCenter, SHELTER_SEARCH_RADIUS, occupied, spacing)
                .filter(t -> !alphaCentred || !FenceDetection.excludes(this.mob.level(), pen, t, here.getY()))
                .filter(t -> !tooCloseToProtected.test(t))
                .filter(t -> alphaCentred || t.distSqr(here) <= (double)(FLAT_CENTER_MAX_DIST * FLAT_CENTER_MAX_DIST)
                        || Homes.findBetterShelteredHome(this.mob.level(), here, SHELTER_SEARCH_RADIUS, 0, false).isEmpty())
                .orElseGet(() -> {
                    // Fallbacks must stay crowding-aware too: without this they stack bodies
                    // onto the huddle once nearby slots fill up. A stand that keeps the moat
                    // clear beats one that crowds; if even that fails, settle in place —
                    // never on top of a herd-mate.
                    Optional<BlockPos> sheltered = Homes.findBetterShelteredHome(this.mob.level(), here, SHELTER_SEARCH_RADIUS, 0, false)
                            .filter(t -> !alphaCentred || !FenceDetection.excludes(this.mob.level(), pen, t, here.getY()))
                            .filter(t -> !tooCloseToProtected.test(t));
                    if (sheltered.isPresent()) {
                        return sheltered.get();
                    }
                    return Homes.findCliffSafeStand(this.mob.level(), here, SHELTER_SEARCH_RADIUS)
                            .filter(t -> !alphaCentred || !FenceDetection.excludes(this.mob.level(), pen, t, here.getY()))
                            .filter(t -> !tooCloseToProtected.test(t))
                            .orElse(here);
                });
        if (alphaCentred
                && (FenceDetection.excludes(this.mob.level(), pen, this.settleTarget, here.getY())
                    || !Homes.isReachable(this.mob, settleTarget))) {
            // Open-column area unusable (crowded, blocked, or unreachable): fall back to
            // today's behavior rather than pushing toward an impossible bed.
            this.settleTarget = here;
        }
        // A bed seated in (or hanging over) water is never acceptable; the fallback chain may
        // have returned a shoreline cell via the permissive isDryLand scans. Walk the candidate
        // ring for the closest dry bed so a herd never beds down with its body in the pond.
        if (!Homes.isDryBed(this.mob.level(), this.settleTarget)) {
            BlockPos dryBed = Homes.findUnoccupiedStand(this.mob.level(), this.settleTarget, 4, occupied, spacing)
                    .filter(t -> !alphaCentred || !FenceDetection.excludes(this.mob.level(), pen, t, here.getY()))
                    .orElse(null);
            if (dryBed == null) {
                // Nothing dry near the wet target: search around the animal instead. Committing
                // the wet target here would walk the member INTO the pond to sleep — the
                // waterline-settle bug — so the search widens before any wet cell is accepted.
                dryBed = Homes.findUnoccupiedStand(this.mob.level(), here, 6, occupied, spacing)
                        .filter(t -> !alphaCentred || !FenceDetection.excludes(this.mob.level(), pen, t, here.getY()))
                        .orElse(null);
            }
            if (dryBed != null) {
                this.settleTarget = dryBed;
            } else if (Homes.isDryBed(this.mob.level(), here)) {
                this.settleTarget = here;
            } else {
                // Last resort: any proper dry bed within the normal settle range. If even that
                // fails, abort this settle attempt — standing awake at the waterline beats
                // wading out to sleep. Back off briefly so the failed search doesn't re-run
                // every tick.
                this.settleTarget = Homes.findUnoccupiedStand(this.mob.level(), here, SHELTER_SEARCH_RADIUS, occupied, spacing)
                        .filter(t -> !alphaCentred || !FenceDetection.excludes(this.mob.level(), pen, t, here.getY()))
                        .orElse(null);
                if (this.settleTarget == null) {
                    this.nextSettleRetryGameTime = this.mob.level().getGameTime() + 100L
                            + this.mob.getRandom().nextInt(100);
                    return;
                }
            }
        }
        if (herd != null) {
            herd.setSleepSpotClaim(this.mob.getUUID(), this.settleTarget, this.mob.level().getGameTime());
        }
        // Anchor to the actual surface stand (a 1-block-above target gets snapped down so a
        // member never bumps an air block forever) and shift off any wall corner.
        BlockPos anchored = Homes.surfaceStandForMove(this.mob.level(), this.settleTarget, this.mob);
        if (anchored == null) {
            anchored = this.settleTarget;
        }
        anchored = Homes.offsetStandFromCorners(this.mob.level(), anchored);
        this.mob.setData(ModAttachments.SLEEP_TARGET, anchored.immutable());
        if (anchored.closerThan(here, 1.5)) {
            this.mob.getNavigation().stop();
        } else {
            this.mob.getNavigation().moveTo((double)anchored.getX() + 0.5, (double)anchored.getY(), (double)anchored.getZ() + 0.5, 1.0);
        }
    }

    public void stop() {
        HerdManager.Herd herd = HerdManager.herdOf(this.mob);
        if (herd != null) {
            herd.clearSleepSpotClaim(this.mob.getUUID());
        }
        // Keep the sleep target visible while the animal is asleep — the morning wake
        // (SleepEvents.wake) is what clears it. Only drop it here if the animal was
        // interrupted before actually falling asleep.
        if (!this.mob.getData(ModAttachments.SLEEPING)) {
            this.mob.removeData(ModAttachments.SLEEP_TARGET);
        }
        this.settleTarget = null;
    }

    public void tick() {
        if (this.settleTarget == null) {
            this.mob.getNavigation().stop();
            return;
        }
        double distSqr = this.mob.distanceToSqr(Vec3.atBottomCenterOf(this.settleTarget));
        if (distSqr <= 2.25) {
            this.mob.getNavigation().stop();
            return;
        }
        if (this.mob.getNavigation().isDone()) {
            BlockPos anchored = Homes.surfaceStandForMove(this.mob.level(), this.settleTarget, this.mob);
            if (anchored != null) {
                anchored = Homes.offsetStandFromCorners(this.mob.level(), anchored);
                this.mob.getNavigation().moveTo((double)anchored.getX() + 0.5, (double)anchored.getY(), (double)anchored.getZ() + 0.5, 1.0);
            } else {
                this.mob.getNavigation().stop();
            }
        }
    }

    private boolean shouldSettle() {
        long dayTime;
        if (this.mob.getData(ModAttachments.SLEEPING)) {
            return false;
        }
        // A mob mid-water cannot path onto a dry bed above its water level (it would bump
        // the shore forever) — the dedicated SeekShoreGoal must own MOVE until it is beached.
        // Holding the settle flag here starved that goal at priority 3, leaving pushed
        // swimmers to glide in place all night instead of stepping out onto the bank.
        if (this.mob.isInWaterOrBubble()) {
            return false;
        }
        // A waterline cell passes the in-water test (feet dry, body overhanging the pond)
        // but is exactly the "sitting in the water trying to sleep" state. Do not settle
        // here; wait for the current spot-search cooldown, then re-search dry ground.
        if (this.mob.level().getGameTime() < this.nextSettleRetryGameTime) {
            return false;
        }
        if (this.mob.hasData(ModAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        if (Hunger.isUrgentlyHungry(this.mob) || Thirst.isUrgentlyThirsty(this.mob)) {
            return false;
        }
        // A herd member farther than its species' sleep radius from its alpha must rejoin the
        // herd first — settling away from the alpha both splits the sleeping herd and, because
        // this goal outranks FollowAlphaGoal, cancels the escape path that would have walked it
        // back (the "escaping but standing still" freeze). herdRestRadius widens 2x while the
        // alpha is down so it matches FollowAlphaGoal's relaxed release circle (no dead zone).
        double _sleepR = FollowPathing.herdRestRadius(this.mob);
        if (FollowPathing.distanceToAlpha(this.mob) > _sleepR) {
            return false;
        }
        Optional<SpeciesSleepSettings> settingsOpt = SleepSettingsManager.get(this.mob.getType());
        if (settingsOpt.isEmpty()) {
            return false;
        }
        SpeciesSleepSettings settings = settingsOpt.get();
        if (!settings.isSleepTime(dayTime = this.mob.level().getDayTime())) {
            return false;
        }
        if (Homes.shouldTravelHome(this.mob, dayTime, settings)) {
            return false;
        }
        if (Homes.ticksIntoSleepWindow(dayTime, settings) < SleepEvents.bedtimeDelay(this.mob, settings)) {
            return true;
        }
        // Past bedtime the normal flow is dozing in place (SleepEvents' direct entry), but
        // that entry requires a proper dry bed. A member woken or beached onto a waterline
        // cell after bedtime used to strand there: SeekShoreGoal only fires for true
        // swimmers and follow stands down inside the rest circle, so nothing walked it
        // inland. Let the settle walk run for exactly that case — pick a dry bed, walk to
        // it, and the direct entry dozes the member on arrival.
        return !this.standingOnDryBed();
    }

    /** 10-tick memo so a settled herd doesn't pay the dry-bed block reads every tick. */
    private boolean standingOnDryBed() {
        long now = this.mob.level().getGameTime();
        if (this.dryBedCheckGameTime != Long.MIN_VALUE && now - this.dryBedCheckGameTime < 10L) {
            return this.dryBedCheckResult;
        }
        this.dryBedCheckGameTime = now;
        this.dryBedCheckResult = Homes.isDryBed(this.mob.level(), this.mob.blockPosition());
        return this.dryBedCheckResult;
    }
}

