/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.server.packs.resources.PreparableReloadListener
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.ai.goal.FollowParentGoal
 *  net.minecraft.world.entity.ai.goal.Goal
 *  net.minecraft.world.entity.ai.goal.WrappedGoal
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.entity.animal.Wolf
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.phys.AABB
 *  net.neoforged.bus.api.SubscribeEvent
 *  net.neoforged.neoforge.event.AddReloadListenerEvent
 *  net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
 *  net.neoforged.neoforge.event.entity.living.LivingDamageEvent$Pre
 *  net.neoforged.neoforge.event.server.ServerStoppingEvent
 *  net.neoforged.neoforge.event.tick.EntityTickEvent$Post
 */
package com.charybdis180.ethological.herd;

import com.charybdis180.ethological.Ethological;
import com.charybdis180.ethological.herd.HerdAttachments;
import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.HerdSettingsManager;
import com.charybdis180.ethological.herd.MotherData;
import com.charybdis180.ethological.herd.SpeciesHerdSettings;
import com.charybdis180.ethological.herd.goal.FollowAlphaGoal;
import com.charybdis180.ethological.herd.goal.FollowParentGoal;
import com.charybdis180.ethological.herd.goal.GatherGoal;
import com.charybdis180.ethological.herd.goal.HerdWaryGoal;
import com.charybdis180.ethological.herd.goal.NurseGoal;
import com.charybdis180.ethological.herd.goal.ScatterGoal;
import com.charybdis180.ethological.home.FenceDetection;
import com.charybdis180.ethological.home.HomeAttachments;
import com.charybdis180.ethological.home.HomeData;
import com.charybdis180.ethological.home.HomeSettingsManager;
import com.charybdis180.ethological.home.NomadicMigration;
import com.charybdis180.ethological.home.SpeciesHomeSettings;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.sleep.SleepDisturbance;
import com.charybdis180.ethological.sleep.SleepEvents;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

public final class HerdEvents {
    // A member must be unreachable AND outside the follow envelope for this many ticks before
    // it is treated as fenced off. Stragglers trailing a migrating alpha or crossing water
    // legitimately sit outside the envelope for a full travel leg; ejecting them instantly is
    // what drove the split/rejoin oscillation (H7/H8 logs showed the same members ejected and
    // re-merged repeatedly). 400 ticks = 20s, longer than a typical pause window.
    private static final long SPLIT_GRACE_TICKS = 400L;
    // herd UUID -> (member UUID -> gameTime when it first looked like a split candidate).
    // Keyed per herd so one herd's prune cannot wipe another herd's grace timers — the old
    // member-keyed map let herd B's prune clear herd A's entries, so the timer never accumulated
    // (the logs showed 90 grace-start / 0 grace-skip on the same members).
    private static final java.util.Map<UUID, java.util.Map<UUID, Long>> separatedSince = new HashMap<>();

