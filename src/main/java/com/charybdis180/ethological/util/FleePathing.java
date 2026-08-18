package com.charybdis180.ethological.util;

import com.charybdis180.ethological.avoidance.Avoidance;
import com.charybdis180.ethological.avoidance.CrowdGrid;
import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class FleePathing {
    /** Only the farthest few candidates get a real pathfinder query; the rest are rejected cheaply. */
    private static final int PATH_QUERIES = 2;
    /** Crowd bonus subtracted from a flee candidate's ranking per nearby occupied cell, so
     *  near-ties flee into open ground instead of through the herd (which causes pile-ups). */
    private static final double CROWD_BONUS = 6.0D;

    private FleePathing() {
    }

    public static Path beelineAway(PathfinderMob mob, Vec3 threatPos, double distance, int attempts) {
        return beelineAway(mob, threatPos, distance, attempts, null);
    }

    /**
     * @param streamBias optional direction to blend into the flee heading (~0.3 weight)
     *                   so herds stream together instead of star-bursting.
     */
    public static Path beelineAway(
            PathfinderMob mob,
            Vec3 threatPos,
            double distance,
            int attempts,
            @Nullable Vec3 streamBias) {
        Path path;
        Vec3 away = FleePathing.directAway(mob, threatPos, distance);
        if (away != null) {
            away = FleePathing.blendAway(mob.position(), away, streamBias, distance);
            if ((path = mob.getNavigation().createPath(away.x, away.y, away.z, 1)) != null
                    && path.canReach()
                    && isSafeFleePath(mob, path)) {
                return path;
            }
        }
        Path bestPath = null;
        double bestDistanceSqr = -1.0;
        // Cheap pass: generate and blend every candidate, then rank them by distance from the
        // threat — the same criterion that picks the winner. Only the top few get a real
        // pathfinder query, and they're checked best-first so the chosen path matches the old
        // "farthest safe" result whenever it was reachable.
        ArrayList<Vec3> candidates = new ArrayList<>();
        for (int i = 0; i < attempts; ++i) {
            Vec3 candidate = DefaultRandomPos.getPosAway(
                    (PathfinderMob)mob,
                    (int)((int)distance),
                    (int)((int)(distance / 2.0)),
                    (Vec3)threatPos);
            if (candidate == null) {
                continue;
            }
            candidate = FleePathing.blendAway(mob.position(), candidate, streamBias, distance);
            candidates.add(candidate);
        }
        candidates.sort(Comparator.comparingDouble((Vec3 v) ->
                v.distanceToSqr(threatPos) - CROWD_BONUS * blockedCellsNear(mob, v)).reversed());
        int pathable = Math.min(PATH_QUERIES, candidates.size());
        for (int i = 0; i < pathable; ++i) {
            Vec3 candidate = candidates.get(i);
            double distanceSqr = candidate.distanceToSqr(threatPos);
            Path path2 = mob.getNavigation().createPath(candidate.x, candidate.y, candidate.z, 1);
            if (path2 == null
                    || !path2.canReach()
                    || !isSafeFleePath(mob, path2)
                    || !(distanceSqr > bestDistanceSqr)) {
                continue;
            }
            bestDistanceSqr = distanceSqr;
            bestPath = path2;
        }
        return bestPath;
    }

    /** Counts occupied crowd cells within 2 blocks of a candidate flee column (the candidate
     *  cell plus its 4 orthogonal neighbors), so the ranking can bias toward open ground. */
    private static double blockedCellsNear(PathfinderMob mob, Vec3 pos) {
        Level level = mob.level();
        int x = Mth.floor(pos.x);
        int z = Mth.floor(pos.z);
        int yBucket = Mth.floor(pos.y) >> 2;
        BlockPos self = mob.blockPosition();
        int count = 0;
        if (CrowdGrid.isBlockedXZ(level, x, z, yBucket, self)) {
            ++count;
        }
        if (CrowdGrid.isBlockedXZ(level, x + 1, z, yBucket, self)) {
            ++count;
        }
        if (CrowdGrid.isBlockedXZ(level, x - 1, z, yBucket, self)) {
            ++count;
        }
        if (CrowdGrid.isBlockedXZ(level, x, z + 1, yBucket, self)) {
            ++count;
        }
        if (CrowdGrid.isBlockedXZ(level, x, z - 1, yBucket, self)) {
            ++count;
        }
        return count;
    }

    public static Vec3 directAway(PathfinderMob mob, Vec3 threatPos, double distance) {        Vec3 mobPos = mob.position();
        Vec3 delta = mobPos.subtract(threatPos);
        double lengthSqr = delta.lengthSqr();
        if (lengthSqr < 1.0E-4) {
            return null;
        }
        return mobPos.add(delta.normalize().scale(distance));
    }

    private static Vec3 blendAway(Vec3 mobPos, Vec3 awayTarget, @Nullable Vec3 streamBias, double distance) {
        if (streamBias == null || streamBias.lengthSqr() < 1.0E-6) {
            return awayTarget;
        }
        Vec3 primary = awayTarget.subtract(mobPos);
        if (primary.lengthSqr() < 1.0E-6) {
            return awayTarget;
        }
        Vec3 blended = primary.normalize().scale(0.7).add(streamBias.normalize().scale(0.3));
        if (blended.lengthSqr() < 1.0E-6) {
            return awayTarget;
        }
        return mobPos.add(blended.normalize().scale(distance));
    }

    /** Reject flee routes through open water or hard hazards when possible. */
    private static boolean isSafeFleePath(PathfinderMob mob, Path path) {
        Level level = mob.level();
        return !Avoidance.pathIsUnsafe(level, path);
    }
}
