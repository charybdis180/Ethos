package com.charybdis180.ethological.home;

import com.charybdis180.ethological.avoidance.Avoidance;
import com.charybdis180.ethological.avoidance.CliffAvoidance;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Chooses a safe, low-effort direction for a nomadic herd alpha.
 *
 * <p>Terrain is sampled cheaply for all headings first. Minecraft navigation
 * is only invoked for the best few headings, keeping planning bounded even
 * when many herds are migrating at once.</p>
 */
public final class MigrationPathPlanner {
    private static final double LOOKAHEAD = 32.0D;
    private static final double SAMPLE_STEP = 8.0D;
    private static final int PATH_CANDIDATES = 3;
    private static final double MIN_HORIZONTAL_PROGRESS = 3.0D;
    private static final int MAX_PATH_STEP_Y = 4;
    /** Cost penalty per water sample so rivers are crossed but not preferred over dry routes. */
    private static final double WATER_COST = 6.0D;
    /** Cost penalty per occupied mid-path node, scaled to {@code pathCost}'s units
     *  (routeLength*0.15 etc.). One crowded node is roughly the cost of a small detour. */
    private static final double CROWD_PENALTY_PATH = 2.0D;

    private static final double[] HEADING_OFFSETS = {
            0.0D,
            Math.PI / 12.0D, -Math.PI / 12.0D,
            Math.PI / 6.0D, -Math.PI / 6.0D,
            Math.PI / 4.0D, -Math.PI / 4.0D,
            Math.PI / 3.0D, -Math.PI / 3.0D,
            Math.PI / 2.0D, -Math.PI / 2.0D,
            Math.PI * 2.0D / 3.0D, -Math.PI * 2.0D / 3.0D,
            Math.PI * 5.0D / 6.0D, -Math.PI * 5.0D / 6.0D,
            Math.PI
    };

    private MigrationPathPlanner() {
    }

    public record Plan(double heading, Path path, double score) {
    }

