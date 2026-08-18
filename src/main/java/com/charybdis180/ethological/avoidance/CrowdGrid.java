package com.charybdis180.ethological.avoidance;

import com.charybdis180.ethological.herd.HerdAttachments;
import com.charybdis180.ethological.herd.MotherData;
import com.charybdis180.ethological.sleep.SleepAttachments;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Shared crowd occupancy memory + separation steering for Ethological herd animals.
 *
 * <p>Mirrors {@link Avoidance#HAZARD_MEMORY} (cell key -> expiry game time, TTL, size cap),
 * but cells are 2x2 with a Y bucket so a cave animal does not block a surface animal two
 * levels up. Occupancy is written once per animal per tick (one map put) and only READ during
 * candidate scoring, so the grid adds no per-tick scans of its own beyond the bounded,
 * UUID-staggered separation AABB pass.</p>
 */
public final class CrowdGrid {
    /** How long a written cell stays hot, in game ticks. Animals move ~0.2 blocks/tick;
     *  15t keeps a stationary animal's cell hot without going stale mid-walk. */
    static final long OCCUPANCY_TTL_TICKS = 15L;
    /** Blunt cap: when exceeded the whole grid is cleared, like {@code HAZARD_MEMORY}. */
    private static final int OCCUPANCY_LIMIT = 4096;

    /** Cell size is 2x2 blocks in XZ (shift 1), coarse Y bucket is 4 blocks (shift 2). */
    private static final int XZ_SHIFT = 1;
    private static final int Y_SHIFT = 2;

    /** Separation steering parameters. */
    static final double SEPARATION_RADIUS = 2.0D;
    private static final double SEPARATION_RADIUS_SQR = SEPARATION_RADIUS * SEPARATION_RADIUS;
    private static final double HARD_MIN_DIST = 0.6D;
    private static final double MAX_NUDGE = 0.12D;
    /** Staggered separation scan interval: 3-5 ticks by UUID. */
    static final int SEPARATE_INTERVAL_MIN = 3;
    static final int SEPARATE_INTERVAL_SPAN = 3;

    private static final Map<Long, Long> OCCUPIED = new ConcurrentHashMap<>();

    private CrowdGrid() {
    }

    /** Records the animal's current feet cell as occupied until {@code gameTime + TTL}. */
    public static void register(Animal animal, long gameTime) {
        BlockPos pos = animal.blockPosition();
        if (OCCUPIED.size() >= OCCUPANCY_LIMIT) {
            OCCUPIED.clear();
        }
        OCCUPIED.put(cellKey(pos.getX(), pos.getZ(), pos.getY()), gameTime + OCCUPANCY_TTL_TICKS);
    }

    /**
     * True when the cell of {@code pos} is occupied, unexpired, and not the mover's own cell.
     */
    public static boolean isBlocked(Level level, BlockPos pos, BlockPos selfPos) {
        return isCellOccupied(level, pos.getX(), pos.getZ(), pos.getY(), selfPos);
    }

    /**
     * XZ/Y-bucket variant for candidate pre-screening before any path exists.
     *
     * @param yBucket the candidate's coarse Y bucket (use {@code y >> 2})
     */
    public static boolean isBlockedXZ(Level level, int x, int z, int yBucket, BlockPos selfPos) {
        return isCellOccupied(level, x, z, yBucket << Y_SHIFT, selfPos);
    }

    /**
     * Count of path nodes (excluding the first {@code skipNearStart} and last
     * {@code skipNearEnd}) whose cell is occupied. Callers start next to mates and must be
     * allowed to ARRIVE next to mates, so the plan uses skip 2 near the start and 3 at the end.
     */
    public static int countCrowdedNodes(Level level, Path path, BlockPos selfPos, int skipNearStart, int skipNearEnd) {
        if (path == null) {
            return 0;
        }
        int count = 0;
        int last = path.getNodeCount() - 1;
        for (int i = skipNearStart; i <= last - skipNearEnd; ++i) {
            BlockPos node = path.getNodePos(i);
            if (isCellOccupied(level, node.getX(), node.getZ(), node.getY(), selfPos)) {
                ++count;
            }
        }
        return count;
    }

    /** Convenience: {@link #countCrowdedNodes} with the plan's skip margins == 0. */
    public static boolean isClean(Level level, Path path, BlockPos selfPos) {
        return countCrowdedNodes(level, path, selfPos, 2, 3) == 0;
    }

    /**
     * Staggered separation pass. Writes the animal's own cell, then nudges it away from any
     * nearby animal (excluding its own nursing mother) when it is awake. Sleeping animals
     * never move — waking herd-mates keep clear of sleeping clumps instead.
     */
    public static void separate(Animal animal, ServerLevel level, long now) {
        register(animal, now);
        if (Boolean.TRUE.equals(animal.getData(SleepAttachments.SLEEPING))) {
            return;
        }
        List<Animal> nearby = level.getEntitiesOfClass(Animal.class,
                animal.getBoundingBox().inflate(SEPARATION_RADIUS),
                other -> other != animal
                        && other.isAlive()
                        && !isNursingMother(animal, other));
        if (nearby.isEmpty()) {
            return;
        }
        Vec3 self = animal.position();
        double pushX = 0.0D;
        double pushZ = 0.0D;
        for (Animal other : nearby) {
            Vec3 otherPos = other.position();
            double dx = self.x() - otherPos.x();
            double dz = self.z() - otherPos.z();
            double distSqr = dx * dx + dz * dz;
            if (distSqr <= 1.0E-6D || distSqr > SEPARATION_RADIUS_SQR) {
                continue;
            }
            double dist = Math.sqrt(distSqr);
            // Linear falloff: full repulsion at HARD_MIN_DIST, zero at SEPARATION_RADIUS.
            double strength = 1.0D - (dist - HARD_MIN_DIST) / (SEPARATION_RADIUS - HARD_MIN_DIST);
            double inv = 1.0D / dist;
            pushX += (dx * inv) * strength;
            pushZ += (dz * inv) * strength;
        }
        double lenSqr = pushX * pushX + pushZ * pushZ;
        if (lenSqr <= 1.0E-6D) {
            return;
        }
        // A separated animal at a cliff lip must be steered off the lip, never nudged
        // toward it by the crowd push. Take the edge-away steer instead (same magnitude).
        Vec3 away = CliffAvoidance.edgePushAway(level, animal.blockPosition());
        if (away.lengthSqr() > 1.0E-6D) {
            nudge(animal, away.scale(MAX_NUDGE));
            return;
        }
        double len = Math.sqrt(lenSqr);
        double scale = Math.min(len, MAX_NUDGE) / len;
        nudge(animal, new Vec3(pushX * scale, 0.0D, pushZ * scale));
    }

    private static boolean isNursingMother(Animal baby, Animal other) {
        if (!baby.isBaby() || !baby.hasData(HerdAttachments.MOTHER)) {
            return false;
        }
        MotherData link = baby.getData(HerdAttachments.MOTHER);
        return link.isActive(baby.level().getGameTime()) && other.getUUID().equals(link.motherId());
    }

    /**
     * Applies a horizontal separation nudge without corrupting the mover's ground-contact
     * state. {@link Entity#move} derives {@code onGround} from {@code verticalCollision &&
     * pos.y < 0}; a pure-horizontal vector therefore clears {@code onGround} even on flat
     * ground. Vanilla {@code Chicken.aiStep} then reads that stale flag and ramps the wing
     * flap animation until the next physics tick re-grounds the mob — so a herd chicken
     * flaps every few ticks while a separation push fires. Restore the pre-nudge state and
     * let the next tick's normal gravity re-derive the true ground contact.
     */
    private static void nudge(Animal animal, Vec3 delta) {
        boolean wasOnGround = animal.onGround();
        animal.move(MoverType.SELF, delta);
        if (wasOnGround && !animal.onGround()) {
            animal.setOnGround(true);
        }
    }

    private static boolean isCellOccupied(Level level, int x, int z, int y, BlockPos selfPos) {
        if (selfPos != null
                && (x >> XZ_SHIFT) == (selfPos.getX() >> XZ_SHIFT)
                && (z >> XZ_SHIFT) == (selfPos.getZ() >> XZ_SHIFT)
                && (y >> Y_SHIFT) == (selfPos.getY() >> Y_SHIFT)) {
            return false;
        }
        Long expiry = OCCUPIED.get(cellKey(x, z, y));
        return expiry != null && level.getGameTime() < expiry;
    }

    /** 21-bit XZ cells + 10-bit Y bucket packed into one long. */
    private static long cellKey(int x, int z, int y) {
        return ((long)(x >> XZ_SHIFT) & 0x1FFFFFL) << 42
                ^ ((long)(z >> XZ_SHIFT) & 0x1FFFFFL) << 21
                ^ ((long)(y >> Y_SHIFT) & 0x3FFL);
    }
}
