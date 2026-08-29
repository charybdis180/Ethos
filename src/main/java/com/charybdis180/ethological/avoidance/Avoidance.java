package com.charybdis180.ethological.avoidance;

import com.charybdis180.ethological.home.Homes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.Nullable;

/**
 * Lightweight hard/soft avoidance helpers.
 * // TODO: wire EthologicalConfig.avoidance for radii / enable flags.
 */
public final class Avoidance {
    /** Hard detect / flee trigger radius (blocks). */
    public static final int HARD_RADIUS = 8;
    public static final double HARD_RADIUS_SQR = HARD_RADIUS * HARD_RADIUS;
    /** How far to flee from a hard hazard. */
    public static final double FLEE_DISTANCE = 14.0;
    public static final int FLEE_PATH_ATTEMPTS = 8;
    public static final double FLEE_SPEED = 1.5;
    public static final double FLEE_SPRINT_SPEED = 1.7;
    public static final float FLEE_CLOSE_DISTANCE = 6.0f;
    /** Clear hazard attachment after this many ticks if still attached. */
    public static final long HAZARD_TTL_TICKS = 300L;
    /** Base interval between hazard ring scans (staggered by UUID). */
    public static final int SCAN_INTERVAL_MIN = 20;
    public static final int SCAN_INTERVAL_SPAN = 21;

    /**
     * Cost per water cell for home-species pathing. Deliberately finite (soft), not the
     * vanilla -1.0 block: animals should avoid wading when a dry route is cheaper, but a
     * nomad herd or a drinker that must cross a river still can. 40/cell makes a narrow
     * pond far pricier than a detour, while an effectively-unavoidable crossing stays
     * possible. The dry-stand selection in the goal stand pickers is the primary
     * deterrent; this malus is the cheap second-order nudge inside the pathfinder.
     */
    public static final float WATER_MALUS = 40.0f;
    /** Water-adjacent land cells cost extra so banks are slightly less attractive. */
    public static final float WATER_BORDER_MALUS = 8.0f;
    public static final float DAMAGE_FIRE_MALUS = 16.0f;
    public static final float DANGER_FIRE_MALUS = 16.0f;
    public static final float LAVA_MALUS = 16.0f;

    /** How long a discovered hazard stays in the shared memory (10 minutes, refreshed on re-discovery). */
    public static final long HAZARD_MEMORY_TTL = 12000L;
    /** Cap on remembered hazard cells; cleared when exceeded (cheap, bounded). */
    public static final int HAZARD_MEMORY_LIMIT = 2048;
    /** Paths are rejected when a node lands within this radius (blocks) of a remembered hazard cell. */
    public static final int HAZARD_AVOID_RADIUS = 4;

    /** Shared hazard memory: XZ cell (4-block quantization) -> expiry game time. Written when
     * any animal discovers lava/fire (reactive scan, hazard block placed, or taking fire damage)
     * and consulted by path validation so the whole herd routes around a known portal instead of
     * repeatedly pathing into it. Mirrors the Homes water-cache pattern: coarse key, TTL, cap. */
    private static final Map<Long, Long> HAZARD_MEMORY = new ConcurrentHashMap<>();

    private Avoidance() {
    }

