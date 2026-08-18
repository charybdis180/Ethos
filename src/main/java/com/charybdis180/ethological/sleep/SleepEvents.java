/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Vec3i
 *  net.minecraft.core.particles.ParticleOptions
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.server.packs.resources.PreparableReloadListener
 *  net.minecraft.util.Mth
 *  net.minecraft.world.effect.MobEffectInstance
 *  net.minecraft.world.effect.MobEffects
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.MoverType
 *  net.minecraft.world.entity.ai.attributes.Attributes
 *  net.minecraft.world.entity.ai.goal.Goal
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.phys.Vec3
 *  net.neoforged.bus.api.SubscribeEvent
 *  net.neoforged.neoforge.event.AddReloadListenerEvent
 *  net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
 *  net.neoforged.neoforge.event.entity.living.LivingDamageEvent$Pre
 *  net.neoforged.neoforge.event.tick.EntityTickEvent$Post
 */
package com.charybdis180.ethological.sleep;

import com.charybdis180.ethological.ModParticles;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.goal.FollowPathing;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.sleep.SleepDisturbance;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import com.charybdis180.ethological.sleep.goal.AsleepGoal;
import com.charybdis180.ethological.sleep.goal.FleeThreatGoal;
import com.charybdis180.ethological.sleep.goal.SettleForSleepGoal;
import com.charybdis180.ethological.social.Familiarity;
import com.charybdis180.ethological.thirst.Thirst;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public final class SleepEvents {
    public static final float SAFE_DISTANCE = 36.0f;
    private static final long PROXIMITY_WAKE_VIGILANCE_TICKS = 80L;
    /** After a proximity wake, stay awake while a player remains this close. */
    private static final double STAY_AWAKE_DISTANCE = 6.0;
    private static final double STAY_AWAKE_DISTANCE_SQR = STAY_AWAKE_DISTANCE * STAY_AWAKE_DISTANCE;
    /** Block break/place is louder — wake sleepers a bit farther out. */
    private static final double BLOCK_WAKE_DISTANCE = 8.0;
    private static final double HOSTILE_WAKE_DISTANCE = 8.0;
    private static final double HOSTILE_WAKE_DISTANCE_SQR = HOSTILE_WAKE_DISTANCE * HOSTILE_WAKE_DISTANCE;
    private static final long THUNDER_WAKE_VIGILANCE_TICKS = 60L;
    private static final long HOSTILE_WAKE_VIGILANCE_TICKS = 80L;
    private static final int PROXIMITY_WAKE_INTERVAL_MIN = 20;
    private static final int PROXIMITY_WAKE_INTERVAL_SPAN = 21;

    private SleepEvents() {
    }

    public static long bedtimeDelay(Animal animal, SpeciesSleepSettings settings) {
        int max = settings.maxBedtimeDelayTicks();
        return max <= 0 ? 0L : (long)com.charybdis180.ethological.util.Personality.memoizedSalt(animal.getUUID(), "bedtime", max + 1);
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener((PreparableReloadListener)new SleepSettingsManager());
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof Animal)) {
            return;
        }
        Animal animal = (Animal)entity;
        if (SleepSettingsManager.get(animal.getType()).isEmpty()) {
            return;
        }
        if (((Boolean)animal.getData(SleepAttachments.SLEEPING)).booleanValue()) {
            animal.setSilent(true);
        }
        animal.goalSelector.addGoal(1, (Goal)new AsleepGoal(animal));
        animal.goalSelector.addGoal(2, (Goal)new FleeThreatGoal(animal));
        animal.goalSelector.addGoal(3, (Goal)new SettleForSleepGoal(animal));
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        boolean herdCalm;
        SleepDisturbance disturbance;
        Entity threat;
        Entity entity = event.getEntity();
        if (!(entity instanceof Animal)) {
            return;
        }
        Animal animal = (Animal)entity;
        Level level = animal.level();
        if (!(level instanceof ServerLevel)) {
            return;
        }
        ServerLevel serverLevel = (ServerLevel)level;
        long now = animal.level().getGameTime();
        // Expire disturbance unconditionally (even for species without sleep settings).
        if (animal.hasData(SleepAttachments.SLEEP_DISTURBANCE) && ((threat = serverLevel.getEntity((disturbance = (SleepDisturbance)animal.getData(SleepAttachments.SLEEP_DISTURBANCE)).threatId())) == null || !threat.isAlive() || now - disturbance.disturbedGameTime() > 1200L)) {
            animal.removeData(SleepAttachments.SLEEP_DISTURBANCE);
            if (SleepSettingsManager.get(animal.getType()).isPresent()) {
                animal.setData(SleepAttachments.SLEEP_VIGILANCE,(now + 120L));
            }
        }
        Optional<SpeciesSleepSettings> settingsOpt = SleepSettingsManager.get(animal.getType());
        if (settingsOpt.isEmpty()) {
            // Unsupported species: don't create or mutate any other sleep attachments.
            if (animal.hasData(SleepAttachments.SLEEPING) && ((Boolean)animal.getData(SleepAttachments.SLEEPING)).booleanValue()) {
                SleepEvents.wake(animal);
            }
            return;
        }
        boolean sleeping = (Boolean)animal.getData(SleepAttachments.SLEEPING);
        // setSilent writes NBT on every call; skip when the flag already matches so a
        // waking herd stops paying the sync cost each tick.
        if (animal.isSilent() != sleeping) {
            animal.setSilent(sleeping);
        }
        SpeciesSleepSettings settings = settingsOpt.get();
        boolean disturbed = animal.hasData(SleepAttachments.SLEEP_DISTURBANCE);
        boolean vigilant = now < (Long)animal.getData(SleepAttachments.SLEEP_VIGILANCE);
        // Proximity-woken animals stay alert while a player loiters nearby. The player scan
        // and the sleep transition are the awake-animal body — gated to ~every 10 ticks so a
        // large herd standing around does not re-scan players and re-evaluate bedtime each tick.
        if (!sleeping && vigilant
                && com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "sleep_awake", now, 10L)
                && SleepEvents.refreshStayAwakeNearPlayer(animal, serverLevel, now)) {
            vigilant = true;
        }
        boolean hungry = Hunger.isUrgentlyHungry((Entity)animal);
        boolean thirsty = Thirst.isUrgentlyThirsty((Entity)animal);
        boolean inWater = animal.isInWaterOrBubble();
        long dayTime = animal.level().getDayTime();
        boolean pastBedtime = settings.isSleepTime(dayTime) && Homes.ticksIntoSleepWindow(dayTime, settings) >= SleepEvents.bedtimeDelay(animal, settings);
        boolean bl = herdCalm = HerdManager.panicPhaseOf(animal, now) == HerdManager.PanicPhase.NONE;
        if (sleeping) {
            // A herd member whose alpha has wandered out of its sleep-proximity radius must
            // wake and rejoin, otherwise a member that fell asleep in range stays asleep far
            // from the herd (the entry gate blocks NEW sleeps out of range but never wakes
            // one already asleep). distanceToAlpha is -1 for un-herded animals, which never
            // triggers this.
            double _sleepAlphaDist = FollowPathing.distanceToAlpha(animal);
            boolean alphaOutOfRange = _sleepAlphaDist >= 0.0 && _sleepAlphaDist > FollowPathing.sleepRadius(animal);
            boolean emergencyWake = disturbed || vigilant || hungry || thirsty || inWater || !herdCalm || alphaOutOfRange;
            if (emergencyWake) {
                SleepEvents.wake(animal);
            } else if (!pastBedtime) {
                // Sleep window has ended — this is the natural morning wake. Stagger it per-UUID
                // by a 0-200 tick salt so a waking herd does not all clear SLEEPING on the same
                // tick (which synchronized the whole morning burst: water/food scans, herd
                // reconcile flood fills, first follow repaths). Animals wake gradually over ~10s
                // while still waking instantly for any emergency condition above.
                long wakeSalt = com.charybdis180.ethological.util.Personality.memoizedSalt(animal.getUUID(), "wake_stagger", 201);
                if (Homes.inTickRange(Homes.dayTick(dayTime), (long)settings.sleepEndTick(), (long)settings.sleepEndTick() + wakeSalt)) {
                    SleepEvents.returnToSleepPosition(animal, settings);
                } else {
                    SleepEvents.wake(animal);
                }
            } else {
                SleepEvents.returnToSleepPosition(animal, settings);
            }
        } else if (com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "sleep_awake", now, 10L)
                && pastBedtime && !disturbed && !vigilant && !hungry && !thirsty && !inWater && herdCalm) {
            double _alphaDist = FollowPathing.distanceToAlpha(animal);
            double _sleepR = FollowPathing.sleepRadius(animal);
            if (_alphaDist <= _sleepR) {
                animal.setData(SleepAttachments.SLEEP_YAW,Float.valueOf(animal.yBodyRot));
                animal.setData(SleepAttachments.SLEEP_POS,animal.blockPosition());
                animal.getNavigation().stop();
                animal.setSilent(true);
                animal.setData(SleepAttachments.SLEEPING,true);
            } else {
            }
        }
        sleeping = (Boolean)animal.getData(SleepAttachments.SLEEPING);
        if (sleeping) {
            long proximityInterval = (long)PROXIMITY_WAKE_INTERVAL_MIN
                    + com.charybdis180.ethological.util.Personality.memoizedSalt(animal.getUUID(), "prox_wake_iv", PROXIMITY_WAKE_INTERVAL_SPAN);
            if (com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "prox_wake", now, proximityInterval)) {
                SleepEvents.checkProximityWake(animal, serverLevel, now);
                sleeping = (Boolean)animal.getData(SleepAttachments.SLEEPING);
            }
        }
        if (sleeping
                && serverLevel.isThundering()
                && com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "thunder_wake", now, 40L)) {
            SleepEvents.wake(animal);
            animal.setData(SleepAttachments.SLEEP_VIGILANCE, now + THUNDER_WAKE_VIGILANCE_TICKS);
            sleeping = false;
        }
        if (sleeping
                && com.charybdis180.ethological.util.Personality.tickGate(
                        animal.getUUID(),
                        "hostile_wake",
                        now,
                        40L + com.charybdis180.ethological.util.Personality.memoizedSalt(animal.getUUID(), "hostile_wake_iv", 21))) {
            if (SleepEvents.checkHostileProximityWake(animal, serverLevel, now)) {
                sleeping = false;
            }
        }
        if (sleeping) {
            // Refresh the 100-tick regen effect periodically instead of re-applying (and re-syncing) every tick.
            if (!animal.hasEffect(MobEffects.REGENERATION)
                    || com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "sleep_regen", now, 80L)) {
                animal.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, settings.regenAmplifier(), true, false, true));
            }
            SleepEvents.applySleepGravity(animal);
            SleepEvents.emitSleepZ(animal, serverLevel, now);
        }
    }

    private static void checkProximityWake(Animal animal, ServerLevel level, long now) {
        for (Player player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            if (player.distanceToSqr((Entity)animal) > Familiarity.activityWakeDistanceSqr(animal, player)) {
                continue;
            }
            // Quiet walking is fine — only noisy player movement wakes sleepers.
            if (!SleepEvents.isNoisyPlayerMovement(player)) {
                continue;
            }
            SleepEvents.wakeNearPlayer(animal, player, now);
            return;
        }
    }

    /** Throttled wake from nearby hostiles (zombies, etc.) — vigilance only, no flee. */
    private static boolean checkHostileProximityWake(Animal animal, ServerLevel level, long now) {
        AABB box = animal.getBoundingBox().inflate(HOSTILE_WAKE_DISTANCE);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, SleepEvents::isHostileWakeThreat)) {
            if (animal.distanceToSqr(mob) > HOSTILE_WAKE_DISTANCE_SQR) {
                continue;
            }
            SleepEvents.wake(animal);
            animal.setData(SleepAttachments.SLEEP_VIGILANCE, now + HOSTILE_WAKE_VIGILANCE_TICKS);
            return true;
        }
        return false;
    }

    private static boolean isHostileWakeThreat(Mob mob) {
        return mob.isAlive() && mob.getType().getCategory() == MobCategory.MONSTER && !(mob instanceof Animal);
    }

    private static boolean isNoisyPlayerMovement(Player player) {
        if (player.isSprinting()) {
            return true;
        }
        // Ascending jump (positive Y while airborne). Quiet walking stays on ground.
        return !player.onGround() && player.getDeltaMovement().y > 0.05;
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || player.isSpectator()) {
            return;
        }
        SleepEvents.wakeSleepersNearBlock(level, event.getPos(), player);
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Entity placer = event.getEntity();
        if (!(placer instanceof Player) || ((Player)placer).isSpectator()) {
            return;
        }
        SleepEvents.wakeSleepersNearBlock(level, event.getPos(), (Player)placer);
    }

    private static void wakeSleepersNearBlock(ServerLevel level, BlockPos pos, Player player) {
        long now = level.getGameTime();
        AABB box = new AABB(pos).inflate(BLOCK_WAKE_DISTANCE);
        for (Animal animal : level.getEntitiesOfClass(Animal.class, box, a -> ((Boolean)a.getData(SleepAttachments.SLEEPING)).booleanValue() && SleepSettingsManager.get(a.getType()).isPresent())) {
            SleepEvents.wakeNearPlayer(animal, player, now);
        }
    }

    private static void wakeNearPlayer(Animal animal, Player player, long now) {
        SleepEvents.wake(animal);
        boolean onlyNearby = SleepEvents.isOnlyNearbyHuman(animal, player, levelPlayersBox(animal));
        long vigilance = Familiarity.proximityVigilanceTicks(animal, player, onlyNearby);
        // Vigilance keeps them briefly alert; do not set SLEEP_DISTURBANCE — that is
        // reserved for attacks and is what drives FleeThreatGoal. While a player stays
        // within STAY_AWAKE_DISTANCE, tick refresh extends this vigilance.
        animal.setData(SleepAttachments.SLEEP_VIGILANCE, (now + vigilance));
    }

    private static double levelPlayersBox(Animal animal) {
        return STAY_AWAKE_DISTANCE + 2.0;
    }

    private static boolean isOnlyNearbyHuman(Animal animal, Player trusted, double radius) {
        double radiusSqr = radius * radius;
        for (Player other : animal.level().players()) {
            if (other == trusted || other.isSpectator() || !other.isAlive()) {
                continue;
            }
            if (other.distanceToSqr((Entity)animal) <= radiusSqr) {
                return false;
            }
        }
        return true;
    }

    /**
     * If a living player is within {@link #STAY_AWAKE_DISTANCE}, extend vigilance so the
     * animal cannot settle back to sleep until the player leaves.
     * @return true if vigilance was refreshed
     */
    private static boolean refreshStayAwakeNearPlayer(Animal animal, ServerLevel level, long now) {
        for (Player player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            if (player.distanceToSqr((Entity)animal) > STAY_AWAKE_DISTANCE_SQR) {
                continue;
            }
            animal.setData(SleepAttachments.SLEEP_VIGILANCE, now + PROXIMITY_WAKE_VIGILANCE_TICKS);
            return true;
        }
        return false;
    }

    private static void emitSleepZ(Animal animal, ServerLevel level, long now) {
        if (!com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "sleep_z", now, 160L)) {
            return;
        }
        float yawRad = ((Float)animal.getData(SleepAttachments.SLEEP_YAW)).floatValue() * ((float)Math.PI / 180);
        double faceDist = (double)animal.getBbWidth() * 0.55 + 0.5;
        double faceY = 0.45;
        double ox = (double)(-Mth.sin((float)yawRad)) * faceDist;
        double oz = (double)Mth.cos((float)yawRad) * faceDist;
        level.sendParticles((ParticleOptions)ModParticles.Z.get(), animal.getX() + ox, animal.getY() + faceY, animal.getZ() + oz, 1, 0.0, 0.02, 0.0, 0.0);
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingDamageEvent.Pre event) {
        LivingEntity livingEntity = event.getEntity();
        if (!(livingEntity instanceof Animal)) {
            return;
        }
        Animal animal = (Animal)livingEntity;
        if (animal.level().isClientSide()) {
            return;
        }
        if (SleepSettingsManager.get(animal.getType()).isEmpty()) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        if (attacker != null && attacker != animal) {
            animal.setData(SleepAttachments.SLEEP_DISTURBANCE,new SleepDisturbance(attacker.getUUID(), animal.level().getGameTime()));
        }
        if (((Boolean)animal.getData(SleepAttachments.SLEEPING)).booleanValue()) {
            SleepEvents.wake(animal);
        }
    }

    private static void applySleepGravity(Animal animal) {
        if (animal.onGround() || animal.isInWater()) {
            return;
        }
        double gravity = animal.getAttributeValue(Attributes.GRAVITY);
        Vec3 motion = animal.getDeltaMovement();
        double newY = Math.max((motion.y - gravity) * 0.98, -3.92);
        animal.setDeltaMovement(motion.x, newY, motion.z);
        animal.move(MoverType.SELF, animal.getDeltaMovement());
    }

    private static void returnToSleepPosition(Animal animal, SpeciesSleepSettings settings) {
        double maxDist;
        BlockPos sleepPos = (BlockPos)animal.getData(SleepAttachments.SLEEP_POS);
        if (sleepPos.equals(BlockPos.ZERO)) {
            return;
        }
        double distSqr = animal.distanceToSqr(Vec3.atCenterOf((Vec3i)sleepPos));
        if (distSqr <= (maxDist = (double)settings.sleepReturnRadius()) * maxDist) {
            return;
        }
        // Pushed too far from the sleeping spot: wake up and walk instead of gliding in the sleep pose.
        SleepEvents.wake(animal);
        animal.setData(SleepAttachments.SLEEP_VIGILANCE,(animal.level().getGameTime() + 60L));
    }

    public static void wake(Animal animal) {
        animal.getNavigation().stop();
        animal.setSilent(false);
        animal.setData(SleepAttachments.SLEEPING,false);
        animal.removeData(SleepAttachments.SLEEP_YAW);
        animal.removeData(SleepAttachments.SLEEP_POS);
        animal.removeData(SleepAttachments.SLEEP_TARGET);
    }
}

