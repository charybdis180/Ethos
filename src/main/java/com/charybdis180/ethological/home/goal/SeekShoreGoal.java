package com.charybdis180.ethological.home.goal;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.home.Homes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Gets an animal out of open water when no other behavior is pulling it somewhere.
 * Migration, fleeing, drinking, eating, and following all outrank this, so it only
 * fires for idle animals that would otherwise bob in place until a need drops.
 *
 * <p>Also owns the "shoved into a pond while herding" walk: a pushed-in-water sleeper
 * wakes and this goal carries it to a reachable dry stand near the herd (preferring
 * the herd's own sleep-bed region), then hands back so {@code SettleForSleepGoal}
 * can re-settle it beside the alpha. Disturbed mobs are FleeThreatGoal's job.
 */
public class SeekShoreGoal
extends Goal {
    private static final double SPEED = 1.0;
    private static final int MAX_SEARCH_RADIUS = 16;
    private static final int REPATH_TICKS = 20;
    private final Animal mob;
    private BlockPos shore;
    private long nextSearchGameTime;
    private int repathCooldown;

    public SeekShoreGoal(Animal mob) {
        this.mob = mob;
        this.shore = null;
        this.repathCooldown = 0;
        this.nextSearchGameTime = 0L;
    }

    @Override
    public boolean canUse() {
        if (!this.mob.isInWaterOrBubble()) {
            return false;
        }
        // A pushed-in-water animal is often still marked SLEEPING (it was shoved while the
        // herd slept). It cannot stay asleep mid-water — wake it so shore-seeking can act.
        if (this.mob.getData(ModAttachments.SLEEPING)) {
            com.charybdis180.ethological.sleep.SleepEvents.wake(this.mob);
        }
        // A swimmer does not get to choose "rest" as an option — it must get out.
        // SLEEP_DISTURBANCE mobs are handled by FleeThreatGoal (priority 2), which already
        // owns MOVE; letting SeekShoreGoal run too would fight it for the path.
        if (this.mob.hasData(ModAttachments.SLEEP_DISTURBANCE) || this.mob.getData(ModAttachments.RESTING)) {
            return false;
        }
        long now = this.mob.level().getGameTime();
        if (now < this.nextSearchGameTime) {
            return this.shore != null;
        }
        this.nextSearchGameTime = now + REPATH_TICKS;
        this.shore = this.findShore();
        return this.shore != null;
    }

    @Override
    public boolean canContinueToUse() {
        // Keep owning MOVE until the swimmer is properly on dry land — but hand back the
        // moment it is, so the settle/rest goals can take over without a fight. The dry-bed
        // walk is SettleForSleepGoal's (0.1.42 gate); this goal only beaches the animal.
        // "Dry feet" alone is not enough: a waterline cell has dry feet but a wet body, so
        // only release once a true dry bed (no water in the body column or 4 neighbors).
        return this.mob.isInWaterOrBubble()
                || (!Homes.isDryBed(this.mob.level(), this.mob.blockPosition()) && this.shore != null);
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
    }

    @Override
    public void tick() {
        // Continuously own MOVE while the mob is in water: the goal re-issues a fresh
        // path every REPATH_TICKS, and if the current one is exhausted early it repaths
        // immediately. Previously this only repathed on the start-tick, so a mob pushed
        // into a pond simply drifted in place once the initial path ran out.
        if (this.repathCooldown > 0) {
            --this.repathCooldown;
            return;
        }
        if (this.shore == null || this.mob.getNavigation().isDone()) {
            this.shore = this.findShore();
        }
        if (this.shore != null) {
            this.repathCooldown = REPATH_TICKS;
            BlockPos anchored = Homes.surfaceStandForMove(this.mob.level(), this.shore, this.mob);
            if (anchored == null) {
                anchored = this.shore;
            }
            anchored = Homes.offsetStandFromCorners(this.mob.level(), anchored);
            this.mob.getNavigation().moveTo(
                    (double)anchored.getX() + 0.5,
                    (double)anchored.getY(),
                    (double)anchored.getZ() + 0.5,
                    SPEED);
        }
    }

    @Override
    public void stop() {
        this.shore = null;
        this.repathCooldown = 0;
    }

    private BlockPos findShore() {
        Level level = this.mob.level();
        BlockPos origin = this.mob.blockPosition();
        // Heard animals that get shoved into a pond are the "return to the herd" case: they
        // should beach at a dry stand NEAR the alpha's sleep area, not the nearest bit of
        // dry bank. Prefer the herd's own dry-bed region; fall back to a ring around the
        // alpha, then a ring around the swimmer itself.
        com.charybdis180.ethological.herd.HerdManager.Herd herd = com.charybdis180.ethological.herd.HerdManager.herdOf(this.mob);
        if (herd != null) {
            BlockPos bed = com.charybdis180.ethological.sleep.SleepEvents.drySleepSpotNearHerd(this.mob);
            if (bed != null && this.beachLevelOk(bed) && Homes.isReachable(this.mob, bed)) {
                return bed;
            }
        }
        BlockPos center = origin;
        if (herd != null && herd.alphaId != null && level instanceof net.minecraft.server.level.ServerLevel sl) {
            net.minecraft.world.entity.Entity alpha = sl.getEntity(herd.alphaId);
            if (alpha != null && alpha.isAlive()) {
                center = alpha.blockPosition();
            }
        }
        BlockPos stand = this.ringShore(level, center, MAX_SEARCH_RADIUS + 12);
        if (stand != null) {
            return stand;
        }
        return this.ringShore(level, origin, MAX_SEARCH_RADIUS);
    }

    private BlockPos ringShore(Level level, BlockPos center, int maxRadius) {
        for (int ring = 2; ring <= maxRadius; ring += 2) {
            for (int dx = -ring; dx <= ring; dx += ring) {
                for (int dz = -ring; dz <= ring; ++dz) {
                    BlockPos stand = this.dryStand(level, center.getX() + dx, center.getZ() + dz);
                    if (stand != null && Homes.isReachable(this.mob, stand)) {
                        return stand;
                    }
                }
            }
            for (int dz = -ring; dz <= ring; dz += ring) {
                for (int dx = -ring + 1; dx < ring; ++dx) {
                    BlockPos stand = this.dryStand(level, center.getX() + dx, center.getZ() + dz);
                    if (stand != null && Homes.isReachable(this.mob, stand)) {
                        return stand;
                    }
                }
            }
        }
        return null;
    }

    /** True when a candidate shore stand is at or one block above the swimmer's own water
     *  level — the only ones a swimming mob can actually step onto without the endless
     *  "bump the bank" loop. Matches {@link #dryStand}. */
    private boolean beachLevelOk(BlockPos stand) {
        if (!this.mob.isInWaterOrBubble()) {
            return true;
        }
        int waterY = Homes.waterLevelY(this.mob.level(), this.mob.blockPosition());
        return stand.getY() <= waterY + 1;
    }

    private BlockPos dryStand(Level level, int x, int z) {
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
        BlockPos stand = surface.above();
        if (!Homes.isDryLand(level, stand)) {
            return null;
        }
        // A swimmer rides the water surface and cannot climb out onto a stand above its
        // own water level — the endless "bump the bank" loop. Only stands at or one block
        // above the swimmer's water level are reachable, matching surfaceStandForMove.
        if (this.mob.isInWaterOrBubble()) {
            int waterY = Homes.waterLevelY(level, this.mob.blockPosition());
            if (stand.getY() > waterY + 1) {
                return null;
            }
        }
        return stand;
    }
}
