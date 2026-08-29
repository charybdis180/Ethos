package com.charybdis180.ethological.herd.goal;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.avoidance.Avoidance;
import com.charybdis180.ethological.avoidance.CliffAvoidance;
import com.charybdis180.ethological.avoidance.CrowdGrid;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.HerdSettingsManager;
import com.charybdis180.ethological.herd.SpeciesHerdSettings;
import com.charybdis180.ethological.home.Homes;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.Nullable;

/**
 * Validated, surface-aware follow pathing so herd members regroup on the alpha's
 * terrain instead of diving into caves or dead-ends that happen to be closer in 3D.
 *
 * <p>Every committed path is anchored to a heightmap surface stand and must end
 * on that stand, so a tunnel that runs under the alpha never wins over the long
 * surface route. When the direct route is blocked, members march in validated
 * surface hops toward the target, walking around ridges without a full map.</p>
 */
public final class FollowPathing {
    /** Hops tried, longest first, when the direct route to the target is blocked. */
    private static final double[] HOP_DISTANCES = {20.0D, 16.0D, 12.0D, 10.0D, 8.0D, 6.0D};
    /** Bearing offsets probed from the direct bearing to the target. */
    private static final double[] BEARING_OFFSETS = {
            0.0D, Math.PI / 6.0D, -Math.PI / 6.0D, Math.PI / 3.0D, -Math.PI / 3.0D
    };
    /** Cap on pathfinder queries per hop probe so stuck herds cannot tank the tick.
     *  Reduced from 8 to 5: the log showed hopToward dominating the server tick
     *  (~800-2700ms per 100 ticks) with each probe an expensive A* run, and the
     *  first few probes (direct bearing + near offsets) succeed in nearly every
     *  real hit — the extra 3 far-offset probes mostly burn time on dead ends. */
    private static final int MAX_HOP_CHECKS = 5;
    /** A hop that lands within this horizontal distance of the target is good enough to commit. */
    private static final double HOP_GOOD_ENOUGH_SQR = 8.0D * 8.0D;
    /** Penalty per radian a hop drifts off the direct bearing, to prefer straight-ish routes. */
    private static final double BEARING_DRIFT_PENALTY = 24.0D;
    /** Crowd penalty per occupied mid-path node, in squared-distance units (one crowded node
     *  ≈ 5 blocks of detour). Soft preference only — a crowded route still wins when every
     *  alternative is crowded, so regrouping/escaping can never fail from crowding. */
    private static final double CROWD_PENALTY = 25.0D;
    /** Short hops tried when every longer hop is blocked, so a barrier-bound member keeps moving. */
    private static final double[] NUDGE_DISTANCES = {5.0D, 4.0D, 3.0D, 2.0D};
    /** Bearing offsets probed for a nudge hop. */
    private static final double[] NUDGE_OFFSETS = {
            0.0D, Math.PI / 6.0D, -Math.PI / 6.0D, Math.PI / 4.0D, -Math.PI / 4.0D
    };
    /** A nudge hop must gain at least this much horizontal progress to count. */
    private static final double NUDGE_MIN_PROGRESS = 1.5D;
    /** Cap on pathfinder queries per nudge probe. */
    private static final int MAX_NUDGE_CHECKS = 8;    /** Max per-segment step a follow path may climb. */
    public static final int MAX_STEP_Y = 4;
    /** Max |path end Y - target stand Y| before a path counts as leading underground. */
    private static final int END_Y_TOLERANCE = 4;
    /** Reject paths that gain too little horizontal progress. */
    private static final double MIN_HORIZONTAL_PROGRESS = 3.0D;
    /** Winner/failure memo for {@link #hopToward}, keyed per mob. The value packs
     *  {gameTime, mobX, mobZ, targetColKey, standX, standY, standZ, foundFlag}. */
    private static final Map<UUID, long[]> HOP_MEMO = new HashMap<UUID, long[]>();
    /** Freshness window for a {@link #HOP_MEMO} entry. */
    private static final long HOP_MEMO_TICKS = 60L;
    private static final int HOP_MEMO_LIMIT = 4096;
    /** Per-tick pathfinder budget so a herd of stuck members cannot tank the tick:
     *  once the tick's quota of hop pathfinds is spent, remaining members defer by one
     *  repath cycle instead of all hammering A* in the same tick. Reduced from 24 to 12 —
     *  the hop probes were consuming several hundred ms per tick even under the old cap. */
    private static long hopStampTick = -1L;
    private static int hopPathsThisTick = 0;
    private static final int MAX_HOP_PATHS_PER_TICK = 12;
    /** Memo for {@link #isSeparated}, keyed per mob: {gameTime, resultFlag}. Separation
     *  is decided from surface probes that rarely change within a second, and the escape
     *  plan itself is already gated by far coarser failure memos. */
    private static final Map<UUID, long[]> SEPARATED_MEMO = new HashMap<UUID, long[]>();
    private static final long SEPARATED_MEMO_TICKS = 20L;
    private static final int SEPARATED_MEMO_LIMIT = 4096;
    /** A column this far above the mob (in any direction) means the mob is in a hole. */
    private static final int HOLE_DEPTH = 3;
    /** Ring radii sampled around the mob to decide whether it is trapped at a floor level
     * far below the surrounding terrain (a pit/ravine). Two radii catch both narrow pits
     * (inner ring hits the walls) and wide depressions (outer ring hits the rim). */
    private static final int[] HOLE_PROBE_RADII = {4, 9};
    /** A vertical gap this large between the mob's surface and the herd's surface means
     * the mob is separated (cliff above the herd, or flooded cave below it) and normal
     * follow fallbacks cannot bridge it — the member must escape toward the herd's level.
     * Raised from 3 so a migrating alpha climbing a gentle slope no longer flags the
     * whole trailing herd as separated at once; genuine pits are still caught by the
     * local HOLE_PROBE ring check below. */
    private static final int ESCAPE_LEVEL_GAP = 5;
    /** When picking an escape stand, reject stands that move farther from the herd level
     * than the current gap (they only make the separation worse). */
    private static final int ESCAPE_MOVE_AWAY_TOLERANCE = 2;
    /** Max horizontal distance a member may travel to adopt a herd-mate's shared escape
     * stand. Gating this stops a whole separated herd from converging on the exact stand
     * one member found (the pile-up seen when an alpha climbed a slope); members beyond
     * this run their own scan toward the current herd surface instead. */
    private static final double SHARED_ESCAPE_MAX_DIST = 24.0D;

    /** Cap on pathfinder queries per escape probe, so a trapped herd cannot tank the tick.
     * 8 rings x 8 bearings = 64; the full circle must be probed so a wide pit's climbable
     * edge is found, but this only runs when a member is genuinely separated and re-picks
     * only after reaching (or abandoning) the committed stand. */
    private static final int MAX_ESCAPE_CHECKS = 64;
    /** Escape probe rings, near-to-far. */
    private static final double[] ESCAPE_RINGS = {3.0D, 4.0D, 6.0D, 8.0D, 10.0D, 12.0D, 16.0D, 24.0D};
    /** Full-circle bearings for escape probes. */
    private static final double[] ESCAPE_BEARINGS = {
            0.0D, Math.PI / 4.0D, Math.PI / 2.0D, 3.0D * Math.PI / 4.0D,
            Math.PI, 5.0D * Math.PI / 4.0D, 3.0D * Math.PI / 2.0D, 7.0D * Math.PI / 4.0D
    };
    /** Escape probes must reach stands 8-21 blocks up a pit wall, but the mob's
     * navigation was built with a 16-block FOLLOW_RANGE / 256-node search budget —
     * too small to route out of a deep pit, so every rim stand returns
     * "no-canReach". Boost both the search radius and the node budget for the
     * escape/pit-surface probes, restoring them immediately after the query.
     * The 96-block radius is sized for a separated member up to ~2.5x the cow
     * follow distance (12) away whose route around a ditch/ravine runs well past
     * the 48-block cap — the observed failure mode where the direct path to the
     * alpha always returned "no-canReach". */
    private static final int ESCAPE_FOLLOW_RANGE = 96;
    private static final float ESCAPE_NODE_MULTIPLIER = 3.0F;

