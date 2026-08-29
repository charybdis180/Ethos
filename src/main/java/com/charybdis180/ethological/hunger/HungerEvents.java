package com.charybdis180.ethological.hunger;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.config.EthologicalConfig;
import com.charybdis180.ethological.debug.ActiveBehavior;
import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.MotherData;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.hunger.PastureRecovery;
import com.charybdis180.ethological.hunger.HungerData;
import com.charybdis180.ethological.hunger.HungerSettingsManager;
import com.charybdis180.ethological.hunger.SpeciesHungerSettings;
import com.charybdis180.ethological.hunger.goal.EatFoodGoal;
import com.charybdis180.ethological.hunger.goal.RootForageGoal;
import com.charybdis180.ethological.hunger.goal.RuminateGoal;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import com.charybdis180.ethological.social.Familiarity;
import com.charybdis180.ethological.thirst.Thirst;
import com.charybdis180.ethological.trample.TrampleGoal;
import com.charybdis180.ethological.util.BetterDaysCompat;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.EatBlockGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

public final class HungerEvents {
    private HungerEvents() {
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new HungerSettingsManager());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            PastureRecovery.tick(serverLevel);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PastureRecovery.clear();
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
        Optional<SpeciesHungerSettings> settingsOpt = HungerSettingsManager.get(animal.getType());
        if (settingsOpt.isEmpty()) {
            return;
        }
        SpeciesHungerSettings settings = settingsOpt.get();
        long now = animal.level().getGameTime();
        if (!animal.hasData(ModAttachments.HUNGER_DATA)) {
            Hunger.setData((Entity)animal, new HungerData(settings.maxHunger(), settings.maxHunger(), now + 1L + (long)animal.getRandom().nextInt(settings.depletionIntervalTicks()), now + (long)settings.starveDamageIntervalTicks()));
        } else if (Hunger.getMaxHunger((Entity)animal) != settings.maxHunger()) {
            HungerData data = Hunger.data((Entity)animal);
            Hunger.setData((Entity)animal, data.withMaxHunger(settings.maxHunger()).withHunger(Math.min(data.hunger(), settings.maxHunger())));
        }
        animal.goalSelector.addGoal(5, (Goal)new RuminateGoal(animal));
        EatFoodGoal eatFood = new EatFoodGoal(animal, 1.0, 16, 3);
        if (animal instanceof Pig pig) {
            // Pigs get a root-foraging fallback paired with their eat goal: the pair shares
            // failure counts (eat scans fail -> pig digs) and success resets (dig found food
            // -> both goals' cooldowns/failure streaks clear). Priority 3 like drinking so a
            // need trek can't be preempted mid-walk by rest/follow/curiosity at 4-5.
            RootForageGoal rootForage = new RootForageGoal(pig, eatFood);
            eatFood.setRootSibling(rootForage);
            animal.goalSelector.addGoal(3, (Goal)rootForage);
        }
        animal.goalSelector.addGoal(5, (Goal)eatFood);
        if (animal instanceof Sheep || animal instanceof Cow) {
            animal.goalSelector.addGoal(6, (Goal)new TrampleGoal(animal));
        }
        Goal vanillaGraze = null;
        for (WrappedGoal wrapped : animal.goalSelector.getAvailableGoals()) {
            if (!(wrapped.getGoal() instanceof EatBlockGoal)) continue;
            vanillaGraze = wrapped.getGoal();
            break;
        }
        if (vanillaGraze != null) {
            animal.goalSelector.removeGoal(vanillaGraze);
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Entity entity = event.getTarget();
        if (!(entity instanceof Animal)) {
            return;
        }
        Animal animal = (Animal)entity;
        Optional<SpeciesHungerSettings> settingsOpt = HungerSettingsManager.get(animal.getType());
        if (settingsOpt.isEmpty() || !animal.hasData(ModAttachments.HUNGER_DATA)) {
            return;
        }
        SpeciesHungerSettings settings = settingsOpt.get();
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !animal.isFood(stack)) {
            return;
        }
        int amount = Math.max(1, Math.round((float)settings.hungerPerFood() * settings.handFeedMultiplier()));
        Hunger.feed((Entity)animal, amount);
        Familiarity.handFed(animal, event.getEntity());
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        HungerData data;
        Entity entity = event.getEntity();
        if (!(entity instanceof Animal)) {
            return;
        }
        Animal animal = (Animal)entity;
        if (animal.level().isClientSide()) {
            return;
        }
        Optional<SpeciesHungerSettings> settingsOpt = HungerSettingsManager.get(animal.getType());
        if (settingsOpt.isEmpty()) {
            return;
        }
        if (!animal.hasData(ModAttachments.HUNGER_DATA)) {
            return;
        }
        SpeciesHungerSettings settings = settingsOpt.get();
        long now = animal.level().getGameTime();
        if (now >= (data = Hunger.data((Entity)animal)).nextDepletionGameTime()) {
            int interval = HungerEvents.effectiveDepletionInterval(animal, settings);
            if (!settings.depleteWhileSleeping() && animal.getData(ModAttachments.SLEEPING)) {
                Hunger.setData((Entity)animal, data.withNextDepletion(now + (long)interval));
            } else {
                data = data.withHunger(Math.max(0, data.hunger() - 1)).withNextDepletion(now + (long)interval);
                Hunger.setData((Entity)animal, data);
            }
        }
        if (data.hunger() <= 0) {
            // Refresh the 100-tick effect periodically instead of re-applying (and re-syncing) every tick.
            if (!animal.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)
                    || com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "hunger_slow", now, 80L)) {
                animal.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 0, true, false, true));
            }
            if (now >= data.nextStarveGameTime()) {
                animal.hurt(animal.damageSources().starve(), settings.starveDamage());
                Hunger.setData((Entity)animal, Hunger.data((Entity)animal).withNextStarve(now + (long)settings.starveDamageIntervalTicks()));
            }
        }
        // A rooted crop lingers in the mouth through the chew window, then is swallowed.
        if (animal.hasData(ModAttachments.MOUTH_ITEM) && !Hunger.isEating((Entity)animal)) {
            animal.removeData(ModAttachments.MOUTH_ITEM);
        }
        ActiveBehavior.update(animal);
    }

    private static int effectiveDepletionInterval(Animal animal, SpeciesHungerSettings settings) {
        Sheep sheep;
        // Global drain-speed option first, then per-animal personality and state multipliers.
        float global = EthologicalConfig.CONFIG.comfort.hungerDrainMultiplier.get().floatValue();
        float interval = (float)settings.depletionIntervalTicks() * HungerEvents.individualPace(animal) * global;
        if (EthologicalConfig.CONFIG.compat.betterDaysSyncDrain.get()) {
            interval *= BetterDaysCompat.drainScale(animal.level());
        }
        if (animal instanceof Sheep && (sheep = (Sheep)animal).isSheared()) {
            interval *= Math.max(0.05f, settings.shearedDepletionMultiplier());
        }
        return Math.max(1, Math.round(interval));
    }

    private static float individualPace(Animal animal) {
        return com.charybdis180.ethological.util.Personality.clampPace(animal.getUUID(), "hunger_pace");
    }

    @SubscribeEvent
    public static void onBabySpawn(BabyEntitySpawnEvent event) {
        Animal parentA;
        Mob mob;
        block10: {
            block9: {
                if (Hunger.isStarving((Entity)event.getParentA()) || Hunger.isStarving((Entity)event.getParentB()) || Thirst.isDehydrated((Entity)event.getParentA()) || Thirst.isDehydrated((Entity)event.getParentB())) {
                    event.setCanceled(true);
                    HungerEvents.resetLove(event);
                    return;
                }
                mob = event.getParentA();
                if (!(mob instanceof Animal)) break block9;
                parentA = (Animal)mob;
                mob = event.getParentB();
                if (mob instanceof Animal) break block10;
            }
            return;
        }
        Animal parentB = (Animal)mob;
        if (!Hunger.supports((Entity)parentA) && !Hunger.supports((Entity)parentB)) {
            return;
        }
        long dayTime = parentA.level().getDayTime();
        Optional<SpeciesSleepSettings> sleepA = SleepSettingsManager.get(parentA.getType());
        Optional<SpeciesSleepSettings> sleepB = SleepSettingsManager.get(parentB.getType());
        if (sleepA.map(s -> s.isSleepTime(dayTime)).orElse(false).booleanValue() || sleepB.map(s -> s.isSleepTime(dayTime)).orElse(false).booleanValue()) {
            event.setCanceled(true);
            HungerEvents.resetLove(event);
            return;
        }
        long now = parentA.level().getGameTime();
        if (HerdManager.panicPhaseOf(parentA, now) != HerdManager.PanicPhase.NONE || HerdManager.panicPhaseOf(parentB, now) != HerdManager.PanicPhase.NONE) {
            event.setCanceled(true);
            HungerEvents.resetLove(event);
            return;
        }
        AgeableMob child = event.getChild();
        if (!(child instanceof Animal)) {
            return;
        }
        Animal baby = (Animal)child;
        Animal mother = parentA;
        long followTicks = ((Integer)EthologicalConfig.CONFIG.comfort.motherFollowTicks.get()).intValue();
        baby.setData(ModAttachments.MOTHER, MotherData.create(mother.getUUID(), now, followTicks));
        if (mother.hasData(ModAttachments.HERD_DATA) && mother.level() instanceof ServerLevel) {
            HerdManager.Herd herd = HerdManager.getOrCreate(((HerdData)mother.getData(ModAttachments.HERD_DATA)).herdId(), mother.getUUID());
            herd.members.add(baby.getUUID());
            baby.setData(ModAttachments.HERD_DATA,new HerdData(herd.id, false));
        }
    }

    private static void resetLove(BabyEntitySpawnEvent event) {
        Mob a = event.getParentA();
        Mob b = event.getParentB();
        if (a instanceof Animal animalA) {
            animalA.resetLove();
        }
        if (b instanceof Animal animalB) {
            animalB.resetLove();
        }
    }
}

