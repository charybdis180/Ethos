package com.charybdis180.ethological.avoidance.goal;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.avoidance.Avoidance;
import com.charybdis180.ethological.avoidance.AvoidanceHazard;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.util.FleePathing;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Flees from a nearby hard hazard (fire / lava) recorded on the animal.
 */
public class AvoidHazardGoal extends Goal {
    private static final int REPATH_TICKS = 10;

    private final Animal mob;
    private BlockPos hazardPos;
    private double lastSpeed = Double.MAX_VALUE;
    private int repathCooldown;

    public AvoidHazardGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        this.hazardPos = this.findHazard();
        return this.hazardPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        this.hazardPos = this.findHazard();
        return this.hazardPos != null;
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
        this.moveAway();
    }

    @Override
    public void tick() {
        if (this.hazardPos == null) {
            return;
        }
        double speed = this.currentSpeed();
        if (this.repathCooldown > 0) {
            --this.repathCooldown;
            if (speed > this.lastSpeed) {
                this.repathCooldown = REPATH_TICKS;
                this.moveAway();
            }
            return;
        }
        if (this.mob.getNavigation().isDone() || speed > this.lastSpeed) {
            this.repathCooldown = REPATH_TICKS;
            this.moveAway();
        }
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
        this.hazardPos = null;
        this.lastSpeed = Double.MAX_VALUE;
    }

    private double currentSpeed() {
        double distSqr = this.mob.blockPosition().distSqr(this.hazardPos);
        double closeSqr = Avoidance.FLEE_CLOSE_DISTANCE * Avoidance.FLEE_CLOSE_DISTANCE;
        return distSqr <= closeSqr ? Avoidance.FLEE_SPRINT_SPEED : Avoidance.FLEE_SPEED;
    }

    private BlockPos findHazard() {
        if (HerdManager.panicPhaseOf(this.mob, this.mob.level().getGameTime()) != HerdManager.PanicPhase.NONE) {
            return null;
        }
        Level level = this.mob.level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        if (!this.mob.hasData(ModAttachments.HAZARD)) {
            return null;
        }
        AvoidanceHazard hazard = this.mob.getData(ModAttachments.HAZARD);
        long now = serverLevel.getGameTime();
        if (!Avoidance.hazardStillValid(serverLevel, hazard, this.mob.blockPosition(), now)) {
            this.mob.removeData(ModAttachments.HAZARD);
            return null;
        }
        return hazard.pos();
    }

    private void moveAway() {
        if (this.hazardPos == null) {
            return;
        }
        double speed = this.currentSpeed();
        Vec3 threat = Vec3.atCenterOf(this.hazardPos);
        Path path = FleePathing.beelineAway(
                this.mob,
                threat,
                Avoidance.FLEE_DISTANCE,
                Avoidance.FLEE_PATH_ATTEMPTS);
        if (path != null) {
            this.mob.getNavigation().moveTo(path, speed);
            this.lastSpeed = speed;
        }
    }
}
