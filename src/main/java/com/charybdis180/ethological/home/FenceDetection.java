/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.tags.BlockTags
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.level.block.FenceGateBlock
 *  net.minecraft.world.level.block.state.BlockState
 *  net.minecraft.world.level.block.state.properties.Property
 */
package com.charybdis180.ethological.home;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.pathfinder.Path;

public final class FenceDetection {
    private static final int ESCAPE_RADIUS = 32;
    private static final int[][] DIRECTIONS = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** Flood results are cached so animals standing in the same pen share one BFS. */
    private static final long REGION_CACHE_TTL_TICKS = 200L;
    private static final int REGION_CACHE_MAX = 1024;
    private static final java.util.Map<Long, CachedEnclosure> ENCLOSURE_CACHE = new java.util.HashMap<Long, CachedEnclosure>();
    private static final java.util.Map<Long, CachedRegion> REGION_CACHE = new java.util.HashMap<Long, CachedRegion>();
    /** Verdict of the escape-route probe for an enclosure, so a pen full of animals
     * pays the pathfinder probe once per {@link #REGION_CACHE_TTL_TICKS} instead of
     * once per animal. Keyed by the same region key as the flood caches. */
    private static final java.util.Map<Long, CachedEscape> ESCAPE_CACHE = new java.util.HashMap<Long, CachedEscape>();
    /** Escape-probe rings (blocks from the mob) at which dry outside stands are tested. */
    private static final int[] ESCAPE_PROBE_RINGS = {8, 12, 16, 24};
    /** Eight bearings per ring, so a narrow pit's climbable edge is found from any side. */
    private static final int[] ESCAPE_PROBE_BEARINGS = {0, 45, 90, 135, 180, 225, 270, 315};
    /** Cap on pathfinder queries per escape probe, so a pen full of animals cannot tank the tick. */
    private static final int MAX_ESCAPE_PROBES = 32;
    /** Escape paths must climb no more than this per node. */
    private static final int ESCAPE_MAX_STEP_Y = 4;
    /** Escape paths must gain at least this much horizontal progress to count. */
    private static final double ESCAPE_MIN_PROGRESS = 3.0D;
    /** Max |path end Y - stand Y| before an escape path counts as leading underground. */
    private static final int ESCAPE_END_Y_TOLERANCE = 4;
    /** Widened pathfinder budget for escape probes, mirroring the pit-escape search so a
     * rim stand 8-21 blocks above a basin floor is reachable. */
    private static final int ESCAPE_FOLLOW_RANGE = 48;
    private static final float ESCAPE_NODE_MULTIPLIER = 2.0F;

    private record CachedEnclosure(boolean enclosed, long gameTime) {
    }

    private record CachedRegion(LongSet region, long gameTime) {
    }

    private record CachedEscape(boolean hasRoute, long gameTime) {
    }

    private FenceDetection() {
    }

    /**
     * BFS boundary test: the animal is fenced in iff a flood fill of passable columns
     * cannot reach the edge of the search square. Unlike the old 4-ray probe, this
     * follows gaps, corners, and open gates.
     *
     * <p>A natural basin (ditch, ravine, shallow depression) trips the flood fill —
     * its walls read as unjumpable columns — even though the animal can climb out.
     * A real fence stops the pathfinder cold, but a climbable rim does not, so the
     * flood verdict is confirmed against an actual escape probe: the animal is only
     * considered fenced in when the pathfinder cannot find ANY route to a dry stand
     * outside the flood region.</p>
     */
    public static boolean isFencedIn(Animal animal) {
        Level level = animal.level();
        BlockPos origin = animal.blockPosition();
        if (!FenceDetection.isEnclosed(level, origin.getX(), origin.getZ(), origin.getY(), ESCAPE_RADIUS)) {
            return false;
        }
        return !FenceDetection.hasEscapeRoute(animal, origin);
    }

