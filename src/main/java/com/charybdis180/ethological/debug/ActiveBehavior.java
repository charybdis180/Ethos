/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Vec3i
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.phys.Vec3
 */
package com.charybdis180.ethological.debug;

import com.charybdis180.ethological.avoidance.AvoidanceAttachments;
import com.charybdis180.ethological.debug.DebugAttachments;
import com.charybdis180.ethological.herd.FollowStyle;
import com.charybdis180.ethological.herd.HerdAttachments;
import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.HerdSettingsManager;
import com.charybdis180.ethological.herd.MotherData;
import com.charybdis180.ethological.herd.SpeciesHerdSettings;
import com.charybdis180.ethological.herd.goal.FollowAlphaGoal;
import com.charybdis180.ethological.herd.goal.FollowPathing;
import com.charybdis180.ethological.herd.goal.NurseGoal;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.home.NomadicMigration;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.hunger.HungerAttachments;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.sleep.SleepDisturbance;
import com.charybdis180.ethological.sleep.SleepEvents;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import com.charybdis180.ethological.social.SocialAttachments;
import com.charybdis180.ethological.social.goal.CuriousGoal;
import com.charybdis180.ethological.social.goal.VigilanceGoal;
import com.charybdis180.ethological.thirst.ThirstAttachments;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class ActiveBehavior {
    private static volatile boolean enabled;
    private static volatile long nextEnabledPollGameTime;

    private ActiveBehavior() {
    }

    /** Debug labels are only visible while a player holds a debug stick, so skip all the per-tick resolution work otherwise. */
    private static boolean isEnabled(ServerLevel level) {
        long now = level.getGameTime();
        if (now >= nextEnabledPollGameTime) {
            nextEnabledPollGameTime = now + 20L;
            boolean found = false;
            for (net.minecraft.world.entity.player.Player player : level.players()) {
                if (!player.getMainHandItem().is(net.minecraft.world.item.Items.DEBUG_STICK) && !player.getOffhandItem().is(net.minecraft.world.item.Items.DEBUG_STICK)) continue;
                found = true;
                break;
            }
            enabled = found;
        }
        return enabled;
    }

    public static void update(Animal animal) {
        Level checkLevel = animal.level();
        if (!(checkLevel instanceof ServerLevel) || !ActiveBehavior.isEnabled((ServerLevel)checkLevel)) {
            return;
        }
        long now = animal.level().getGameTime();
        HerdManager.PanicPhase phase = HerdManager.panicPhaseOf(animal, now);
        byte phaseByte = (byte)phase.ordinal();
        if (!animal.hasData(DebugAttachments.PANIC_PHASE) || (Byte)animal.getData(DebugAttachments.PANIC_PHASE) != phaseByte) {
            animal.setData(DebugAttachments.PANIC_PHASE,phaseByte);
        }
        String label = ActiveBehavior.resolve(animal, phase);
        if (!animal.hasData(DebugAttachments.ACTIVE_BEHAVIOR) || !label.equals(animal.getData(DebugAttachments.ACTIVE_BEHAVIOR))) {
            animal.setData(DebugAttachments.ACTIVE_BEHAVIOR,label);
        }
    }

    public static String current(Animal animal) {
        return animal.hasData(DebugAttachments.ACTIVE_BEHAVIOR) ? (String)animal.getData(DebugAttachments.ACTIVE_BEHAVIOR) : "idle";
    }

    private static String resolve(Animal animal, HerdManager.PanicPhase phase) {
        if (((Boolean)animal.getData(SleepAttachments.SLEEPING)).booleanValue()) {
            return "sleeping";
        }
        return switch (phase) {
            default -> throw new MatchException(null, null);
            case HerdManager.PanicPhase.SCATTER -> "scatter";
            case HerdManager.PanicPhase.WARY -> "wary";
            case HerdManager.PanicPhase.GATHER -> "gather";
            case HerdManager.PanicPhase.NONE -> ActiveBehavior.resolveCalm(animal);
        };
    }

    private static String resolveCalm(Animal animal) {
        if (ActiveBehavior.isFleeing(animal)) {
            return "flee";
        }
        if (animal.hasData(AvoidanceAttachments.HAZARD)) {
            return "avoid_hazard";
        }
        if (animal.hasData(ThirstAttachments.WATER_TARGET)) {
            return "drink";
        }
        if (animal.hasData(SocialAttachments.STARTLE)) {
            return "startle";
        }
        if (animal.hasData(SocialAttachments.PLAY)) {
            return "play";
        }
        if (((Boolean)animal.getData(SleepAttachments.RESTING)).booleanValue()) {
            return "rest";
        }
        if (ActiveBehavior.isSettling(animal)) {
            return "settle";
        }
        if (ActiveBehavior.isReturning(animal)) {
            return "return";
        }
        if (animal.hasData(HungerAttachments.FOOD_TARGET)) {
            return "eat";
        }
        if (ActiveBehavior.isGoalRunning(animal, NurseGoal.class)) {
            return "nurse";
        }
        if (ActiveBehavior.isFollowingParent(animal)) {
            return "follow_parent";
        }
        if (ActiveBehavior.isEscaping(animal)) {
            return "escaping";
        }
        if (Hunger.isRuminating((Entity)animal)) {
            return "ruminate";
        }
        if (ActiveBehavior.isFollowingAlpha(animal)) {
            return "follow_alpha";
        }
        if (ActiveBehavior.isMigrating(animal)) {
            return "migrate";
        }
        if (ActiveBehavior.isGoalRunning(animal, CuriousGoal.class)) {
            return "curious";
        }
        if (ActiveBehavior.isGoalRunning(animal, VigilanceGoal.class)) {
            return "vigilant";
        }
        if (Homes.effectiveHome(animal).isPresent() && !animal.getNavigation().isDone()) {
            return "stroll";
        }
        return "idle";
    }

    private static boolean isGoalRunning(Animal animal, Class<? extends Goal> goalClass) {
        for (WrappedGoal wrapped : animal.goalSelector.getAvailableGoals()) {
            if (wrapped.isRunning() && goalClass.isInstance(wrapped.getGoal())) {
                return true;
            }
        }
        return false;
    }

    /** A member is genuinely escaping only when it is separated AND more than 2x its species
     * follow range from the alpha, mirroring {@link FollowAlphaGoal#ESCAPE_DISTANCE_MULTIPLIER}.
     * A member right next to its alpha — even one sitting in a shallow pit, on a ledge, or a
     * few blocks below the herd surface — is NOT escaping: the follow chain climbs that out
     * cheaply, and the alpha itself can never run the escape logic. Without the distance gate
     * the label fires for every member whose local terrain merely reads as separated, which is
     * what made members "do escaping" while standing beside the alpha. */
    private static boolean isEscaping(Animal animal) {
        if (!FollowPathing.isSeparated(animal)) {
            return false;
        }
        boolean alphaFlag = false;
        boolean holdsSeat = false;
        boolean alphaResolvable = false;
        if (animal.hasData(HerdAttachments.HERD_DATA)) {
            HerdData data = (HerdData)animal.getData(HerdAttachments.HERD_DATA);
            alphaFlag = data.alpha();
            if (animal.level() instanceof ServerLevel serverLevel) {
                HerdManager.Herd herd = HerdManager.get(data.herdId());
                if (herd != null && herd.alphaId != null) {
                    holdsSeat = herd.alphaId.equals(animal.getUUID());
                    Entity alphaEntity = serverLevel.getEntity(herd.alphaId);
                    alphaResolvable = alphaEntity instanceof Animal
                            && alphaEntity.isAlive()
                            && alphaEntity != animal;
                }
            }
        }
        if (alphaFlag || holdsSeat) {
            return false;
        }
        if (!alphaResolvable) {
            return false;
        }
        double follow = HerdSettingsManager.get(animal.getType())
                .map(SpeciesHerdSettings::followDistance)
                .orElse(10.0);
        double alphaDist = FollowPathing.distanceToAlpha(animal);
        if (alphaDist < 0.0 || alphaDist <= follow * FollowAlphaGoal.ESCAPE_DISTANCE_MULTIPLIER) {
            return false;
        }
        return true;
    }

    private static boolean isFleeing(Animal animal) {
        LivingEntity living;
        if (!animal.hasData(SleepAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        Level level = animal.level();
        if (!(level instanceof ServerLevel)) {
            return false;
        }
        ServerLevel serverLevel = (ServerLevel)level;
        SleepDisturbance disturbance = (SleepDisturbance)animal.getData(SleepAttachments.SLEEP_DISTURBANCE);
        Entity entity = serverLevel.getEntity(disturbance.threatId());
        if (!(entity instanceof LivingEntity) || !(living = (LivingEntity)entity).isAlive()) {
            return false;
        }
        return living.distanceTo((Entity)animal) <= 36.0f;
    }

    private static boolean isSettling(Animal animal) {
        long dayTime;
        if (animal.hasData(SleepAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        Optional<SpeciesSleepSettings> settingsOpt = SleepSettingsManager.get(animal.getType());
        if (settingsOpt.isEmpty()) {
            return false;
        }
        SpeciesSleepSettings settings = settingsOpt.get();
        if (!settings.isSleepTime(dayTime = animal.level().getDayTime())) {
            return false;
        }
        if (Homes.shouldTravelHome(animal, dayTime, settings)) {
            return false;
        }
        return Homes.ticksIntoSleepWindow(dayTime, settings) < SleepEvents.bedtimeDelay(animal, settings);
    }

    private static boolean isReturning(Animal animal) {
        if (animal.hasData(SleepAttachments.SLEEP_DISTURBANCE)) {
            return false;
        }
        Optional<SpeciesSleepSettings> settingsOpt = SleepSettingsManager.get(animal.getType());
        if (settingsOpt.isEmpty()) {
            return false;
        }
        SpeciesSleepSettings settings = settingsOpt.get();
        long dayTime = animal.level().getDayTime();
        if (!Homes.shouldTravelHome(animal, dayTime, settings)) {
            return false;
        }
        Optional<BlockPos> home = Homes.effectiveHome(animal);
        if (home.isEmpty()) {
            return false;
        }
        if (settings.isSleepTime(dayTime)) {
            return animal.distanceToSqr(Vec3.atCenterOf((Vec3i)((Vec3i)home.get()))) > 2.25;
        }
        double distance = Math.sqrt(animal.distanceToSqr(Vec3.atCenterOf((Vec3i)((Vec3i)home.get()))));
        long lead = Math.min(3000L, 200L + (long)(distance / 0.2));
        return Homes.ticksUntilSleepStart(dayTime, settings.sleepStartTick()) <= lead;
    }

    private static boolean isFollowingParent(Animal animal) {
        if (!animal.isBaby() || !animal.hasData(HerdAttachments.MOTHER)) {
            return false;
        }
        MotherData data = (MotherData)animal.getData(HerdAttachments.MOTHER);
        return animal.level().getGameTime() < data.followUntilGameTime();
    }

    private static boolean isFollowingAlpha(Animal animal) {
        Animal alpha;
        HerdManager.Herd herd;
        Optional<SpeciesHerdSettings> settingsOpt;
        block9: {
            block8: {
                Level level;
                if (!animal.hasData(HerdAttachments.HERD_DATA)) {
                    return false;
                }
                HerdData data = (HerdData)animal.getData(HerdAttachments.HERD_DATA);
                if (data.alpha()) {
                    return false;
                }
                settingsOpt = HerdSettingsManager.get(animal.getType());
                if (settingsOpt.isEmpty() || !((level = animal.level()) instanceof ServerLevel)) {
                    return false;
                }
                ServerLevel serverLevel = (ServerLevel)level;
                herd = HerdManager.get(data.herdId());
                if (herd == null || herd.alphaId == null) {
                    return false;
                }
                Entity entity = serverLevel.getEntity(herd.alphaId);
                if (!(entity instanceof Animal)) break block8;
                alpha = (Animal)entity;
                if (entity != animal) break block9;
            }
            return false;
        }
        double follow = settingsOpt.get().followDistance();
        if (settingsOpt.get().followStyle() == FollowStyle.SURROUND) {
            // SURROUND: label "following" while the member is away from its personal
            // ring station (same angle + radius-fraction hash FollowAlphaGoal uses), so
            // the label matches the goal instead of a fixed ring.
            double angle = com.charybdis180.ethological.util.Personality.angleRadians(animal.getUUID(), "follow_station");
            double fraction = FollowAlphaGoal.stationFraction(animal);
            Vec3 anchor = alpha.position().add(Math.cos(angle) * follow * fraction, 0.0, Math.sin(angle) * follow * fraction);
            double stop = Math.max(1.0, follow * 0.15);
            return animal.distanceToSqr(anchor) > stop * stop;
        }
        return (double)animal.distanceTo((Entity)alpha) > follow;
    }

    private static boolean isMigrating(Animal animal) {
        if (Homes.effectiveHome(animal).isPresent()) {
            return false;
        }
        boolean heading = Homes.effectiveMigrationHeading(animal).isPresent();
        boolean navActive = !animal.getNavigation().isDone();
        boolean result = heading || navActive;
        return result;
    }
}

