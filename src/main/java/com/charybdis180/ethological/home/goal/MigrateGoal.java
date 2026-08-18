package com.charybdis180.ethological.home.goal;

import com.charybdis180.ethological.avoidance.Avoidance;
import com.charybdis180.ethological.avoidance.CliffAvoidance;
import com.charybdis180.ethological.avoidance.CrowdGrid;
import com.charybdis180.ethological.herd.HerdAttachments;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.HerdSettingsManager;
import com.charybdis180.ethological.herd.SpeciesHerdSettings;
import com.charybdis180.ethological.herd.goal.FollowPathing;
import com.charybdis180.ethological.home.FenceDetection;
import com.charybdis180.ethological.home.HomeAttachments;
import com.charybdis180.ethological.home.HomeSettingsManager;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.home.MigrationPathPlanner;
import com.charybdis180.ethological.home.NomadicMigration;
import com.charybdis180.ethological.home.SpeciesHomeSettings;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Directional wandering when the animal has no home. Nomadic species march in
 * travel legs ({@link NomadicMigration#LEG_DISTANCE_MIN}–{@link NomadicMigration#LEG_DISTANCE_MAX}
 * blocks), pause briefly so eat/drink can run, then continue.
 */
public class MigrateGoal extends Goal {
    private static final double[] HOP_DISTANCES = {20.0D, 16.0D, 12.0D, 10.0D, 8.0D, 6.0D};
    /**
     * Probe offsets from the preferred migration heading. Index 0 (current) is
     * always tried first; turns are only considered after preferred failures.
     */
    private static final double[] HEADING_PROBE_OFFSETS = {
            0.0D,
            Math.PI / 2.0D,
            -Math.PI / 2.0D,
            Math.PI,
            Math.PI / 4.0D,
            -Math.PI / 4.0D
    };
    private static final double CORRIDOR_SCOUT_RANGE = 24.0D;
    private static final double HEADING_JITTER = 0.25D;
    private static final int RANDOM_FALLBACKS = 6;
    private static final int REPATH_TICKS = 10;
    /** Backoff after every heading/hop/random fallback fails, so a truly blocked
     * alpha (e.g. standing in a pit whose walls exceed its step height) does not
     * hammer the pathfinder every REPATH_TICKS. */
    private static final int FAILED_REPATH_BACKOFF = 80;
    private static final int PATH_MAX_STEP_Y = 4;
    private static final double PATH_MIN_HORIZONTAL = 3.0D;
    private static final int STATIONARY_TICKS = 40;
    private static final double STATIONARY_MOVE_SQR = 0.04D;
    /** Require this many failed preferred-heading repaths before accepting a turn. */
    private static final int PREFERRED_FAILURES_BEFORE_TURN = 3;
    /** Largest heading change committed at once without a sustained stuck-block. 120 degrees. */
    private static final double MAX_TURN_ANGLE = Math.PI * 2.0D / 3.0D;
    /** Extra thrust while wading/swimming: water drag halves a cow's already-low walk speed. */
    private static final double WATER_SPEED_MULT = 1.75D;
    /** Re-path cadence while in water; the current path is re-used between probes. */
    private static final int WATER_REPATH_TICKS = 15;
    /** The vanilla navigation caps A* at a 16-block FOLLOW_RANGE / 256-node budget,
     * which cannot route out of a deep pit (rim stands 8-21 blocks above the mob).
     * Surface/escape probes widen the search and node budget for a single query. */
    private static final int ESCAPE_FOLLOW_RANGE = 48;
    private static final float ESCAPE_NODE_MULTIPLIER = 3.0F;
    /** Max ticks a migrating alpha holds in place waiting for trailing members.
     * Bounded so a genuinely stuck member cannot freeze the herd indefinitely. */
    private static final long HERD_HOLD_MAX_TICKS = 1200L; // 60s
    /** Hold only once a member is beyond this multiple of the species follow range,
     * matching {@link FollowAlphaGoal#ESCAPE_DISTANCE_MULTIPLIER} so the member's own
     * escape pathing and the alpha's wait agree on the same 2x boundary. */
    private static final double HOLD_TRIGGER_MULT = 2.0;
    /** Resume marching once trailing drops below this multiple (hysteresis). */
    private static final double HOLD_RELEASE_MULT = 1.5;

    private final Animal mob;
    private int repathCooldown;
    private int preferredFailures;
    private boolean escapingWater;
    private int stationaryTicks;
    private int waterRepathCooldown;
    private Vec3 lastPos;
    private boolean fencedIn;
    private long nextFenceCheckGameTime;
    private Vec3 legStart;
    private double legTargetDistance;
    private boolean smartPlanSelected;
    private boolean hardStuck;
    /** Game time until the alpha may resume marching after waiting for the herd;
     * Long.MIN_VALUE when no hold is armed. */
    private long herdHoldUntil = Long.MIN_VALUE;

    public MigrateGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        long now = this.mob.level().getGameTime();
        // Keep an active needs pause alive while the herd still wants food/drink, and
        // end it (with a march window) once the hold cap is reached so a foodless
        // region is searched instead of pinning the herd. Runs every tick because
        // MigrateGoal is not running while paused.
        NomadicMigration.refreshNeedsPause(this.mob, now);
        if (HomeSettingsManager.get(this.mob.getType()).isEmpty()) {
            return false;
        }
        if (this.mob.isBaby() && this.mob.hasData(HerdAttachments.MOTHER)) {
            return false;
        }
        // Only the herd alpha migrates; members trail it via FollowAlphaGoal. This
        // also stops members from self-migrating past the alpha (the member oscillation).
        if (NomadicMigration.scheduleOwner(this.mob) != this.mob) {
            return false;
        }
        if (Homes.effectiveHome(this.mob).isPresent()) {
            return false;
        }
        if (this.mob.getData(SleepAttachments.SLEEPING) || this.mob.hasData(SleepAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        if (NomadicMigration.isPaused(this.mob)) {
            return false;
        }
        // A starving/thirsty alpha must stop to eat/drink instead of starting a new
        // leg: EatFoodGoal (priority 5) is locked out while MigrateGoal holds MOVE,
        // so yield here and let the needs pause hold until the herd is fed. The
        // post-cap march window suppresses this so a foodless area gets searched —
        // but only while the herd cannot actually satisfy the need where it stands:
        // the moment reachable food is available, a starving alpha breaks the march.
        boolean urgentYield = NomadicMigration.urgentNeedsYield(this.mob);
        boolean marchWin = NomadicMigration.isInNeedsMarchWindow(this.mob, now);
        boolean foodNow = NomadicMigration.canSatisfyUrgentFoodNow(this.mob);
        if (urgentYield && (!marchWin || foodNow)) {
            NomadicMigration.beginNeedsPause(this.mob);
            return false;
        }
        // A natural ditch/basin trips the fence flood-fill (any 2-solid-block column
        // reads as an enclosure) even though a cow can climb out. Nomadic species are
        // wild wanderers, so let them keep attempting to migrate; only real pens stop them.
        if (this.isFencedIn() && !this.isNomadic()) {
            return false;
        }
        return SleepSettingsManager.get(this.mob.getType())
                .map(s -> !s.isSleepTime(this.mob.level().getDayTime()))
                .orElse(true);
    }

    @Override
    public boolean canContinueToUse() {
        long now = this.mob.level().getGameTime();
        if (Homes.effectiveHome(this.mob).isPresent()
                || this.mob.getData(SleepAttachments.SLEEPING)
                || this.mob.hasData(SleepAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        if (this.mob.isBaby() && this.mob.hasData(HerdAttachments.MOTHER)) {
            return false;
        }
        if (NomadicMigration.scheduleOwner(this.mob) != this.mob) {
            return false;
        }
        // A starving/thirsty alpha stops marching mid-leg: release MOVE so
        // EatFoodGoal/DrinkWaterGoal can run, and hold the herd in a needs pause
        // until fed (capped, then the herd resumes searching fresh ground). The
        // march window only suppresses this while food is genuinely absent nearby.
        boolean urgentYield = NomadicMigration.urgentNeedsYield(this.mob);
        boolean marchWin = NomadicMigration.isInNeedsMarchWindow(this.mob, now);
        boolean foodNow = NomadicMigration.canSatisfyUrgentFoodNow(this.mob);
        if (urgentYield && (!marchWin || foodNow)) {
            NomadicMigration.beginNeedsPause(this.mob);
            return false;
        }
        if (NomadicMigration.isPaused(this.mob)) {
            return false;
        }
        if (this.isFencedIn() && !this.isNomadic()) {
            return false;
        }
        return SleepSettingsManager.get(this.mob.getType())
                .map(s -> !s.isSleepTime(this.mob.level().getDayTime()))
                .orElse(true);
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
        this.preferredFailures = 0;
        this.escapingWater = false;
        this.stationaryTicks = 0;
        this.lastPos = this.mob.position();
        this.smartPlanSelected = false;
        this.hardStuck = false;
        this.beginLeg();
        this.moveAhead();
    }

    @Override
    public void tick() {
        if (this.mob.isInWaterOrBubble()) {
            if (!this.escapingWater) {
                this.escapingWater = true;
            }
            this.repathCooldown = REPATH_TICKS;
            this.stationaryTicks = 0;
            this.preferredFailures = PREFERRED_FAILURES_BEFORE_TURN;
            this.lastPos = this.mob.position();
            if (this.waterRepathCooldown > 0) {
                --this.waterRepathCooldown;
            } else if (!this.mob.getNavigation().isDone()) {
                // Still has a valid escape path in progress: don't fire the whole
                // heading/hop/random chain again mid-route. Just wait a short beat.
                this.waterRepathCooldown = 4;
            } else {
                // Stagger the water repath per animal so several water-crossers do not
                // fire their escape chains on the same tick.
                this.waterRepathCooldown = com.charybdis180.ethological.util.Personality.bounded(
                        this.mob.getUUID(), "water_repath", WATER_REPATH_TICKS, WATER_REPATH_TICKS + 7);
                this.escapeWater();
            }
            return;
        }
        this.escapingWater = false;


        // Hold the alpha for a herd that stretched past 2x follow range: stop navigation
        // so trailing members (already escape-pathing back via FollowAlphaGoal) get time
        // to rejoin before the alpha resumes marching the leg.
        if (this.holdForHerd()) {
            return;
        }

        if (this.legStart != null && this.horizontalLegDistance() >= this.legTargetDistance) {
            if (this.isHeadingOwner()) {
                this.finishLeg();
            }
            return;
        }

        Vec3 now = this.mob.position();
        boolean barelyMoved = this.lastPos != null && now.distanceToSqr(this.lastPos) < STATIONARY_MOVE_SQR;
        this.lastPos = now;
        if (barelyMoved) {
            if (++this.stationaryTicks >= STATIONARY_TICKS) {
                this.stationaryTicks = 0;
                this.hardStuck = true;
                this.repathCooldown = REPATH_TICKS;
                // Stuck long enough: allow alternate headings if preferred still fails.
                this.preferredFailures = PREFERRED_FAILURES_BEFORE_TURN;
                this.smartPlanSelected = false;
                this.moveAhead();
                return;
            }
        } else {
            this.stationaryTicks = 0;
        }

        if (this.repathCooldown > 0) {
            --this.repathCooldown;
        } else if (this.mob.getNavigation().isDone()) {
            this.repathCooldown = REPATH_TICKS;
            this.moveAhead();
        }
    }

    /**
     * Wait for a stretched herd: hold the alpha in place while the farthest loaded member
     * is beyond {@link #HOLD_TRIGGER_MULT}x the species follow range. Only loaded members
     * count (unloaded members cannot path), and the wait is bounded by
     * {@link #HERD_HOLD_MAX_TICKS} so a genuinely stuck member cannot freeze the herd.
     */
    private boolean holdForHerd() {
        if (!(this.mob.level() instanceof ServerLevel serverLevel)
                || !this.mob.hasData(HerdAttachments.HERD_DATA)) {
            this.herdHoldUntil = Long.MIN_VALUE;
            return false;
        }
        HerdManager.Herd herd = HerdManager.get(this.mob.getData(HerdAttachments.HERD_DATA).herdId());
        double leash = HerdSettingsManager.get(this.mob.getType())
                .map(SpeciesHerdSettings::followDistance)
                .orElse(10.0);
        // A member that is genuinely separated (different surface level, no walkable
        // route back to the herd) cannot rejoin by walking — it must use the escape
        // plan. A member whose full escape scan found NO reachable stand (confirmed by
        // the escape-failure memo) has no route back at all — it is treated as trapped
        // and separated from the herd. Holding the alpha for either only freezes the
        // whole herd while the member makes no progress, so truly stuck members are
        // excluded from the hold trigger.
        double trailing = 0.0;
        int separatedSkipped = 0;
        if (herd != null) {
            for (UUID memberId : herd.members) {
                if (memberId.equals(this.mob.getUUID())) {
                    continue;
                }
                Entity member = serverLevel.getEntity(memberId);
                if (!(member instanceof PathfinderMob pf)) {
                    continue;
                }
                if (FollowPathing.isSeparated(pf) || FollowPathing.isEscapeScanBlocked(pf)) {
                    separatedSkipped++;
                    continue;
                }
                trailing = Math.max(trailing, member.distanceTo(this.mob));
            }
        }
        long now = serverLevel.getGameTime();
        if (trailing > leash * HOLD_TRIGGER_MULT) {
            if (this.herdHoldUntil == Long.MIN_VALUE) {
                this.herdHoldUntil = now + HERD_HOLD_MAX_TICKS;
            }
            if (now < this.herdHoldUntil) {
                this.mob.getNavigation().stop();
                return true;
            }
        } else if (trailing < leash * HOLD_RELEASE_MULT) {
            this.herdHoldUntil = Long.MIN_VALUE;
        }
        return false;
    }

    @Override
    public void stop() {
        this.escapingWater = false;
        this.stationaryTicks = 0;
        this.preferredFailures = 0;
        this.lastPos = null;
        this.legStart = null;
        this.smartPlanSelected = false;
        this.hardStuck = false;
        this.herdHoldUntil = Long.MIN_VALUE;
        this.mob.getNavigation().stop();
    }

    private void beginLeg() {
        NomadicMigration.beginTravelLeg(this.mob);
        NomadicMigration.ensureHeading(this.mob);
        this.legStart = this.mob.position();
        int span = NomadicMigration.LEG_DISTANCE_MAX - NomadicMigration.LEG_DISTANCE_MIN + 1;
        this.legTargetDistance = NomadicMigration.LEG_DISTANCE_MIN + this.mob.getRandom().nextInt(span);
        this.preferredFailures = 0;
        this.smartPlanSelected = false;
        this.hardStuck = false;
    }

    private void finishLeg() {
        this.mob.getNavigation().stop();
        this.legStart = null;
        Animal owner = NomadicMigration.scheduleOwner(this.mob);
        long now = this.mob.level().getGameTime();
        // During the post-cap march window the herd is searching fresh ground, so a
        // finished leg must not re-enter a needs pause (refreshNeedsPause is also
        // suppressed in-window); just refocus briefly and chain the next leg.
        boolean marchWindow = NomadicMigration.isInNeedsMarchWindow(owner, now);
        boolean pauseForNeeds = !marchWindow && NomadicMigration.shouldPauseForNeeds(owner);
        int pause;
        if (pauseForNeeds) {
            // Needs pause: refreshNeedsPause (run each tick from canUse) extends it
            // while the herd still wants food/drink, so the meal is not cut off the
            // moment this initial window expires. Capped so a foodless region resumes.
            NomadicMigration.beginNeedsPause(this.mob);
            pause = NomadicMigration.NEEDS_PAUSE_SEGMENT;
        } else {
            int span = NomadicMigration.REFOCUS_TICKS_MAX - NomadicMigration.REFOCUS_TICKS_MIN + 1;
            pause = NomadicMigration.REFOCUS_TICKS_MIN + this.mob.getRandom().nextInt(span);
        }
        NomadicMigration.beginPause(this.mob, pause);
    }

    private double horizontalLegDistance() {
        if (this.legStart == null) {
            return 0.0D;
        }
        Vec3 now = this.mob.position();
        double dx = now.x - this.legStart.x;
        double dz = now.z - this.legStart.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void moveAhead() {
        double speed = this.migrationSpeed();
        double preferred = this.readHeading();

        // The alpha picks one terrain-aware route for the leg. Herd members
        // continue to follow the alpha and never run the expensive planner.
        if (this.isHeadingOwner()
                && NomadicMigration.isNomadicHomeless(this.mob)
                && !this.smartPlanSelected) {
            MigrationPathPlanner.Plan plan = MigrationPathPlanner.findBest(this.mob, preferred);
            this.smartPlanSelected = true;
            if (plan != null && this.tryCommitHeading(plan.heading(), speed, plan.path())) {
                this.preferredFailures = 0;
                return;
            }
        }

        // 1) Always recheck the current migration heading before rotating.
        double jittered = preferred;
        if (this.isHeadingOwner() && !this.smartPlanSelected) {
            jittered += (this.mob.getRandom().nextDouble() - 0.5D) * HEADING_JITTER * 2.0D;
        }
        Path path = this.findPathOnHeading(jittered);
        double usedHeading = jittered;
        if (path == null && Math.abs(jittered - preferred) > 1.0E-4D) {
            path = this.findPathOnHeading(preferred);
            usedHeading = preferred;
        }
        if (path != null) {
            this.preferredFailures = 0;
            if (this.isHeadingOwner()) {
                this.writeHeading(usedHeading);
            }
            this.mob.getNavigation().moveTo(path, speed);
            return;
        }

        this.preferredFailures++;

        // 2) Keep the stored heading until preferred has failed repeatedly.
        if (this.preferredFailures < PREFERRED_FAILURES_BEFORE_TURN) {
            this.mob.getNavigation().stop();
            return;
        }

        if (this.isHeadingOwner() && NomadicMigration.isNomadicHomeless(this.mob)) {
            this.smartPlanSelected = false;
            MigrationPathPlanner.Plan plan = MigrationPathPlanner.findBest(this.mob, preferred);
            this.smartPlanSelected = true;
            if (plan != null && this.tryCommitHeading(plan.heading(), speed, plan.path())) {
                this.preferredFailures = 0;
                return;
            }
        }

        // 3) Probe alternate angles without writing until one yields a real path.
        for (int i = 1; i < HEADING_PROBE_OFFSETS.length; i++) {
            double candidate = preferred + HEADING_PROBE_OFFSETS[i];
            Path turnPath = this.findPathOnHeading(candidate);
            if (turnPath != null && this.tryCommitHeading(candidate, speed, turnPath)) {
                this.preferredFailures = 0;
                return;
            }
        }

        Path fallback = this.randomDryPath();
        if (fallback != null) {
            this.mob.getNavigation().moveTo(fallback, speed);
            return;
        }
        this.repathCooldown = FAILED_REPATH_BACKOFF;
        this.mob.getNavigation().stop();
    }

    private void escapeWater() {
        double speed = this.migrationSpeed() * WATER_SPEED_MULT;
        double preferred = this.readHeading();

        Path path = this.findPathOnHeading(preferred);
        if (path != null) {
            this.mob.getNavigation().moveTo(path, speed);
            return;
        }

        for (int i = 1; i < HEADING_PROBE_OFFSETS.length; i++) {
            double candidate = preferred + HEADING_PROBE_OFFSETS[i];
            Path turnPath = this.findPathOnHeading(candidate);
            if (turnPath != null && this.tryCommitHeading(candidate, speed, turnPath)) {
                return;
            }
        }

        Path shore = this.findNearbyDryPath();
        if (shore != null) {
            this.mob.getNavigation().moveTo(shore, speed);
            return;
        }
        Path fallback = this.randomDryPath();
        if (fallback != null) {
            this.mob.getNavigation().moveTo(fallback, speed);
            return;
        }
        this.mob.getNavigation().stop();
    }

    /**
     * Try hop distances along {@code heading}. Does not mutate stored migration heading.
     * Corridor scouting is limited to the hop being attempted so far-ahead terrain
     * cannot force a turn when a shorter hop is clear.
     */
    @Nullable
    private Path findPathOnHeading(double heading) {
        for (double distance : HOP_DISTANCES) {
            double scout = Math.min(CORRIDOR_SCOUT_RANGE, Math.max(8.0D, distance));
            if (!Homes.migrationCorridorOk(this.mob.level(), this.mob.blockPosition(), heading, scout)) {
                continue;
            }
            Path path = this.pathAlong(heading, distance);
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    private Path randomDryPath() {
        Path fallback = null;
        for (int i = 0; i < RANDOM_FALLBACKS; i++) {
            Vec3 candidate = DefaultRandomPos.getPos(this.mob, 16, 7);
            if (candidate == null) {
                continue;
            }
            Path path = this.pathToSurface(Mth.floor(candidate.x), Mth.floor(candidate.z));
            if (path == null) {
                continue;
            }
            // Prefer a route that does not lead through standing herd-mates, but never fail
            // migration on crowding alone — remember the first crowded success as the fallback.
            if (CrowdGrid.isClean(this.mob.level(), path, this.mob.blockPosition())) {
                return path;
            }
            if (fallback == null) {
                fallback = path;
            }
        }
        return fallback;
    }

    private Path findNearbyDryPath() {
        BlockPos origin = this.mob.blockPosition();
        Path fallback = null;
        for (int ring = 2; ring <= 16; ring += 2) {
            for (int dx = -ring; dx <= ring; dx += ring) {
                for (int dz = -ring; dz <= ring; dz++) {
                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    if (!this.isDryColumn(x, z)) {
                        continue;
                    }
                    Path path = this.pathToSurface(x, z);
                    if (path == null) {
                        continue;
                    }
                    if (CrowdGrid.isClean(this.mob.level(), path, origin)) {
                        return path;
                    }
                    if (fallback == null) {
                        fallback = path;
                    }
                }
            }
            for (int dz = -ring; dz <= ring; dz += ring) {
                for (int dx = -ring + 1; dx < ring; dx++) {
                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    if (!this.isDryColumn(x, z)) {
                        continue;
                    }
                    Path path = this.pathToSurface(x, z);
                    if (path == null) {
                        continue;
                    }
                    if (CrowdGrid.isClean(this.mob.level(), path, origin)) {
                        return path;
                    }
                    if (fallback == null) {
                        fallback = path;
                    }
                }
            }
        }
        return fallback;
    }

    /**
     * Cheap dry-land prefilter shared by the escape/fallback scans: only call the
     * pathfinder for columns whose heightmap surface is dry standing ground. Mirrors
     * exactly the acceptance predicate in {@link #pathToSurface}, so no candidate the
     * pathfinder would accept is skipped, while water columns are never probed.
     */
    private boolean isDryColumn(int x, int z) {
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!level.hasChunkAt(new BlockPos(x, 0, z))) {
            return false;
        }
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
        return Homes.isDryLand(level, surface.above()) || Homes.isDryLand(level, surface);
    }

    private Path pathAlong(double heading, double distance) {
        double x = this.mob.getX() + Mth.cos((float) heading) * distance;
        double z = this.mob.getZ() + Mth.sin((float) heading) * distance;
        return this.pathToSurface(Mth.floor(x), Mth.floor(z));
    }

    private Path pathToSurface(int blockX, int blockZ) {
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return null;
        }
        BlockPos column = new BlockPos(blockX, this.mob.blockPosition().getY(), blockZ);
        if (!level.hasChunkAt(column)) {
            return null;
        }
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(blockX, 0, blockZ));
        BlockPos stand = surface.above();
        if (!Homes.isDryLand(level, stand) && !Homes.isDryLand(level, surface)) {
            return null;
        }
        BlockPos target = Homes.isDryLand(level, stand) ? stand : surface;
        // Cliff lips are not valid migration-leg destinations, but an animal that is
        // escaping water may need a rim stand that sits 3+ blocks above the water surface —
        // keep that escape flow working and only guard dry-leg landings.
        if (!this.mob.isInWaterOrBubble() && !CliffAvoidance.isEdgeSafe(level, target)) {
            return null;
        }
        // Widen the A* search beyond the navigation's 16-block FOLLOW_RANGE / 256-node
        // budget: the dry rim of a pit the alpha is wading in can be 8-21 blocks above it,
        // and the default search only ever finds a partial path around the pit floor.
        this.mob.getNavigation().setMaxVisitedNodesMultiplier(ESCAPE_NODE_MULTIPLIER);
        Path path;
        try {
            // 3-arg overload: (pos, accuracy, followRange).
            path = this.mob.getNavigation().createPath(target, 1, ESCAPE_FOLLOW_RANGE);
        } finally {
            this.mob.getNavigation().resetMaxVisitedNodesMultiplier();
        }
        if (path == null || path.getNodeCount() <= 0) {
            return null;
        }
        // Water crossings are allowed: nomadic herds wade/swim rivers instead of turning back.
        if (Homes.pathHasSteepStep(path, PATH_MAX_STEP_Y)) {
            return null;
        }
        if (!Homes.pathHasHorizontalProgress(path, this.mob.blockPosition(), PATH_MIN_HORIZONTAL)) {
            return null;
        }
        // Hard hazards (lava, fire) and remembered hazard cells are rejected outright so a
        // nomadic herd routes around a portal instead of repeatedly pathing into it. The
        // preferredFailures -> turn / planner / random fallback chain rotates the herd away.
        if (Avoidance.pathTouchesHardHazard(level, path)
                || Avoidance.pathPassesNearHazard(level, path, Avoidance.HAZARD_AVOID_RADIUS)) {
            return null;
        }
        return path;
    }

    private double migrationSpeed() {
        return HomeSettingsManager.get(this.mob.getType())
                .map(SpeciesHomeSettings::migrationSpeed)
                .orElse(1.0D);
    }

    private boolean isFencedIn() {
        long now = this.mob.level().getGameTime();
        if (now >= this.nextFenceCheckGameTime) {
            this.fencedIn = FenceDetection.isFencedIn(this.mob);
            this.nextFenceCheckGameTime = now + 200L;
        }
        return this.fencedIn;
    }

    private boolean isNomadic() {
        return HomeSettingsManager.get(this.mob.getType())
                .map(SpeciesHomeSettings::nomadic)
                .orElse(false);
    }

    private Animal headingSource() {
        return NomadicMigration.scheduleOwner(this.mob);
    }

    private boolean isHeadingOwner() {
        return this.headingSource() == this.mob;
    }

    private double readHeading() {
        Animal source = this.headingSource();
        NomadicMigration.ensureHeading(source);
        return source.getData(HomeAttachments.NOMAD_HEADING);
    }

    private void writeHeading(double heading) {
        this.headingSource().setData(HomeAttachments.NOMAD_HEADING, heading);
    }

    /**
     * Commit a candidate heading by writing it to the schedule owner and moving.
     * Large turns are gated by {@link NomadicMigration#TURN_COOLDOWN_TICKS} so a
     * reversal cannot cascade into back-and-forth pacing; a herd that is truly
     * stuck (hardStuck) may turn regardless. Returns false when the turn is
     * rejected, leaving the stored heading untouched.
     */
    private boolean tryCommitHeading(double candidate, double speed, Path path) {
        if (this.isHeadingOwner()) {
            double current = this.readHeading();
            double diff = headingDiff(current, candidate);
            if (diff > MAX_TURN_ANGLE) {
                if (!this.hardStuck && NomadicMigration.isTurnBlocked(this.mob)) {
                    return false;
                }
                NomadicMigration.beginTurnBlock(this.mob);
            }
            this.writeHeading(candidate);
        }
        this.hardStuck = false;
        this.mob.getNavigation().moveTo(path, speed);
        return true;
    }

    private static double headingDiff(double from, double to) {
        double diff = (to - from) % (Math.PI * 2.0D);
        if (diff > Math.PI) {
            diff -= Math.PI * 2.0D;
        } else if (diff < -Math.PI) {
            diff += Math.PI * 2.0D;
        }
        return Math.abs(diff);
    }
}