    public static Plan findBest(Animal mob, double preferredHeading) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return null;
        }

        BlockPos origin = mob.blockPosition();
        List<HeadingCandidate> terrainCandidates = new ArrayList<>();
        for (double offset : HEADING_OFFSETS) {
            double heading = normalize(preferredHeading + offset);
            double terrainCost = terrainCost(level, origin, heading);
            if (Double.isInfinite(terrainCost)) {
                continue;
            }

            // Preserve a usable heading when two routes are otherwise similar.
            terrainCost += Math.abs(angleDifference(heading, preferredHeading)) * 2.0D;
            terrainCandidates.add(new HeadingCandidate(heading, terrainCost));
        }

        terrainCandidates.sort(Comparator.comparingDouble(HeadingCandidate::score));
        Plan best = null;
        int checked = Math.min(PATH_CANDIDATES, terrainCandidates.size());
        for (int i = 0; i < checked; ++i) {
            HeadingCandidate candidate = terrainCandidates.get(i);
            BlockPos target = surfaceTarget(level, mob, candidate.heading());
            if (target == null) {
                continue;
            }
            // Never commit a migration leg that ends on a cliff lip.
            if (!CliffAvoidance.isEdgeSafe(level, target)) {
                continue;
            }

            Path path = mob.getNavigation().createPath(
                    target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1);
            if (!usablePath(level, path, origin)) {
                continue;
            }

            double score = candidate.score() + pathCost(level, path, origin);
            if (best == null || score < best.score()) {
                best = new Plan(candidate.heading(), path, score);
            }
        }
        return best;
    }

    private static double terrainCost(ServerLevel level, BlockPos origin, double heading) {
        int previousY = surfaceStandY(level, origin.getX(), origin.getZ());
        if (previousY == Integer.MIN_VALUE) {
            previousY = waterStandY(level, origin.getX(), origin.getZ());
            if (previousY == Integer.MIN_VALUE) {
                return Double.POSITIVE_INFINITY;
            }
        }

        double cost = 0.0D;
        int previousDelta = 0;
        int cumulativeClimb = 0;
        boolean sampled = false;
        for (double distance = SAMPLE_STEP; distance <= LOOKAHEAD + 0.01D; distance += SAMPLE_STEP) {
            int x = Mth.floor(origin.getX() + 0.5D + Math.cos(heading) * distance);
            int z = Mth.floor(origin.getZ() + 0.5D + Math.sin(heading) * distance);
            if (!level.hasChunkAt(new BlockPos(x, origin.getY(), z))) {
                continue;
            }
            // A heading whose corridor crosses a hard hazard (lava, fire) is dead on
            // arrival — reject it before the pathfinder is ever asked.
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            if (Avoidance.isHardHazard(level, surface.above()) || Avoidance.isHardHazard(level, surface)) {
                return Double.POSITIVE_INFINITY;
            }
            int y = surfaceStandY(level, x, z);
            if (y == Integer.MIN_VALUE) {
                y = waterStandY(level, x, z);
                if (y == Integer.MIN_VALUE) {
                    return Double.POSITIVE_INFINITY;
                }
                cost += WATER_COST;
            }

            int delta = y - previousY;
            int absoluteDelta = Math.abs(delta);
            if (absoluteDelta > 6) {
                return Double.POSITIVE_INFINITY;
            }
            if (delta > 0 && (cumulativeClimb += delta) > 12) {
                return Double.POSITIVE_INFINITY;
            }
            cost += absoluteDelta * 1.5D;
            cost += Math.max(0, delta) * 0.75D;
            cost += Math.abs(delta - previousDelta) * 0.5D;
            previousDelta = delta;
            previousY = y;
            sampled = true;
        }
        return sampled ? cost : Double.POSITIVE_INFINITY;
    }

    private static BlockPos surfaceTarget(ServerLevel level, Animal mob, double heading) {
        int x = Mth.floor(mob.getX() + 0.5D + Math.cos(heading) * LOOKAHEAD);
        int z = Mth.floor(mob.getZ() + 0.5D + Math.sin(heading) * LOOKAHEAD);
        BlockPos column = new BlockPos(x, mob.blockPosition().getY(), z);
        if (!level.hasChunkAt(column)) {
            return null;
        }

        BlockPos surface = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
        BlockPos stand = surface.above();
        if (Homes.isDryLand(level, stand)) {
            return stand;
        }
        return Homes.isDryLand(level, surface) ? surface : null;
    }

    private static boolean usablePath(ServerLevel level, Path path, BlockPos origin) {
        if (path == null || path.getNodeCount() <= 0 || !path.canReach()) {
            return false;
        }
        // Hard hazards (lava, cacti) still block migration; open water is allowed
        // so herds can wade rivers instead of turning back.
        if (Avoidance.pathTouchesHardHazard(level, path)
                || Avoidance.pathPassesNearHazard(level, path, Avoidance.HAZARD_AVOID_RADIUS)
                || Homes.pathHasSteepStep(path, MAX_PATH_STEP_Y)
                || !Homes.pathHasHorizontalProgress(path, origin, MIN_HORIZONTAL_PROGRESS)) {
            return false;
        }
        return true;
    }

    private static double pathCost(ServerLevel level, Path path, BlockPos origin) {
        double routeLength = 0.0D;
        double verticalMovement = 0.0D;
        int previousY = path.getNodePos(0).getY();
        for (int i = 1; i < path.getNodeCount(); ++i) {
            BlockPos previous = path.getNodePos(i - 1);
            BlockPos current = path.getNodePos(i);
            double dx = current.getX() - previous.getX();
            double dy = current.getY() - previous.getY();
            double dz = current.getZ() - previous.getZ();
            routeLength += Math.sqrt(dx * dx + dy * dy + dz * dz);
            verticalMovement += Math.abs(current.getY() - previousY);
            previousY = current.getY();
        }

        BlockPos end = path.getNodePos(path.getNodeCount() - 1);
        double dx = end.getX() + 0.5D - (origin.getX() + 0.5D);
        double dz = end.getZ() + 0.5D - (origin.getZ() + 0.5D);
        double horizontalProgress = Math.sqrt(dx * dx + dz * dz);
        // Crowding only nudges the score — a herd still chooses a slightly longer clean route
        // over a straight one through standing herd-mates, but never refuses the only option.
        double crowded = com.charybdis180.ethological.avoidance.CrowdGrid.countCrowdedNodes(level, path, origin, 2, 3);
        return routeLength * 0.15D + verticalMovement * 2.0D - horizontalProgress * 0.2D
                + CROWD_PENALTY_PATH * crowded;
    }

    private static int surfaceStandY(ServerLevel level, int x, int z) {
        BlockPos column = new BlockPos(x, 0, z);
        if (!level.hasChunkAt(column)) {
            return Integer.MIN_VALUE;
        }
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
        BlockPos stand = surface.above();
        if (Homes.isDryLand(level, stand)) {
            return stand.getY();
        }
        return Homes.isDryLand(level, surface) ? surface.getY() : Integer.MIN_VALUE;
    }

    /** Topmost water surface Y for the column at x,z, or MIN_VALUE when it is dry land. */
    private static int waterStandY(ServerLevel level, int x, int z) {
        BlockPos column = new BlockPos(x, 0, z);
        if (!level.hasChunkAt(column)) {
            return Integer.MIN_VALUE;
        }
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
        BlockPos water = null;
        if (level.getFluidState(surface).is(FluidTags.WATER)) {
            water = surface;
        } else if (level.getFluidState(surface.above()).is(FluidTags.WATER)) {
            water = surface.above();
        } else if (level.getFluidState(surface.below()).is(FluidTags.WATER)) {
            water = surface.below();
        }
        if (water == null) {
            return Integer.MIN_VALUE;
        }
        BlockPos top = water;
        while (level.getFluidState(top.above()).is(FluidTags.WATER)) {
            top = top.above();
        }
        return top.getY();
    }

    private static double normalize(double angle) {
        double normalized = angle % (Math.PI * 2.0D);
        return normalized < 0.0D ? normalized + Math.PI * 2.0D : normalized;
    }

    private static double angleDifference(double first, double second) {
        double difference = (first - second) % (Math.PI * 2.0D);
        if (difference > Math.PI) {
            difference -= Math.PI * 2.0D;
        } else if (difference < -Math.PI) {
            difference += Math.PI * 2.0D;
        }
        return difference;
    }

    private record HeadingCandidate(double heading, double score) {
    }
}