    /** Last rejection reason recorded by {@link #isValidFollowPath}, for debugging. */
    private static String lastRejectReason = "none";

    /** Column of the stand chosen by the most recent {@link #escapePlan} call, so the
     * caller can commit to the same escape step across repaths (fixing direction flips). */
    private static BlockPos lastEscapeStand = null;

    /** How long a failed escape stand stays blacklisted, in game ticks. */
    private static final long ESCAPE_BLACKLIST_TTL = 300L;
    /** Blacklisted escape columns keyed by mob UUID: column long -> game time it was blacklisted.
     * A stand that was committed to and then failed (path became unreachable, or the mob reached
     * it but is still separated) is blacklisted so the next escape scan tries a different
     * direction instead of re-picking the same water/ledge stand forever. */
    private static final Map<UUID, Map<Long, Long>> ESCAPE_BLACKLIST = new HashMap<>();

    /** Cooldown between full escape scans that found NO reachable stand at all, in game ticks.
     * A mob truly trapped (pit with no climbable rim inside the widened search budget) re-runs
     * the whole 64-probe scan on every repath cycle and always fails — pure pathfinder churn.
     * Memoize the failure so the scan is skipped until the mob moves materially. Long enough
     * that a pacing, still-stuck mob does not re-scan every repath cycle. */
    private static final long ESCAPE_RESCAN_TICKS = 3600L;
    /** The mob must move at least this far (squared) from the failed-scan position to re-scan. */
    private static final double ESCAPE_RESCAN_MOVE_SQR = 9.0D;
    /** Last full escape-scan failure: mob UUID -> { gameTime, x, z }. */
    private static final Map<UUID, long[]> ESCAPE_FAILURE_MEMO = new HashMap<>();

    /** Cap on concurrent escape scans per game tick. When a herd gets trapped, every separated
     * member fires escapePlan near-simultaneously and each full scan costs tens of ms — a whole
     * herd scanning at once is a 200ms+ spike. Rate-limit the scans so at most this many run per
     * tick; the rest return null (deferred) and retry on their next repath backoff. */
    private static final int MAX_ESCAPE_SCANS_PER_TICK = 2;
    private static long lastEscapeScanTick = Long.MIN_VALUE;
    private static int escapeScansThisTick = 0;

    /** Cap on consecutive widened pathfinder failures per escape scan. A genuinely trapped mob
     * (dug pit with no climbable rim inside the search budget) makes dozens of widened queries
     * that all return "no-canReach" — a single failed scan then costs ~500ms. When several
     * directions in a row fail the same way, the rest will too, so stop probing and record the
     * failure memo instead. A mob that CAN escape finds a reachable stand within a few probes
     * (sloped pits reset the counter on every success), so this rarely cuts off a real escape.
     * Reduced from 8 to 5: log evidence showed failed scans still running 39-69 queries (~800ms). */
    private static final int MAX_ESCAPE_CONSECUTIVE_FAILS = 5;

    /** Records an escape stand column as failed for this mob for {@link #ESCAPE_BLACKLIST_TTL} ticks. */
    public static void blacklistEscapeStand(PathfinderMob mob, BlockPos stand) {
        long col = columnKey(stand);
        ESCAPE_BLACKLIST.computeIfAbsent(mob.getUUID(), k -> new HashMap<>())
                .put(col, mob.level().getGameTime() + ESCAPE_BLACKLIST_TTL);
    }

    /**
     * Clears every per-mob memo this class holds for {@code mob} — hop memo, separation memo,
     * escape blacklists, and the escape-scan failure memo. Called by the follow watchdog when
     * a member has made no progress, so its next repath is genuinely fresh: without this, the
     * escalation would just replay the memoized failures and re-pick the same dead ends.
     */
    public static void resetFor(PathfinderMob mob) {
        UUID id = mob.getUUID();
        HOP_MEMO.remove(id);
        SEPARATED_MEMO.remove(id);
        ESCAPE_BLACKLIST.remove(id);
        ESCAPE_FAILURE_MEMO.remove(id);
    }

    /** True when the mob's escape scan should skip this stand column this tick. */
    public static boolean isEscapeStandBlacklisted(PathfinderMob mob, BlockPos stand) {
        Map<Long, Long> byMob = ESCAPE_BLACKLIST.get(mob.getUUID());
        if (byMob == null) {
            return false;
        }
        long now = mob.level().getGameTime();
        long col = columnKey(stand);
        Long until = byMob.get(col);
        if (until == null) {
            return false;
        }
        if (now >= until) {
            byMob.remove(col);
            if (byMob.isEmpty()) {
                ESCAPE_BLACKLIST.remove(mob.getUUID());
            }
            return false;
        }
        return true;
    }

    /** 2D column key (Y dropped) so blacklisting a water surface also blocks the floor below it. */
    private static long columnKey(BlockPos pos) {
        return (long) pos.getX() << 32 ^ (long) pos.getZ() & 0xFFFFFFFFL;
    }

    /** Records a full escape scan that found no reachable stand, so {@link #escapePlan}
     * skips the expensive re-scan until the mob moves away from the failed position. */
    private static void rememberEscapeFailure(PathfinderMob mob) {
        if (ESCAPE_FAILURE_MEMO.size() >= 512) {
            ESCAPE_FAILURE_MEMO.clear();
        }
        ESCAPE_FAILURE_MEMO.put(mob.getUUID(), new long[]{mob.level().getGameTime(), Double.doubleToLongBits(mob.getX()), Double.doubleToLongBits(mob.getZ())});
    }

    /** True while a recent full escape scan found nothing and the mob has not moved away —
     * used by callers to skip the hop/nudge fallback chain that would only fail the same way. */
    public static boolean isEscapeScanBlocked(PathfinderMob mob) {
        long[] fail = ESCAPE_FAILURE_MEMO.get(mob.getUUID());
        if (fail == null) {
            return false;
        }
        long now = mob.level().getGameTime();
        if (now - fail[0] >= ESCAPE_RESCAN_TICKS) {
            ESCAPE_FAILURE_MEMO.remove(mob.getUUID());
            return false;
        }
        double dx = mob.getX() - Double.longBitsToDouble(fail[1]);
        double dz = mob.getZ() - Double.longBitsToDouble(fail[2]);
        return dx * dx + dz * dz < ESCAPE_RESCAN_MOVE_SQR;
    }

    public static String lastRejectReason() {
        return lastRejectReason;
    }

    public static BlockPos lastEscapeStand() {
        return lastEscapeStand;
    }

    private FollowPathing() {
    }

    /**
     * Per-tick budget for widened (96-block / 3x-node) escape pathfinder queries. A single
     * trapped member's full ring-bearings scan can fire up to 64 of these, and each costs
     * ~6ms (10-50x a vanilla query), so a few concurrent scans detonate a 700ms+ tick when
     * several large herds cram together (observed: fp.stand 735ms in tick 3001 with three
     * co-located cow packs). The budget is global (all herds/levels share it): it caps worst
     * case A* cost per tick at ~12 * 6ms ≈ 72ms, while being large enough that a single
     * trapped member's full scan completes within ~5 ticks (the 80-tick repath backoff retries
     * naturally). Deferrals never write a false "trapped" failure memo (see
     * {@link #wideBudgetExhausted}).
     */
    private static final int MAX_WIDE_PATHS_PER_TICK = 12;
    private static long widePathStampTick = -1L;
    private static int widePathsThisTick = 0;

