package com.charybdis180.ethological.herd.goal;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.herd.HerdManager;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

public class GatherGoal
extends Goal {
    private static final double GATHERED_DISTANCE = 2.0;
    private final Animal mob;
    private Animal alpha;
    private int repathCooldown;

    public GatherGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    private boolean gathering() {
        return HerdManager.panicPhaseOf(this.mob, this.mob.level().getGameTime()) == HerdManager.PanicPhase.GATHER;
    }

    public boolean canUse() {
        Animal alphaAnimal;
        block9: {
            block8: {
                if (!this.gathering() || !this.mob.hasData(ModAttachments.HERD_DATA)) {
                    return false;
                }
                if (((HerdData)this.mob.getData(ModAttachments.HERD_DATA)).alpha()) {
                    return false;
                }
                Level level = this.mob.level();
                if (!(level instanceof ServerLevel)) {
                    return false;
                }
                ServerLevel serverLevel = (ServerLevel)level;
                HerdManager.Herd herd = HerdManager.get(((HerdData)this.mob.getData(ModAttachments.HERD_DATA)).herdId());
                if (herd == null || herd.alphaId == null) {
                    return false;
                }
                Entity entity = serverLevel.getEntity(herd.alphaId);
                if (!(entity instanceof Animal)) break block8;
                alphaAnimal = (Animal)entity;
                if (entity != this.mob) break block9;
            }
            return false;
        }
        if (FollowPathing.arrived(this.mob, GATHERED_DISTANCE, this.rallySlotXZ(alphaAnimal))) {
            return false;
        }
        this.alpha = alphaAnimal;
        return true;
    }

    public boolean canContinueToUse() {
        return this.gathering() && this.alpha != null && this.alpha.isAlive()
                && !FollowPathing.arrived(this.mob, GATHERED_DISTANCE, this.rallySlotXZ(this.alpha));
    }

    public void start() {
        this.repathCooldown = 0;
        this.moveToAlpha();
    }

    public void tick() {
        if (this.repathCooldown > 0) {
            --this.repathCooldown;
        } else if (this.mob.getNavigation().isDone()) {
            this.repathCooldown = 20;
            this.moveToAlpha();
        }
    }

    public void stop() {
        this.alpha = null;
    }

    private void moveToAlpha() {
        BlockPos slotXZ = this.rallySlotXZ(this.alpha);
        Path path = FollowPathing.pathToSurfaceStand(this.mob, slotXZ, FollowPathing.MAX_STEP_Y);
        if (path == null) {
            path = FollowPathing.hopToward(this.mob, slotXZ, FollowPathing.MAX_STEP_Y);
        }
        if (path != null) {
            this.mob.getNavigation().moveTo(path, this.speed());
        }
    }

    private BlockPos rallySlotXZ(Animal alpha) {
        Vec3 slot = this.rallySlot(alpha);
        return new BlockPos(Mth.floor(slot.x), Mth.floor(slot.y), Mth.floor(slot.z));
    }

    private Vec3 rallySlot(Animal alpha) {
        double angle = com.charybdis180.ethological.util.Personality.angleRadians(this.mob.getUUID(), "gather_angle");
        double radius = 2.5 + com.charybdis180.ethological.util.Personality.salt(this.mob.getUUID(), "gather_radius", 21) / 10.0;
        return alpha.position().add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
    }

    private double speed() {
        return 1.25 + com.charybdis180.ethological.util.Personality.salt(this.mob.getUUID(), "gather_speed", 21) / 100.0;
    }
}

