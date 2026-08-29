package com.charybdis180.ethological.thirst.goal;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.config.EthologicalConfig;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.goal.FollowPathing;
import com.charybdis180.ethological.home.FenceDetection;
import com.charybdis180.ethological.home.HomeSettingsManager;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.home.NomadicMigration;
import com.charybdis180.ethological.thirst.SpeciesThirstSettings;
import com.charybdis180.ethological.thirst.Thirst;
import com.charybdis180.ethological.thirst.ThirstSettingsManager;
import com.charybdis180.ethological.thirst.WaterTargetData;
import it.unimi.dsi.fastutil.longs.LongSet;
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
    public static final int HOMELESS_WATER_RADIUS = 96;
    private static final int URGENT_WATER_RADIUS = 160;
    private static final int REACHABILITY_ATTEMPTS = 6;
    private static final int SIP_INTERVAL_TICKS = 15;
    /** Travel budget for an emergency march toward far water — long treks need minutes,
     *  while normal treks stay capped at the classic 600-tick limit. */
    private static final int EMERGENCY_TREK_TICKS = 3600;
    private final Animal mob;
    private BlockPos waterPos;
    private BlockPos shorePos;
    private int travelTicks;
    private int approachStuckTicks;
    private int stationaryTicks;
    private int repathCooldown;
    /** Set when the current target came from the emergency far scan; grants a longer travel budget. */
    private boolean emergencyTrek;
    /** True while running as the alpha herd relay (searching/leading for dehydrating mates);
     *  exempts this run from the nomadic travel-leg cutoff in canContinueToUse. */
    private boolean alphaRelayRun;
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
    /** Game time of the last emergency far-water scan for this mob (per-mob throttle). */
    private long nextEmergencySearchGameTime;
    private static final long EMERGENCY_RESCAN_INTERVAL = 200L;
    /** Far-scan candidates the emergency commitment tries, nearest-first, before giving up. */
    private static final int EMERGENCY_CANDIDATES = 5;
    /** Max non-urgent herd members that may hold a WATER_TARGET on the herd's shared source
     *  at once. Extra drink-due members wait in a salted queue and depart as lanes free up,
     *  so a big penned herd drinks in waves instead of shoving at one bank. Urgent animals
     *  and the alpha relay always bypass — dehydration outranks etiquette. */
    private static final int DRINK_CONCURRENCY_LIMIT = 3;
    /** Throttle for the alpha's "is any mate dehydrating?" relay scan. */
    private long nextMateScanGameTime;
    private static final long MATE_SCAN_INTERVAL = 100L;
    /** Cached verdict of the last relay scan so the throttle is meaningful. */
    private boolean herdHasDehydratingMates;
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
    // Pen interior flood region (null when not fenced in): while non-null every adopted
    // or committed water target must sit inside it, so penned animals stop staring at
    // ponds across the fence. Resolved once per search.
    private LongSet penRegion;
    private int penRefY;

    public DrinkWaterGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private SpeciesThirstSettings settings() {
        return ThirstSettingsManager.get(this.mob.getType()).orElse(null);
    }

    /**
     * Throttled herd relay check: is any loaded mate dehydrating? The alpha engages drink
     * mode on a hit so IT runs the emergency search and leads everyone to water, keeping
     * the herd together instead of one member breaking off alone.
     */
    private boolean isAlphaWithDehydratingMate(long now) {
        if (now < this.nextMateScanGameTime) {
            return this.herdHasDehydratingMates;
        }
        this.nextMateScanGameTime = now + MATE_SCAN_INTERVAL;
        this.herdHasDehydratingMates = HerdManager.hasDehydratingMate(this.mob);
        return this.herdHasDehydratingMates;
    }

    public boolean canUse() {
        SpeciesThirstSettings settings = this.settings();
        if (settings == null || !Thirst.hasThirstData(this.mob)) {
            return false;
        }
        if (this.mob.getData(ModAttachments.SLEEPING) || this.mob.hasData(ModAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        long gameTime = this.mob.level().getGameTime();
        // Herd relay: when a mate is dehydrating, the alpha engages drink mode even if it is
        // not thirsty itself — it runs the emergency far search and LEADS the herd to water
        // (its share is what mates adopt), instead of one desperate member breaking off alone.
        boolean urgent = Thirst.isUrgentlyThirsty(this.mob);
        boolean alphaRelay = !urgent && this.isAlphaWithDehydratingMate(gameTime);
        // Travel-leg gate: ordinary needs yield to an active migration march — but the
        // alpha relay MUST bypass it, otherwise a marching alpha can never start the
        // search-and-lead while its mates dehydrate behind it.
        if (NomadicMigration.blocksNeedsGoals(this.mob) && !urgent && !alphaRelay) {
            return false;
        }
        if (!urgent && !alphaRelay) {
            if (!Thirst.isDrinkDue(this.mob, settings)) {
                return false;
            }
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
        // Drink-lane queue: when the herd's shared water already has its quota of active
        // drinkers, hold this member back with a short salted recheck instead of letting it
        // join the crush at the bank. Only gates non-urgent members heading for the SHARED
        // source — urgent animals, alpha relays, and herds without a fresh share are unaffected.
        HerdManager.Herd queueHerd = HerdManager.herdOf(this.mob);
        BlockPos queuedShare = queueHerd != null ? queueHerd.waterShare(gameTime) : null;
        if (!urgent && !alphaRelay && queuedShare != null
                && HerdManager.activeDrinkersOnWater(this.mob, queuedShare) >= DRINK_CONCURRENCY_LIMIT) {
            this.nextSearchGameTime = gameTime
                    + com.charybdis180.ethological.util.Personality.salt(this.mob.getUUID(), "drink_lane", 40);
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
            } else if (Thirst.isUrgentlyThirsty(this.mob)) {
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
        // Pen gate first: a fenced-in animal only ever adopts water inside its enclosure.
        this.penRegion = FenceDetection.pennedRegionOf(this.mob);
        this.penRefY = this.mob.blockPosition().getY();
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
        boolean urgent = Thirst.isUrgentlyThirsty(this.mob);
        boolean found = false;

        // The alpha searches for the whole herd; a non-alpha member only scans on its own when
        // it is dehydrating (urgent) or when no alpha is loaded to search. This turns a morning
        // herd's water search from N full spiral scans into 1 (the alpha's) + per-member cheap
        // shore/path adoption.
        Optional<Animal> alphaOpt = Homes.herdAlpha(this.mob);
        boolean isAlpha = alphaOpt.isPresent() && alphaOpt.get() == this.mob;
        boolean herdHasLoadedAlpha = alphaOpt.isPresent();
        // "Actually leading" check: the alpha only leads a drink march while its own goal
        // holds a WATER_TARGET (cleared by stop()). A loaded-but-migrating/idle alpha runs
        // no search, so deferring to it left dehydrating followers searching nothing.
        boolean alphaLeading = herdHasLoadedAlpha
                && ((Animal)alphaOpt.get()).hasData(ModAttachments.WATER_TARGET);
        // Herd relay engaged for this member: dehydrating follower whose alpha is genuinely
        // leading. It follows the alpha's lead instead of breaking off on its own trek.
        boolean relayEngaged = urgent && !isAlpha && alphaLeading;

        // 1) Adopt the herd's shared water when a mate (usually the alpha) found it within the
        //    TTL window: one shore check + one reachability path instead of a full ring scan.
        //    Relay-engaged followers adopt the share at ANY distance — the goal is keeping the
        //    herd together on the march, so a far share commits best-effort (no strict
        //    reachability gate) and they walk with the alpha toward it.
        if (herd != null) {
            BlockPos shared = herd.waterShare(now);
            boolean shareInRange = shared != null && shared.distSqr(center) <= (double)radius * (double)radius;
            if (shared != null && (shareInRange || relayEngaged)) {
                found = this.tryAdoptWater(shared, shoreClaims)
                        || relayEngaged && this.commitEmergencyTrek(shared);
            }
        }
        if (found) {
            return true;
        }
        // 2) Non-alpha members defer the full scan to the alpha ONLY while the alpha is
        //    genuinely leading a drink march (holds a WATER_TARGET). A loaded-but-idle or
        //    migrating alpha runs no search and sets no share, so deferring to it left
        //    dehydrating followers standing around doing follow_alpha forever — each now
        //    falls through to its own scan below. Defer ONLY when there is no in-range
        //    share to adopt: if the alpha already found water that is in range but this
        //    member could not adopt it (shore unreachable, e.g. it sits in a pit), the
        //    alpha's water is not for this member — run its own scan instead.
        //    Relay-engaged followers skip their own scans entirely while the alpha leads.
        if (herd != null && !isAlpha && alphaLeading && (!urgent || relayEngaged)) {
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
            if (!found && Thirst.isUrgentlyThirsty(this.mob)) {
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
        // 3a) Emergency: no adoptable water anywhere in the dense scan. The alpha (or any
        //      animal with no loaded alpha) runs the far scan — it commits, shares the
        //      source with the herd below, and LEADS everyone there. A dehydrating
        //      follower only holds off while its alpha is actually leading (holding a
        //      WATER_TARGET); an idle/migrating alpha means every dehydrating member
        //      searches for itself instead of waiting on a march that never starts.
        if (!found && urgent && (!herdHasLoadedAlpha || isAlpha || !alphaLeading)) {
            long gameTime2 = this.mob.level().getGameTime();
            if (gameTime2 >= this.nextEmergencySearchGameTime) {
                this.nextEmergencySearchGameTime = gameTime2 + EMERGENCY_RESCAN_INTERVAL;
                List<BlockPos> distant = Homes.findDistantWater(this.mob.level(), center, EMERGENCY_CANDIDATES);
                if (!distant.isEmpty() && this.commitNavigableTrek(distant)) {
                    found = true;
                } else {
                    // Either the far world is genuinely dry or every candidate is unroutable
                    // (cliff-walled water, island across open water). Back off like a failed
                    // search; a terrain change re-triggers it via the normal backoff cadence.
                    ++this.failedSearches;
                    this.nextSearchGameTime = Math.max(this.nextSearchGameTime, gameTime2 + 600L);
                }
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
        // Penned animals never adopt water outside the fence, even when the herd share
        // points there (an alpha outside the pen is not leading them anywhere reachable).
        if (FenceDetection.excludes(this.mob.level(), this.penRegion, surface, this.penRefY)) {
            return false;
        }
        Optional<BlockPos> shoreOpt = this.resolveShore(surface, shoreClaims);
        if (shoreOpt.isEmpty() || FenceDetection.excludes(this.mob.level(), this.penRegion, shoreOpt.get(), this.penRefY)) {
            return false;
        }
        if (!Homes.isStrictlyReachable(this.mob, shoreOpt.get())) {
            return false;
        }
        this.waterPos = surface;
        this.shorePos = shoreOpt.get();
        this.mob.setData(ModAttachments.WATER_TARGET, new WaterTargetData(this.waterPos, this.shorePos));
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

    /**
     * Emergency adoption of distant water, navigation-aware. Tries the scan's candidates
     * nearest-first and commits to the FIRST one the pathfinder can actually route to (shore
     * stand first; the water surface itself is accepted only when already in/near it). This is
     * what keeps an alpha on a cliff from committing to a pond at its feet: an unroutable
     * target produces no path, so it is skipped for the next candidate instead of freezing
     * the trek at the cliff edge.
     */
    private boolean commitEmergencyTrek(BlockPos surface) {
        // Same pen rule as tryAdoptWater: a relay march must not drag penned members
        // toward water they can never reach across the fence.
        if (FenceDetection.excludes(this.mob.level(), this.penRegion, surface, this.penRefY)) {
            return false;
        }
        Set<BlockPos> shoreClaims = HerdManager.drinkStandsOfHerdMates(this.mob);
        this.waterPos = surface;
        this.shorePos = this.resolveShore(surface, shoreClaims).orElse(surface);
        if (FenceDetection.excludes(this.mob.level(), this.penRegion, this.shorePos, this.penRefY)) {
            return false;
        }
        this.emergencyTrek = true;
        this.mob.setData(ModAttachments.WATER_TARGET, new WaterTargetData(this.waterPos, this.shorePos));
        return true;
    }

    /**
     * Emergency adoption of distant water, navigation-aware, with a TIERED proof so far
     * water can actually be committed to at night. Near stands (within
     * {@link FollowPathing#FULL_ROUTE_PROOF_RANGE_SQR}) keep the strict full-route check via
     * {@link Homes#isStrictlyReachable} — that is what keeps an alpha on a cliff from committing
     * to a pond at its feet. Far stands only require the first leg toward them to be routable
     * ({@link FollowPathing#firstLegRoutable}): demanding a full-route proof at 100+ blocks
     * rejects nearly every real destination (the pathfinder's node budget gives up long before
     * the target), which left dehydrating members standing still all night. The march re-resolves
     * shore/leg as terrain loads ({@link #refreshShore}) and the travel budget is minutes-long,
     * so a leg that later proves blocked aborts and re-scans instead of freezing in place.
     */
    private boolean commitNavigableTrek(List<BlockPos> candidates) {
        Set<BlockPos> shoreClaims = HerdManager.drinkStandsOfHerdMates(this.mob);
        for (BlockPos candidate : candidates) {
            // Far-scan candidates outside the pen are dead ends for penned animals.
            if (FenceDetection.excludes(this.mob.level(), this.penRegion, candidate, this.penRefY)) {
                continue;
            }
            Optional<BlockPos> shoreOpt = this.resolveShore(candidate, shoreClaims);
            BlockPos stand = shoreOpt.orElse(null);
            if (stand != null && FenceDetection.excludes(this.mob.level(), this.penRegion, stand, this.penRefY)) {
                continue;
            }
            // In-water animals may commit to the surface itself; everyone else needs a dry stand.
            if (stand == null && !this.mob.isInWaterOrBubble()) {
                continue;
            }
            double distSqr = this.mob.distanceToSqr(stand != null
                    ? Vec3.atCenterOf(stand)
                    : Vec3.atCenterOf(candidate));
            boolean ok = stand == null || FollowPathing.firstLegRoutable(this.mob, stand, distSqr);
            if (!ok) {
                continue;
            }
            this.waterPos = candidate;
            this.shorePos = stand != null ? stand : candidate;
            this.emergencyTrek = true;
            this.mob.setData(ModAttachments.WATER_TARGET, new WaterTargetData(this.waterPos, this.shorePos));
            return true;
        }
        return false;
    }

    public boolean canContinueToUse() {
        // Time-box need goals to the pause window so a failing drink target cannot
        // hold a migrating herd still through the travel leg. A nomadic animal that
        // is ALREADY at the water and lapping is allowed to finish the bout to full
        // (otherwise the travel-leg cutoff aborts the drink the moment thirst climbs
        // past the urgent threshold, leaving it ~30% full). An alpha-relay run bypasses:
        // the march must not cancel the very search-and-lead that will bring the herd
        // back to water.
        if (NomadicMigration.blocksNeedsGoals(this.mob)
                && !Thirst.isUrgentlyThirsty(this.mob)
                && !this.alphaRelayRun
                && !this.isAtDrinkPosition()) {
            return false;
        }
        return !this.finished && this.settings() != null && (Boolean)this.mob.getData(ModAttachments.SLEEPING) == false && !this.mob.hasData(ModAttachments.SLEEP_DISTURBANCE);
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
        this.emergencyTrek = false;
        this.alphaRelayRun = !Thirst.isUrgentlyThirsty(this.mob)
                && this.isAlphaWithDehydratingMate(this.mob.level().getGameTime());
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
        Vec3 waterCenter = Vec3.atCenterOf(this.waterPos);
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
        if (++this.travelTicks > (this.emergencyTrek ? EMERGENCY_TREK_TICKS : 600) || this.approachStuckTicks > 100) {
            this.finished = true;
            return;
        }
        BlockPos target = this.mob.isInWaterOrBubble() ? this.waterPos : this.shorePos;
        Vec3 targetCenter = Vec3.atCenterOf(target);
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
        int maxThirst = Thirst.getMaxThirst(this.mob);
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
                serverLevel.sendParticles(ParticleTypes.SPLASH, this.mob.getX(), this.mob.getY() + 0.35, this.mob.getZ(), 5, 0.15, 0.1, 0.15, 0.0);
            }
            // Each sip restores a share of the tank. Keep lapping until it is full.
            if (Thirst.getThirst(this.mob) < maxThirst) {
                int sipAmount = Math.max(1, maxThirst / Math.max(1, total / SIP_INTERVAL_TICKS));
                Thirst.drinkSip(this.mob, sipAmount);
            }
        }
        ++this.elapsedDrinkTicks;
        // The drink is over once the tank is full (the animation/sound above already
        // looped through the whole bout). The cap only guards a pathological stall.
        if (Thirst.getThirst(this.mob) >= maxThirst) {
            Thirst.drink(this.mob);
            this.finished = true;
            return;
        }
        if (this.elapsedDrinkTicks > Math.max(600, total * 10)) {
            this.finished = true;
        }
    }

    private void pulseHeadDip() {
        long until = this.mob.level().getGameTime() + 40L;
        this.mob.setData(ModAttachments.HEAD_DIP_UNTIL,until);
        Animal animal = this.mob;
        if (animal instanceof Sheep) {
            Sheep sheep = (Sheep)animal;
            this.mob.level().broadcastEntityEvent((Entity)sheep, (byte)10);
        }
    }

    /** Preferred shore stand near a water surface: herd-claimed stands first, any stand second. */
    private Optional<BlockPos> resolveShore(BlockPos surface, Set<BlockPos> shoreClaims) {
        return Homes.findShoreStandNearWater(this.mob.level(), surface, this.mob.blockPosition(), shoreClaims)
                .or(() -> Homes.findShoreStandNearWater(this.mob.level(), surface, this.mob.blockPosition(), Set.of()));
    }

    private void refreshShore() {
        // Emergency trek: the stand we committed may have been the raw water surface (no dry
        // shore found at scan distance). As terrain becomes visible up close, re-resolve a real
        // dry stand; fall back to re-running the far scan if this water also proves unreachable.
        Set<BlockPos> shoreClaims = HerdManager.drinkStandsOfHerdMates(this.mob);
        Optional<BlockPos> shoreOpt = this.resolveShore(this.waterPos, shoreClaims);
        if (shoreOpt.isEmpty() || FenceDetection.excludes(this.mob.level(), this.penRegion, shoreOpt.get(), this.penRefY)) {
            if (this.emergencyTrek) {
                List<BlockPos> distant = new ArrayList<>(Homes.findDistantWater(this.mob.level(), this.mob.blockPosition(), EMERGENCY_CANDIDATES));
                distant.removeIf(p -> p.equals(this.waterPos));
                if (!distant.isEmpty() && this.commitNavigableTrek(distant)) {
                    return;
                }
            }
            this.finished = true;
            return;
        }
        this.shorePos = shoreOpt.get();
        this.mob.setData(ModAttachments.WATER_TARGET, new WaterTargetData(this.waterPos, this.shorePos));
    }

    private void pathToDrinkSpot() {
        BlockPos target = this.mob.isInWaterOrBubble() ? this.waterPos : this.shorePos;
        BlockPos anchored = target == null ? null : Homes.surfaceStandForMove(this.mob.level(), target, this.mob);
        if (anchored == null) {
            // Keep the raw aim when the column has no usable stand (should not happen for
            // reachable shore/water, but never strand the drinker mid-water).
            if (target != null) {
                anchored = Homes.offsetStandFromCorners(this.mob.level(), target);
            }
        } else {
            anchored = Homes.offsetStandFromCorners(this.mob.level(), anchored);
        }
        if (anchored != null) {
            this.mob.getNavigation().moveTo((double)anchored.getX() + 0.5, (double)anchored.getY(), (double)anchored.getZ() + 0.5, 1.1);
        } else {
            this.mob.getNavigation().stop();
        }
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
        Vec3 toWater = Vec3.atCenterOf(this.waterPos).subtract(this.mob.position());
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
        this.mob.removeData(ModAttachments.WATER_TARGET);
        this.startedEffects = false;
        this.approachStuckTicks = 0;
        this.stationaryTicks = 0;
        this.repathCooldown = 0;
        this.lastPos = null;
        this.emergencyTrek = false;
        this.alphaRelayRun = false;
    }
}