    @Nullable
    private static Path createPathWide(PathfinderMob mob, BlockPos stand) {
        long now = mob.level().getGameTime();
        if (now != widePathStampTick) {
            widePathStampTick = now;
            widePathsThisTick = 0;
        }
        if (widePathsThisTick >= MAX_WIDE_PATHS_PER_TICK) {
            return null;
        }
        ++widePathsThisTick;
        mob.getNavigation().setMaxVisitedNodesMultiplier(ESCAPE_NODE_MULTIPLIER);
        try {
            // 3-arg overload: (pos, accuracy, followRange).
            Path wide = mob.getNavigation().createPath(stand, 1, ESCAPE_FOLLOW_RANGE);
            return wide;
        } finally {
            mob.getNavigation().resetMaxVisitedNodesMultiplier();
        }
    }

    /**
     * True when the widened-path budget was exhausted at least once this tick. Used to
     * suppress the "no escape route found" failure memo: if a scan was cut short by the
     * budget, absence of an escape is NOT proven, so the mob must re-scan next cycle
     * instead of being marked trapped for 3600 ticks.
     */
    public static boolean wideBudgetExhausted() {
        return widePathStampTick != Long.MIN_VALUE && widePathsThisTick >= MAX_WIDE_PATHS_PER_TICK;
    }

    /**
     * Path to the surface stand at the target column, using the mob's normal navigation
     * budget (16-block FOLLOW_RANGE / 256 nodes). Cheap enough for every follow member.
     * Returns null when the route is blocked, passes through hazards, has steep steps,
     * or ends underground instead of on the surface.
     */
    @Nullable
    public static Path pathToSurfaceStand(PathfinderMob mob, BlockPos target, int maxStepY) {
        return pathToSurfaceStandImpl(mob, target, maxStepY, false);
    }

    /**
     * Like {@link #pathToSurfaceStand}, but with the widened search budget used by the
     * pit-escape flow, where the rim stand can be 8-21 blocks above the mob and the
     * default budget cannot route out of a deep pit. Only the escape re-commit needs
     * this — normal follow re-paths stay on the cheap narrow query.
     */
    @Nullable
    public static Path pathToSurfaceStandWide(PathfinderMob mob, BlockPos target, int maxStepY) {
        return pathToSurfaceStandImpl(mob, target, maxStepY, true);
    }

    @Nullable
    private static Path pathToSurfaceStandImpl(PathfinderMob mob, BlockPos target, int maxStepY, boolean wide) {
        BlockPos stand = Homes.surfaceStand(mob.level(), target.getX(), target.getZ());
        if (stand == null) {
            lastRejectReason = "stand-null";
            return null;
        }
        // A grounded mob must commit to a DRY destination stand: a water-surface stand only
        // makes it wade/swim in on the spot (the alpha near a pond case). Swimming mobs keep
        // water stands so they can beach via the escape chain.
        if (!mob.isInWater() && !mob.isSwimming() && !Homes.isDryLand(mob.level(), stand)) {
            lastRejectReason = "stand-wet";
            return null;
        }
        // Cliff lips are not valid follow/reach destinations, but pit-escape (wide) keeps
        // rim stands — climbing out is more important than standing a block off the edge.
        if (!wide && !CliffAvoidance.isEdgeSafe(mob.level(), stand)) {
            lastRejectReason = "stand-cliff";
            return null;
        }
        Path path;
        if (wide) {
            path = createPathWide(mob, stand);
        } else {
            path = mob.getNavigation().createPath(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, 1);
        }
        boolean valid = isValidFollowPath(mob.level(), path, mob.blockPosition(), stand, maxStepY,
                MIN_HORIZONTAL_PROGRESS, dipFloorY(mob, maxStepY));
        return valid ? path : null;
    }

    /** 26-bit XZ packing for hop column dedupe and memo keys (same scheme as Homes's
     *  surface-stand memo and Avoidance's cellKey). */
    private static long columnKey(int x, int z) {
        return ((long)(x & 0x3FFFFFF) << 32) ^ (z & 0x3FFFFFFL);
    }

    /**
     * March toward {@code target} via validated surface hops when the direct route is
     * blocked. Returns the best reachable hop (preferring hops that end nearest the
     * target on a near-direct bearing), or null when nothing is reachable.
     */
    @Nullable
    public static Path hopToward(PathfinderMob mob, BlockPos target, int maxStepY) {
        long now = mob.level().getGameTime();
        if (hopStampTick != now) {
            hopStampTick = now;
            hopPathsThisTick = 0;
        }
        int mobX = mob.blockPosition().getX();
        int mobZ = mob.blockPosition().getZ();
        long targetCol = columnKey(target.getX() >> 2, target.getZ() >> 2);

        // Reuse a recent winner or known failure against the same (4-block quantized)
        // target from roughly the same spot, so herds hammering one obstacle collapse
        // to a single pathfind per repath cycle.
        long[] memo = HOP_MEMO.get(mob.getUUID());
        if (memo != null && now - memo[0] < HOP_MEMO_TICKS && memo[3] == targetCol) {
            int dx = mobX - (int)memo[1];
            int dz = mobZ - (int)memo[2];
            if (dx * dx + dz * dz <= 36) {
                if (memo[7] == 0L) {
                    return null; // known failure — fall through to nudge/escape as usual
                }
                BlockPos stand = new BlockPos((int)memo[4], (int)memo[5], (int)memo[6]);
                if (hopPathsThisTick < MAX_HOP_PATHS_PER_TICK) {
                    // Re-validate the memoized stand for a grounded mob (the memo is dry-gated
                    // at write time, but a swimmer's memo could otherwise leak to a grounded
                    // repath of the same column).
                    if (!mob.isInWater() && !mob.isSwimming() && !Homes.isDryLand(mob.level(), stand)) {
                        HOP_MEMO.remove(mob.getUUID());
                        return null;
                    }
                    hopPathsThisTick++;
                    Path path = mob.getNavigation().createPath(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, 1);
                    if (isValidFollowPath(mob.level(), path, mob.blockPosition(), stand, maxStepY,
                            MIN_HORIZONTAL_PROGRESS, dipFloorY(mob, maxStepY))) {
                        return path;
                    }
                }
                HOP_MEMO.remove(mob.getUUID()); // stale — rerun the full loop
            }
        }

        double dx = (target.getX() + 0.5) - mob.getX();
        double dz = (target.getZ() + 0.5) - mob.getZ();
        double bearing = Math.atan2(dz, dx);
        Path best = null;
        BlockPos bestStand = null;
        double bestScore = Double.MAX_VALUE;
        int checks = 0;
        LongOpenHashSet seen = new LongOpenHashSet();
        for (double distance : HOP_DISTANCES) {
            for (double offset : BEARING_OFFSETS) {
                if (++checks > MAX_HOP_CHECKS) {
                    return best;
                }
                double heading = bearing + offset;
                int x = Mth.floor(mob.getX() + 0.5 + Math.cos(heading) * distance);
                int z = Mth.floor(mob.getZ() + 0.5 + Math.sin(heading) * distance);
                // Skip duplicate columns and the mob's own column: hops there can't help.
                if ((x == mobX && z == mobZ) || !seen.add(columnKey(x, z))) {
                    continue;
                }
                BlockPos stand = Homes.surfaceStand(mob.level(), x, z);
                if (stand == null) {
                    continue;
                }
                // A grounded mob hops toward a DRY stand only — water-surface columns pull it
                // off the edge into the pond. Swimmers keep water stands so they can beach.
                boolean dryStand = Homes.isDryLand(mob.level(), stand);
                if (!mob.isInWater() && !mob.isSwimming() && !dryStand) {
                    continue;
                }
                if (!CliffAvoidance.isEdgeSafe(mob.level(), stand)) {
                    continue;
                }
                if (hopPathsThisTick >= MAX_HOP_PATHS_PER_TICK) {
                    return best; // budget spent — commit best-so-far, others defer a cycle
                }
                hopPathsThisTick++;
                Path path = mob.getNavigation().createPath(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, 1);
                if (!isValidFollowPath(mob.level(), path, mob.blockPosition(), stand, maxStepY,
                        MIN_HORIZONTAL_PROGRESS, dipFloorY(mob, maxStepY))) {
                    continue;
                }
                double endDx = (stand.getX() + 0.5) - (target.getX() + 0.5);
                double endDz = (stand.getZ() + 0.5) - (target.getZ() + 0.5);
                double endDistSqr = endDx * endDx + endDz * endDz;
                if (endDistSqr <= HOP_GOOD_ENOUGH_SQR) {
                    HOP_MEMO.put(mob.getUUID(), new long[] {now, mobX, mobZ, targetCol,
                            stand.getX(), stand.getY(), stand.getZ(), 1L});
                    trimHopMemo();
                    return path;
                }
                double crowded = CrowdGrid.countCrowdedNodes(mob.level(), path, mob.blockPosition(), 2, 3);
                // Dry hops always beat wet hops — a water stand is only accepted when no dry
                // hop at all can advance the member (e.g. rejoin the alpha across a river).
                double score = endDistSqr + Math.abs(offset) * BEARING_DRIFT_PENALTY + CROWD_PENALTY * crowded
                        + (dryStand ? 0.0D : 1_000_000.0D);
                if (score < bestScore) {
                    bestScore = score;
                    best = path;
                    bestStand = stand;
                }
            }
        }
        HOP_MEMO.put(mob.getUUID(), best != null && bestStand != null
                ? new long[] {now, mobX, mobZ, targetCol,
                        bestStand.getX(), bestStand.getY(), bestStand.getZ(), 1L}
                : new long[] {now, mobX, mobZ, targetCol, 0, 0, 0, 0L});
        trimHopMemo();
        return best;
    }