    public static boolean isHardHazard(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (state.getFluidState().is(FluidTags.LAVA)) {
            return true;
        }
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.LAVA)) {
            return true;
        }
        if (state.is(BlockTags.FIRE)) {
            return true;
        }
        // Lit campfires count as hard hazards animals should leave.
        return state.getBlock() instanceof CampfireBlock && state.hasProperty(CampfireBlock.LIT)
                && state.getValue(CampfireBlock.LIT);
    }

    public static boolean isHardHazard(LevelReader level, BlockPos pos) {
        if (level instanceof ServerLevel sl) {
            LevelChunk chunk = sl.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                return false;
            }
            return isHardHazard(chunk.getBlockState(pos));
        }
        return level.hasChunkAt(pos) && isHardHazard(level.getBlockState(pos));
    }

    public static boolean pathTouchesHardHazard(Level level, Path path) {
        if (path == null) {
            return true;
        }
        for (int i = 0; i < path.getNodeCount(); ++i) {
            BlockPos node = path.getNodePos(i);
            if (isHardHazard(level, node) || isHardHazard(level, node.below())) {
                return true;
            }
        }
        return false;
    }

    public static boolean pathCrossesOpenWater(Level level, Path path) {
        return Homes.pathCrossesOpenWater(level, path);
    }

    /**
     * True if this flee/path candidate is unsafe (open water or hard hazard).
     */
    public static boolean pathIsUnsafe(Level level, Path path) {
        return pathCrossesOpenWater(level, path) || pathTouchesHardHazard(level, path);
    }

    /**
     * Chebyshev ring scan for the nearest hard hazard within {@link #HARD_RADIUS}.
     * Prefers closer rings; returns null if none found.
     */
    @Nullable
    public static BlockPos findNearestHardHazard(Level level, BlockPos center) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int ring = 0; ring <= HARD_RADIUS; ++ring) {
            for (int dy = -1; dy <= 2; ++dy) {
                for (int dx = -ring; dx <= ring; ++dx) {
                    int stepZ = ring == 0 ? 1 : (Math.abs(dx) == ring ? 1 : ring * 2);
                    for (int dz = -ring; dz <= ring; dz += stepZ) {
                        if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) {
                            continue;
                        }
                        cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        if (!level.hasChunkAt(cursor)) {
                            continue;
                        }
                        if (isHardHazard(level, cursor)) {
                            return cursor.immutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    public static boolean hazardStillValid(Level level, AvoidanceHazard hazard, BlockPos animalPos, long now) {
        if (hazard == null || hazard.pos().equals(BlockPos.ZERO)) {
            return false;
        }
        if (now - hazard.detectedGameTime() > HAZARD_TTL_TICKS) {
            return false;
        }
        if (hazard.pos().distSqr(animalPos) > HARD_RADIUS_SQR) {
            return false;
        }
        return isHardHazard(level, hazard.pos());
    }

    /** Records a hazard block in the shared memory so pathing can avoid it after the
     * reactive scan falls out of range. Refresh on re-discovery extends the TTL. */
    public static void rememberHazard(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        if (HAZARD_MEMORY.size() >= HAZARD_MEMORY_LIMIT) {
            HAZARD_MEMORY.clear();
        }
        HAZARD_MEMORY.put(cellKey(pos.getX() >> 2, pos.getZ() >> 2), level.getGameTime() + HAZARD_MEMORY_TTL);
    }

    /** Clears the shared hazard memory (a hazard block was removed or extinguished). */
    public static void invalidateHazardMemory() {
        HAZARD_MEMORY.clear();
    }

    /**
     * True when any path node either touches a live hard hazard (lava/fire, including the
     * node below it) or sits within {@code radius} of a remembered hazard cell from the
     * shared memory. The live check catches fresh fires; the memory check keeps whole herds
     * routing around a portal one member already discovered, even after the 8-block reactive
     * scan has fallen out of range. Cells are 4x4, so a node's own cell plus its neighbors
     * covers the radius.
     */
    public static boolean pathPassesNearHazard(Level level, Path path, int radius) {
        if (path == null) {
            return true;
        }
        long now = level.getGameTime();
        int cellRadius = Math.max(1, radius >> 2);
        for (int i = 0; i < path.getNodeCount(); ++i) {
            BlockPos node = path.getNodePos(i);
            if (isHardHazard(level, node) || isHardHazard(level, node.below())) {
                return true;
            }
            int cx = node.getX() >> 2;
            int cz = node.getZ() >> 2;
            for (int dx = -cellRadius; dx <= cellRadius; ++dx) {
                for (int dz = -cellRadius; dz <= cellRadius; ++dz) {
                    if (isRememberedHazard(cx + dx, cz + dz, now)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isRememberedHazard(int cellX, int cellZ, long now) {
        Long expiry = HAZARD_MEMORY.get(cellKey(cellX, cellZ));
        return expiry != null && now < expiry;
    }

    /** 4-block XZ cell key: ((cellX & 0xFFFFFFFF) << 32) ^ (cellZ & 0xFFFFFFFF). */
    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX & 0xFFFFFFFFL) << 32 ^ (long) cellZ & 0xFFFFFFFFL;
    }
}
