/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.ai.goal.Goal
 *  net.minecraft.world.entity.ai.goal.Goal$Flag
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.phys.Vec3
 */
package com.charybdis180.ethological.herd.goal;

import com.charybdis180.ethological.herd.FollowStyle;
import com.charybdis180.ethological.herd.HerdAttachments;
import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.HerdSettingsManager;
import com.charybdis180.ethological.herd.SpeciesHerdSettings;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.hunger.HungerAttachments;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

public class FollowAlphaGoal
extends Goal {
    private static final int REPATH_TICKS = 20;
    /** Per-member offset for the FIRST path after the goal starts, so a waking
     * herd does not all fire pathfinder queries on the same tick. */
    private static final int FIRST_REPATH_STAGGER_MIN = 4;
    private static final int FIRST_REPATH_STAGGER_SPAN = 12;
    /** Vary the steady-state repath interval per member so the herd cannot
     * re-synchronize into a single pathfinder burst every REPATH_TICKS. */
    private static final int REPATH_VARIATION_SPAN = 8;
    /** Backoff after all follow fallbacks fail: a blocked cow retries the full
     * pathfinding chain (direct + waypoint + up to 12 hop probes) every tick group,
     * hammering the pathfinder for a route that is still blocked. Wait longer so
     * blocked members stop burning pathfinder queries until the alpha moves. */
    private static final int FAILED_REPATH_BACKOFF = 80;
    /** Spacing between chain attempts after a short nudge hop, so a blocked member
     * keeps moving without hammering the pathfinder every tick. */
    private static final int NUDGE_REPATH_BACKOFF = 40;
    /** When the full follow chain fails and the alpha has not moved, remember where
     * the alpha stood and skip the whole chain on the next repath until the alpha
     * actually travels, so a still-blocked member stops hammering the pathfinder. */
    private static final double BLOCKAGE_MOVE_RECHECK_SQR = 64.0; // 8 blocks, squared
    private static final int BLOCKAGE_MEMO_TICKS = FAILED_REPATH_BACKOFF;
    /** A member farther than this multiple of its follow distance from the alpha is
     * treated as separated and jumps straight to the escape path. Members within it
     * keep using the normal follow chain (direct / waypoint / hop / nudge), which
     * climbs hillsides cheaply — the escape scan must not fire while simply walking
     * up or down a slope. Set to 2x per the species follow range. */
    public static final double ESCAPE_DISTANCE_MULTIPLIER = 2.0D;
    private final Animal mob;
    private Animal alpha;
    private boolean wary;
    private int repathCooldown;
    /** When non-null, follow the alpha's own column instead of a SURROUND ring
     * station whose stand is unreachable; cleared once the ring is reachable again. */
    private BlockPos fallbackTarget;
    /** When non-null, the member is mid-escape from a pit/cliff/water separation: this is the
     * exact stand column it committed to (from {@link FollowPathing#escapePlan}), so it keeps
     * walking the same direction instead of flipping between probes every repath. */
    private BlockPos escapeTarget;
    /** Alpha XZ at the last total-failure, plus the game time, so a blocked member
     * reuses the backoff instead of re-probing an alpha that has not moved. */
    private double lastBlockedAlphaX;
    private double lastBlockedAlphaZ;
    private long lastBlockedGameTime = Long.MIN_VALUE;

    public FollowAlphaGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    private SpeciesHerdSettings settings() {
        return HerdSettingsManager.get(this.mob.getType()).orElse(null);
    }

    private boolean isWary() {
        return HerdManager.panicPhaseOf(this.mob, this.mob.level().getGameTime()) == HerdManager.PanicPhase.WARY;
    }

    private boolean shouldYieldForFood() {
        if (this.wary) {
            return false;
        }
        boolean foodTarget = this.mob.hasData(HungerAttachments.FOOD_TARGET);
        boolean urgentHungry = Hunger.hasHungerData(this.mob) && Hunger.isUrgentlyHungry(this.mob);
        boolean urgentThirsty = com.charybdis180.ethological.thirst.Thirst.hasThirstData(this.mob)
                && com.charybdis180.ethological.thirst.Thirst.isUrgentlyThirsty(this.mob);
        // DrinkWaterGoal sets WATER_TARGET only once it has located REACHABLE water
        // (see tryAdoptWater -> isStrictlyReachable). Yielding follow on urgent thirst
        // alone, while the drink goal sits in a no-water backoff, leaves the member with
        // NO running goal: it stands still doing "escaping" instead of moving. So only
        // yield on thirst when the drink goal can actually act.
        boolean waterTarget = this.mob.hasData(com.charybdis180.ethological.thirst.ThirstAttachments.WATER_TARGET);
        boolean result = foodTarget || urgentHungry || waterTarget;
        return result;
    }

    private double followDistance(SpeciesHerdSettings settings) {
        if (this.wary) {
            return 5.0;
        }
        return settings.followDistance();
    }

    private Vec3 stationOf(Animal alpha, double follow, SpeciesHerdSettings settings) {
        if (this.wary) {
            return alpha.position();
        }
        double angle = com.charybdis180.ethological.util.Personality.angleRadians(this.mob.getUUID(), "follow_station");
        double fraction = FollowAlphaGoal.stationFraction(this.mob);
        return alpha.position().add(Math.cos(angle) * follow * fraction, 0.0, Math.sin(angle) * follow * fraction);
    }

    /**
     * Fraction of the follow distance a member holds its personal station at.
     * Stable per member (UUID-hashed), so each animal keeps an individual spot inside
     * the follow circle instead of every member converging on the alpha.
     * FOLLOW: a loose trail pack at 0.2-0.6 x follow. SURROUND: a ring hugging the
     * follow distance at 0.55-0.8 x follow. The band widens a little per herd member
     * (follow_spread_per_member_percent) but is clamped so the member always lands
     * inside the circle with room for the arrival tolerance.
     */
    public static double stationFraction(Animal mob) {
        Optional<SpeciesHerdSettings> opt = HerdSettingsManager.get(mob.getType());
        if (opt.isEmpty()) {
            return 0.5;
        }
        SpeciesHerdSettings settings = opt.get();
        double minF;
        double maxF;
        if (settings.followStyle() == FollowStyle.SURROUND) {
            minF = 0.55;
            maxF = 0.8;
        } else {
            minF = 0.2;
            maxF = 0.6;
        }
        double band = maxF - minF;
        double spread = settings.followSpreadPerMemberPercent();
        double widen = Math.min(0.15, band * 0.5) * spread * 10.0;
        double min = Math.max(0.05, minF - widen);
        double max = Math.min(0.9, maxF + widen);
        return min + (max - min) * com.charybdis180.ethological.util.Personality.unit(mob.getUUID(), "follow_station_radius");
    }

    /** Block column of the station this member is regrouping onto. */
    private BlockPos stationTargetXZ(SpeciesHerdSettings settings, double follow) {
        if (this.fallbackTarget != null) {
            return this.fallbackTarget;
        }
        Vec3 station = this.stationOf(this.alpha, follow, settings);
        return new BlockPos(Mth.floor(station.x), Mth.floor(station.y), Mth.floor(station.z));
    }

    public boolean canUse() {
        Animal alphaAnimal;
        SpeciesHerdSettings settings;
        block14: {
            block13: {
                long dayTime;
                Object sleep;
                Optional<SpeciesSleepSettings> sleepOpt;
                settings = this.settings();
                if (settings == null || !this.mob.hasData(HerdAttachments.HERD_DATA)) {
                    return false;
                }
                if (this.mob.isBaby() && this.mob.hasData(HerdAttachments.MOTHER)) {
                    return false;
                }
                HerdData data = (HerdData)this.mob.getData(HerdAttachments.HERD_DATA);
                if (data.alpha()) {
                    return false;
                }
                this.wary = this.isWary();
                if (((Boolean)this.mob.getData(SleepAttachments.SLEEPING)).booleanValue() || this.mob.hasData(SleepAttachments.SLEEP_DISTURBANCE) && !this.wary) {
                    return false;
                }
                if (this.shouldYieldForFood()) {
                    return false;
                }
                if (!this.wary && Hunger.isRuminating((Entity)this.mob)) {
                    return false;
                }
                if (!this.wary && (sleepOpt = SleepSettingsManager.get(this.mob.getType())).isPresent() && ((SpeciesSleepSettings)(sleep = sleepOpt.get())).isSleepTime(dayTime = this.mob.level().getDayTime()) && !Homes.shouldTravelHome(this.mob, dayTime, (SpeciesSleepSettings)sleep)) {
                    return false;
                }
                sleep = this.mob.level();
                if (!(sleep instanceof ServerLevel)) {
                    return false;
                }
                ServerLevel serverLevel = (ServerLevel)sleep;
                HerdManager.Herd herd = HerdManager.get(data.herdId());
                if (herd == null || herd.alphaId == null) {
                    return false;
                }
                Entity entity = serverLevel.getEntity(herd.alphaId);
                if (!(entity instanceof Animal)) break block13;
                alphaAnimal = (Animal)entity;
                if (entity != this.mob) break block14;
            }
            return false;
        }
        double follow = this.followDistance(settings);
        if ((double)this.mob.distanceTo((Entity)alphaAnimal) <= follow) {
            return false;
        }
        this.alpha = alphaAnimal;
        return true;
    }

    public boolean canContinueToUse() {
        long dayTime;
        SpeciesSleepSettings sleep;
        Optional<SpeciesSleepSettings> sleepOpt;
        SpeciesHerdSettings settings = this.settings();
        if (settings == null) {
            return false;
        }
        if (this.mob.isBaby() && this.mob.hasData(HerdAttachments.MOTHER)) {
            return false;
        }
        this.wary = this.isWary();
        if (this.shouldYieldForFood()) {
            return false;
        }
        if (!this.wary && Hunger.isRuminating((Entity)this.mob)) {
            return false;
        }
        if (!this.wary && (sleepOpt = SleepSettingsManager.get(this.mob.getType())).isPresent() && (sleep = sleepOpt.get()).isSleepTime(dayTime = this.mob.level().getDayTime()) && !Homes.shouldTravelHome(this.mob, dayTime, sleep)) {
            return false;
        }
        if (this.alpha == null || !this.alpha.isAlive() || ((Boolean)this.mob.getData(SleepAttachments.SLEEPING)).booleanValue() || this.mob.hasData(SleepAttachments.SLEEP_DISTURBANCE) && !this.wary) {
            return false;
        }
        double follow = this.followDistance(settings);
        // Release the goal the moment the member is back inside the follow circle, so
        // idle goals (rest/stroll/play) can take the MOVE slot again. Previously the
        // goal held MOVE until 0.6x follow of the station, which trapped members inside
        // the free zone in a "following" state.
        boolean arrivedStop = this.mob.distanceTo(this.alpha) <= follow;
        return !arrivedStop;
    }

    public void start() {
        // Stagger the very first path by member UUID so a waking herd does not
        // slam the pathfinder with ~20 simultaneous moveToAlpha chains.
        this.repathCooldown = FIRST_REPATH_STAGGER_MIN
                + Math.floorMod(this.mob.getUUID().hashCode(), FIRST_REPATH_STAGGER_SPAN);
        if (this.repathCooldown <= FIRST_REPATH_STAGGER_MIN) {
            this.moveToAlpha();
        }
    }

    public void tick() {
        SpeciesHerdSettings settings = this.settings();
        if (settings != null && this.alpha != null) {
            double follow = this.followDistance(settings);
            // Back inside the follow circle, or arrived at this member's personal station:
            // stop navigating so idle goals take over. The goal releases on the circle
            // boundary (see canContinueToUse), so this only stops the path mid-tick.
            boolean arrived = this.mob.distanceTo(this.alpha) <= follow
                    || FollowPathing.arrived(this.mob, Math.max(1.0, follow * 0.15), this.stationTargetXZ(settings, follow));
            if (arrived) {
                this.mob.getNavigation().stop();
                return;
            }
        }
        if (this.repathCooldown > 0) {
            --this.repathCooldown;
        } else if (this.mob.getNavigation().isDone()) {
            this.repathCooldown = REPATH_TICKS
                    + Math.floorMod(this.mob.getUUID().hashCode(), REPATH_VARIATION_SPAN);
            this.moveToAlpha();
        }
    }

    public void stop() {
        this.mob.getNavigation().stop();
        this.alpha = null;
        this.fallbackTarget = null;
        this.escapeTarget = null;
    }

    private void moveToAlpha() {
        Level level;
        SpeciesHerdSettings settings = this.settings();
        double follow = settings != null ? this.followDistance(settings) : 5.0;
        Vec3 station = settings != null ? this.stationOf(this.alpha, follow, settings) : this.alpha.position();
        BlockPos stationXZ = new BlockPos(Mth.floor(station.x()), Mth.floor(station.y()), Mth.floor(station.z()));


        // The last full chain failed and the alpha has not moved enough since: skip the
        // whole fallback chain and just back off — nothing has changed for this member,
        // so re-probing the same blocked route only burns pathfinder queries.
        long now = this.mob.level().getGameTime();
        double adx = this.alpha.getX() - this.lastBlockedAlphaX;
        double adz = this.alpha.getZ() - this.lastBlockedAlphaZ;
        if (this.lastBlockedGameTime != Long.MIN_VALUE
                && now - this.lastBlockedGameTime < BLOCKAGE_MEMO_TICKS
                && adx * adx + adz * adz <= BLOCKAGE_MOVE_RECHECK_SQR) {
            // A member trapped at the bottom of a hole must keep walking the floor even when
            // the alpha is still, or it bobs forever. The memo only suppresses re-probing the
            // same blocked follow chain; the floor-walk escape still deserves to run.
            Path escape = this.escapePath(stationXZ);
            if (escape != null) {
                this.mob.getNavigation().moveTo(escape, 1.1);
                this.repathCooldown = NUDGE_REPATH_BACKOFF;
                return;
            }
            this.repathCooldown = FAILED_REPATH_BACKOFF;
            return;
        }

        double x = station.x();
        double y = station.y();
        double z = station.z();
        if (this.wary && this.isSentinel() && (level = this.mob.level()) instanceof ServerLevel) {
            double dz;
            double dx;
            double len;
            Entity threat;
            ServerLevel serverLevel = (ServerLevel)level;
            HerdManager.Herd herd = HerdManager.get(((HerdData)this.mob.getData(HerdAttachments.HERD_DATA)).herdId());
            if (herd != null && herd.threatId() != null && (threat = serverLevel.getEntity(herd.threatId())) != null && threat.isAlive() && (len = Math.sqrt((dx = threat.getX() - this.alpha.getX()) * dx + (dz = threat.getZ() - this.alpha.getZ()) * dz)) > 0.001) {
                x += dx / len * 2.0;
                z += dz / len * 2.0;
            }
        }
        double speed = this.wary ? 1.3 : 1.1;
        Path path = FollowPathing.pathToSurfaceStand(this.mob, stationXZ, FollowPathing.MAX_STEP_Y);
        if (path != null) {
            this.fallbackTarget = null;
            this.lastBlockedGameTime = Long.MIN_VALUE;
            boolean _moved = this.mob.getNavigation().moveTo(path, speed);
            this.shareRoute(path);
            return;
        }
        // A recent full escape scan found no reachable stand anywhere within the widened
        // budget, and the mob has not moved since — the direct/inner/waypoint/hop probes that
        // follow would fail identically. Skip the whole fallback chain (each probe costs a
        // pathfinder query) and just back off until the mob moves or the memo expires. Unless
        // a herd-mate just escaped to a shared stand: that proves a route exists NOW, so the
        // memo should not suppress trying it.
        boolean _freshSharedEscape = false;
        if (this.mob instanceof Animal _animal && this.mob.hasData(HerdAttachments.HERD_DATA)) {
            HerdManager.Herd _h = HerdManager.get(this.mob.getData(HerdAttachments.HERD_DATA).herdId());
            if (_h != null) {
                _freshSharedEscape = _h.escapeShare(this.mob.level().getGameTime()) != null;
            }
        }
        if (FollowPathing.isEscapeScanBlocked(this.mob) && !_freshSharedEscape) {
            this.lastBlockedAlphaX = this.alpha.getX();
            this.lastBlockedAlphaZ = this.alpha.getZ();
            this.lastBlockedGameTime = now;
            this.repathCooldown = FAILED_REPATH_BACKOFF;
            return;
        }
        // A member farther than ESCAPE_DISTANCE_MULTIPLIER x follow range from its alpha is
        // genuinely separated (pit/cliff/water) and cannot use the inner-ring/waypoint/hop/
        // nudge probes below — every one of those aims at the alpha's station, which is
        // unreachable from the separation, so they burn ~20 pathfinder queries that all fail
        // before the escape path is even reached (the lag spikes). Jump straight to the
        // escape path; it draws a full accessible path to the alpha if possible, else the
        // best reachable stand that closes the gap. Members WITHIN 2x follow range keep
        // using the normal chain: a member right beside the alpha with a Y-gap is just on
        // a hillside and the chain climbs it cheaply (the "escape on a hillside" bug).
        double _hDist2 = Math.hypot(this.mob.getX() - this.alpha.getX(), this.mob.getZ() - this.alpha.getZ());
        boolean _escapeMode = _hDist2 > follow * ESCAPE_DISTANCE_MULTIPLIER;
        if (_escapeMode) {
            Path escape = this.escapePath(stationXZ);
            if (escape != null) {
                this.lastBlockedGameTime = Long.MIN_VALUE;
                this.mob.getNavigation().moveTo(escape, speed);
                this.repathCooldown = NUDGE_REPATH_BACKOFF;
                return;
            }
            this.escapeTarget = null;
            this.lastBlockedAlphaX = this.alpha.getX();
            this.lastBlockedAlphaZ = this.alpha.getZ();
            this.lastBlockedGameTime = now;
            this.repathCooldown = FAILED_REPATH_BACKOFF;
            return;
        }
        // If the direct ring stand was rejected as out of the pathfinder's search
        // budget AND the member is farther out than the farthest inner probe, the
        // inner probes 2-4 would fail the same way — skip them (keep probe #1, the
        // nearest, which can succeed inside A*'s budget) and jump to the waypoint branch.
        boolean directNoCanReach = "no-canReach".equals(FollowPathing.lastRejectReason());
        double alphaDist = this.mob.distanceTo(this.alpha);
        // SURROUND ring stations can land in unreachable terrain (river, cliff,
        // ditch wall) even when the alpha itself stands on good land. Probe inward
        // from this member's own ring radius so the fallback keeps the surround look
        // when the exact ring stand is unreachable; only fall back to the alpha's
        // own column when every inner stand is also unreachable.
        if (settings != null && !this.wary && settings.followStyle() == FollowStyle.SURROUND) {
            double angle = com.charybdis180.ethological.util.Personality.angleRadians(this.mob.getUUID(), "follow_station");
            // Re-base the probe band on this member's personal station radius (the same
            // fraction stationOf() used), stepping inward so the herd still holds the ring.
            double stationFrac = FollowAlphaGoal.stationFraction(this.mob);
            double[] innerRadii = {follow * stationFrac * 1.0D, follow * stationFrac * 0.85D, follow * stationFrac * 0.7D, follow * 0.4D, follow * 0.25D};
            boolean skipInner = directNoCanReach && alphaDist > follow * 0.25D;
            for (int ri = 0; ri < innerRadii.length; ri++) {
                if (skipInner && ri > 0) {
                    break;
                }
                double radius = innerRadii[ri];
                double px = this.alpha.getX() + Math.cos(angle) * radius;
                double pz = this.alpha.getZ() + Math.sin(angle) * radius;
                BlockPos probe = new BlockPos(Mth.floor(px), Mth.floor(this.alpha.getY()), Mth.floor(pz));
                Path probePath = FollowPathing.pathToSurfaceStand(this.mob, probe, FollowPathing.MAX_STEP_Y);
                if (probePath != null) {
                    this.fallbackTarget = probe;
                    this.lastBlockedGameTime = Long.MIN_VALUE;
                    this.mob.getNavigation().moveTo(probePath, speed);
                    return;
                }
            }
            BlockPos alphaXZ = new BlockPos(Mth.floor(this.alpha.getX()), Mth.floor(this.alpha.getY()), Mth.floor(this.alpha.getZ()));
            Path alphaPath = FollowPathing.pathToSurfaceStand(this.mob, alphaXZ, FollowPathing.MAX_STEP_Y);
            if (alphaPath != null) {
                this.fallbackTarget = alphaXZ;
                this.lastBlockedGameTime = Long.MIN_VALUE;
                this.mob.getNavigation().moveTo(alphaPath, speed);
                return;
            }
        }
        if (this.mob.level() instanceof ServerLevel serverLevel && this.mob.hasData(HerdAttachments.HERD_DATA)) {
            HerdManager.Herd herd = HerdManager.get(this.mob.getData(HerdAttachments.HERD_DATA).herdId());
            BlockPos waypoint = herd != null ? HerdManager.routeWaypointAhead(serverLevel, herd, this.mob) : null;
            if (waypoint != null) {
                Path waypointPath = FollowPathing.pathToSurfaceStand(this.mob, waypoint, FollowPathing.MAX_STEP_Y);
                if (waypointPath != null) {
                    this.lastBlockedGameTime = Long.MIN_VALUE;
                    this.mob.getNavigation().moveTo(waypointPath, speed);
                    return;
                }
            }
        }
        Path hop = FollowPathing.hopToward(this.mob, stationXZ, FollowPathing.MAX_STEP_Y);
        if (hop != null) {
            this.lastBlockedGameTime = Long.MIN_VALUE;
            this.mob.getNavigation().moveTo(hop, speed);
            return;
        }
        // Last resort: short validated hops keep a barrier-blocked member making
        // forward progress (milling along the obstacle) instead of standing still
        // while the alpha pulls away. Space out re-attempts to keep the pathfinder cheap.
        Path nudge = FollowPathing.nudgeToward(this.mob, stationXZ, FollowPathing.MAX_STEP_Y);
        if (nudge != null) {
            this.lastBlockedGameTime = Long.MIN_VALUE;
            this.mob.getNavigation().moveTo(nudge, speed);
            this.repathCooldown = NUDGE_REPATH_BACKOFF;
            return;
        }
        // A member at the bottom of a pit/ravine can never reach rim-level stands — every
        // probe above fails "no-canReach" and it bobs forever while the herd pulls away.
        // Walk the floor to a climbable edge instead of standing still. Escapes commit to
        // a single stand and ignore the herd-follow leash, so a separated member can path
        // out to the herd level rather than only toward the alpha's ring.
        Path escape = this.escapePath(stationXZ);
        if (escape != null) {
            this.lastBlockedGameTime = Long.MIN_VALUE;
            this.mob.getNavigation().moveTo(escape, speed);
            this.repathCooldown = NUDGE_REPATH_BACKOFF;
            return;
        }
        this.escapeTarget = null;
        // Remember where the alpha stood so we skip this whole chain until it moves.
        this.lastBlockedAlphaX = this.alpha.getX();
        this.lastBlockedAlphaZ = this.alpha.getZ();
        this.lastBlockedGameTime = now;
        this.repathCooldown = FAILED_REPATH_BACKOFF;
    }

    /** Publish this member's validated route so herd-mates can trail the same path. */
    private void shareRoute(Path path) {
        if (!(this.mob.level() instanceof ServerLevel serverLevel) || !this.mob.hasData(HerdAttachments.HERD_DATA)) {
            return;
        }
        HerdManager.Herd herd = HerdManager.get(this.mob.getData(HerdAttachments.HERD_DATA).herdId());
        if (herd != null) {
            HerdManager.shareRoute(serverLevel, herd, path);
        }
    }

    /** Invalidate the herd's shared escape stand when this member proved it unusable, so
     * the rest of the herd stops following a route that fails (unreachable / didn't escape). */
    private void clearSharedEscape(BlockPos stand) {
        if (!(this.mob.level() instanceof ServerLevel serverLevel) || !this.mob.hasData(HerdAttachments.HERD_DATA)) {
            return;
        }
        HerdManager.Herd herd = HerdManager.get(this.mob.getData(HerdAttachments.HERD_DATA).herdId());
        if (herd == null) {
            return;
        }
        BlockPos shared = herd.escapeShare(serverLevel.getGameTime());
        if (shared != null && shared.equals(stand)) {
            herd.setEscapeShare(null, Long.MIN_VALUE);
        }
    }

    /**
     * Escape path for a member separated from the herd by a pit, cliff, flooded cave, or open
     * water that it cannot cross with normal follow fallbacks. Commits to {@link #escapeTarget}:
     * once one escape stand is picked, subsequent repaths keep walking that same stand until the
     * member reaches it (or it becomes unreachable), instead of re-scoring every direction and
     * flipping. When not separated, returns null and clears any stale escape target.
     */
    private Path escapePath(BlockPos stationXZ) {
        if (this.escapeTarget != null) {
            BlockPos stand = Homes.surfaceStand(this.mob.level(), this.escapeTarget.getX(), this.escapeTarget.getZ());
            if (stand != null && Math.abs(this.mob.blockPosition().getY() - stand.getY()) <= FollowPathing.MAX_STEP_Y
                    && Math.abs(this.mob.getX() - (this.escapeTarget.getX() + 0.5)) < 2.0
                    && Math.abs(this.mob.getZ() - (this.escapeTarget.getZ() + 0.5)) < 2.0) {
                // Reached the committed stand. If the member is STILL separated, this stand
                // did not actually escape it (e.g. a water-surface stand in the middle of a
                // pit that it keeps swimming to) — blacklist the column so the next scan
                // tries a different direction instead of re-picking it forever. Return null
                // so the caller applies the failure backoff before re-running the full scan.
                if (FollowPathing.isSeparated(this.mob)) {
                    FollowPathing.blacklistEscapeStand(this.mob, this.escapeTarget);
                    this.clearSharedEscape(this.escapeTarget);
                    this.escapeTarget = null;
                    return null;
                }
                this.escapeTarget = null;
            } else {
                Path committed = FollowPathing.pathToSurfaceStandWide(this.mob, this.escapeTarget, FollowPathing.MAX_STEP_Y);
                if (committed != null) {
                    return committed;
                }
                if (FollowPathing.wideBudgetExhausted()) {
                    // The wide-path budget was spent by other members this tick, NOT a proof
                    // that the committed stand became unreachable. Keep the escape target so
                    // this member resumes walking it on the next repath instead of blacklisting
                    // a good route and re-scanning from scratch.
                    return null;
                }
                // The committed path became unreachable — blacklist it so we stop hammering
                // the pathfinder for a direction that no longer works.
                FollowPathing.blacklistEscapeStand(this.mob, this.escapeTarget);
                this.clearSharedEscape(this.escapeTarget);
                this.escapeTarget = null;
            }
        }
        // Attempt A — full accessible path to the alpha's own surface stand. This is the
        // "draw a full accessible path if possible" step: when the alpha's terrain is
        // reachable at all, a single widened query to the alpha's column beats any probe
        // scan, and committing to it keeps the member walking straight at the herd instead
        // of oscillating between bridge stands. Committing also means later repaths re-path
        // to this same stand until the member arrives or it becomes unreachable.
        // A recent full escape scan found NOTHING and this mob has not moved — re-running
        // this widened query would fail the same way, so skip straight to the (cheap,
        // memoized) escapePlan below and let the caller's backoff gate the retry.
        if (this.alpha != null && !FollowPathing.isEscapeScanBlocked(this.mob)) {
            BlockPos alphaStand = Homes.surfaceStand(this.mob.level(),
                    this.alpha.blockPosition().getX(), this.alpha.blockPosition().getZ());
            if (alphaStand != null) {
                Path full = FollowPathing.pathToSurfaceStandWide(this.mob, alphaStand, FollowPathing.MAX_STEP_Y);
                if (full != null) {
                    this.escapeTarget = alphaStand;
                    return full;
                }
            }
        }
        // Attempt B — bridge scan: only when the alpha's own stand is unreachable (e.g. the
        // cave exit runs DEEPER than the herd surface, or a cliff blocks the direct route).
        // escapePlan probes the herd-shared stand first, then ring/bearing stands that close
        // the vertical gap to the herd's level (with the relaxed deep pass for cave exits).
        // It rate-limits concurrent scans and memoizes failures, so this stays bounded.
        Path escape = FollowPathing.escapePlan(this.mob, stationXZ, FollowPathing.MAX_STEP_Y);
        if (escape != null) {
            this.escapeTarget = FollowPathing.lastEscapeStand();
        } else {
            // Attempt C — truly impassable: escapePlan already recorded the failure memo, so
            // isEscapeScanBlocked is true and the caller backs off instead of re-scanning.
            // The member is treated as trapped (fenced-in terrain) and stops burning
            // pathfinder queries until it moves or the memo expires.
            this.escapeTarget = null;
        }
        return escape;
    }

    private boolean isSentinel() {
        if (!(this.mob.level() instanceof ServerLevel serverLevel) || !this.mob.hasData(HerdAttachments.HERD_DATA)) {
            return false;
        }
        HerdManager.Herd herd = HerdManager.get(this.mob.getData(HerdAttachments.HERD_DATA).herdId());
        if (herd == null || herd.alphaId == null) {
            return false;
        }
        List<UUID> adults = new ArrayList<>();
        for (UUID memberId : herd.members) {
            if (memberId.equals(herd.alphaId)) {
                continue;
            }
            Entity entity = serverLevel.getEntity(memberId);
            if (!(entity instanceof Animal mate) || !mate.isAlive() || mate.isBaby()) {
                continue;
            }
            adults.add(memberId);
        }
        if (adults.isEmpty()) {
            return false;
        }
        adults.sort(UUID::compareTo);
        long slot = Math.floorMod(
                serverLevel.getGameTime() / 600L + (long)herd.id.hashCode(),
                (long)adults.size());
        return adults.get((int)slot).equals(this.mob.getUUID());
    }
}

