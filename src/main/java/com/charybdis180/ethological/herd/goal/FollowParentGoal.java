package com.charybdis180.ethological.herd.goal;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.HerdSettingsManager;
import com.charybdis180.ethological.herd.MotherData;
import com.charybdis180.ethological.herd.SpeciesHerdSettings;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.thirst.Thirst;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Keeps babies near their recorded mother (species {@code mother_follow_distance})
 * unless play, hunger, thirst, panic, or sleep need the MOVE slot.
 */
public class FollowParentGoal extends Goal {
    private static final double SPEED = 1.2;
    private static final double ARRIVE_RATIO = 0.8;
    private static final int REPATH_TICKS = 10;

    private final Animal mob;
    private Animal parent;
    private int repathCooldown;

    public FollowParentGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /** True when a mothered baby is beyond the species mother-follow leash. */
    public static boolean isAwayFromMother(Animal animal) {
        if (!animal.isBaby() || !animal.hasData(ModAttachments.MOTHER)) {
            return false;
        }
        MotherData data = animal.getData(ModAttachments.MOTHER);
        if (!data.isActive(animal.level().getGameTime())) {
            return false;
        }
        Animal mother = resolveMother(animal, data);
        if (mother == null) {
            return false;
        }
        return animal.distanceTo(mother) > motherFollowDistance(animal);
    }

    public static double motherFollowDistance(Animal animal) {
        return HerdSettingsManager.get(animal.getType())
                .map(SpeciesHerdSettings::motherFollowDistance)
                .orElse(SpeciesHerdSettings.DEFAULT_MOTHER_FOLLOW_DISTANCE);
    }

    @Override
    public boolean canUse() {
        if (!this.mob.isBaby() || !this.mob.hasData(ModAttachments.MOTHER)) {
            return false;
        }
        if (this.shouldYield()) {
            return false;
        }
        MotherData data = this.mob.getData(ModAttachments.MOTHER);
        if (!data.isActive(this.mob.level().getGameTime())) {
            this.mob.removeData(ModAttachments.MOTHER);
            return false;
        }
        this.parent = resolveMother(this.mob, data);
        return this.parent != null && this.mob.distanceTo(this.parent) > motherFollowDistance(this.mob);
    }

    @Override
    public boolean canContinueToUse() {
        if (this.parent == null || !this.parent.isAlive() || !this.mob.isBaby()) {
            return false;
        }
        if (!this.mob.hasData(ModAttachments.MOTHER)) {
            return false;
        }
        if (this.shouldYield()) {
            return false;
        }
        MotherData data = this.mob.getData(ModAttachments.MOTHER);
        if (!data.isActive(this.mob.level().getGameTime())) {
            this.mob.removeData(ModAttachments.MOTHER);
            return false;
        }
        return !FollowPathing.arrived(this.mob, this.arriveDistance(), this.parent.blockPosition());
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
        this.moveToParent();
    }

    @Override
    public void tick() {
        if (this.parent == null) {
            return;
        }
        double dist = this.mob.distanceTo(this.parent);
        double follow = motherFollowDistance(this.mob);
        if (FollowPathing.arrived(this.mob, this.arriveDistance(), this.parent.blockPosition())) {
            this.mob.getNavigation().stop();
            return;
        }
        if (this.repathCooldown > 0) {
            --this.repathCooldown;
        } else if (this.mob.getNavigation().isDone() || dist > follow * 2.0) {
            this.repathCooldown = REPATH_TICKS;
            this.moveToParent();
        }
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
        this.parent = null;
    }

    private double arriveDistance() {
        return motherFollowDistance(this.mob) * ARRIVE_RATIO;
    }

    private boolean shouldYield() {
        if (Boolean.TRUE.equals(this.mob.getData(ModAttachments.SLEEPING))
                || this.mob.hasData(ModAttachments.SLEEP_DISTURBANCE)) {
            return true;
        }
        if (this.mob.hasData(ModAttachments.PLAY) || this.mob.hasData(ModAttachments.STARTLE)) {
            return true;
        }
        if (this.mob.hasData(ModAttachments.FOOD_TARGET) || this.mob.hasData(ModAttachments.WATER_TARGET)) {
            return true;
        }
        if (Hunger.isUrgentlyHungry(this.mob) || Thirst.isUrgentlyThirsty(this.mob)) {
            return true;
        }
        return HerdManager.panicPhaseOf(this.mob, this.mob.level().getGameTime()) != HerdManager.PanicPhase.NONE;
    }

    private void moveToParent() {
        BlockPos parentXZ = this.parent.blockPosition();
        Path path = FollowPathing.pathToSurfaceStand(this.mob, parentXZ, FollowPathing.MAX_STEP_Y);
        if (path == null) {
            path = FollowPathing.hopToward(this.mob, parentXZ, FollowPathing.MAX_STEP_Y);
        }
        if (path != null) {
            this.mob.getNavigation().moveTo(path, SPEED);
        }
    }

    private static Animal resolveMother(Animal baby, MotherData data) {
        Level level = baby.level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        Entity entity = serverLevel.getEntity(data.motherId());
        if (entity instanceof Animal mother
                && mother.getType() == baby.getType()
                && mother.isAlive()) {
            return mother;
        }
        return null;
    }
}