    private HerdEvents() {
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener((PreparableReloadListener)new HerdSettingsManager());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        HerdManager.clear();
        separatedSince.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (level.isClientSide() || !(level instanceof ServerLevel)) {
            return;
        }
        // Periodically drop herds with no loaded members so the static map cannot grow forever.
        if (Math.floorMod(level.getGameTime(), 1200L) != 0L) {
            return;
        }
        HerdManager.evictEmptyHerds((ServerLevel)level);
        // Drop grace timers for herds that no longer exist (merged/evicted) so the map cannot grow.
        separatedSince.keySet().removeIf(herdId -> HerdManager.get(herdId) == null);
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
        if (HerdSettingsManager.get(animal.getType()).isEmpty()) {
            return;
        }
        // Warm the in-memory herd registry immediately so herd-scoped logic (e.g. the
        // alpha-only water search relay in DrinkWaterGoal) engages from the first tick
        // after a chunk load, instead of waiting up to 100 ticks for the herd_tick gate.
        if (animal.hasData(HerdAttachments.HERD_DATA)) {
            HerdData data = animal.getData(HerdAttachments.HERD_DATA);
            HerdManager.Herd herd = HerdManager.getOrCreate(data.herdId(), animal.getUUID());
            herd.members.add(animal.getUUID());
            if (data.alpha() && !animal.isBaby()) {
                herd.alphaId = animal.getUUID();
            }
        }
        animal.goalSelector.addGoal(2, (Goal)new ScatterGoal(animal));
        animal.goalSelector.addGoal(2, (Goal)new HerdWaryGoal(animal));
        animal.goalSelector.addGoal(3, (Goal)new GatherGoal(animal));
        animal.goalSelector.addGoal(5, (Goal)new FollowParentGoal(animal));
        animal.goalSelector.addGoal(5, (Goal)new FollowAlphaGoal(animal));
        animal.goalSelector.addGoal(5, (Goal)new NurseGoal(animal));
        animal.goalSelector.addGoal(5, (Goal)new com.charybdis180.ethological.home.goal.SeekShoreGoal(animal));
        Goal vanillaFollowParent = null;
        for (WrappedGoal wrapped : animal.goalSelector.getAvailableGoals()) {
            if (!(wrapped.getGoal() instanceof net.minecraft.world.entity.ai.goal.FollowParentGoal)) continue;
            vanillaFollowParent = wrapped.getGoal();
            break;
        }
        if (vanillaFollowParent != null) {
            animal.goalSelector.removeGoal(vanillaFollowParent);
        }
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        HerdManager.Herd herd;
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
        Optional<SpeciesHerdSettings> settingsOpt = HerdSettingsManager.get(animal.getType());
        if (settingsOpt.isEmpty()) {
            return;
        }
        SpeciesHerdSettings settings = settingsOpt.get();
        long now = animal.level().getGameTime();
        if (animal.isBaby() && animal.hasData(HerdAttachments.MOTHER)
                && com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "mother_tick", now, 20L)) {
            HerdEvents.tickBabyMother(serverLevel, animal, now);
        }
        if (animal.hasData(HerdAttachments.HERD_DATA)
                && com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "wary_resatter", now, 5L)
                && (herd = HerdManager.get(((HerdData)animal.getData(HerdAttachments.HERD_DATA)).herdId())) != null
                && herd.threatId() != null
                && herd.phaseAt(serverLevel, now, animal) == HerdManager.PanicPhase.WARY) {
            Entity threat = serverLevel.getEntity(herd.threatId());
            double resatter = settings.resatterDistance();
            if (threat != null && threat.isAlive() && (double)threat.distanceTo((Entity)animal) <= resatter) {
                herd.startMemberScatter(animal.getUUID(), now);
                Ethological.LOGGER.debug("Ethological: herd {} member re-scatters \u2014 {} came too close",herd.id,threat.getType());
            }
        }
        if (!com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "herd_tick", now, 100L)) {
            return;
        }
        // One wolf scan per herd (the alpha's), with the radius widened to cover the herd spread.
        if (animal.hasData(HerdAttachments.HERD_DATA) && ((HerdData)animal.getData(HerdAttachments.HERD_DATA)).alpha()) {
            HerdEvents.tryAlertNearbyWolf(serverLevel, animal, settings, now);
        }
        if (!animal.hasData(HerdAttachments.HERD_DATA)) {
            if (animal.isBaby() && animal.hasData(HerdAttachments.MOTHER)) {
                MotherData motherLink = (MotherData)animal.getData(HerdAttachments.MOTHER);
                if (motherLink.isActive(now)) {
                    Animal motherAnimal;
                    UUID motherId = motherLink.motherId();
                    Entity mother = serverLevel.getEntity(motherId);
                    if (mother instanceof Animal && (motherAnimal = (Animal)mother).hasData(HerdAttachments.HERD_DATA)) {
                        UUID herdId = ((HerdData)motherAnimal.getData(HerdAttachments.HERD_DATA)).herdId();
                        HerdManager.Herd herd2 = HerdManager.getOrCreate(herdId, motherId);
                        herd2.members.add(animal.getUUID());
                        animal.setData(HerdAttachments.HERD_DATA, new HerdData(herd2.id, false));
                    }
                    return;
                }
                animal.removeData(HerdAttachments.MOTHER);
            }
            HerdEvents.tryJoinOrFormHerd(serverLevel, animal, settings);
            return;
        }
        HerdData data = (HerdData)animal.getData(HerdAttachments.HERD_DATA);
        HerdManager.Herd herd3 = HerdManager.getOrCreate(data.herdId(), animal.getUUID());
        herd3.members.add(animal.getUUID());
        // Never let a baby's alpha attachment flag claim leadership.
        if (data.alpha() && animal.isBaby()) {
            animal.setData(HerdAttachments.HERD_DATA, new HerdData(herd3.id, false));
            data = (HerdData)animal.getData(HerdAttachments.HERD_DATA);
            if (animal.getUUID().equals(herd3.alphaId)) {
                UUID elected = HerdManager.electAlpha(serverLevel, herd3.members);
                if (elected != null) {
                    herd3.alphaId = elected;
                }
                HerdManager.syncAlphaFlags(serverLevel, herd3);
            }
        }
        if (data.alpha() && !animal.isBaby()) {
            herd3.alphaId = animal.getUUID();
        }
        boolean isAlpha = animal.getUUID().equals(herd3.alphaId) && !animal.isBaby();
        boolean alphaMissing = !HerdManager.isAlphaResolvable(serverLevel, herd3);
        // Any loaded member can reconcile when the alpha is gone/unresolvable — otherwise a dead alpha freezes the herd.
        if (isAlpha || alphaMissing) {
            HerdManager.reconcile(serverLevel, herd3, settings.maxSize());
            isAlpha = animal.getUUID().equals(herd3.alphaId);
        }
        if (isAlpha) {
            // Compute the alpha's reachable region once and share it between the split and merge
            // passes — both were flooding the same 32-block radius back-to-back every herd tick.
            LongSet alphaRegion = HerdEvents.splitFencedOffMembers(serverLevel, animal, herd3, settings);
            HerdEvents.tryMergeNearbyHerds(serverLevel, animal, herd3, settings, alphaRegion);
            if (herd3.phaseAt(serverLevel, now, animal) == HerdManager.PanicPhase.GATHER) {
                boolean regrouped = herd3.members.stream().map(arg_0 -> ((ServerLevel)serverLevel).getEntity(arg_0)).filter(e -> e instanceof Animal).allMatch(e -> (double)e.distanceTo((Entity)animal) <= settings.followDistance());
                if (regrouped) {
                    herd3.markGathered();
                    Ethological.LOGGER.debug("Ethological: herd {} regrouped after the panic \u2014 staying wary",herd3.id);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity living = event.getEntity();
        if (!(living instanceof Animal) || living.level().isClientSide()) {
            return;
        }
        Level level = living.level();
        if (!(level instanceof ServerLevel)) {
            return;
        }
        HerdManager.onMemberDied((ServerLevel)level, (Animal)living);
    }

    private static void tryMergeNearbyHerds(ServerLevel level, Animal alpha, HerdManager.Herd self, SpeciesHerdSettings settings, LongSet sharedRegion) {
        if (HerdManager.adultCount(level, self) >= settings.maxSize()) {
            return;
        }
        if (self.isPanicking()) {
            return;
        }
        List<Animal> nearby = level.getEntitiesOfClass(Animal.class, alpha.getBoundingBox().inflate((double)settings.joinRadius()), other -> other != alpha && other.getType() == alpha.getType() && other.hasData(HerdAttachments.HERD_DATA));
        if (nearby.isEmpty()) {
            return;
        }
        BlockPos alphaPos = alpha.blockPosition();
        // Cheap pass: collect the distinct nearby herds that could actually accept a merge before
        // paying for a flood fill. A herd whose every loaded member is a baby can never be merged
        // (mergeInto drops babies from candidates), and the log showed such doomed attempts repeated
        // dozens of times per session, each flooding the radius.
        HashSet<UUID> seen = new HashSet<UUID>();
        seen.add(self.id);
        ArrayList<Animal> mergeable = new ArrayList<Animal>();
        for (Animal other2 : nearby) {
            HerdManager.Herd otherHerd;
            UUID otherHerdId = ((HerdData)other2.getData(HerdAttachments.HERD_DATA)).herdId();
            if (!seen.add(otherHerdId) || (otherHerd = HerdManager.get(otherHerdId)) == null || otherHerd.members.isEmpty() || otherHerd.isPanicking() || HerdManager.adultCount(level, otherHerd) == 0) continue;
            mergeable.add(other2);
        }
        if (mergeable.isEmpty()) {
            return;
        }
        // Reuse the region the split pass already flooded for the same alpha+radius; only flood
        // again when the split pass skipped it (no candidate beyond the envelope).
        long fillStart = System.nanoTime();
        LongSet alphaRegion = sharedRegion != null ? sharedRegion : FenceDetection.reachableColumns((Level)level, alphaPos.getX(), alphaPos.getZ(), alphaPos.getY(), settings.joinRadius());
        long fillUs = (System.nanoTime() - fillStart) / 1000L;
        for (Animal other2 : mergeable) {
            HerdManager.Herd absorbed;
            HerdManager.Herd survivor;
            HerdManager.Herd otherHerd = HerdManager.get(((HerdData)other2.getData(HerdAttachments.HERD_DATA)).herdId());
            if (otherHerd == null || !FenceDetection.canRejoin((Level)level, alphaRegion, other2.blockPosition(), alphaPos.getY())) continue;
            if (self.members.size() > otherHerd.members.size() || self.members.size() == otherHerd.members.size() && self.id.toString().compareTo(otherHerd.id.toString()) <= 0) {
                survivor = self;
                absorbed = otherHerd;
            } else {
                survivor = otherHerd;
                absorbed = self;
            }
            int room = settings.maxSize() - HerdManager.adultCount(level, survivor);
            if (room <= 0) continue;
            HerdManager.mergeInto(level, survivor, absorbed, room);
            if (HerdManager.get(self.id) != self) {
                return;
            }
            if (HerdManager.adultCount(level, self) < settings.maxSize()) continue;
            return;
        }
    }

    private static void tryJoinOrFormHerd(ServerLevel level, Animal animal, SpeciesHerdSettings settings) {
        List<Animal> nearby = level.getEntitiesOfClass(Animal.class, animal.getBoundingBox().inflate((double)settings.joinRadius()), other -> other != animal && other.getType() == animal.getType());
        if (nearby.isEmpty()) {
            return;
        }
        // Only flood-fill the reachable region when some nearby animal can actually join:
        // unherded babies never join (no herd to enter, cannot seed a pack), so a
        // neighborhood of only babies makes the flood wasted work.
        boolean hasCandidate = false;
        for (Animal other : nearby) {
            if (other.hasData(HerdAttachments.HERD_DATA) || !other.isBaby()) {
                hasCandidate = true;
                break;
            }
        }
        if (!hasCandidate) {
            return;
        }
        HerdManager.Herd bestHerd = null;
        double bestDistance = Double.MAX_VALUE;
        ArrayList<Animal> unherdedAdults = new ArrayList<Animal>();
        BlockPos selfPos = animal.blockPosition();
        LongSet selfRegion = FenceDetection.reachableColumns((Level)level, selfPos.getX(), selfPos.getZ(), selfPos.getY(), settings.joinRadius());
        for (Animal other2 : nearby) {
            if (!FenceDetection.canRejoin((Level)level, selfRegion, other2.blockPosition(), selfPos.getY())) continue;
            if (other2.hasData(HerdAttachments.HERD_DATA)) {
                double distance;
                HerdManager.Herd herd = HerdManager.get(((HerdData)other2.getData(HerdAttachments.HERD_DATA)).herdId());
                if (herd == null || HerdManager.adultCount(level, herd) >= settings.maxSize() || !((distance = other2.distanceToSqr((Entity)animal)) < bestDistance)) continue;
                bestHerd = herd;
                bestDistance = distance;
                continue;
            }
            // Babies never found herds; only adults seed a new pack.
            if (!other2.isBaby()) {
                unherdedAdults.add(other2);
            }
        }
        if (bestHerd != null) {
            bestHerd.members.add(animal.getUUID());
            animal.setData(HerdAttachments.HERD_DATA, new HerdData(bestHerd.id, false));
            Ethological.LOGGER.debug("Ethological: {} joined herd {} ({} members)", new Object[]{animal.getType(), bestHerd.id, bestHerd.members.size()});
            return;
        }
        // Babies without a mother wait for an existing herd — they must not become alpha of a new one.
        if (animal.isBaby()) {
            return;
        }
        if (unherdedAdults.isEmpty()) {
            return;
        }
        List<Animal> founders = unherdedAdults.stream().sorted(Comparator.comparingDouble(other -> other.distanceToSqr((Entity)animal))).limit((long)settings.maxSize() - 1L).toList();
        HashSet<UUID> ids = new HashSet<UUID>();
        ids.add(animal.getUUID());
        founders.forEach(other -> ids.add(other.getUUID()));
        UUID alphaId = HerdManager.electAlpha(level, ids);
        if (alphaId == null) {
            alphaId = animal.getUUID();
        }
        HerdManager.Herd herd = HerdManager.create(alphaId);
        herd.members.addAll(ids);
        animal.setData(HerdAttachments.HERD_DATA, new HerdData(herd.id, animal.getUUID().equals(alphaId)));
        for (Animal founder : founders) {
            founder.setData(HerdAttachments.HERD_DATA, new HerdData(herd.id, founder.getUUID().equals(alphaId)));
        }
        Ethological.LOGGER.debug("Ethological: formed herd {} with {} members (alpha {})", new Object[]{herd.id, herd.members.size(), alphaId});
        HerdEvents.prepareNomadicAlpha(level, alphaId);
    }

    private static void prepareNomadicAlpha(ServerLevel level, UUID alphaId) {
        Entity entity = level.getEntity(alphaId);
        if (!(entity instanceof Animal alpha)) {
            return;
        }
        if (!HomeSettingsManager.get(alpha.getType()).map(SpeciesHomeSettings::nomadic).orElse(false)) {
            return;
        }
        NomadicMigration.resetGrazeAnchor(alpha);
        NomadicMigration.ensureHeading(alpha);
        NomadicMigration.beginTravelLeg(alpha);
    }

    /**
     * @return the alpha's reachable column region, or null when no flood fill was needed
     * (no candidates, or every loaded member already sits inside the follow envelope).
     */
    private static LongSet splitFencedOffMembers(ServerLevel level, Animal alpha, HerdManager.Herd herd, SpeciesHerdSettings settings) {
        if (herd.isPanicking() || herd.alphaId == null || !herd.alphaId.equals(alpha.getUUID())) {
            return null;
        }
        BlockPos alphaPos = alpha.blockPosition();
        int radius = settings.joinRadius();
        // Cheap pre-pass: gather loaded members inside the radius before paying for any flood fill.
        LinkedHashMap<UUID, BlockPos> candidates = new LinkedHashMap<UUID, BlockPos>();
        for (UUID memberId : herd.members) {
            BlockPos memberPos;
            Entity e;
            if (memberId.equals(alpha.getUUID()) || !((e = level.getEntity(memberId)) instanceof Animal)) continue;
            Animal member = (Animal)e;
            if (!e.isAlive() || Math.abs((memberPos = member.blockPosition()).getX() - alphaPos.getX()) > radius || Math.abs(memberPos.getZ() - alphaPos.getZ()) > radius) continue;
            candidates.put(memberId, memberPos);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        double followEnvelope = settings.followDistance(); // base leash, matches FollowAlphaGoal
        // Members up to this distance are stragglers, not fenced-off strays. The threshold sits
        // at 2x the base follow range plus a margin so the alpha hold + member escape path (both
        // 2x) act before a member is ever split off; ejecting closer members drove the
        // split/rejoin oscillation. Only members beyond the margin can be treated as separated.
        double straggleThreshold = followEnvelope * 2.0 + 3.0;
        // Cheap distance pass first: when every member is inside the straggle margin, none can be
        // ejected, so the flood fill is pure waste. Only pay for reachability when someone is far.
        boolean anyBeyondMargin = false;
        for (Map.Entry<UUID, BlockPos> entry : candidates.entrySet()) {
            double d = Math.sqrt(alpha.distanceToSqr(Vec3.atCenterOf((Vec3i)entry.getValue())));
            if (d > straggleThreshold) {
                anyBeyondMargin = true;
                break;
            }
        }
        java.util.Map<UUID, Long> herdGrace = separatedSince.computeIfAbsent(herd.id, k -> new HashMap<UUID, Long>());
        if (!anyBeyondMargin) {
            for (Map.Entry<UUID, BlockPos> entry : candidates.entrySet()) {
                herdGrace.remove(entry.getKey());
            }
            // Prune entries for members that are no longer candidates of this herd (rejoined or gone).
            herdGrace.keySet().removeIf(id -> !candidates.containsKey(id));
            if (herdGrace.isEmpty()) {
                separatedSince.remove(herd.id);
            }
            return null;
        }
        long fillStart = System.nanoTime();
        LongSet alphaRegion = FenceDetection.reachableColumns((Level)level, alphaPos.getX(), alphaPos.getZ(), alphaPos.getY(), radius);
        long fillUs = (System.nanoTime() - fillStart) / 1000L;
        LinkedHashMap<UUID, BlockPos> separated = new LinkedHashMap<UUID, BlockPos>();
        long now = level.getGameTime();
        for (Map.Entry<UUID, BlockPos> entry : candidates.entrySet()) {
            BlockPos memberPos = entry.getValue();
            double memberDist = Math.sqrt(alpha.distanceToSqr(Vec3.atCenterOf((Vec3i)memberPos)));
            if (memberDist <= straggleThreshold) {
                herdGrace.remove(entry.getKey());
                continue;
            }
            if (alphaRegion.contains(FenceDetection.pack(memberPos.getX(), memberPos.getZ()))) {
                herdGrace.remove(entry.getKey());
                continue;
            }
            // Beyond the straggle margin AND unreachable: a split candidate, but only eject after a
            // sustained separation so a member trailing a migration leg has time to rejoin.
            Long since = herdGrace.get(entry.getKey());
            if (since == null) {
                herdGrace.put(entry.getKey(), now);
                continue;
            }
            if (now - since < SPLIT_GRACE_TICKS) {
                continue;
            }
            separated.put(entry.getKey(), memberPos);
            herdGrace.remove(entry.getKey());
        }
        // Prune entries for members that are no longer candidates of this herd (rejoined or gone).
        herdGrace.keySet().removeIf(id -> !candidates.containsKey(id));
        if (herdGrace.isEmpty()) {
            separatedSince.remove(herd.id);
        }
        if (separated.isEmpty()) {
            return alphaRegion;
        }
        LinkedHashMap<UUID, BlockPos> remaining = new LinkedHashMap<>(separated);
        ArrayList<LinkedHashSet<UUID>> components = new ArrayList<>();
        while (!remaining.isEmpty()) {
            boolean grew;
            Map.Entry<UUID, BlockPos> seed = remaining.entrySet().iterator().next();
            LinkedHashSet<UUID> component = new LinkedHashSet<>();
            component.add(seed.getKey());
            remaining.remove(seed.getKey());
            LongSet region = FenceDetection.reachableColumns((Level)level, seed.getValue().getX(), seed.getValue().getZ(), seed.getValue().getY(), radius);
            do {
                grew = false;
                Iterator<Map.Entry<UUID, BlockPos>> it = remaining.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, BlockPos> entry = it.next();
                    if (!region.contains(FenceDetection.pack(entry.getValue().getX(), entry.getValue().getZ()))) continue;
                    it.remove();
                    component.add(entry.getKey());
                    grew = true;
                }
            } while (grew);
            components.add(component);
        }
        for (LinkedHashSet<UUID> component : components) {
            HerdEvents.splitOffComponent(level, herd, component);
        }
        return alphaRegion;
    }

    private static void splitOffComponent(ServerLevel level, HerdManager.Herd herd, Set<UUID> component) {
        LinkedHashSet<UUID> departing = new LinkedHashSet<UUID>(component);
        for (UUID memberId : herd.members) {
            Animal baby;
            Object e;
            if (departing.contains(memberId) || !((e = level.getEntity(memberId)) instanceof Animal) || !(baby = (Animal)e).isBaby() || !baby.hasData(HerdAttachments.MOTHER) || !departing.contains(((MotherData)baby.getData(HerdAttachments.MOTHER)).motherId())) continue;
            departing.add(memberId);
        }
        UUID newAlphaId = HerdManager.electAlpha(level, departing);
        HerdManager.Herd newHerd = HerdManager.create(newAlphaId);
        newHerd.members.addAll(departing);
        herd.members.removeAll(departing);
        for (UUID id : departing) {
            Entity e = level.getEntity(id);
            if (!(e instanceof Animal)) continue;
            Animal animal = (Animal)e;
            animal.setData(HerdAttachments.HERD_DATA,new HerdData(newHerd.id, id.equals(newAlphaId)));
        }
        Entity alphaEntity = level.getEntity(newAlphaId);
        if (alphaEntity instanceof Animal newAlphaAnimal) {
            boolean nomadic = HomeSettingsManager.get(newAlphaAnimal.getType())
                    .map(SpeciesHomeSettings::nomadic)
                    .orElse(false);
            HomeData home = nomadic
                    ? new HomeData(newAlphaAnimal.blockPosition(), true)
                    : new HomeData(newAlphaAnimal.blockPosition());
            newAlphaAnimal.setData(HomeAttachments.HOME, home);
            for (UUID id : departing) {
                Entity e = level.getEntity(id);
                if (id.equals(newAlphaId) || !(e instanceof Animal animal)) {
                    continue;
                }
                animal.setData(HomeAttachments.HOME, home);
            }
            if (nomadic) {
                HerdEvents.prepareNomadicAlpha(level, newAlphaId);
            }
        }
        Ethological.LOGGER.debug("Ethological: fenced-off herd members {} split into new herd {} (alpha {})", new Object[]{departing.size(), newHerd.id, newAlphaId});
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingDamageEvent.Pre event) {
        Level level;
        LivingEntity livingEntity = event.getEntity();
        if (!(livingEntity instanceof Animal)) {
            return;
        }
        Animal victim = (Animal)livingEntity;
        if (victim.level().isClientSide()) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof LivingEntity) || attacker == victim) {
            return;
        }
        if (event.getNewDamage() <= 0.0f) {
            return;
        }
        if (event.getSource().is(DamageTypes.STARVE) || event.getSource().is(DamageTypes.DRY_OUT)) {
            return;
        }
        long now = victim.level().getGameTime();
        HashSet<UUID> alerted = new HashSet<UUID>();
        alerted.add(victim.getUUID());
        alerted.add(attacker.getUUID());
        if (victim.hasData(HerdAttachments.HERD_DATA) && (level = victim.level()) instanceof ServerLevel) {
            ServerLevel serverLevel = (ServerLevel)level;
            HerdManager.Herd herd = HerdManager.get(((HerdData)victim.getData(HerdAttachments.HERD_DATA)).herdId());
            if (herd != null) {
                herd.startPanic(attacker.getUUID(), now);
                Ethological.LOGGER.debug("Ethological: herd {} alerted by {} \u2014 panic!",herd.id,attacker.getType());
                for (UUID memberId : herd.members) {
                    Entity member = serverLevel.getEntity(memberId);
                    if (!(member instanceof Animal)) continue;
                    Animal memberAnimal = (Animal)member;
                    if (!alerted.add(memberId)) continue;
                    HerdEvents.alert(memberAnimal, attacker, now);
                }
            }
        }
        double radius = HerdSettingsManager.get(victim.getType()).map(SpeciesHerdSettings::crossSpeciesAlertRadius).orElse(16.0);
        for (Animal other : victim.level().getEntitiesOfClass(Animal.class, victim.getBoundingBox().inflate(radius))) {
            if (!alerted.add(other.getUUID())) continue;
            HerdEvents.alert(other, attacker, now);
        }
    }

    private static void tryAlertNearbyWolf(ServerLevel level, Animal animal, SpeciesHerdSettings settings, long now) {
        HerdManager.Herd herd = HerdManager.get(((HerdData)animal.getData(HerdAttachments.HERD_DATA)).herdId());
        if (herd == null) {
            return;
        }
        if (herd.isPanicking()) {
            return;
        }
        double radius = settings.crossSpeciesAlertRadius() + settings.followDistance();
        AABB box = animal.getBoundingBox().inflate(radius);
        for (Wolf wolf : level.getEntitiesOfClass(Wolf.class, box, w -> w.isAlive() && !w.isTame())) {
            Animal prey;
            boolean targetingMate = false;
            LivingEntity livingEntity = wolf.getTarget();
            if (livingEntity instanceof Animal && (prey = (Animal)livingEntity).hasData(HerdAttachments.HERD_DATA) && ((HerdData)prey.getData(HerdAttachments.HERD_DATA)).herdId().equals(herd.id)) {
                targetingMate = true;
            }
            if (!targetingMate && (double)wolf.distanceTo((Entity)animal) > radius) continue;
            herd.startPanic(wolf.getUUID(), now);
            Ethological.LOGGER.debug("Ethological: herd {} flushed by nearby wolf",herd.id);
            for (UUID memberId : herd.members) {
                Entity member = level.getEntity(memberId);
                if (!(member instanceof Animal)) continue;
                Animal mate = (Animal)member;
                HerdEvents.alert(mate, (Entity)wolf, now);
            }
            return;
        }
    }

    private static void alert(Animal target, Entity attacker, long now) {
        if (SleepSettingsManager.get(target.getType()).isEmpty()) {
            return;
        }
        target.setData(SleepAttachments.SLEEP_DISTURBANCE,new SleepDisturbance(attacker.getUUID(), now));
        if (((Boolean)target.getData(SleepAttachments.SLEEPING)).booleanValue()) {
            SleepEvents.wake(target);
        }
    }

    /**
     * Orphan / expired-mother cleanup, plus copy mother's flee disturbance onto the baby.
     * Unloaded mothers are kept until {@link MotherData#followUntilGameTime()} expires.
     */
    private static void tickBabyMother(ServerLevel level, Animal baby, long now) {
        if (!baby.hasData(HerdAttachments.MOTHER)) {
            return;
        }
        MotherData motherData = (MotherData)baby.getData(HerdAttachments.MOTHER);
        if (!motherData.isActive(now)) {
            baby.removeData(HerdAttachments.MOTHER);
            return;
        }
        Entity motherEntity = level.getEntity(motherData.motherId());
        if (motherEntity == null) {
            // Unloaded: keep attachment until followUntil expires.
            return;
        }
        if (!motherEntity.isAlive()) {
            baby.removeData(HerdAttachments.MOTHER);
            return;
        }
        if (!(motherEntity instanceof Animal mother)) {
            return;
        }
        if (!mother.hasData(SleepAttachments.SLEEP_DISTURBANCE)) {
            return;
        }
        SleepDisturbance disturbance = (SleepDisturbance)mother.getData(SleepAttachments.SLEEP_DISTURBANCE);
        if (!baby.hasData(SleepAttachments.SLEEP_DISTURBANCE)) {
            baby.setData(SleepAttachments.SLEEP_DISTURBANCE, disturbance);
            if (((Boolean)baby.getData(SleepAttachments.SLEEPING)).booleanValue()) {
                SleepEvents.wake(baby);
            }
        }
    }
}

