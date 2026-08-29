package com.charybdis180.ethological.herd.goal;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.config.EthologicalConfig;
import com.charybdis180.ethological.herd.MotherData;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.social.SocialCalm;
import java.util.EnumSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;

/** Baby approaches mother for a small hunger transfer. Yields to panic / flee / drink. */
public class NurseGoal extends Goal {
    private static final double SPEED = 1.15;
    private static final double CONTACT_DIST = 2.0;
    private static final int REPATH_TICKS = 15;
    private final Animal baby;
    private Animal mother;
    private long cooldownUntil;
    private int repathCooldown;
    private int nurseTicks;

    public NurseGoal(Animal baby) {
        this.baby = baby;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private static int motherDrain() {
        return EthologicalConfig.CONFIG.comfort.nurseMotherDrain.get();
    }

    private static int feedAmount() {
        return EthologicalConfig.CONFIG.comfort.nurseFeedAmount.get();
    }

    private static int cooldownTicks() {
        return EthologicalConfig.CONFIG.comfort.nurseCooldownTicks.get();
    }

    @Override
    public boolean canUse() {
        if (!this.baby.isBaby() || this.baby.level().getGameTime() < this.cooldownUntil) {
            return false;
        }
        if (!Hunger.supports(this.baby) || !Hunger.wantsFood(this.baby)) {
            return false;
        }
        if (!SocialCalm.stillCalm(this.baby)) {
            return false;
        }
        Animal mom = this.resolveMother();
        if (mom == null || !mom.isAlive() || mom.isBaby()) {
            return false;
        }
        int drain = motherDrain();
        if (!Hunger.supports(mom) || Hunger.getHunger(mom) <= drain) {
            return false;
        }
        if (!SocialCalm.stillCalm(mom)) {
            return false;
        }
        this.mother = mom;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        int drain = motherDrain();
        return this.mother != null
                && this.mother.isAlive()
                && Hunger.wantsFood(this.baby)
                && Hunger.getHunger(this.mother) > drain
                && SocialCalm.stillCalm(this.baby)
                && SocialCalm.stillCalm(this.mother)
                && this.nurseTicks < 40;
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
        this.nurseTicks = 0;
    }

    @Override
    public void tick() {
        if (this.mother == null) {
            return;
        }
        this.baby.getLookControl().setLookAt(this.mother, 10.0f, (float)this.baby.getMaxHeadXRot());
        this.mother.getLookControl().setLookAt(this.baby, 10.0f, (float)this.mother.getMaxHeadXRot());
        this.mother.getNavigation().stop();
        if (this.baby.distanceTo(this.mother) <= CONTACT_DIST) {
            this.baby.getNavigation().stop();
            if (++this.nurseTicks >= 12) {
                Hunger.feed(this.baby, feedAmount());
                int drain = motherDrain();
                if (drain > 0 && Hunger.hasHungerData(this.mother)) {
                    var data = Hunger.data(this.mother);
                    Hunger.setData(this.mother, data.withHunger(Math.max(0, data.hunger() - drain)));
                }
                this.cooldownUntil = this.baby.level().getGameTime() + cooldownTicks();
                this.nurseTicks = 40;
            }
            return;
        }
        if (this.repathCooldown > 0) {
            --this.repathCooldown;
        } else {
            this.baby.getNavigation().moveTo(this.mother, SPEED);
            this.repathCooldown = REPATH_TICKS;
        }
    }

    @Override
    public void stop() {
        this.baby.getNavigation().stop();
        this.mother = null;
        this.nurseTicks = 0;
    }

    private Animal resolveMother() {
        if (!this.baby.hasData(ModAttachments.MOTHER)) {
            return null;
        }
        MotherData link = this.baby.getData(ModAttachments.MOTHER);
        Level level = this.baby.level();
        if (!(level instanceof ServerLevel serverLevel) || !link.isActive(serverLevel.getGameTime())) {
            return null;
        }
        Entity entity = serverLevel.getEntity(link.motherId());
        return entity instanceof Animal animal ? animal : null;
    }
}
