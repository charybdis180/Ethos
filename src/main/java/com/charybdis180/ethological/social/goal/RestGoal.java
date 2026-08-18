package com.charybdis180.ethological.social.goal;

import com.charybdis180.ethological.herd.HerdAttachments;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.goal.FollowParentGoal;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.home.NomadicMigration;
import com.charybdis180.ethological.hunger.GrazePatches;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.social.SocialCalm;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class RestGoal extends Goal {
    private static final double SPEED = 1.0;
    private static final double ARRIVE_DIST = 1.5;
    private static final int SHADE_SEARCH_RADIUS = 10;
    private static final int MIN_REST_TICKS = 400;
    private static final int REST_TICKS_SPAN = 400;
    private static final int RETRY_MIN_TICKS = 300;
    private static final int RETRY_TICKS_SPAN = 300;
    private static final float ROLL_CHANCE = 0.4f;
    private static final float SHADE_WHIM_CHANCE = 0.3f;
    private static final double HUDDLE_RANGE = 8.0;
    private final Animal mob;
    private BlockPos restSpot;
    private long restUntilGameTime;
    private long nextRollGameTime;
    private boolean lying;

    public RestGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        long now = this.mob.level().getGameTime();
        if (now < this.nextRollGameTime) {
            return false;
        }
        this.nextRollGameTime = now + RETRY_MIN_TICKS + (long)this.mob.getRandom().nextInt(RETRY_TICKS_SPAN);
        if (this.mob.getRandom().nextFloat() >= ROLL_CHANCE || !SocialCalm.canIdle(this.mob)) {
            return false;
        }
        if (GrazePatches.isPatchLeader(this.mob) && Hunger.herdWantsFood(this.mob)) {
            return false;
        }
        if (NomadicMigration.isTraveling(this.mob)) {
            return false;
        }
        if (FollowParentGoal.isAwayFromMother(this.mob)) {
            return false;
        }
        if (this.wouldBeLastStandingAdult()) {
            return false;
        }
        BlockPos here = this.mob.blockPosition();
        BlockPos huddle = this.nearestRestingMateSpot();
        boolean wantShade = Homes.isHotFor(this.mob) || this.mob.getRandom().nextFloat() < SHADE_WHIM_CHANCE;
        if (huddle != null) {
            this.restSpot = wantShade
                    ? Homes.findBetterShelteredHome(this.mob.level(), huddle, SHADE_SEARCH_RADIUS, 0, false).orElse(huddle)
                    : huddle;
        } else {
            this.restSpot = wantShade
                    ? Homes.findBetterShelteredHome(this.mob.level(), here, SHADE_SEARCH_RADIUS, 0, false).orElse(here)
                    : here;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (FollowParentGoal.isAwayFromMother(this.mob)) {
            return false;
        }
        if (this.mob.level().getGameTime() >= this.restUntilGameTime || !SocialCalm.stillCalm(this.mob)) {
            return false;
        }
        return this.mob.tickCount % 10 != 0
                || !this.mob.level().isRainingAt(this.mob.blockPosition())
                || Homes.isSheltered(this.mob.level(), this.mob.blockPosition());
    }

    @Override
    public void start() {
        this.lying = false;
        this.restUntilGameTime = this.mob.level().getGameTime() + MIN_REST_TICKS
                + (long)this.mob.getRandom().nextInt(REST_TICKS_SPAN);
        if (this.restSpot.closerThan((Vec3i)this.mob.blockPosition(), ARRIVE_DIST)) {
            this.mob.getNavigation().stop();
        } else {
            this.mob.getNavigation().moveTo(
                    (double)this.restSpot.getX() + 0.5,
                    (double)this.restSpot.getY(),
                    (double)this.restSpot.getZ() + 0.5,
                    SPEED);
        }
    }

    @Override
    public void tick() {
        if (this.lying) {
            this.mob.getNavigation().stop();
            return;
        }
        if (this.mob.distanceToSqr(Vec3.atBottomCenterOf((Vec3i)this.restSpot)) <= 2.25
                || this.mob.getNavigation().isDone()) {
            // Re-check last-adult rule right before lying down.
            if (this.wouldBeLastStandingAdult()) {
                this.restUntilGameTime = this.mob.level().getGameTime();
                return;
            }
            this.mob.getNavigation().stop();
            this.lying = true;
            this.mob.setData(SleepAttachments.SLEEP_YAW, Float.valueOf(this.mob.yBodyRot));
            this.mob.setData(SleepAttachments.RESTING, true);
        }
    }

    @Override
    public void stop() {
        if (this.lying) {
            this.mob.setData(SleepAttachments.RESTING, false);
            this.lying = false;
        }
        this.mob.getNavigation().stop();
    }

    private BlockPos nearestRestingMateSpot() {
        if (!this.mob.hasData(HerdAttachments.HERD_DATA) || !(this.mob.level() instanceof ServerLevel level)) {
            return null;
        }
        HerdManager.Herd herd = HerdManager.get(this.mob.getData(HerdAttachments.HERD_DATA).herdId());
        if (herd == null) {
            return null;
        }
        Animal best = null;
        double bestDist = HUDDLE_RANGE * HUDDLE_RANGE;
        for (UUID id : herd.members) {
            if (id.equals(this.mob.getUUID())) {
                continue;
            }
            Entity entity = level.getEntity(id);
            if (!(entity instanceof Animal mate) || !mate.isAlive()) {
                continue;
            }
            if (!((Boolean)mate.getData(SleepAttachments.RESTING)).booleanValue()) {
                continue;
            }
            double dist = this.mob.distanceToSqr(mate);
            if (dist < bestDist) {
                bestDist = dist;
                best = mate;
            }
        }
        return best == null ? null : best.blockPosition();
    }

    /** Keep one adult sentinel standing in herds of size ≥ 3. */
    private boolean wouldBeLastStandingAdult() {
        if (this.mob.isBaby() || !this.mob.hasData(HerdAttachments.HERD_DATA)) {
            return false;
        }
        Level level = this.mob.level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        HerdManager.Herd herd = HerdManager.get(this.mob.getData(HerdAttachments.HERD_DATA).herdId());
        if (herd == null || herd.members.size() < 3) {
            return false;
        }
        int loadedAdults = 0;
        int standingAdults = 0;
        for (UUID id : herd.members) {
            Entity entity = serverLevel.getEntity(id);
            if (!(entity instanceof Animal mate) || !mate.isAlive() || mate.isBaby()) {
                continue;
            }
            ++loadedAdults;
            boolean resting = ((Boolean)mate.getData(SleepAttachments.RESTING)).booleanValue();
            boolean sleeping = ((Boolean)mate.getData(SleepAttachments.SLEEPING)).booleanValue();
            if (!resting && !sleeping) {
                ++standingAdults;
            }
        }
        if (loadedAdults < 3) {
            return false;
        }
        // If this mob is already resting/sleeping, it isn't the last standing.
        boolean selfDown = ((Boolean)this.mob.getData(SleepAttachments.RESTING)).booleanValue()
                || ((Boolean)this.mob.getData(SleepAttachments.SLEEPING)).booleanValue();
        if (selfDown) {
            return false;
        }
        return standingAdults <= 1;
    }
}
