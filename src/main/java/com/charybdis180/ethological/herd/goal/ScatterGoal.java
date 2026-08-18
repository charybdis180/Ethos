package com.charybdis180.ethological.herd.goal;

import com.charybdis180.ethological.herd.HerdAttachments;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.util.FleePathing;
import com.charybdis180.ethological.util.Personality;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public class ScatterGoal extends Goal {
    private static final int REPATH_TICKS = 10;
    private static final double FLEE_DISTANCE = 14.0;
    private final Animal mob;
    private int repathCooldown;

    public ScatterGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    private boolean scattering() {
        return HerdManager.panicPhaseOf(this.mob, this.mob.level().getGameTime()) == HerdManager.PanicPhase.SCATTER;
    }

    @Override
    public boolean canUse() {
        return this.scattering();
    }

    @Override
    public boolean canContinueToUse() {
        return this.scattering();
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
        this.sprintAway();
    }

    @Override
    public void tick() {
        if (this.repathCooldown > 0) {
            --this.repathCooldown;
        } else if (this.mob.getNavigation().isDone()) {
            this.repathCooldown = REPATH_TICKS;
            this.sprintAway();
        }
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
    }

    private void sprintAway() {
        Entity threat = this.resolveThreat();
        Vec3 stream = this.nearestScatterMateStream();
        if (threat != null) {
            Path path = FleePathing.beelineAway(
                    (PathfinderMob)this.mob, threat.position(), FLEE_DISTANCE, 4, stream);
            if (path != null) {
                this.mob.getNavigation().moveTo(path, this.speed());
                return;
            }
        }
        // No threat / no safe beeline: stream with mates if possible, else random.
        if (stream != null && stream.lengthSqr() > 1.0E-4) {
            Vec3 target = this.mob.position().add(stream.normalize().scale(FLEE_DISTANCE));
            Path streamed = this.mob.getNavigation().createPath(target.x, target.y, target.z, 1);
            if (streamed != null && streamed.canReach()) {
                this.mob.getNavigation().moveTo(streamed, this.speed());
                return;
            }
        }
        Vec3 target = DefaultRandomPos.getPos((PathfinderMob)this.mob, 10, 5);
        if (target != null) {
            this.mob.getNavigation().moveTo(target.x, target.y, target.z, this.speed());
        }
    }

    private double speed() {
        return 1.45 + Personality.salt(this.mob.getUUID(), "scatter_speed", 31) / 100.0;
    }

    @Nullable
    private Vec3 nearestScatterMateStream() {
        if (!this.mob.hasData(HerdAttachments.HERD_DATA) || !(this.mob.level() instanceof ServerLevel level)) {
            return null;
        }
        HerdManager.Herd herd = HerdManager.get(this.mob.getData(HerdAttachments.HERD_DATA).herdId());
        if (herd == null) {
            return null;
        }
        Animal best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3 bestDelta = null;
        for (UUID id : herd.members) {
            if (id.equals(this.mob.getUUID())) {
                continue;
            }
            Entity entity = level.getEntity(id);
            if (!(entity instanceof Animal mate) || !mate.isAlive()) {
                continue;
            }
            if (HerdManager.panicPhaseOf(mate, level.getGameTime()) != HerdManager.PanicPhase.SCATTER) {
                continue;
            }
            Vec3 delta = mate.getDeltaMovement();
            if (delta.horizontalDistanceSqr() < 0.0025) {
                // Prefer look/body facing when nearly still.
                float yaw = mate.yBodyRot * ((float)Math.PI / 180.0f);
                delta = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
            }
            double dist = this.mob.distanceToSqr(mate);
            if (dist < bestDist) {
                bestDist = dist;
                best = mate;
                bestDelta = delta;
            }
        }
        return best == null ? null : bestDelta;
    }

    private Entity resolveThreat() {
        if (!this.mob.hasData(HerdAttachments.HERD_DATA) || !(this.mob.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        HerdManager.Herd herd = HerdManager.get(this.mob.getData(HerdAttachments.HERD_DATA).herdId());
        if (herd == null || herd.threatId() == null) {
            return null;
        }
        return serverLevel.getEntity(herd.threatId());
    }
}
