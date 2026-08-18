package com.charybdis180.ethological.social.goal;

import com.charybdis180.ethological.herd.goal.FollowParentGoal;
import com.charybdis180.ethological.social.Familiarity;
import com.charybdis180.ethological.social.SocialCalm;
import java.util.EnumSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;

public class CuriousGoal extends Goal {
    private static final double GIVE_UP_RANGE = 26.0;
    private static final double SPEED = 1.0;
    private static final int WATCH_TICKS = 200;
    private static final int COOLDOWN_TICKS = 1200;
    private static final int REPATH_TICKS = 15;
    private final Animal mob;
    private Player targetPlayer;
    private long cooldownUntilGameTime;
    private long nextScanGameTime;
    private int watchTicks;
    private int repathCooldown;

    public CuriousGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        long now = this.mob.level().getGameTime();
        if (now < this.cooldownUntilGameTime || now < this.nextScanGameTime) {
            return false;
        }
        this.nextScanGameTime = now + 40L + (long)this.mob.getRandom().nextInt(40);
        if (!SocialCalm.stillCalm(this.mob) || !SocialCalm.hasTimeToIdle(this.mob)) {
            return false;
        }
        if (FollowParentGoal.isAwayFromMother(this.mob)) {
            return false;
        }
        Player player = this.findFoodHolder();
        if (player == null) {
            return false;
        }
        this.targetPlayer = player;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (FollowParentGoal.isAwayFromMother(this.mob)) {
            return false;
        }
        return this.targetPlayer != null
                && this.targetPlayer.isAlive()
                && this.isHoldingFood(this.targetPlayer)
                && (double)this.mob.distanceTo((Entity)this.targetPlayer) <= GIVE_UP_RANGE
                && this.watchTicks < WATCH_TICKS
                && SocialCalm.stillCalm(this.mob);
    }

    @Override
    public void start() {
        this.watchTicks = 0;
        this.repathCooldown = 0;
    }

    @Override
    public void tick() {
        if (this.targetPlayer == null) {
            return;
        }
        this.mob.getLookControl().setLookAt((Entity)this.targetPlayer, 10.0f, (float)this.mob.getMaxHeadXRot());
        double watch = Familiarity.curiousWatchRange(this.mob, this.targetPlayer);
        if ((double)this.mob.distanceTo((Entity)this.targetPlayer) <= watch) {
            this.mob.getNavigation().stop();
            if (++this.watchTicks >= WATCH_TICKS) {
                this.cooldownUntilGameTime = this.mob.level().getGameTime() + COOLDOWN_TICKS;
            }
            return;
        }
        if (this.repathCooldown > 0) {
            --this.repathCooldown;
        } else {
            this.mob.getNavigation().moveTo((Entity)this.targetPlayer, SPEED);
            this.repathCooldown = REPATH_TICKS;
        }
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
        this.targetPlayer = null;
        long now = this.mob.level().getGameTime();
        if (now + 200L > this.cooldownUntilGameTime) {
            this.cooldownUntilGameTime = now + 200L;
        }
    }

    private Player findFoodHolder() {
        Player best = null;
        double bestDistSqr = Double.MAX_VALUE;
        for (Player player : this.mob.level().players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            double notice = Familiarity.curiousNoticeRange(this.mob, player);
            double distSqr = this.mob.distanceToSqr((Entity)player);
            if (distSqr > notice * notice || !this.isHoldingFood(player) || distSqr >= bestDistSqr) {
                continue;
            }
            bestDistSqr = distSqr;
            best = player;
        }
        return best;
    }

    private boolean isHoldingFood(Player player) {
        return this.mob.isFood(player.getMainHandItem()) || this.mob.isFood(player.getOffhandItem());
    }
}