    /**
     * True when the pathfinder can reach a dry stand outside the flood region. Probes
     * surface stands on near-to-far rings and asks the widened A* search whether the
     * animal can actually walk there; the first reachable outside stand wins, so a
     * climbable ditch escapes after a few probes while a fenced pen exhausts the rings
     * and reports no route. The verdict is cached per region like the flood results.
     */
    private static boolean hasEscapeRoute(Animal animal, BlockPos origin) {
        Level level = animal.level();
        long key = FenceDetection.regionKey(level, origin.getX(), origin.getZ(), origin.getY(), ESCAPE_RADIUS);
        long now = level.getGameTime();
        CachedEscape cached = ESCAPE_CACHE.get(key);
        if (cached != null && now - cached.gameTime() < REGION_CACHE_TTL_TICKS) {
            return cached.hasRoute();
        }
        LongSet region = FenceDetection.reachableColumns(level, origin.getX(), origin.getZ(), origin.getY(), ESCAPE_RADIUS);
        boolean hasRoute = false;
        int probes = 0;
        outer:
        for (int ring : ESCAPE_PROBE_RINGS) {
            for (int bearing : ESCAPE_PROBE_BEARINGS) {
                if (++probes > MAX_ESCAPE_PROBES) {
                    break outer;
                }
                double rad = Math.toRadians((double)bearing);
                int x = origin.getX() + Mth.floor(0.5D + Math.cos(rad) * (double)ring);
                int z = origin.getZ() + Mth.floor(0.5D + Math.sin(rad) * (double)ring);
                BlockPos stand = Homes.surfaceStand(level, x, z);
                if (stand == null) {
                    continue;
                }
                // Escaping means getting out to the surrounding surface, not tunneling deeper.
                if (stand.getY() < origin.getY() - 1) {
                    continue;
                }
                // A water stand is not an escape; the animal must reach dry ground.
                if (!Homes.isDryLand(level, stand)) {
                    continue;
                }
                // A stand still inside the flood region is still within the enclosure.
                if (region.contains(FenceDetection.pack(stand.getX(), stand.getZ()))) {
                    continue;
                }
                Path path = FenceDetection.createEscapePath(animal, stand);
                if (FenceDetection.isValidEscapePath(path, origin, stand)) {
                    hasRoute = true;
                    break outer;
                }
            }
        }
        if (ESCAPE_CACHE.size() >= REGION_CACHE_MAX) {
            ESCAPE_CACHE.clear();
        }
        ESCAPE_CACHE.put(key, new CachedEscape(hasRoute, now));
        return hasRoute;
    }

    /** Widened A* query so a rim stand 8-21 blocks above the basin floor is reachable. */
    private static Path createEscapePath(Animal animal, BlockPos stand) {
        animal.getNavigation().setMaxVisitedNodesMultiplier(ESCAPE_NODE_MULTIPLIER);
        try {
            return animal.getNavigation().createPath(stand, 1, ESCAPE_FOLLOW_RANGE);
        } finally {
            animal.getNavigation().resetMaxVisitedNodesMultiplier();
        }
    }

    private static boolean isValidEscapePath(Path path, BlockPos origin, BlockPos stand) {
        if (path == null || path.getNodeCount() <= 0 || !path.canReach()) {
            return false;
        }
        if (Homes.pathHasSteepStep(path, ESCAPE_MAX_STEP_Y)) {
            return false;
        }
        if (!Homes.pathHasHorizontalProgress(path, origin, ESCAPE_MIN_PROGRESS)) {
            return false;
        }
        BlockPos end = path.getNodePos(path.getNodeCount() - 1);
        return Math.abs(end.getY() - stand.getY()) <= ESCAPE_END_Y_TOLERANCE;
    }

    private static boolean isEnclosed(Level level, int sx, int sz, int refY, int radius) {
        long key = FenceDetection.regionKey(level, sx, sz, refY, radius);
        long now = level.getGameTime();
        CachedEnclosure cached = ENCLOSURE_CACHE.get(key);
        if (cached != null && now - cached.gameTime() < REGION_CACHE_TTL_TICKS) {
            return cached.enclosed();
        }
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        if (FenceDetection.columnBlocksPassage(level, sx, sz, refY, probe)) {
            return false;
        }
        LongOpenHashSet reached = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        long start = FenceDetection.pack(sx, sz);
        queue.enqueue(start);
        reached.add(start);
        boolean enclosed = true;
        while (!queue.isEmpty()) {
            long node = queue.dequeueLong();
            int nx = (int)(node >> 32);
            int nz = (int)node;
            if (Math.abs(nx - sx) >= radius || Math.abs(nz - sz) >= radius) {
                enclosed = false;
                break;
            }
            for (int[] dir : DIRECTIONS) {
                int x = nx + dir[0];
                int z = nz + dir[1];
                long k = FenceDetection.pack(x, z);
                if (!reached.add(k) || FenceDetection.columnBlocksPassage(level, x, z, refY, probe)) continue;
                queue.enqueue(k);
            }
        }
        if (ENCLOSURE_CACHE.size() >= REGION_CACHE_MAX) {
            ENCLOSURE_CACHE.clear();
        }
        ENCLOSURE_CACHE.put(key, new CachedEnclosure(enclosed, now));
        return enclosed;
    }

