package com.charybdis180.ethological.herd.goal;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.herd.HerdManager;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

public class HerdWaryGoal
extends Goal {
    private final Animal mob;
    private LivingEntity threat;
    private boolean holding;

    public HerdWaryGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    public boolean canUse() {
        this.threat = this.findThreat();
        return this.threat != null;
    }

    public boolean canContinueToUse() {
        this.threat = this.findThreat();
        return this.threat != null;
    }

    public void start() {
        this.holding = false;
        this.moveAway();
    }

    public void tick() {
        if (this.threat == null) {
            return;
        }
        if (this.holding) {
            if (this.groupSpread() > 7.0) {
                return;
            }
            this.holding = false;
        }
        if (this.groupSpread() > 10.0) {
            this.holding = true;
            this.mob.getNavigation().stop();
            return;
        }
        if (this.mob.getNavigation().isDone()) {
            this.moveAway();
        }
    }

    public void stop() {
        this.threat = null;
        this.holding = false;
    }

    private double groupSpread() {
        ServerLevel serverLevel;
        block6: {
            block5: {
                Level level = this.mob.level();
                if (!(level instanceof ServerLevel)) break block5;
                serverLevel = (ServerLevel)level;
                if (this.mob.hasData(ModAttachments.HERD_DATA)) break block6;
            }
            return 0.0;
        }
        HerdManager.Herd herd = HerdManager.get(((HerdData)this.mob.getData(ModAttachments.HERD_DATA)).herdId());
        if (herd == null) {
            return 0.0;
        }
        double max = 0.0;
        for (UUID memberId : herd.members) {
            Entity member;
            if (memberId.equals(this.mob.getUUID()) || (member = serverLevel.getEntity(memberId)) == null) continue;
            max = Math.max(max, (double)member.distanceTo(this.mob));
        }
        return max;
    }

    private LivingEntity findThreat() {
        LivingEntity living;
        if (!this.mob.hasData(ModAttachments.HERD_DATA) || !((HerdData)this.mob.getData(ModAttachments.HERD_DATA)).alpha()) {
            return null;
        }
        if (HerdManager.panicPhaseOf(this.mob, this.mob.level().getGameTime()) != HerdManager.PanicPhase.WARY) {
            return null;
        }
        Level level = this.mob.level();
        if (!(level instanceof ServerLevel)) {
            return null;
        }
        ServerLevel serverLevel = (ServerLevel)level;
        HerdManager.Herd herd = HerdManager.get(((HerdData)this.mob.getData(ModAttachments.HERD_DATA)).herdId());
        if (herd == null || herd.threatId() == null) {
            return null;
        }
        Entity entity = serverLevel.getEntity(herd.threatId());
        if (!(entity instanceof LivingEntity) || !(living = (LivingEntity)entity).isAlive()) {
            return null;
        }
        return living.distanceTo(this.mob) <= 36.0f ? living : null;
    }

    private void moveAway() {
        Vec3 threatPos = this.threat.position();
        Path bestPath = null;
        double bestDistanceSqr = -1.0;
        for (int i = 0; i < 8; ++i) {
            double distanceSqr;
            Path path;
            Vec3 candidate = DefaultRandomPos.getPosAway(this.mob, (int)16, (int)7, (Vec3)threatPos);
            if (candidate == null || (path = this.mob.getNavigation().createPath(candidate.x, candidate.y, candidate.z, 1)) == null || !path.canReach() || !((distanceSqr = candidate.distanceToSqr(threatPos)) > bestDistanceSqr)) continue;
            bestDistanceSqr = distanceSqr;
            bestPath = path;
        }
        if (bestPath != null) {
            this.mob.getNavigation().moveTo(bestPath, 1.2);
        }
    }
}