    /** Distance (squared) within which a trek commitment still demands a full-route
     *  canReach proof. Beyond it the A* node budget makes full-route proofs unreliable
     *  (they fail even across open plains), so callers fall back to first-leg validation. */
    public static final double FULL_ROUTE_PROOF_RANGE_SQR = 64.0 * 64.0;

    /**
     * Commitment gate for LONG treks (e.g. the emergency far-water march). A full-route
     * pathfind to a target beyond ~64 blocks routinely exhausts the node budget and reports
     * no-canReach even across open ground, so demanding {@link Homes#isStrictlyReachable}
     * for a 96-192 block candidate rejects nearly every real destination and strands the
     * searcher standing still. This tiers the proof: near stands keep the strict full-route
     * check; far stands only prove the FIRST LEG is routable (one validated hop toward the
     * target, hazard- and edge-checked). The caller then walks the trek leg-by-leg and
     * re-resolves as terrain loads. False means nothing toward the target is reachable now.
     */
    public static boolean firstLegRoutable(PathfinderMob mob, BlockPos stand, double distSqr) {
        if (distSqr <= FULL_ROUTE_PROOF_RANGE_SQR) {
            return Homes.isStrictlyReachable(mob, stand);
        }
        Path leg = hopToward(mob, stand, MAX_STEP_Y);
        return leg != null;
    }

    private static void trimHopMemo() {
        if (HOP_MEMO.size() > HOP_MEMO_LIMIT) {
            HOP_MEMO.clear();
        }
    }

    /**
     * Last-resort short hop toward {@code target} when direct routes and longer hops
     * are all blocked. Lets a member stuck against a barrier keep making forward
     * progress (milling along the obstacle) instead of standing dead-still between
     * repath backoffs. Validates hazards and steps, but allows shorter hops than
     * {@link #hopToward} would consider worth committing.
     */
    @Nullable
    public static Path nudgeToward(PathfinderMob mob, BlockPos target, int maxStepY) {
        double dx = (target.getX() + 0.5) - mob.getX();
        double dz = (target.getZ() + 0.5) - mob.getZ();
        double bearing = Math.atan2(dz, dx);
        Path best = null;
        double bestEndSqr = Double.MAX_VALUE;
        int checks = 0;
        int mobX = mob.blockPosition().getX();
        int mobZ = mob.blockPosition().getZ();
        LongOpenHashSet seen = new LongOpenHashSet();
        for (double distance : NUDGE_DISTANCES) {
            for (double offset : NUDGE_OFFSETS) {
                if (++checks > MAX_NUDGE_CHECKS) {
                    return best;
                }
                double heading = bearing + offset;
                int x = Mth.floor(mob.getX() + 0.5 + Math.cos(heading) * distance);
                int z = Mth.floor(mob.getZ() + 0.5 + Math.sin(heading) * distance);
                // Skip duplicate columns and the mob's own column, like hopToward.
                if ((x == mobX && z == mobZ) || !seen.add(columnKey(x, z))) {
                    continue;
                }
                BlockPos stand = Homes.surfaceStand(mob.level(), x, z);
                if (stand == null) {
                    continue;
                }
                // Grounded mobs nudge onto DRY stands only (same reason as hopToward).
                boolean dryStand = Homes.isDryLand(mob.level(), stand);
                if (!mob.isInWater() && !mob.isSwimming() && !dryStand) {
                    continue;
                }
                if (!CliffAvoidance.isEdgeSafe(mob.level(), stand)) {
                    continue;
                }
                Path path = mob.getNavigation().createPath(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, 1);
                if (!isValidFollowPath(mob.level(), path, mob.blockPosition(), stand, maxStepY,
                        NUDGE_MIN_PROGRESS, dipFloorY(mob, maxStepY))) {
                    continue;
                }
                double endDx = (stand.getX() + 0.5) - (target.getX() + 0.5);
                double endDz = (stand.getZ() + 0.5) - (target.getZ() + 0.5);
                double endDistSqr = endDx * endDx + endDz * endDz
                        + CROWD_PENALTY * CrowdGrid.countCrowdedNodes(mob.level(), path, mob.blockPosition(), 2, 3)
                        + (dryStand ? 0.0D : 1_000_000.0D);
                if (endDistSqr < bestEndSqr) {
                    bestEndSqr = endDistSqr;
                    best = path;
                }
            }
        }
        return best;
    }

    /**
     * True when the mob is separated from the herd's surface level by terrain it cannot
     * climb with normal follow fallbacks. Detects both directions: the mob in a hole BELOW
     * the surrounding surface (stand above it), and the mob on a ledge ABOVE the surrounding
     * surface (stand below it). Also true when the mob's own column sits far below the herd
     * alpha's surface column — the flooded-cave / open-water case where local probes look
     * level but the alpha is a story above.
     */
    public static boolean isSeparated(PathfinderMob mob) {
        long now = mob.level().getGameTime();
        long[] memo = SEPARATED_MEMO.get(mob.getUUID());
        if (memo != null && now - memo[0] < SEPARATED_MEMO_TICKS) {
            return memo[1] == 1L;
        }
        boolean result;
        BlockPos herdSurface = herdSurface(mob);
        if (herdSurface != null && Math.abs(herdSurface.getY() - mob.blockPosition().getY()) >= ESCAPE_LEVEL_GAP) {
            result = true;
        } else {
            int mobY = mob.blockPosition().getY();
            int highCount = 0;
            int lowCount = 0;
            int samples = 0;
            for (int radius : HOLE_PROBE_RADII) {
                for (int dx = -radius; dx <= radius; dx += radius) {
                    for (int dz = -radius; dz <= radius; dz += radius) {
                        BlockPos stand = Homes.surfaceStand(mob.level(), mob.blockPosition().getX() + dx, mob.blockPosition().getZ() + dz);
                        if (stand == null) {
                            continue;
                        }
                        samples++;
                        if (stand.getY() - mobY >= HOLE_DEPTH) {
                            highCount++;
                        } else if (mobY - stand.getY() >= HOLE_DEPTH) {
                            lowCount++;
                        }
                    }
                }
            }
            boolean sep = samples >= 4 && (highCount * 2 >= samples || lowCount * 2 >= samples);
            result = sep;
        }
        SEPARATED_MEMO.put(mob.getUUID(), new long[] {now, result ? 1L : 0L});
        if (SEPARATED_MEMO.size() > SEPARATED_MEMO_LIMIT) {
            SEPARATED_MEMO.clear();
        }
        return result;
    }

