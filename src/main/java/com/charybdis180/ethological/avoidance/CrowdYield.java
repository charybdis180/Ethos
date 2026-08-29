package com.charybdis180.ethological.avoidance;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.config.EthologicalConfig;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.MotherData;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Detects a pathing animal that is stuck against a stationary animal and records an
 * ephemeral "yield request" on the stationary blocker so its {@link
 * com.charybdis180.ethological.avoidance.goal.YieldGoal} can step it aside.
 *
 * <p>Cost model: a bounded, UUID-keyed stuck tracker written only while the mover has an
 * active path (one map entry + a distance compare per gated call), and an AABB entity
 * probe that runs only after {@link #STUCK_TICKS} of no progress, then at most once per
 * {@link #REQUEST_COOLDOWN} per mover. Expired requests are dropped lazily inside
 * {@link #hasActiveRequest}, so the registry cannot grow stale.</p>
 */
public final class CrowdYield {
    /** Mover UUID -> {lastX10, lastZ10, lastTick, stationaryTicks, cooldownUntil}. */
    private static final Map<UUID, long[]> STUCK_STATE = new ConcurrentHashMap<>();
    /** Blocker UUID -> {heading bits (mover's path heading, radians), dueGameTime}. */
    private static final Map<UUID, long[]> YIELD_REQUESTS = new ConcurrentHashMap<>();
    /** Blunt caps, matching the HAZARD_MEMORY / OCCUPIED overflow-clear pattern. */
    private static final int STUCK_LIMIT = 4096;
    private static final int YIELD_REQUEST_LIMIT = 1024;

    /** Accumulated stationary ticks (while pathing) before the probe fires. */
    private static final long STUCK_TICKS = 30L;
    /** Position compare scale: 0.1-block resolution in the packed ints below. */
    private static final int POS_SCALE = 10;
    /** Squared move threshold in 0.1-block units: 0.2 blocks => 2 units => 4 sq. */
    private static final long STUCK_MOVE_SQR = 4L;
    /** Ticks a yield request stays valid on the blocker. */
    private static final long REQUEST_TTL = 120L;
    /** Per-mover cooldown after a probe, so a stuck mover cannot spam requests. */
    private static final long REQUEST_COOLDOWN = 100L;
    /** Probe distances ahead of the mover along its heading. */
    private static final double[] PROBE_DISTANCES = {1.5D, 2.0D, 2.5D};

    private CrowdYield() {
    }

    /**
     * Per-mover stuck tracking. Called on a 5-tick gate from the tick event. Drops the
     * entry when the mover has no active path, otherwise accumulates stationary time and
     * fires {@link #requestYieldFromBlocker} once the mover has been making no progress
     * for {@link #STUCK_TICKS} and the per-mover cooldown has elapsed.
     */
    public static void tick(Animal mover, ServerLevel level, long now) {
        if (!EthologicalConfig.CONFIG.comfort.yieldOnBlock.get()) {
            return;
        }
        Path path = mover.getNavigation().getPath();
        if (path == null || mover.getNavigation().isDone()) {
            STUCK_STATE.remove(mover.getUUID());
            return;
        }
        long[] state = STUCK_STATE.computeIfAbsent(mover.getUUID(),
                k -> new long[] {0L, 0L, now, 0L, Long.MIN_VALUE});
        int x10 = (int)(mover.getX() * POS_SCALE);
        int z10 = (int)(mover.getZ() * POS_SCALE);
        long dx = (long)x10 - state[0];
        long dz = (long)z10 - state[1];
        boolean barelyMoved = state[2] != now && dx * dx + dz * dz < STUCK_MOVE_SQR;
        state[0] = x10;
        state[1] = z10;
        long delta = now - state[2];
        state[2] = now;
        if (barelyMoved) {
            state[3] += delta;
            if (state[3] >= STUCK_TICKS && now >= state[4]) {
                state[4] = now + REQUEST_COOLDOWN;
                state[3] = 0L;
                requestYieldFromBlocker(mover, level, now);
            }
        } else {
            state[3] = 0L;
        }
        if (STUCK_STATE.size() >= STUCK_LIMIT) {
            STUCK_STATE.clear();
        }
    }

    /** Probes ahead of a stuck mover and records a yield request on the nearest eligible
     *  stationary animal found. Also bounds the request registry by overflow-clear. */
    private static void requestYieldFromBlocker(Animal mover, ServerLevel level, long now) {
        double heading = moverHeading(mover);
        Vec3 pos = mover.position();
        for (double dist : PROBE_DISTANCES) {
            double px = pos.x + Math.cos(heading) * dist;
            double pz = pos.z + Math.sin(heading) * dist;
            AABB box = new AABB(px - 0.5D, pos.y, pz - 0.5D, px + 0.5D, pos.y + 1.5D, pz + 0.5D)
                    .inflate(1.0D);
            Animal best = null;
            double bestDistSqr = Double.MAX_VALUE;
            for (Animal other : level.getEntitiesOfClass(Animal.class, box)) {
                if (other == mover || !isYieldableBlocker(other, now)) {
                    continue;
                }
                double distSqr = other.distanceToSqr(pos);
                if (distSqr < bestDistSqr) {
                    bestDistSqr = distSqr;
                    best = other;
                }
            }
            if (best != null) {
                YIELD_REQUESTS.put(best.getUUID(),
                        new long[] {Double.doubleToLongBits(heading), now + REQUEST_TTL});
                if (YIELD_REQUESTS.size() >= YIELD_REQUEST_LIMIT) {
                    YIELD_REQUESTS.clear();
                }
                return;
            }
        }
    }

    /**
     * A valid blocker is alive, awake, calm (no panic / play / startle), genuinely
     * stationary (navigation done — a ruminator, rester, or idle stander), and not a
     * mothered baby with an active link. An active follower/eater/drinker/migrator has an
     * active path and is never picked. An animal already holding a live yield request is
     * skipped so multiple movers cannot overwrite the blocker's request.
     */
    private static boolean isYieldableBlocker(Animal other, long now) {
        if (other.getData(ModAttachments.SLEEPING).booleanValue()
                || other.hasData(ModAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        if (HerdManager.panicPhaseOf(other, now) != HerdManager.PanicPhase.NONE) {
            return false;
        }
        if (other.hasData(ModAttachments.PLAY) || other.hasData(ModAttachments.STARTLE)) {
            return false;
        }
        if (!other.getNavigation().isDone()) {
            return false;
        }
        if (other.isBaby() && other.hasData(ModAttachments.MOTHER)
                && ((MotherData)other.getData(ModAttachments.MOTHER)).isActive(now)) {
            return false;
        }
        return !hasActiveRequest(other, now);
    }

    /** True while the blocker holds a live (unexpired) yield request. Expired requests are
     *  dropped lazily here so the registry never grows stale. */
    public static boolean hasActiveRequest(Animal blocker, long now) {
        long[] req = YIELD_REQUESTS.get(blocker.getUUID());
        if (req == null) {
            return false;
        }
        if (now >= req[1]) {
            YIELD_REQUESTS.remove(blocker.getUUID());
            return false;
        }
        return true;
    }

    /** The mover-heading (radians) stored on the blocker's request, or 0 when none. */
    public static double moverHeadingOf(Animal blocker) {
        long[] req = YIELD_REQUESTS.get(blocker.getUUID());
        return req == null ? 0.0D : Double.longBitsToDouble(req[0]);
    }

    /** Game time the blocker's request expires, or {@link Long#MIN_VALUE} when none. */
    public static long requestDueOf(Animal blocker) {
        long[] req = YIELD_REQUESTS.get(blocker.getUUID());
        return req == null ? Long.MIN_VALUE : req[1];
    }

    /** Drop the blocker's yield request (on arrival or interruption). */
    public static void clearRequest(Animal blocker) {
        YIELD_REQUESTS.remove(blocker.getUUID());
    }

    /** Heading from the mover toward its path target, falling back to body yaw. */
    private static double moverHeading(Animal mover) {
        Path path = mover.getNavigation().getPath();
        if (path != null && path.getTarget() != null) {
            double dx = path.getTarget().getX() + 0.5D - mover.getX();
            double dz = path.getTarget().getZ() + 0.5D - mover.getZ();
            if (dx * dx + dz * dz > 1.0E-4D) {
                return Math.atan2(dz, dx);
            }
        }
        return Math.toRadians(mover.yBodyRot);
    }
}
