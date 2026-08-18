/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Vec3i
 *  net.minecraft.server.packs.resources.PreparableReloadListener
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.ai.goal.Goal
 *  net.minecraft.world.entity.ai.goal.RandomStrollGoal
 *  net.minecraft.world.entity.ai.goal.WrappedGoal
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.level.LevelReader
 *  net.minecraft.world.level.pathfinder.PathType
 *  net.minecraft.world.phys.Vec3
 *  net.neoforged.bus.api.SubscribeEvent
 *  net.neoforged.neoforge.event.AddReloadListenerEvent
 *  net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
 *  net.neoforged.neoforge.event.tick.EntityTickEvent$Post
 */
package com.charybdis180.ethological.home;

import com.charybdis180.ethological.Ethological;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.home.FenceDetection;
import com.charybdis180.ethological.home.HomeAttachments;
import com.charybdis180.ethological.home.HomeData;
import com.charybdis180.ethological.home.HomeSettingsManager;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.home.SpeciesHomeSettings;
import com.charybdis180.ethological.home.goal.BoundedStrollGoal;
import com.charybdis180.ethological.home.goal.MigrateGoal;
import com.charybdis180.ethological.home.goal.ReturnHomeGoal;
import com.charybdis180.ethological.hunger.GrazePatches;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public final class HomeEvents {
    private HomeEvents() {
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener((PreparableReloadListener)new HomeSettingsManager());
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
        if (HomeSettingsManager.get(animal.getType()).isEmpty()) {
            return;
        }
        animal.goalSelector.addGoal(4, (Goal)new ReturnHomeGoal(animal));
        Goal vanillaStroll = null;
        int strollPriority = 6;
        for (WrappedGoal wrapped : animal.goalSelector.getAvailableGoals()) {
            if (!(wrapped.getGoal() instanceof RandomStrollGoal) || wrapped.getGoal() instanceof BoundedStrollGoal) continue;
            vanillaStroll = wrapped.getGoal();
            strollPriority = wrapped.getPriority();
            break;
        }
        if (vanillaStroll != null) {
            animal.goalSelector.removeGoal(vanillaStroll);
        }
        animal.goalSelector.addGoal(strollPriority, (Goal)new BoundedStrollGoal(animal, 1.0, 120));
        animal.goalSelector.addGoal(4, (Goal)new MigrateGoal(animal));
    }

    /** A water source was placed (bucket, dispenser, flooding) — clear the water caches. */
    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (HomeEvents.isWaterLike(event.getPlacedBlock())) {
            Homes.invalidateWaterCaches();
        }
        GrazePatches.invalidateFoodCaches();
    }

    /** Water spread into a new position — animals should be able to find it quickly. */
    @SubscribeEvent
    public static void onFluidPlaced(BlockEvent.FluidPlaceBlockEvent event) {
        if (HomeEvents.isWaterLike(event.getState())) {
            Homes.invalidateWaterCaches();
        }
        GrazePatches.invalidateFoodCaches();
    }

    /** A waterlogged or fluid-bearing block was removed — refresh what animals can reach. */
    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (HomeEvents.isWaterLike(event.getState())) {
            Homes.invalidateWaterCaches();
        }
        GrazePatches.invalidateFoodCaches();
    }

    private static boolean isWaterLike(BlockState state) {
        return state != null && state.getFluidState().is(FluidTags.WATER);
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        Optional<BlockPos> shade;
        Optional<SpeciesSleepSettings> sleepSettings;
        Entity entity = event.getEntity();
        if (!(entity instanceof Animal)) {
            return;
        }
        Animal animal = (Animal)entity;
        if (animal.level().isClientSide()) {
            return;
        }
        Optional<SpeciesHomeSettings> settingsOpt = HomeSettingsManager.get(animal.getType());
        if (settingsOpt.isEmpty()) {
            return;
        }
        SpeciesHomeSettings settings = settingsOpt.get();
        long now = animal.level().getGameTime();
        // A sleeping animal is stationary — running the fence flood-fill and escape
        // pathfinder probes for it is pure waste (single probes cost up to ~140ms).
        // Skip all home validation until it wakes up.
        if (((Boolean)animal.getData(SleepAttachments.SLEEPING)).booleanValue()) {
            return;
        }
        if (com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "home_leash", now, 40L) && Homes.effectiveHome(animal).isPresent() && (sleepSettings = SleepSettingsManager.get(animal.getType())).isPresent()) {
            float radius = (float)Homes.allowedRadius(animal, settings, animal.level().getDayTime(), sleepSettings.get().sleepStartTick());
            if (Math.abs(((Float)animal.getData(HomeAttachments.LEASH_RADIUS)).floatValue() - radius) >= 0.5f) {
                animal.setData(HomeAttachments.LEASH_RADIUS,Float.valueOf(radius));
            }
        }
        if (!Homes.isHomeOwner(animal)) {
            return;
        }
        Optional<BlockPos> ownHome = Homes.homeOf((Entity)animal);
        if (ownHome.isPresent() && com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "home_shelter", now, 40L)) {
            boolean temporary = animal.hasData(HomeAttachments.HOME) && ((HomeData)animal.getData(HomeAttachments.HOME)).temporary();
            HomeEvents.tryNightlyShelterUpgrade(animal, settings, ownHome.get(), temporary);
            ownHome = Homes.homeOf((Entity)animal);
        }
        if (!com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "home_validate", now, settings.validationIntervalTicks())) {
            return;
        }
        if (FenceDetection.isFencedIn(animal)) {
            if (!settings.nomadic()) {
                if (ownHome.isEmpty() || !ownHome.get().equals(animal.blockPosition())) {
                    HomeEvents.setHome(animal, new HomeData(animal.blockPosition()));
                    Ethological.LOGGER.debug("Ethological: {} fenced in — moved home to {}", animal.getType(), animal.blockPosition());
                }
                return;
            }
            // Nomadic herds keep the camp-break schedule even when the fence flood-fill
            // flags the current basin as enclosed (a natural ditch is not a real pen), so
            // a stale temporary camp can still be broken after sleep ends instead of
            // freezing the alpha in place forever.
            if (ownHome.isPresent() && !((HomeData) animal.getData(HomeAttachments.HOME)).temporary()) {
                BlockPos stale = ownHome.get();
                HomeEvents.clearHome(animal);
                ownHome = Optional.empty();
                Ethological.LOGGER.debug("Ethological: {} fenced in — cleared stale nomad home at {}", animal.getType(), stale);
            }
        }
        if (settings.nomadic()) {
            HomeEvents.handleNomadic(animal, settings, ownHome, now);
            return;
        }
        if (ownHome.isEmpty()) {
            Optional<BlockPos> homeOpt = Homes.findSafeHomeStand(animal.level(), animal.blockPosition(), 6, settings.waterSearchRadius());
            if (homeOpt.isPresent()) {
                HomeEvents.setHome(animal, new HomeData(homeOpt.get()));
                Ethological.LOGGER.debug("Ethological: {} established a home at {}",animal.getType(),homeOpt.get());
            } else if (SleepSettingsManager.get(animal.getType()).map(s -> s.isSleepTime(animal.level().getDayTime())).orElse(false).booleanValue()) {
                BlockPos bed = Homes.findBetterShelteredHome(animal.level(), animal.blockPosition(), 12, 0, false)
                        .orElseGet(() -> Homes.findCliffSafeStand(animal.level(), animal.blockPosition(), 12).orElse(animal.blockPosition()));
                HomeEvents.setHome(animal, new HomeData(bed, true));
                Ethological.LOGGER.debug("Ethological: {} set a temporary home at {}",animal.getType(),bed);
            }
            return;
        }
        if (((HomeData)animal.getData(HomeAttachments.HOME)).temporary()) {
            boolean sleepTime = SleepSettingsManager.get(animal.getType()).map(s -> s.isSleepTime(animal.level().getDayTime())).orElse(false);
            if (sleepTime) {
                return;
            }
            Optional<BlockPos> permanent = Homes.findSafeHomeStand(animal.level(), ownHome.get(), 6, settings.waterSearchRadius());
            if (permanent.isPresent()) {
                HomeEvents.setHome(animal, new HomeData(permanent.get(), false));
                Ethological.LOGGER.debug("Ethological: {} made its temporary home at {} permanent",animal.getType(),permanent.get());
            } else {
                HomeEvents.clearHome(animal);
                Ethological.LOGGER.debug("Ethological: {} left its temporary home at {} to keep searching for water",animal.getType(),ownHome.get());
            }
            return;
        }
        if (!Homes.hasAccessibleWater((LevelReader)animal.level(), ownHome.get(), settings.waterSearchRadius())) {
            HomeEvents.clearHome(animal);
            Ethological.LOGGER.debug("Ethological: {} abandoned its home at {} (water no longer accessible)",animal.getType(),ownHome.get());
            return;
        }
        if (!Homes.isCliffSafe(animal.level(), ownHome.get())) {
            Optional<BlockPos> safer = Homes.findSafeHomeStand(animal.level(), ownHome.get(), 6, settings.waterSearchRadius());
            if (safer.isPresent()) {
                HomeEvents.setHome(animal, new HomeData(safer.get()));
                Ethological.LOGGER.debug("Ethological: {} moved its home off a cliff edge to {}",animal.getType(),safer.get());
                return;
            }
        }
        if (Homes.isHotFor(animal) && Homes.shelterScore(animal.level(), ownHome.get()) == 0 && (shade = Homes.findBetterShelteredHome(animal.level(), ownHome.get(), 16, settings.waterSearchRadius(), true)).isPresent()) {
            HomeEvents.setHome(animal, new HomeData(shade.get()));
            Ethological.LOGGER.debug("Ethological: {} moved its home into the shade at {}",animal.getType(),shade.get());
            return;
        }
        double rehomeDistance = (double)settings.wanderRadius() * 1.5;
        if (animal.distanceToSqr(Vec3.atCenterOf((Vec3i)((Vec3i)ownHome.get()))) > rehomeDistance * rehomeDistance) {
            BlockPos current = animal.blockPosition();
            BlockPos homePos = ownHome.get();
            int chebyshev = Math.max(Math.abs(current.getX() - homePos.getX()), Math.abs(current.getZ() - homePos.getZ()));
            boolean canWalkBack = chebyshev <= 32 && FenceDetection.canRejoin(animal.level(), current, homePos, 32);
            if (!canWalkBack) {
                Optional<BlockPos> reloc = Homes.findSafeHomeStand(animal.level(), current, 6, settings.waterSearchRadius());
                if (reloc.isPresent()) {
                    HomeEvents.setHome(animal, new HomeData(reloc.get()));
                    Ethological.LOGGER.debug("Ethological: {} relocated its home to {}",animal.getType(),reloc.get());
                }
            }
        }
    }

    private static boolean tryNightlyShelterUpgrade(Animal animal, SpeciesHomeSettings settings, BlockPos currentHome, boolean temporaryCamp) {
        int searchRadius;
        long dayTime;
        Optional<SpeciesSleepSettings> sleepOpt = SleepSettingsManager.get(animal.getType());
        if (sleepOpt.isEmpty()) {
            return false;
        }
        SpeciesSleepSettings sleep = sleepOpt.get();
        if (!sleep.isSleepTime(dayTime = animal.level().getDayTime())) {
            return false;
        }
        if (Homes.ticksIntoSleepWindow(dayTime, sleep) > 400L) {
            return false;
        }
        int n = searchRadius = settings.nomadic() ? 24 : Math.min(16, Math.max(8, settings.wanderRadius() / 4));
        if (!settings.nomadic()) {
            searchRadius = Math.min(12, searchRadius);
        }
        // rainShelterSearchBonus is applied inside Homes.findBetterShelteredHome when raining
        boolean requireWater = !temporaryCamp;
        Optional<BlockPos> better = Homes.findBetterShelteredHome(animal.level(), currentHome, searchRadius, settings.waterSearchRadius(), requireWater);
        if (better.isEmpty()) {
            return false;
        }
        HomeEvents.setHome(animal, new HomeData(better.get(), temporaryCamp));
        Ethological.LOGGER.debug("Ethological: {} moved {} to better shelter at {}", new Object[]{animal.getType(), temporaryCamp ? "camp" : "home", better.get()});
        return true;
    }

    private static void handleNomadic(Animal animal, SpeciesHomeSettings settings, Optional<BlockPos> home, long now) {
        long dayTime = animal.level().getDayTime();
        long t = Homes.dayTick(dayTime);
        Optional<SpeciesSleepSettings> sleepSettings = SleepSettingsManager.get(animal.getType());
        boolean sleepTime = sleepSettings.map(s -> s.isSleepTime(dayTime)).orElse(false);
        if (home.isEmpty()) {
            if (sleepTime) {
                BlockPos camp = Homes.findBetterShelteredHome(animal.level(), animal.blockPosition(), 16, 0, false)
                        .orElseGet(() -> Homes.findCliffSafeStand(animal.level(), animal.blockPosition(), 16).orElse(animal.blockPosition()));
                HomeEvents.setHome(animal, new HomeData(camp, true));
                Ethological.LOGGER.debug("Ethological: {} camped for the night at {} (no water found)",animal.getType(),camp);
                return;
            }
            boolean campTime = sleepSettings.map(s -> t >= (long)settings.campTimeTick() && t < (long)s.sleepStartTick()).orElse(false);
            if (campTime && Homes.hasAccessibleWater((LevelReader)animal.level(), animal.blockPosition(), settings.waterSearchRadius())) {
                BlockPos camp = Homes.findBetterShelteredHome(animal.level(), animal.blockPosition(), 24, settings.waterSearchRadius(), true)
                        .orElseGet(() -> Homes.findCliffSafeStand(animal.level(), animal.blockPosition(), 24).orElse(animal.blockPosition()));
                HomeEvents.setHome(animal, new HomeData(camp, true));
                Ethological.LOGGER.debug("Ethological: {} set up camp by water at {}",animal.getType(),camp);
            }
            return;
        }
        if (((HomeData)animal.getData(HomeAttachments.HOME)).temporary()) {
            // A camp set during campTime must hold through the night so the shrinking
            // pre-sleep circle can gather the herd; break camp only after sleep ends.
            // "Night over" is everything OUTSIDE [campTime, sleepEnd): with the old
            // `t >= sleepEnd` test a temp camp survived the whole day whenever the day
            // counter wrapped past 23000 while the mob was unloaded (or the player slept
            // through the [23000, 24000) window), leaving hasHome=true and blocking
            // MigrateGoal. The wrap-aware range check also breaks camp the morning after.
            boolean nightOver = sleepSettings.map(s -> !Homes.inTickRange(t, settings.campTimeTick(), s.sleepEndTick())).orElse(true);
            if (nightOver) {
                HomeEvents.clearHome(animal);
                Ethological.LOGGER.debug("Ethological: {} broke camp at {}, resuming migration",animal.getType(),home.get());
            }
        }
    }

    private static void setHome(Animal animal, HomeData home) {
        animal.setData(HomeAttachments.HOME,home);
        HerdManager.propagateHomeFromAlpha(animal, Optional.of(home));
    }

    private static void clearHome(Animal animal) {
        animal.removeData(HomeAttachments.HOME);
        HerdManager.propagateHomeFromAlpha(animal, Optional.empty());
    }
}