    /**
     * True when the surface stands around the mob sit at least {@link #HOLE_DEPTH} above it
     * (or below it) in the majority of directions — the mob is at the bottom of a genuine
     * local pit/ravine, or on a ledge/tower. Unlike {@link #isSeparated}, this ignores the
     * herd's surface column entirely, so a plain hillside (where the alpha is a few blocks
     * up the slope but the mob's own surroundings are at its level) stays false. Used as a
     * stand-alone signal for the escape gate: a member whose immediate surroundings rise
     * steeply all around is genuinely boxed even when it is still horizontally near the
     * alpha (the herd-surface check alone would miss the pit under those conditions).
     */
    public static boolean isLocallyHole(PathfinderMob mob) {
        int mobY = mob.blockPosition().getY();
        int highCount = 0;
        int lowCount = 0;
        int samples = 0;
        for (int radius : HOLE_PROBE_RADII) {
            for (int dx = -radius; dx <= radius; dx += radius) {
                for (int dz = -radius; dz <= radius; dz += radius) {
                    BlockPos stand = Homes.surfaceStand(mob.level(), mob.blockPosition().getX() + dx, mob.blockPosition().getZ() + dz);
                    if (stand == null) {
                        continue;
                    }
                    samples++;
                    if (stand.getY() - mobY >= HOLE_DEPTH) {
                        highCount++;
                    } else if (mobY - stand.getY() >= HOLE_DEPTH) {
                        lowCount++;
                    }
                }
            }
        }
        return samples >= 4 && (highCount * 2 >= samples || lowCount * 2 >= samples);
    }

    /** Surface stand at the herd alpha's current column, if the mob has a herd. */
    private static BlockPos herdSurface(PathfinderMob mob) {
        if (!(mob instanceof Animal animal)
                || !animal.hasData(com.charybdis180.ethological.registry.ModAttachments.HERD_DATA)) {
            return null;
        }
        com.charybdis180.ethological.herd.HerdManager.Herd herd = com.charybdis180.ethological.herd.HerdManager.get(
                animal.getData(com.charybdis180.ethological.registry.ModAttachments.HERD_DATA).herdId());
        if (herd == null || herd.alphaId == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return null;
        }
        net.minecraft.world.entity.Entity alpha = serverLevel.getEntity(herd.alphaId);
        if (!(alpha instanceof Animal alphaAnimal) || !alphaAnimal.isAlive()) {
            return null;
        }
        return Homes.surfaceStand(mob.level(), alphaAnimal.blockPosition().getX(), alphaAnimal.blockPosition().getZ());
    }

    /** The herd alpha this mob must stay near, or null when un-herded / unresolvable. */
    @Nullable
    private static Animal alphaOf(PathfinderMob mob) {
        if (!(mob instanceof Animal animal)
                || !animal.hasData(com.charybdis180.ethological.registry.ModAttachments.HERD_DATA)) {
            return null;
        }
        HerdManager.Herd herd = HerdManager.get(
                animal.getData(com.charybdis180.ethological.registry.ModAttachments.HERD_DATA).herdId());
        if (herd == null || herd.alphaId == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return null;
        }
        net.minecraft.world.entity.Entity alpha = serverLevel.getEntity(herd.alphaId);
        return alpha instanceof Animal alphaAnimal && alphaAnimal.isAlive() ? alphaAnimal : null;
    }

    /** Configurable sleep-proximity radius: half the herd's effective follow span by default.
     *  A pack member may not settle/sleep until within this distance of its alpha. */
    public static double sleepRadius(PathfinderMob mob) {
        if (!(mob instanceof Animal animal)) {
            return 0.0;
        }
        Optional<SpeciesHerdSettings> settings = HerdSettingsManager.get(animal.getType());
        if (settings.isEmpty()) {
            return 0.0;
        }
        return settings.get().followDistance() * settings.get().sleepFollowMultiplier();
    }

    /** Alpha-down relaxation factor shared by follow-release and sleep-entry: while the alpha
     *  sleeps/rests, both circles widen by this multiple so a member released from following
     *  at 2x follow distance is also ALLOWED to fall asleep there. Without sharing the factor,
     *  the wider release circle strands members in a dead zone — close enough that
     *  FollowAlphaGoal says "arrived" (stands still, shows follow_alpha/vigilant), but outside
     *  the sleep gate, which refuses to let them settle. Must stay >= FollowAlphaGoal's
     *  alpha-down settle multiplier so the two circles never disagree. */
    public static final double ALPHA_DOWN_RELAXATION = 2.0;

    /** Sleep-entry radius widened while the alpha is down (asleep or resting). Un-herded or
     *  alpha-less animals have no one to relax against, so they keep the strict radius. */
    public static double herdRestRadius(PathfinderMob mob) {
        Animal alpha = alphaOf(mob);
        double strict = sleepRadius(mob);
        if (alpha == null || strict <= 0.0) {
            return strict;
        }
        boolean alphaDown = alpha.getData(com.charybdis180.ethological.registry.ModAttachments.SLEEPING)
                || alpha.getData(com.charybdis180.ethological.registry.ModAttachments.RESTING);
        return alphaDown ? strict * ALPHA_DOWN_RELAXATION : strict;
    }

    /** 3D distance from the mob to its herd alpha, or -1 when no herd/alpha resolves. */
    public static double distanceToAlpha(PathfinderMob mob) {
        Animal alpha = alphaOf(mob);
        return alpha == null ? -1.0 : mob.distanceTo(alpha);
    }