    public static boolean canRejoin(Level level, BlockPos from, BlockPos to, int radius) {
        int refY = from.getY();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        if (FenceDetection.columnBlocksPassage(level, from.getX(), from.getZ(), refY, probe) || FenceDetection.columnBlocksPassage(level, to.getX(), to.getZ(), refY, probe)) {
            return false;
        }
        LongSet reached = FenceDetection.reachableColumns(level, from.getX(), from.getZ(), refY, radius);
        return reached.contains(FenceDetection.pack(to.getX(), to.getZ()));
    }

    /** Membership test against a region precomputed with reachableColumns, so callers can flood-fill once and test many candidates. */
    public static boolean canRejoin(Level level, LongSet fromRegion, BlockPos to, int refY) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        if (FenceDetection.columnBlocksPassage(level, to.getX(), to.getZ(), refY, probe)) {
            return false;
        }
        return fromRegion.contains(FenceDetection.pack(to.getX(), to.getZ()));
    }

    public static LongSet reachableColumns(Level level, int sx, int sz, int refY, int radius) {
        long key = FenceDetection.regionKey(level, sx, sz, refY, radius);
        long now = level.getGameTime();
        CachedRegion cached = REGION_CACHE.get(key);
        if (cached != null && now - cached.gameTime() < REGION_CACHE_TTL_TICKS) {
            return cached.region();
        }
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        LongOpenHashSet reached = new LongOpenHashSet();
        if (FenceDetection.columnBlocksPassage(level, sx, sz, refY, probe)) {
            if (REGION_CACHE.size() >= REGION_CACHE_MAX) {
                REGION_CACHE.clear();
            }
            REGION_CACHE.put(key, new CachedRegion(reached, now));
            return reached;
        }
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        long start = FenceDetection.pack(sx, sz);
        queue.enqueue(start);
        reached.add(start);
        while (!queue.isEmpty()) {
            long node = queue.dequeueLong();
            int nx = (int)(node >> 32);
            int nz = (int)node;
            for (int[] dir : DIRECTIONS) {
                int x = nx + dir[0];
                int z = nz + dir[1];
                if (Math.abs(x - sx) > radius || Math.abs(z - sz) > radius) continue;
                long k = FenceDetection.pack(x, z);
                if (!reached.add(k) || FenceDetection.columnBlocksPassage(level, x, z, refY, probe)) continue;
                queue.enqueue(k);
            }
        }
        if (REGION_CACHE.size() >= REGION_CACHE_MAX) {
            REGION_CACHE.clear();
        }
        REGION_CACHE.put(key, new CachedRegion(reached, now));
        return reached;
    }

    /**
     * Coarse-grained cache key so two animals standing in the same pen share one
     * flood. Center is chunk-quantized (>>2, ~4 blocks), Y is bucketed (>>3) and the
     * level identity is folded in to avoid cross-world collisions. The returned region
     * is treated as immutable (callers only test membership).
     */
    private static long regionKey(Level level, int sx, int sz, int refY, int radius) {
        long key = System.identityHashCode(level);
        key = key * 31L + (sx >> 2);
        key = key * 31L + (sz >> 2 & 0xffffffffL);
        key = key * 31L + (refY >> 3 & 0xffffffffL);
        key = key * 31L + (radius & 0xffffffffL);
        return key;
    }

    public static long pack(long x, long z) {
        return x << 32 ^ z & 0xFFFFFFFFL;
    }

    private static boolean columnBlocksPassage(Level level, int x, int z, int refY, BlockPos.MutableBlockPos probe) {
        int solidCount = 0;
        for (int y = refY; y <= refY + 1; ++y) {
            probe.set(x, y, z);
            if (!(level instanceof ServerLevel sl) || sl.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                return true;
            }
            BlockState state = level.getBlockState(probe);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                return false;
            }
            if (FenceDetection.isUnjumpableBarrier(state)) {
                return true;
            }
            ++solidCount;
        }
        return solidCount >= 2;
    }

    private static boolean isUnjumpableBarrier(BlockState state) {
        if (state.is(BlockTags.FENCE_GATES)) {
            return (Boolean)state.getValue((Property)FenceGateBlock.OPEN) == false;
        }
        return state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS);
    }
}



