package com.charybdis180.ethological.avoidance;

import com.charybdis180.ethological.home.Homes;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * On-demand cliff-edge awareness for Ethological animals: whether a column is close enough
 * to a steep drop that standing there is unsafe, and which way to steer a nudged animal off
 * the lip instead of toward it.
 *
 * <p>Mirrors the {@link Homes#surfaceStand} memo pattern: per-level inner maps keyed by a
 * packed XZ column, reset once per level tick, with weak level keys so loaded worlds are not
 * pinned. Column surface heights come from {@link Homes#surfaceStand}, which is itself
 * memoized per (level, tick), so this adds no new block I/O on the hot path.</p>
 */
public final class CliffAvoidance {
    /** A fall taller than this within 1 block counts as an edge worth avoiding. */
    public static final int WARN_DROP = 3;
    /** A fall taller than this within 2 blocks counts as a hard edge worth a wider berth. */
    public static final int HARD_DROP = 8;
    /** Chebyshev radius scanned for warn-level drops. */
    private static final int WARN_RADIUS = 1;
    /** Chebyshev radius scanned for hard-level drops. */
    private static final int HARD_RADIUS = 2;

    /** Per-level, per-tick clearance memo: packed column -> clearance int. */
    private static final Map<Level, Map<Long, Integer>> CLEARANCE_MEMO = new WeakHashMap<>();
    /** Game time each level's memo was stamped, so a new tick resets it. */
    private static final Map<Level, Long> CLEARANCE_STAMP = new WeakHashMap<>();
    /** Blunt cap so a very large herd cannot grow the memo unbounded within one tick. */
    private static final int CLEARANCE_LIMIT = 16384;

    private CliffAvoidance() {
    }

    /**
     * How unsafe the column is, in levels:
     *
     * <ul>
     *   <li>0 = safe — no {@link #WARN_DROP}+ drop within 1 block and no {@link #HARD_DROP}+
     *       drop within 2 blocks (Chebyshev).</li>
     *   <li>1 = a drop of {@link #WARN_DROP}+ blocks within 1 block.</li>
     *   <li>2 = a drop of {@link #HARD_DROP}+ blocks within {@link #HARD_RADIUS} blocks.</li>
     * </ul>
     *
     * Unloaded columns are treated as safe (unknown terrain is never flagged), matching the
     * corridor-sampling philosophy in {@code Homes.migrationCorridorOk}.
     */
    public static int edgeClearance(Level level, BlockPos stand) {
        if (!level.hasChunkAt(stand)) {
            return 0;
        }
        Map<Long, Integer> inner = CLEARANCE_MEMO.get(level);
        Long stamp = CLEARANCE_STAMP.get(level);
        long now = level.getGameTime();
        if (inner != null && stamp != null && stamp.longValue() == now) {
            Integer cached = inner.get(columnKey(stand.getX(), stand.getZ()));
            if (cached != null) {
                return cached.intValue();
            }
        } else {
            inner = new java.util.HashMap<>();
            CLEARANCE_MEMO.put(level, inner);
            CLEARANCE_STAMP.put(level, now);
        }
        int clearance = computeClearance(level, stand.getX(), stand.getY(), stand.getZ());
        inner.put(columnKey(stand.getX(), stand.getZ()), Integer.valueOf(clearance));
        if (inner.size() >= CLEARANCE_LIMIT) {
            inner.clear();
        }
        return clearance;
    }

    /** True when {@link #edgeClearance} is 0 — the column is not near a dangerous drop. */
    public static boolean isEdgeSafe(Level level, BlockPos stand) {
        return edgeClearance(level, stand) == 0;
    }

    /**
     * Unit horizontal vector pointing away from every nearby dangerous drop (the normalized
     * sum of the offsets to the dangerous neighbors in radius), or {@link Vec3#ZERO} when the
     * column is edge-safe. Callers apply it as a small nudge so a separated animal is steered
     * off the lip rather than shoved toward it.
     */
    public static Vec3 edgePushAway(Level level, BlockPos stand) {
        if (edgeClearance(level, stand) == 0) {
            return Vec3.ZERO;
        }
        int baseY = surfaceY(level, stand.getX(), stand.getZ());
        if (baseY == Integer.MIN_VALUE) {
            return Vec3.ZERO;
        }
        double px = 0.0D;
        double pz = 0.0D;
        for (int dx = -HARD_RADIUS; dx <= HARD_RADIUS; ++dx) {
            for (int dz = -HARD_RADIUS; dz <= HARD_RADIUS; ++dz) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int dist = Math.max(Math.abs(dx), Math.abs(dz));
                if (dist > HARD_RADIUS) {
                    continue;
                }
                int neighborY = surfaceY(level, stand.getX() + dx, stand.getZ() + dz);
                if (neighborY == Integer.MIN_VALUE) {
                    continue;
                }
                int drop = baseY - neighborY;
                boolean dangerous = (dist == WARN_RADIUS && drop > WARN_DROP) || (drop > HARD_DROP);
                if (dangerous) {
                    px -= dx;
                    pz -= dz;
                }
            }
        }
        double lenSqr = px * px + pz * pz;
        if (lenSqr <= 1.0E-6D) {
            return Vec3.ZERO;
        }
        double len = Math.sqrt(lenSqr);
        return new Vec3(px / len, 0.0D, pz / len);
    }

    private static int computeClearance(Level level, int x, int baseY, int z) {
        int worst = 0;
        for (int dx = -HARD_RADIUS; dx <= HARD_RADIUS; ++dx) {
            for (int dz = -HARD_RADIUS; dz <= HARD_RADIUS; ++dz) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int dist = Math.max(Math.abs(dx), Math.abs(dz));
                if (dist > HARD_RADIUS) {
                    continue;
                }
                int neighborY = surfaceY(level, x + dx, z + dz);
                if (neighborY == Integer.MIN_VALUE) {
                    continue;
                }
                int drop = baseY - neighborY;
                if (drop > HARD_DROP) {
                    worst = 2;
                } else if (dist == WARN_RADIUS && drop > WARN_DROP && worst < 1) {
                    worst = 1;
                }
            }
        }
        return worst;
    }

    private static int surfaceY(Level level, int x, int z) {
        BlockPos stand = Homes.surfaceStand(level, x, z);
        return stand != null ? stand.getY() : Integer.MIN_VALUE;
    }

    /** 26-bit XZ packing for the clearance memo key, same scheme as Homes's surface-stand memo. */
    private static long columnKey(int x, int z) {
        return ((long)(x & 0x3FFFFFF) << 32) ^ (z & 0x3FFFFFFL);
    }
}
