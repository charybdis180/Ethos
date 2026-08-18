package com.charybdis180.ethological.home.goal;

import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.sleep.SleepAttachments;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Gets an animal out of open water when no other behavior is pulling it somewhere.
 * Migration, fleeing, drinking, eating, and following all outrank this, so it only
 * fires for idle animals that would otherwise bob in place until a need drops.
 */
public class SeekShoreGoal
extends Goal {
    private static final double SPEED = 1.0;
    private static final int MAX_SEARCH_RADIUS = 16;
    private static final int SEARCH_INTERVAL_TICKS = 20;
    private static final int REPATH_TICKS = 20;
    private final Animal mob;
    private BlockPos shore;
    private long nextSearchGameTime;
    private int repathCooldown;

    public SeekShoreGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!this.mob.isInWaterOrBubble()) {
            return false;
        }
        if (((Boolean)this.mob.getData(SleepAttachments.SLEEPING)).booleanValue()
                || ((Boolean)this.mob.getData(SleepAttachments.RESTING)).booleanValue()
                || this.mob.hasData(SleepAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        long now = this.mob.level().getGameTime();
        if (now < this.nextSearchGameTime) {
            return this.shore != null;
        }
        this.nextSearchGameTime = now + SEARCH_INTERVAL_TICKS;
        this.shore = this.findShore();
        return this.shore != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.isInWaterOrBubble() && this.shore != null;
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
    }

    @Override
    public void tick() {
        if (this.repathCooldown > 0) {
            --this.repathCooldown;
            return;
        }
        if (this.shore == null || this.mob.getNavigation().isDone()) {
            this.shore = this.findShore();
        }
        if (this.shore != null) {
            this.repathCooldown = REPATH_TICKS;
            this.mob.getNavigation().moveTo(
                    (double)this.shore.getX() + 0.5,
                    (double)this.shore.getY(),
                    (double)this.shore.getZ() + 0.5,
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
        for (int ring = 2; ring <= MAX_SEARCH_RADIUS; ring += 2) {
            for (int dx = -ring; dx <= ring; dx += ring) {
                for (int dz = -ring; dz <= ring; ++dz) {
                    BlockPos stand = this.dryStand(level, origin.getX() + dx, origin.getZ() + dz);
                    if (stand != null) {
                        return stand;
                    }
                }
            }
            for (int dz = -ring; dz <= ring; dz += ring) {
                for (int dx = -ring + 1; dx < ring; ++dx) {
                    BlockPos stand = this.dryStand(level, origin.getX() + dx, origin.getZ() + dz);
                    if (stand != null) {
                        return stand;
                    }
                }
            }
        }
        return null;
    }

    private BlockPos dryStand(Level level, int x, int z) {
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
        BlockPos stand = surface.above();
        return Homes.isDryLand(level, stand) ? stand : null;
    }
}
