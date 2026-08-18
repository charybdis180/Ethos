package com.charybdis180.ethological.avoidance.goal;

import com.charybdis180.ethological.avoidance.CliffAvoidance;
import com.charybdis180.ethological.avoidance.CrowdGrid;
import com.charybdis180.ethological.avoidance.CrowdYield;
import com.charybdis180.ethological.config.EthologicalConfig;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.goal.FollowPathing;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.social.SocialAttachments;
import com.charybdis180.ethological.thirst.Thirst;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Priority-4 goal that makes a stationary blocker step aside (perpendicular to the stuck
 * mover's heading) so the mover can pass, then clears the yield request and lets the
 * blocker resume its idle behavior.
 *
 * <p>Only runs while a live {@link CrowdYield} request is recorded on this animal, and
 * self-aborts on panic, urgent hunger/thirst, sleep, or request expiry — so it can never
 * delay a need that the priority ladder ranks higher. The step-aside target scan (a few
 * memoized {@link Homes#surfaceStand} lookups) runs only in {@link #start()}.</p>
 */
public class YieldGoal extends Goal {
    private static final double SPEED = 1.0D;
    /** Arrive when within this horizontal distance of the side-step target. */
    private static final double ARRIVE_DIST = 1.2D;
    private static final double ARRIVE_DIST_SQR = ARRIVE_DIST * ARRIVE_DIST;
    /** Perpendicular step distances from the blocker's current position. */
    private static final double[] SIDE_DISTANCES = {2.5D, 3.5D};
    /** Straight-back fallback distance when both sides are blocked. */
    private static final double BACK_DISTANCE = 2.5D;

    private final Animal mob;
    private BlockPos sideTarget;
    private long dueGameTime;
    private int repathCooldown;

    public YieldGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!EthologicalConfig.CONFIG.comfort.yieldOnBlock.get()) {
            return false;
        }
        long now = this.mob.level().getGameTime();
        if (!CrowdYield.hasActiveRequest(this.mob, now)) {
            return false;
        }
        if (this.mob.getData(SleepAttachments.SLEEPING).booleanValue()
                || this.mob.hasData(SleepAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        if (HerdManager.panicPhaseOf(this.mob, now) != HerdManager.PanicPhase.NONE) {
            return false;
        }
        if (this.mob.hasData(SocialAttachments.PLAY) || this.mob.hasData(SocialAttachments.STARTLE)) {
            return false;
        }
        return !Hunger.isUrgentlyHungry(this.mob) && !Thirst.isUrgentlyThirsty(this.mob);
    }

    @Override
    public boolean canContinueToUse() {
        long now = this.mob.level().getGameTime();
        if (now >= this.dueGameTime || this.sideTarget == null) {
            return false;
        }
        if (this.mob.distanceToSqr(Vec3.atBottomCenterOf(this.sideTarget)) <= ARRIVE_DIST_SQR) {
            return false;
        }
        return this.canUse();
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
        long now = this.mob.level().getGameTime();
        this.dueGameTime = CrowdYield.requestDueOf(this.mob);
        if (this.dueGameTime == Long.MIN_VALUE) {
            CrowdYield.clearRequest(this.mob);
            return;
        }
        BlockPos stand = this.pickSideStepTarget();
        if (stand == null) {
            CrowdYield.clearRequest(this.mob);
            return;
        }
        this.sideTarget = stand;
        this.navigateTo(stand);
    }

    @Override
    public void tick() {
        if (this.sideTarget == null || this.mob.level().getGameTime() >= this.dueGameTime) {
            return;
        }
        if (this.repathCooldown > 0) {
            --this.repathCooldown;
            return;
        }
        if (this.mob.getNavigation().isDone()) {
            this.repathCooldown = 10;
            this.navigateTo(this.sideTarget);
        }
    }

    @Override
    public void stop() {
        CrowdYield.clearRequest(this.mob);
        this.mob.getNavigation().stop();
        this.sideTarget = null;
    }

    /** First valid perpendicular stand (either side, 2.5 then 3.5 blocks), else straight back. */
    private BlockPos pickSideStepTarget() {
        double heading = CrowdYield.moverHeadingOf(this.mob);
        Level level = this.mob.level();
        BlockPos here = this.mob.blockPosition();
        for (double angle : new double[] {heading + Math.PI / 2.0D, heading - Math.PI / 2.0D}) {
            for (double dist : SIDE_DISTANCES) {
                BlockPos stand = this.validSideStand(level, here, angle, dist);
                if (stand != null) {
                    return stand;
                }
            }
        }
        return this.validSideStand(level, here, heading + Math.PI, BACK_DISTANCE);
    }

    /** Accept a stand when it is a dry, cliff-safe surface stand on this mob's level step,
     *  and its cell is not crowd-occupied. */
    private BlockPos validSideStand(Level level, BlockPos here, double angle, double dist) {
        int x = here.getX() + (int)Math.round(Math.cos(angle) * dist);
        int z = here.getZ() + (int)Math.round(Math.sin(angle) * dist);
        BlockPos stand = Homes.surfaceStand(level, x, z);
        if (stand == null) {
            return null;
        }
        if (Math.abs(stand.getY() - here.getY()) > FollowPathing.MAX_STEP_Y) {
            return null;
        }
        if (!Homes.isDryLand(level, stand)) {
            return null;
        }
        if (!Homes.isCliffSafe(level, stand)) {
            return null;
        }
        if (!CliffAvoidance.isEdgeSafe(level, stand)) {
            return null; // never step aside onto a cliff lip
        }
        if (CrowdGrid.isBlockedXZ(level, stand.getX(), stand.getZ(), stand.getY() >> 2, here)) {
            return null;
        }
        return stand;
    }

    private void navigateTo(BlockPos stand) {
        Path path = FollowPathing.pathToSurfaceStand(this.mob, stand, FollowPathing.MAX_STEP_Y);
        if (path != null) {
            this.mob.getNavigation().moveTo(path, SPEED);
        } else {
            this.mob.getNavigation().moveTo(
                    stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, SPEED);
        }
    }
}