    /**
     * General-purpose escape plan for a mob separated from its herd by terrain (pit, ledge,
     * flooded cave, open water). Probes the full circle (near-to-far, all bearings) and returns
     * the path to the best reachable stand that closes the vertical gap to the herd surface —
     * climbing out of a pit, descending off a ledge, or beaching from open water. Dry stands
     * are strongly preferred over water-surface stands so a swimming member heads for shore
     * instead of circling between water points. Reachability is decided by the pathfinder
     * itself, so vertical walls are naturally rejected while walkable slopes are accepted. The
     * chosen stand is recorded in {@link #lastEscapeStand} so the caller can commit to it
     * across repaths instead of flipping direction every few ticks.
     */
    @Nullable
    public static Path escapePlan(PathfinderMob mob, BlockPos target, int maxStepY) {
        long now = mob.level().getGameTime();
        // A herd-mate escaped to a proven dry stand recently. Try the shared stand FIRST —
        // before the per-member failure memo and the scan rate limiter — because a member
        // whose own full 64-probe scan failed (and was memoized) can still reach the exact
        // route a mate walked out on. One widened query replaces a full re-scan.
        if (mob instanceof Animal animal) {
            HerdManager.Herd herd = HerdManager.herdOf(animal);
            if (herd != null) {
                BlockPos shared = herd.escapeShare(now);
                // A swimming mob cannot climb out of the water onto a stand above its own
                // water level — reject the shared stand for the same reason the scan below
                // does, so a swimmer never commits to an impossible jump.
                boolean sharedImpossible = shared != null && mob.isInWater()
                        && Homes.waterLevelY(mob.level(), mob.blockPosition()) < shared.getY();
                if (shared != null && !sharedImpossible && !isEscapeStandBlacklisted(mob, shared)) {
                    // Don't let the whole separated herd converge on one far-away stand —
                    // only adopt a mate's proven route when it is actually nearby.
                    double sharedDist = mob.distanceToSqr(shared.getX() + 0.5, shared.getY(), shared.getZ() + 0.5);
                    if (sharedDist <= SHARED_ESCAPE_MAX_DIST * SHARED_ESCAPE_MAX_DIST) {
                    Path sharedPath = createPathWide(mob, shared);
                    if (isValidFollowPath(mob.level(), sharedPath, mob.blockPosition(), shared, maxStepY,
                            1.0D, dipFloorY(mob, maxStepY))) {
                        lastEscapeStand = shared;
                        ESCAPE_FAILURE_MEMO.remove(mob.getUUID());
                        return sharedPath;
                    }
                    }
                }
            }
        }
        long[] fail = ESCAPE_FAILURE_MEMO.get(mob.getUUID());
        if (fail != null) {
            if (now - fail[0] < ESCAPE_RESCAN_TICKS) {
                double dx = mob.getX() - Double.longBitsToDouble(fail[1]);
                double dz = mob.getZ() - Double.longBitsToDouble(fail[2]);
                if (dx * dx + dz * dz < ESCAPE_RESCAN_MOVE_SQR) {
                    return null;
                }
            }
            ESCAPE_FAILURE_MEMO.remove(mob.getUUID());
        }
        // Rate-limit concurrent full scans so a whole trapped herd cannot fire 64-probe scans
        // on the same tick. A deferred scan returns null; the caller backs off and retries on the
        // next repath cycle, spreading the cost over several ticks instead of one big spike.
        if (now != lastEscapeScanTick) {
            lastEscapeScanTick = now;
            escapeScansThisTick = 0;
        }
        if (escapeScansThisTick >= MAX_ESCAPE_SCANS_PER_TICK) {
            return null;
        }
        escapeScansThisTick++;
        int mobY = mob.blockPosition().getY();
        int herdY = mobY;
        BlockPos herdSurface = herdSurface(mob);
        if (herdSurface != null) {
            herdY = herdSurface.getY();
        }
        Path best = null;
        BlockPos bestStand = null;
        double bestScore = Double.MAX_VALUE;
        int checks = 0;
        int rejectBlacklist = 0;
        int rejectGap = 0;
        int rejectPath = 0;
        int rejectNull = 0;
        int rejNoCanReach = 0;
        int rejSteep = 0;
        int rejNoProgress = 0;
        int rejEndY = 0;
        int rejHazard = 0;
        int rejDip = 0;
        int rejNullPath = 0;
        int pathQueries = 0;
        int consecutiveNoCanReach = 0;
        int reachHerdY = 0;
        double mobAlphaDx = (mob.getX() + 0.5D) - (target.getX() + 0.5D);
        double mobAlphaDz = (mob.getZ() + 0.5D) - (target.getZ() + 0.5D);
        double mobAlphaSqr = mobAlphaDx * mobAlphaDx + mobAlphaDz * mobAlphaDz;
        double bestEndSqr = Double.MAX_VALUE;
        outerLoop:
        for (double ring : ESCAPE_RINGS) {
            for (double bearing : ESCAPE_BEARINGS) {
                if (++checks > MAX_ESCAPE_CHECKS) {
                    break outerLoop;
                }
                double heading = bearing;
                int x = Mth.floor(mob.getX() + 0.5 + Math.cos(heading) * ring);
                int z = Mth.floor(mob.getZ() + 0.5 + Math.sin(heading) * ring);
                BlockPos stand = Homes.surfaceStand(mob.level(), x, z);
                if (stand == null) {
                    rejectNull++;
                    continue;
                }
                if (isEscapeStandBlacklisted(mob, stand)) {
                    rejectBlacklist++;
                    continue;
                }
                boolean dry = Homes.isDryLand(mob.level(), stand);
                int gapNow = Math.abs(herdY - mobY);
                int gapThere = Math.abs(herdY - stand.getY());
                // A swimming mob rides the water surface and cannot climb out of the water:
                // any candidate stand whose surface sits ABOVE its own water level is an
                // impossible jump (e.g. a shore water column whose heightmap stand is one
                // block above the mob — the "swimming in place forever" trap). Only stands at
                // or below the mob's own water level count for a swimmer.
                if (mob.isInWater() && Homes.waterLevelY(mob.level(), mob.blockPosition()) < stand.getY()) {
                    rejectGap++;
                    continue;
                }
                // Jump-lip guard: a grounded mob cannot climb onto a stand more than
                // MAX_STEP_Y above its own feet. The pathfinder can report such a stand
                // "reachable" on a 1-block node the mob's actual jump never reliably clears,
                // which is the endless-jump-loop trap — so reject the stand up front.
                if (!mob.isInWater() && stand.getY() - mobY > MAX_STEP_Y) {
                    rejectGap++;
                    continue;
                }
                // Never choose a stand that makes the vertical separation from the herd worse.
                // This alone allows both climbing out of a pit (stand above the mob) and
                // descending off a ledge (stand below the mob) while preventing the mob from
                // tunneling deeper away from the herd's level.
                if (gapThere > gapNow + ESCAPE_MOVE_AWAY_TOLERANCE) {
                    rejectGap++;
                    continue;
                }
                // A mob that is genuinely separated (more than the tolerance below/above the
                // herd surface) must climb/descend — a reachable stand at its OWN level is not an
                // escape, it just re-picks a different spot in the pit. Accepting those made a
                // trapped mob return a useless path (so the failure memo never recorded) and
                // re-run all 64 probes every repath. When separated, only stands that get closer
                // to the herd's level count. A mob within the tolerance is treated as "nearly at
                // herd level" and any same-level reachable stand is a fine escape.
                if (gapNow > ESCAPE_MOVE_AWAY_TOLERANCE && gapThere >= gapNow) {
                    rejectGap++;
                    continue;
                }
                Path path = createPathWide(mob, stand);
                pathQueries++;
                if (!isValidFollowPath(mob.level(), path, mob.blockPosition(), stand, maxStepY,
                        1.0D, dipFloorY(mob, maxStepY))) {
                    rejectPath++;
                    String r = lastRejectReason;
                    switch (r == null ? "null" : r) {
                        case "no-canReach" -> {
                            rejNoCanReach++;
                            // A genuinely trapped mob makes dozens of widened pathfinder queries
                            // that all return "no-canReach". Once several directions in a row fail
                            // the same way, the rest will too — stop probing, remember the failure,
                            // and let the caller back off. This bounds a failed scan to a handful
                            // of queries instead of ~50 (~500ms).
                            if (++consecutiveNoCanReach >= MAX_ESCAPE_CONSECUTIVE_FAILS) {
                                break outerLoop;
                            }
                        }
                        case "steep" -> rejSteep++;
                        case "no-progress" -> rejNoProgress++;
                        case "end-y" -> rejEndY++;
                        case "hazard" -> rejHazard++;
                        case "dip" -> rejDip++;
                        default -> rejNullPath++;
                    }
                    continue;
                }
                consecutiveNoCanReach = 0;
                if (gapThere <= 2) {
                    reachHerdY++;
                }
                double endDx = (stand.getX() + 0.5) - (target.getX() + 0.5);
                double endDz = (stand.getZ() + 0.5) - (target.getZ() + 0.5);
                double endDistSqr = endDx * endDx + endDz * endDz;
                // Prefer the reachable stand that closes the vertical gap to the herd most
                // (climbing toward the rim or descending off the ledge), then the stand nearest
                // the alpha's station so the member heads toward the herd, not away from it.
                // Dry land always beats a water stand so a swimming member beaches.
                double score = (dry ? 0.0D : 65536.0D) + (double)gapThere * 1024.0D + endDistSqr;
                if (score < bestScore) {
                    bestScore = score;
                    bestEndSqr = endDistSqr;
                    best = path;
                    bestStand = stand;
                }
                // A dry stand at the herd's level that is reachable is a fully-good escape —
                // commit to it immediately instead of probing every remaining bearing. This
                // turns a successful scan from 64 pathfinder queries into typically a handful,
                // which matters when a whole herd escapes at once. (A water stand is only
                // acceptable when no dry stand exists, so it never short-circuits here.)
                // A far member must not short-circuit on the first same-level stand though:
                // the rings start at bearing 0 (east), so an arbitrary 3-block stand usually
                // does not head toward the alpha (observed: members 40-60 blocks from the
                // alpha "escaping" onto stands that left them just as far away, or farther).
                // Only early-out when the stand is strictly closer to the alpha than the mob
                // currently is; otherwise keep probing bearings until one heads toward the herd.
                if (dry && gapThere <= 1 && endDistSqr <= 4096.0D
                        && endDistSqr < mobAlphaSqr) {
                    break outerLoop;
                }
            }
        }
        // Beach pass: a member floating in open water has NO working fallback above — the
        // strict scan penalizes/rejects water stands it cannot climb out of, and both the
        // relaxed deep pass and the floor walk explicitly skip swimming mobs, so a swimmer
        // that finds no valid stand records a failure memo and freezes mid-lake ("escaping"
        // while drifting). Reuse the SeekShoreGoal ring strategy here: expand outward over
        // heightmap dry stands and commit to the reachable one closest to the alpha's
        // station. The dip guard stays off because swimming below herd level IS the
        // situation; climbing out at the shore closes the gap afterwards.
        if (mob.isInWater() && (best == null || bestStand == null || !Homes.isDryLand(mob.level(), bestStand))) {
            Level beachLevel = mob.level();
            BlockPos beachOrigin = mob.blockPosition();
            double beachBestScore = Double.MAX_VALUE;
            Path beachBest = null;
            BlockPos beachStand = null;
            int beachChecks = 0;
            int beachFails = 0;
            beachLoop:
            for (int ring = 3; ring <= 24 && beachChecks <= MAX_ESCAPE_CHECKS / 2; ring += 3) {
                for (int dx = -ring; dx <= ring; dx += ring) {
                    for (int dz = -ring; dz <= ring; ++dz) {
                        Path path;
                        BlockPos stand = beachLevel.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                new BlockPos(beachOrigin.getX() + dx, 0, beachOrigin.getZ() + dz)).above();
                        if (!Homes.isDryLand(beachLevel, stand) || isEscapeStandBlacklisted(mob, stand)) {
                            continue;
                        }
                        if (++beachChecks > MAX_ESCAPE_CHECKS / 2) {
                            break beachLoop;
                        }
                        path = createPathWide(mob, stand);
                        if (!isValidFollowPath(beachLevel, path, beachOrigin, stand, maxStepY, 1.0D)) {
                            if ("no-canReach".equals(lastRejectReason)
                                    && ++beachFails >= MAX_ESCAPE_CONSECUTIVE_FAILS) {
                                break beachLoop;
                            }
                            continue;
                        }
                        beachFails = 0;
                        double bDx = (stand.getX() + 0.5) - (target.getX() + 0.5);
                        double bDz = (stand.getZ() + 0.5) - (target.getZ() + 0.5);
                        double score = bDx * bDx + bDz * bDz;
                        if (score < beachBestScore) {
                            beachBestScore = score;
                            beachBest = path;
                            beachStand = stand;
                        }
                    }
                }
            }
            if (beachBest != null) {
                // The chosen shore stand may hug a wall corner right at the waterline — a
                // convex corner the swimmer's AABB clips forever once it reaches the edge.
                // Shift the stand into the open side of the wall and re-validate the route.
                BlockPos offset = Homes.offsetStandFromCorners(beachLevel, beachStand);
                if (offset != beachStand && offset != null) {
                    Path offsetPath = createPathWide(mob, offset);
                    if (isValidFollowPath(beachLevel, offsetPath, beachOrigin, offset, maxStepY, 1.0D)) {
                        beachBest = offsetPath;
                        beachStand = offset;
                    }
                }
                best = beachBest;
                bestStand = beachStand;
            }
        }
        // The strict scan only accepts stands that close the vertical gap to the herd's
        // surface. Some caves cannot be escaped that way — the only route out runs DEEPER
        // into the cave first, then back toward the surface, so every gap-away stand was
        // rejected above. A same-level "best" is also not a real escape for a boxed member
        // (it just re-picks another floor spot in the pit). As a last resort, accept any
        // reachable dry stand that moves the member vertically, preferring the one closest
        // to the herd's level, so a genuinely trapped member descends to the cave's exit
        // instead of standing frozen while the failure memo suppresses re-scans.
        boolean strictMovedVertically = bestStand != null
                && Math.abs(bestStand.getY() - mobY) >= HOLE_DEPTH;
        // The relaxed deep pass exists for cave exits where the only route out runs DEEPER
        // first. For a mob already at/near the herd's surface it is destructive: it accepts
        // any stand 3+ blocks away and re-commits a member that just climbed out back DOWN
        // the wall (observed: cow at mobY=96, herdY=98 re-committed to a stand 5 below).
        // Gate it to genuinely deep positions and let the floor-walk pass carry near-surface
        // members along their own level toward the alpha instead.
        boolean relaxedDeep = herdY - mobY > ESCAPE_LEVEL_GAP;
        if (!strictMovedVertically && !mob.isInWater() && relaxedDeep) {
        } else if (!strictMovedVertically && !mob.isInWater()) {
        }
        if (!strictMovedVertically && !mob.isInWater() && relaxedDeep) {
            bestScore = Double.MAX_VALUE; // discard the strict same-level re-pick
            int relaxedChecks = 0;
            int relaxedConsecFails = 0;
            relaxedLoop:
            for (double ring : ESCAPE_RINGS) {
                for (double bearing : ESCAPE_BEARINGS) {
                    if (++relaxedChecks > MAX_ESCAPE_CHECKS / 2) {
                        break relaxedLoop;
                    }
                    int rx = Mth.floor(mob.getX() + 0.5 + Math.cos(bearing) * ring);
                    int rz = Mth.floor(mob.getZ() + 0.5 + Math.sin(bearing) * ring);
                    BlockPos stand = Homes.surfaceStand(mob.level(), rx, rz);
                    if (stand == null || isEscapeStandBlacklisted(mob, stand)) {
                        continue;
                    }
                    // Must move vertically — a same-floor stand is just a re-pick in the pit.
                    if (Math.abs(stand.getY() - mobY) < HOLE_DEPTH) {
                        continue;
                    }
                    // Never escape deeper into water; a swimming member beaches instead.
                    if (!Homes.isDryLand(mob.level(), stand)) {
                        continue;
                    }
                    Path path = createPathWide(mob, stand);
                    pathQueries++;
                    if (!isValidFollowPath(mob.level(), path, mob.blockPosition(), stand, maxStepY, 1.0D)) {
                        if ("no-canReach".equals(lastRejectReason)
                                && ++relaxedConsecFails >= MAX_ESCAPE_CONSECUTIVE_FAILS) {
                            break relaxedLoop;
                        }
                        continue;
                    }
                    relaxedConsecFails = 0;
                    int relaxedGap = Math.abs(herdY - stand.getY());
                    double endDx = (stand.getX() + 0.5) - (target.getX() + 0.5);
                    double endDz = (stand.getZ() + 0.5) - (target.getZ() + 0.5);
                    double score = (double) relaxedGap * 1024.0D + endDx * endDx + endDz * endDz;
                    if (score < bestScore) {
                        bestScore = score;
                        bestEndSqr = endDx * endDx + endDz * endDz;
                        best = path;
                        bestStand = stand;
                    }
                }
            }
            if (best != null && bestStand != null) {
            }
        }
        // Floor-walk pass: a member at the bottom of a wide pit whose ramp sits beyond the
        // scan rings can neither climb from where it stands nor reach any gap-closing stand,
        // and the failure memo then freezes it in place. As an absolute last resort accept
        // ANY reachable dry stand — including one at the member's OWN level, which the strict
        // and relaxed passes deliberately exclude — preferring the one nearest the alpha's
        // column. Each committed floor stand moves the member toward the herd; moving clears
        // the failure memo, so the next scan sees the ramp from the new position. Only fires
        // when nothing else was found, and is bounded to half the escape budget.
        if (best == null && !mob.isInWater()) {
            bestScore = Double.MAX_VALUE;
            int floorWalkChecks = 0;
            int floorWalkFails = 0;
            floorWalkLoop:
            for (double ring : ESCAPE_RINGS) {
                for (double bearing : ESCAPE_BEARINGS) {
                    if (++floorWalkChecks > MAX_ESCAPE_CHECKS / 2) {
                        break floorWalkLoop;
                    }
                    int fx = Mth.floor(mob.getX() + 0.5 + Math.cos(bearing) * ring);
                    int fz = Mth.floor(mob.getZ() + 0.5 + Math.sin(bearing) * ring);
                    BlockPos stand = Homes.surfaceStand(mob.level(), fx, fz);
                    if (stand == null || isEscapeStandBlacklisted(mob, stand)) {
                        continue;
                    }
                    if (!Homes.isDryLand(mob.level(), stand)) {
                        continue;
                    }
                    Path path = createPathWide(mob, stand);
                    pathQueries++;
                    if (!isValidFollowPath(mob.level(), path, mob.blockPosition(), stand, maxStepY, 1.0D)) {
                        if ("no-canReach".equals(lastRejectReason)
                                && ++floorWalkFails >= MAX_ESCAPE_CONSECUTIVE_FAILS) {
                            break floorWalkLoop;
                        }
                        continue;
                    }
                    floorWalkFails = 0;
                    double endDx = (stand.getX() + 0.5) - (target.getX() + 0.5);
                    double endDz = (stand.getZ() + 0.5) - (target.getZ() + 0.5);
                    double score = endDx * endDx + endDz * endDz;
                    if (score < bestScore) {
                        bestScore = score;
                        bestEndSqr = score;
                        best = path;
                        bestStand = stand;
                    }
                }
            }
            if (best != null && bestStand != null) {
            }
        }
        lastEscapeStand = bestStand;
        if (best == null) {
            // A scan cut short by the widened-path budget did NOT prove the area has no
            // escape route — the failure memo would suppress re-scans for 3600 ticks and
            // strand the member. Only remember the failure when the full scan really ran.
            if (!wideBudgetExhausted()) {
                rememberEscapeFailure(mob);
            }
        } else {
            ESCAPE_FAILURE_MEMO.remove(mob.getUUID());
            // Publish the proven escape route to the herd so a separated herd-mate can adopt
            // this stand with a single widened path query instead of a full 64-probe scan.
            if (bestStand != null && mob instanceof Animal animal) {
                HerdManager.Herd herd = HerdManager.herdOf(animal);
                if (herd != null) {
                    herd.setEscapeShare(bestStand, now);
                }
            }
        }
        return best;
    }

    /**
     * True when the mob is horizontally within {@code stopDist} of the target column
     * AND standing at the same surface level as the target's surface stand. A member
     * in a cave below the target has a mismatched Y and never counts as arrived.
     */
    public static boolean arrived(PathfinderMob mob, double stopDist, BlockPos targetXZ) {
        double dx = mob.getX() - (targetXZ.getX() + 0.5);
        double dz = mob.getZ() - (targetXZ.getZ() + 0.5);
        if (dx * dx + dz * dz > stopDist * stopDist) {
            return false;
        }
        BlockPos targetStand = Homes.surfaceStand(mob.level(), targetXZ.getX(), targetXZ.getZ());
        if (targetStand == null) {
            return false;
        }
        return Math.abs(mob.blockPosition().getY() - targetStand.getY()) <= END_Y_TOLERANCE;
    }

    private static boolean isValidFollowPath(Level level, Path path, BlockPos origin, BlockPos stand, int maxStepY) {
        return isValidFollowPath(level, path, origin, stand, maxStepY, MIN_HORIZONTAL_PROGRESS, NO_DIP_GUARD);
    }

    private static boolean isValidFollowPath(Level level, Path path, BlockPos origin, BlockPos stand, int maxStepY, double minProgress) {
        return isValidFollowPath(level, path, origin, stand, maxStepY, minProgress, NO_DIP_GUARD);
    }

    /** Sentinel: no dip guard — the path may dive below the origin freely. Used by the
     *  relaxed cave-exit and floor-walk passes, where descending first is legitimate. */
    private static final int NO_DIP_GUARD = Integer.MAX_VALUE;

    /** Y floor for the dip guard: follow/escape paths may not dive below this when the mob
     *  is below-but-near the herd's surface, so an escaped member walks around the ditch at
     *  herd level instead of the pathfinder's shorter "dive in, climb out" route back down.
     *  Returns {@link #NO_DIP_GUARD} when the mob is deep in a pit (floor-walking is the
     *  correct direction), when the herd is at/below the mob (descending is correct), or
     *  when there is no herd to guard against. */
    private static int dipFloorY(PathfinderMob mob, int maxStepY) {
        BlockPos herd = herdSurface(mob);
        if (herd == null) {
            return NO_DIP_GUARD;
        }
        int mobY = mob.blockPosition().getY();
        int herdY = herd.getY();
        if (herdY <= mobY) {
            return NO_DIP_GUARD;
        }
        if (herdY - mobY > ESCAPE_LEVEL_GAP + maxStepY) {
            return NO_DIP_GUARD;
        }
        return mobY - maxStepY;
    }

    private static boolean isValidFollowPath(Level level, Path path, BlockPos origin, BlockPos stand, int maxStepY, double minProgress, int dipFloorY) {
        if (path == null || path.getNodeCount() <= 0 || !path.canReach()) {
            lastRejectReason = path == null ? "null" : (!path.canReach() ? "no-canReach" : "no-nodes");
            return false;
        }
        // Cheap O(1)/no-block-I/O predicates first, so most rejects never pay the hazard
        // scan (the only block-I/O predicates here). Conjunction order is pure: all six
        // are functions of the path, so the outcome is identical.
        if (!Homes.pathHasHorizontalProgress(path, origin, minProgress)) {
            lastRejectReason = "no-progress";
            return false;
        }
        BlockPos end = path.getNodePos(path.getNodeCount() - 1);
        if (Math.abs(end.getY() - stand.getY()) > END_Y_TOLERANCE) {
            lastRejectReason = "end-y";
            return false;
        }
        if (Homes.pathHasSteepStep(path, maxStepY)) {
            lastRejectReason = "steep";
            return false;
        }
        if (dipFloorY != NO_DIP_GUARD) {
            int minY = Integer.MAX_VALUE;
            for (int i = 0; i < path.getNodeCount(); i++) {
                int ny = path.getNodePos(i).getY();
                if (ny < minY) {
                    minY = ny;
                }
            }
            if (minY < dipFloorY) {
                lastRejectReason = "dip";
                return false;
            }
        }
        if (Avoidance.pathTouchesHardHazard(level, path)
                || Avoidance.pathPassesNearHazard(level, path, Avoidance.HAZARD_AVOID_RADIUS)) {
            lastRejectReason = "hazard";
            return false;
        }
        lastRejectReason = "ok";
        return true;
    }
}
