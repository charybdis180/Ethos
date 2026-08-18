package com.charybdis180.ethological.social.goal;

import com.charybdis180.ethological.growth.GrowthSettingsManager;
import com.charybdis180.ethological.growth.SpeciesGrowthSettings;
import com.charybdis180.ethological.herd.HerdAttachments;
import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.HerdSettingsManager;
import com.charybdis180.ethological.herd.SpeciesHerdSettings;
import com.charybdis180.ethological.hunger.HungerAttachments;
import com.charybdis180.ethological.home.NomadicMigration;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.social.PlayData;
import com.charybdis180.ethological.social.SocialAttachments;
import com.charybdis180.ethological.social.SocialCalm;
import com.charybdis180.ethological.thirst.ThirstAttachments;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class PlayGoal
extends Goal {
    private static final double PARTNER_RADIUS = 12.0;
    /** End the bout once either player strays past this multiple of follow distance. */
    private static final double HERD_LEASH_MULT = 1.5;
    private final Animal mob;
    private Animal partner;
    private Animal pendingPartner;
    private PlayData.PlayStyle pendingStyle;
    private long nextRollGameTime;
    private int repathCooldown;
    private int phaseTicks;
    private int buttPhase;
    private boolean wasInBout;

    public PlayGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    public boolean canUse() {
        float rollChance;
        if (this.mob.hasData(SocialAttachments.PLAY)) {
            PlayData data = (PlayData)this.mob.getData(SocialAttachments.PLAY);
            if (this.mob.level().getGameTime() >= data.untilGameTime() || this.resolvePartner() == null) {
                this.mob.removeData(SocialAttachments.PLAY);
                return false;
            }
            return true;
        }
        long now = this.mob.level().getGameTime();
        if (now < this.nextRollGameTime) {
            return false;
        }
        // A marching herd doesn't stop to play — defer new bouts until travel pauses.
        if (NomadicMigration.isTraveling(this.mob)) {
            return false;
        }
        Optional<SpeciesGrowthSettings> growth = GrowthSettingsManager.get(this.mob.getType());
        boolean baby = this.mob.isBaby();
        this.nextRollGameTime = now + (long)(baby ? 200 : 300) + (long)this.mob.getRandom().nextInt(baby ? 200 : 300);
        float f = rollChance = baby ? growth.map(SpeciesGrowthSettings::babyPlayChance).orElse(Float.valueOf(0.6f)).floatValue() : 0.4f;
        if (this.mob.getRandom().nextFloat() >= rollChance || !SocialCalm.canIdle(this.mob)) {
            return false;
        }
        Animal partner = this.findPartner(growth);
        if (partner == null) {
            return false;
        }
        PlayData.PlayStyle style = this.chooseStyle(growth, partner, baby);
        if (style == null) {
            return false;
        }
        this.pendingPartner = partner;
        this.pendingStyle = style;
        this.partner = partner;
        return true;
    }

    public boolean canContinueToUse() {
        // Abort the bout as soon as the herd resumes marching so play never
        // pulls a member (or the alpha) away from the migration route.
        if (NomadicMigration.isTraveling(this.mob)) {
            return false;
        }
        if (!this.mob.hasData(SocialAttachments.PLAY)) {
            return false;
        }
        if (this.mob.level().getGameTime() >= ((PlayData)this.mob.getData(SocialAttachments.PLAY)).untilGameTime()) {
            return false;
        }
        if (!SocialCalm.stillCalm(this.mob)) {
            return false;
        }
        if (this.tooFarFromHerd()) {
            return false;
        }
        this.partner = this.resolvePartner();
        return this.partner != null;
    }

    public void start() {
        this.repathCooldown = 0;
        this.phaseTicks = 0;
        this.buttPhase = 0;
        this.wasInBout = true;
        if (!this.mob.hasData(SocialAttachments.PLAY) && this.pendingPartner != null && this.pendingStyle != null) {
            Animal partner = this.pendingPartner;
            PlayData.PlayStyle style = this.pendingStyle;
            long until = this.mob.level().getGameTime() + 240L + (long)this.mob.getRandom().nextInt(160);
            this.mob.setData(SocialAttachments.PLAY, new PlayData(partner.getUUID(), until, style, true, false));
            partner.setData(SocialAttachments.PLAY, new PlayData(this.mob.getUUID(), until, style, false, false));
            this.partner = partner;
        }
        this.pendingPartner = null;
        this.pendingStyle = null;
    }

    public void tick() {
        if (this.partner == null || !this.mob.hasData(SocialAttachments.PLAY)) {
            return;
        }
        if (this.tooFarFromHerd()) {
            this.endBout();
            return;
        }
        PlayData data = (PlayData)this.mob.getData(SocialAttachments.PLAY);
        this.mob.getLookControl().setLookAt((Entity)this.partner, 10.0f, (float)this.mob.getMaxHeadXRot());
        if (data.style() == PlayData.PlayStyle.HEADBUTT) {
            this.tickHeadbutt(data);
        } else {
            this.tickChase(data);
        }
    }

    public void stop() {
        this.mob.getNavigation().stop();
        this.pendingPartner = null;
        this.pendingStyle = null;
        if (this.mob.hasData(SocialAttachments.PLAY)) {
            Animal partner;
            ServerLevel serverLevel;
            Entity entity;
            Level level;
            PlayData data = (PlayData)this.mob.getData(SocialAttachments.PLAY);
            this.mob.removeData(SocialAttachments.PLAY);
            if (data.initiator() && (level = this.mob.level()) instanceof ServerLevel && (entity = (serverLevel = (ServerLevel)level).getEntity(data.partnerId())) instanceof Animal && (partner = (Animal)entity).hasData(SocialAttachments.PLAY) && ((PlayData)partner.getData(SocialAttachments.PLAY)).partnerId().equals(this.mob.getUUID())) {
                partner.removeData(SocialAttachments.PLAY);
            }
        }
        if (this.wasInBout) {
            this.wasInBout = false;
            this.nextRollGameTime = this.mob.level().getGameTime() + 600L + (long)this.mob.getRandom().nextInt(400);
        }
        this.partner = null;
        this.returnToHerd();
    }

    private void tickChase(PlayData data) {
        if (this.repathCooldown > 0) {
            --this.repathCooldown;
        }
        if (data.initiator()) {
            if (this.repathCooldown <= 0 || this.mob.getNavigation().isDone()) {
                this.mob.getNavigation().moveTo((Entity)this.partner, 1.35);
                this.repathCooldown = 10;
            }
            if ((double)this.mob.distanceTo((Entity)this.partner) <= 1.8) {
                this.playEffects();
                if (!data.swapped() && this.mob.getRandom().nextBoolean()) {
                    long until = data.untilGameTime();
                    this.mob.setData(SocialAttachments.PLAY,new PlayData(this.partner.getUUID(), until, PlayData.PlayStyle.CHASE, false, true));
                    this.partner.setData(SocialAttachments.PLAY,new PlayData(this.mob.getUUID(), until, PlayData.PlayStyle.CHASE, true, true));
                } else {
                    this.endBout();
                }
            }
        } else if (this.repathCooldown <= 0 || this.mob.getNavigation().isDone()) {
            Vec3 away = this.fleePosNearHerd();
            if (away != null) {
                this.mob.getNavigation().moveTo(away.x, away.y, away.z, 1.25);
            } else {
                this.endBout();
                return;
            }
            this.repathCooldown = 15;
        }
    }

    private void tickHeadbutt(PlayData data) {
        if (!data.initiator()) {
            this.mob.getNavigation().stop();
            return;
        }
        double dist = this.mob.distanceTo((Entity)this.partner);
        if (this.buttPhase == 0) {
            if (dist <= 4.0) {
                this.buttPhase = 1;
                this.phaseTicks = 0;
                this.mob.getNavigation().stop();
            } else if (--this.repathCooldown <= 0 || this.mob.getNavigation().isDone()) {
                this.mob.getNavigation().moveTo((Entity)this.partner, 1.3);
                this.repathCooldown = 10;
            }
            return;
        }
        if (this.buttPhase == 1) {
            this.mob.getNavigation().stop();
            if (++this.phaseTicks >= 25) {
                this.buttPhase = 2;
                this.phaseTicks = 0;
                this.mob.getNavigation().moveTo((Entity)this.partner, 1.7);
            }
            return;
        }
        if (dist <= 1.6) {
            this.bumpPartner();
            this.playEffects();
            this.endBout();
            return;
        }
        if (++this.phaseTicks > 30) {
            this.endBout();
            return;
        }
        if (this.mob.getNavigation().isDone()) {
            this.mob.getNavigation().moveTo((Entity)this.partner, 1.7);
        }
    }

    private void bumpPartner() {
        Vec3 knock = this.partner.position().subtract(this.mob.position());
        Vec3 flat = new Vec3(knock.x, 0.0, knock.z);
        if (flat.lengthSqr() > 1.0E-4) {
            flat = flat.normalize().scale(0.5);
            this.partner.push(flat.x, 0.25, flat.z);
        }
        this.mob.level().playSound(null, this.partner.blockPosition(), SoundEvents.GOAT_RAM_IMPACT, SoundSource.NEUTRAL, 0.8f, 1.0f);
        Level level = this.mob.level();
        if (level instanceof ServerLevel) {
            ServerLevel serverLevel = (ServerLevel)level;
            serverLevel.sendParticles((ParticleOptions)ParticleTypes.POOF, this.partner.getX(), this.partner.getY() + 0.5, this.partner.getZ(), 8, 0.25, 0.25, 0.25, 0.02);
        }
    }

    private void playEffects() {
        Level level = this.mob.level();
        if (level instanceof ServerLevel) {
            ServerLevel serverLevel = (ServerLevel)level;
            serverLevel.sendParticles((ParticleOptions)ParticleTypes.HAPPY_VILLAGER, this.partner.getX(), this.partner.getY() + 0.6, this.partner.getZ(), 6, 0.3, 0.3, 0.3, 0.0);
        }
    }

    private void endBout() {
        this.mob.removeData(SocialAttachments.PLAY);
        if (this.partner != null && this.partner.hasData(SocialAttachments.PLAY) && ((PlayData)this.partner.getData(SocialAttachments.PLAY)).partnerId().equals(this.mob.getUUID())) {
            this.partner.removeData(SocialAttachments.PLAY);
        }
    }

    private PlayData.PlayStyle chooseStyle(Optional<SpeciesGrowthSettings> growth, Animal partner, boolean baby) {
        boolean chaseAllowed = growth.map(g -> g.allowsPlay("chase")).orElse(true);
        boolean headbuttEligible = this.canHeadbutt(growth, partner, baby);
        if (headbuttEligible && chaseAllowed) {
            return this.mob.getRandom().nextBoolean() ? PlayData.PlayStyle.HEADBUTT : PlayData.PlayStyle.CHASE;
        }
        if (headbuttEligible) {
            return PlayData.PlayStyle.HEADBUTT;
        }
        if (chaseAllowed) {
            return PlayData.PlayStyle.CHASE;
        }
        return null;
    }

    private boolean canHeadbutt(Optional<SpeciesGrowthSettings> growth, Animal partner, boolean baby) {
        if (!(this.mob instanceof Sheep) && !(this.mob instanceof Cow)) {
            return false;
        }
        if (!growth.map(g -> g.allowsPlay("headbutt")).orElse(true)) {
            return false;
        }
        if (baby && !partner.isBaby()) {
            return false;
        }
        return true;
    }

    private Animal findPartner(Optional<SpeciesGrowthSettings> growth) {
        boolean ageGrouped = growth.map(SpeciesGrowthSettings::babyOnlyPlaysWithBabies).orElse(false);
        UUID ownHerdId = this.mob.hasData(HerdAttachments.HERD_DATA)
                ? this.mob.getData(HerdAttachments.HERD_DATA).herdId()
                : null;
        List<Animal> found = this.mob.level().getEntitiesOfClass(Animal.class, this.mob.getBoundingBox().inflate(12.0), other -> other != this.mob && other.getType() == this.mob.getType() && !other.hasData(SocialAttachments.PLAY) && (Boolean)other.getData(SleepAttachments.RESTING) == false && !other.hasData(HungerAttachments.FOOD_TARGET) && !other.hasData(ThirstAttachments.WATER_TARGET) && SocialCalm.canIdle(other) && (!ageGrouped || other.isBaby() == this.mob.isBaby()));
        if (found.isEmpty()) {
            return null;
        }
        // Mutable copy: getEntitiesOfClass / Stream.toList() may be unmodifiable.
        List<Animal> candidates = new ArrayList<>(found);
        // Prefer herd-mates so chase/flee does not peel animals into another group.
        if (ownHerdId != null) {
            List<Animal> herdMates = new ArrayList<>();
            for (Animal other : candidates) {
                if (other.hasData(HerdAttachments.HERD_DATA)
                        && other.getData(HerdAttachments.HERD_DATA).herdId().equals(ownHerdId)) {
                    herdMates.add(other);
                }
            }
            if (!herdMates.isEmpty()) {
                candidates = herdMates;
            }
        }
        if (this.mob.isBaby() && ageGrouped) {
            candidates.sort(Comparator.comparingDouble(other -> this.mob.distanceToSqr(other)));
        } else if (this.mob.isBaby()) {
            candidates.sort(Comparator.comparing((Animal other) -> !other.isBaby()).thenComparingDouble(other -> this.mob.distanceToSqr(other)));
        } else {
            candidates.sort(Comparator.comparingDouble(other -> this.mob.distanceToSqr(other)));
        }
        return candidates.get(0);
    }

    private Animal resolvePartner() {
        Animal animal;
        Level level;
        if (!this.mob.hasData(SocialAttachments.PLAY) || !((level = this.mob.level()) instanceof ServerLevel)) {
            return null;
        }
        ServerLevel serverLevel = (ServerLevel)level;
        Entity entity = serverLevel.getEntity(((PlayData)this.mob.getData(SocialAttachments.PLAY)).partnerId());
        return entity instanceof Animal && (animal = (Animal)entity).hasData(SocialAttachments.PLAY) ? animal : null;
    }

    private Optional<Animal> herdAlpha() {
        if (!this.mob.hasData(HerdAttachments.HERD_DATA)) {
            return Optional.empty();
        }
        Level level = this.mob.level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return Optional.empty();
        }
        HerdData data = this.mob.getData(HerdAttachments.HERD_DATA);
        if (data.alpha()) {
            return Optional.of(this.mob);
        }
        HerdManager.Herd herd = HerdManager.get(data.herdId());
        if (herd == null || herd.alphaId == null) {
            return Optional.empty();
        }
        Entity entity = serverLevel.getEntity(herd.alphaId);
        return entity instanceof Animal alpha && entity != this.mob ? Optional.of(alpha) : Optional.empty();
    }

    private double herdLeashDistance() {
        SpeciesHerdSettings settings = HerdSettingsManager.get(this.mob.getType()).orElse(null);
        if (settings == null) {
            return PARTNER_RADIUS * HERD_LEASH_MULT;
        }
        int size = 1;
        if (this.mob.hasData(HerdAttachments.HERD_DATA)) {
            HerdManager.Herd herd = HerdManager.get(this.mob.getData(HerdAttachments.HERD_DATA).herdId());
            if (herd != null) {
                size = Math.max(1, herd.members.size());
            }
        }
        double follow = settings.followDistance() * (1.0 + (double)(size - 1) * settings.followSpreadPerMemberPercent());
        return follow * HERD_LEASH_MULT;
    }

    private boolean tooFarFromHerd() {
        Optional<Animal> alpha = this.herdAlpha();
        if (alpha.isEmpty()) {
            return false;
        }
        return (double) this.mob.distanceTo(alpha.get()) > this.herdLeashDistance();
    }

    /** Flee away from the partner, but keep the destination inside the herd leash. */
    private Vec3 fleePosNearHerd() {
        Optional<Animal> alphaOpt = this.herdAlpha();
        double leash = this.herdLeashDistance();
        for (int attempt = 0; attempt < 6; attempt++) {
            Vec3 away = DefaultRandomPos.getPosAway((PathfinderMob) this.mob, 10, 6, this.partner.position());
            if (away == null) {
                continue;
            }
            if (alphaOpt.isPresent() && alphaOpt.get().position().distanceToSqr(away) > leash * leash) {
                continue;
            }
            return away;
        }
        // Soft fallback: step toward alpha instead of running farther out.
        if (alphaOpt.isPresent()) {
            Animal alpha = alphaOpt.get();
            Vec3 toward = DefaultRandomPos.getPosTowards(
                    (PathfinderMob) this.mob, 8, 6, alpha.position(), (float) Math.PI / 2.0F);
            if (toward != null) {
                return toward;
            }
        }
        return null;
    }

    private void returnToHerd() {
        Optional<Animal> alpha = this.herdAlpha();
        if (alpha.isEmpty()) {
            return;
        }
        Animal a = alpha.get();
        double follow = this.herdLeashDistance() / HERD_LEASH_MULT;
        if ((double) this.mob.distanceTo(a) <= follow) {
            return;
        }
        this.mob.getNavigation().moveTo(a, 1.1);
    }
}
