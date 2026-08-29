package com.charybdis180.ethological.avoidance;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.herd.MotherData;
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
    /** Away-from-lip steer magnitude on a hard edge (doubles the normal cushion cap). */
    private static final double EDGE_GUARD_NUDGE = 0.24D;
    /** Probe radius for the edge guard's shove-direction check. */
    private static final double EDGE_GUARD_PROBE_RADIUS = 3.0D;
    /** Staggered separation scan interval: 3-5 ticks by UUID. */
    static final int SEPARATE_INTERVAL_MIN = 3;
    static final int SEPARATE_INTERVAL_SPAN = 3;

    private static final Map<Long, Long> OCCUPIED = new ConcurrentHashMap<>();

    private CrowdGrid() {
    }

    /**
     * Soft personal-space query for decision-time spacing: true when no other animal stands
     * within {@code radius} blocks of the candidate spot (2D check, small Y band so stacked
     * terrain does not false-positive). Unlike the physical nudge this never moves anything
     * — callers use it to PREFER unoccupied ground when choosing where to walk or stand, so
     * herds spread out without anyone being shoved.
     */
    public static boolean isSpaced(Level level, BlockPos pos, double radius) {
        double cx = pos.getX() + 0.5D;
        double cz = pos.getZ() + 0.5D;
        int r = (int)Math.ceil(radius);
        List<Animal> nearby = level.getEntitiesOfClass(Animal.class,
                new net.minecraft.world.phys.AABB(
                        pos.getX() - r - 1, pos.getY() - 4, pos.getZ() - r - 1,
                        pos.getX() + r + 2, pos.getY() + 4, pos.getZ() + r + 2));
        if (nearby.isEmpty()) {
            return true;
        }
        double radiusSqr = radius * radius;
        for (Animal other : nearby) {
            if (!other.isAlive()) {
                continue;
            }
            Vec3 p = other.position();
            double dx = p.x() - cx;
            double dz = p.z() - cz;
            if (dx * dx + dz * dz < radiusSqr) {
                return false;
            }
        }
        return true;
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
     *
     * <p>Deliberately a light anti-interpenetration cushion (original 2.0/0.12 feel): the
     * physical push is far too weak to spread a herd, and strengthening it just makes
     * settled animals shove each other around with their bubbles. Wide spacing happens at
     * decision time — station picking, sleep spots, shore stands.</p>
     */
    public static void separate(Animal animal, ServerLevel level, long now) {
        register(animal, now);
        if (Boolean.TRUE.equals(animal.getData(ModAttachments.SLEEPING))) {
            return;
        }
        // A waterborne animal is mid-trek to the shore (seek-shore, beach, rejoin) and must
        // be allowed to push through a sleeping clump on the bank — otherwise the cushion
        // shoves it back into the pond forever. Sleeping herd-mates still never move, and
        // land-based spacing is unchanged; only sleeping bodies stop cushioning a swimmer.
        // Awake bodies (including a second swimmer in shallow water) still separate, so a
        // migrating herd crossing a river keeps spreading out instead of balling up.
        boolean swimmingToShore = animal.isInWaterOrBubble();
        List<Animal> nearby = level.getEntitiesOfClass(Animal.class,
                animal.getBoundingBox().inflate(SEPARATION_RADIUS),
                other -> other != animal
                        && other.isAlive()
                        && !isNursingMother(animal, other)
                        && !(swimmingToShore && Boolean.TRUE.equals(other.getData(ModAttachments.SLEEPING))));
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

    /**
     * Every-tick anti-shove guard for animals standing on or beside a hard cliff edge.
     * The staggered {@link #separate} pass only fires every 3-5 ticks and its redirect uses
     * the normal nudge budget — a dense vanilla-collision crowd shoving toward the lip can
     * still out-push it between passes. This guard runs un-staggered with a doubled cap so
     * the accumulated per-second displacement stays net-away from the drop. Directionally
     * gated: it only fires when a neighbor stands on the field side of the animal (its
     * collision push actually drives toward the lip), so a lone grazer at a scenic overlook
     * is never herded inland.
     */
    public static void edgeGuard(Animal animal, ServerLevel level) {
        if (Boolean.TRUE.equals(animal.getData(ModAttachments.SLEEPING))) {
            return;
        }
        BlockPos stand = animal.blockPosition();
        if (CliffAvoidance.edgeClearance(level, stand) == 0) {
            return;
        }
        Vec3 away = CliffAvoidance.edgePushAway(level, stand);
        if (away.lengthSqr() <= 1.0E-6D) {
            return;
        }
        Vec3 self = animal.position();
        List<Animal> nearby = level.getEntitiesOfClass(Animal.class,
                animal.getBoundingBox().inflate(EDGE_GUARD_PROBE_RADIUS),
                other -> other != animal && other.isAlive());
        boolean shovedTowardLip = false;
        for (Animal other : nearby) {
            // Collision pushes self along (self - other); that vector gains a toward-lip
            // component exactly when (other - self) points inland (+away).
            Vec3 rel = other.position().subtract(self);
            if (rel.x * away.x + rel.z * away.z > 0.15D) {
                shovedTowardLip = true;
                break;
            }
        }
        if (!shovedTowardLip) {
            return;
        }
        nudge(animal, away.scale(EDGE_GUARD_NUDGE));
    }

    private static boolean isNursingMother(Animal baby, Animal other) {
        if (!baby.isBaby() || !baby.hasData(ModAttachments.MOTHER)) {
            return false;
        }
        MotherData link = baby.getData(ModAttachments.MOTHER);
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
