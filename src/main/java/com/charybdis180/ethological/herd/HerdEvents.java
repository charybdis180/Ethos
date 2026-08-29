package com.charybdis180.ethological.herd;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.Ethological;
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
import com.charybdis180.ethological.home.HomeData;
import com.charybdis180.ethological.home.HomeSettingsManager;
import com.charybdis180.ethological.home.NomadicMigration;
import com.charybdis180.ethological.home.SpeciesHomeSettings;
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

    /** Per-animal cooldown between escape-driven secession attempts (600t = 30s, matching
     *  the watchdog release cadence that feeds this path). */
    private static final long SECEDE_RATE_LIMIT_TICKS = 600L;
    /** Per-herd interval for the alpha's pen-enclosure check. FenceDetection's verdict is
     *  cached ~200t per region, so this rate limit costs nothing extra. */
    private static final long ALPHA_PEN_CHECK_TICKS = 200L;
    private static final java.util.Map<UUID, Long> nextSecedeAttemptGameTime = new HashMap<>();
    private static final java.util.Map<UUID, Long> alphaPenCheckGameTime = new HashMap<>();

    private HerdEvents() {
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new HerdSettingsManager());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        HerdManager.clear();
        separatedSince.clear();
        nextSecedeAttemptGameTime.clear();
        alphaPenCheckGameTime.clear();
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
        // Same housekeeping for the secession cooldown and alpha pen-check maps: entries for
        // animals whose entity is gone are dead weight.
        nextSecedeAttemptGameTime.keySet().removeIf(id -> ((ServerLevel)level).getEntity(id) == null);        alphaPenCheckGameTime.keySet().removeIf(herdId -> HerdManager.get(herdId) == null);
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
        if (animal.hasData(ModAttachments.HERD_DATA)) {
            HerdData data = animal.getData(ModAttachments.HERD_DATA);
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
        if (animal.isBaby() && animal.hasData(ModAttachments.MOTHER)
                && com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "mother_tick", now, 20L)) {
            HerdEvents.tickBabyMother(serverLevel, animal, now);
        }
        if (animal.hasData(ModAttachments.HERD_DATA)
                && com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "wary_resatter", now, 5L)
                && (herd = HerdManager.get(((HerdData)animal.getData(ModAttachments.HERD_DATA)).herdId())) != null
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
        if (animal.hasData(ModAttachments.HERD_DATA) && ((HerdData)animal.getData(ModAttachments.HERD_DATA)).alpha()) {
            HerdEvents.tryAlertNearbyWolf(serverLevel, animal, settings, now);
        }
        if (!animal.hasData(ModAttachments.HERD_DATA)) {
            if (animal.isBaby() && animal.hasData(ModAttachments.MOTHER)) {
                MotherData motherLink = (MotherData)animal.getData(ModAttachments.MOTHER);
                if (motherLink.isActive(now)) {
                    Animal motherAnimal;
                    UUID motherId = motherLink.motherId();
                    Entity mother = serverLevel.getEntity(motherId);
                    if (mother instanceof Animal && (motherAnimal = (Animal)mother).hasData(ModAttachments.HERD_DATA)) {
                        UUID herdId = ((HerdData)motherAnimal.getData(ModAttachments.HERD_DATA)).herdId();
                        HerdManager.Herd herd2 = HerdManager.getOrCreate(herdId, motherId);
                        herd2.members.add(animal.getUUID());
                        animal.setData(ModAttachments.HERD_DATA, new HerdData(herd2.id, false));
                    }
                    return;
                }
                animal.removeData(ModAttachments.MOTHER);
            }
            HerdEvents.tryJoinOrFormHerd(serverLevel, animal, settings);
            return;
        }
        HerdData data = (HerdData)animal.getData(ModAttachments.HERD_DATA);
        HerdManager.Herd herd3 = HerdManager.getOrCreate(data.herdId(), animal.getUUID());
        herd3.members.add(animal.getUUID());
        // Never let a baby's alpha attachment flag claim leadership.
        if (data.alpha() && animal.isBaby()) {
            animal.setData(ModAttachments.HERD_DATA, new HerdData(herd3.id, false));
            data = (HerdData)animal.getData(ModAttachments.HERD_DATA);
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
            // Pen-enclosure check (section 2b): a penned alpha's herd is definitionally
            // together — mark it cap-free and skip the split pass this tick. The verdict is
            // FenceDetection-cached and per-herd rate-limited; once the pen opens the flag is
            // cleared and the normal split pass resumes as the terrain-separation safety net.
            if (herd3.penHerd) {
                Long lastCheck = alphaPenCheckGameTime.get(herd3.id);
                if (lastCheck == null || now - lastCheck >= ALPHA_PEN_CHECK_TICKS) {
                    alphaPenCheckGameTime.put(herd3.id, now);
                    if (!FenceDetection.isFencedIn(animal)) {
                        herd3.penHerd = false;
                    } else {
                        // Containment sweep: members standing outside the enclosure can never
                        // reach the alpha's huddle (the split pass is skipped for pen herds),
                        // so they would trail at the fence forever. Eject them to unherded
                        // status; normal join/wander logic takes over away from the pen.
                        // Babies stay — they trail their mothers and cannot rejoin alone.
                        LongSet penRegion = FenceDetection.pennedRegionOf(animal);
                        if (penRegion != null) {
                            int refY = animal.blockPosition().getY();
                            for (UUID memberId : java.util.List.copyOf(herd3.members)) {
                                if (memberId.equals(animal.getUUID())) {
                                    continue;
                                }
                                Entity m = serverLevel.getEntity(memberId);
                                if (!(m instanceof Animal mate) || mate.isBaby()) {
                                    continue;
                                }
                                if (FenceDetection.excludes(serverLevel, penRegion, mate.blockPosition(), refY)) {
                                    HerdManager.removeFromHerd(serverLevel, mate, herd3);
                                }
                            }
                        }
                    }
                }
            } else {
                Long lastCheck = alphaPenCheckGameTime.get(herd3.id);
                if (lastCheck == null || now - lastCheck >= ALPHA_PEN_CHECK_TICKS) {
                    alphaPenCheckGameTime.put(herd3.id, now);
                    if (FenceDetection.isFencedIn(animal)) {
                        herd3.penHerd = true;
                    }
                }
            }
            // Compute the alpha's reachable region once and share it between the split and merge
            // passes — both were flooding the same 32-block radius back-to-back every herd tick.
            LongSet alphaRegion = herd3.penHerd ? null : HerdEvents.splitFencedOffMembers(serverLevel, animal, herd3, settings);
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
        // Pen herds ignore the adult cap: their size is bounded by the enclosure, not the
        // wild pack ceiling. Cross-enclosure merges stay impossible — candidates must pass
        // canRejoin against the alpha's flood region below.
        if (!self.penHerd && HerdManager.adultCount(level, self) >= settings.maxSize()) {
            return;
        }
        if (self.isPanicking()) {
            return;
        }
        List<Animal> nearby = level.getEntitiesOfClass(Animal.class, alpha.getBoundingBox().inflate((double)settings.joinRadius()), other -> other != alpha && other.getType() == alpha.getType() && other.hasData(ModAttachments.HERD_DATA));
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
            UUID otherHerdId = ((HerdData)other2.getData(ModAttachments.HERD_DATA)).herdId();
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
            HerdManager.Herd otherHerd = HerdManager.get(((HerdData)other2.getData(ModAttachments.HERD_DATA)).herdId());
            if (otherHerd == null || !FenceDetection.canRejoin((Level)level, alphaRegion, other2.blockPosition(), alphaPos.getY())) continue;
            if (self.members.size() > otherHerd.members.size() || self.members.size() == otherHerd.members.size() && self.id.toString().compareTo(otherHerd.id.toString()) <= 0) {
                survivor = self;
                absorbed = otherHerd;
            } else {
                survivor = otherHerd;
                absorbed = self;
            }
            int room = settings.maxSize() - HerdManager.adultCount(level, survivor);
            // A pen-herd survivor has no room limit (enclosure-bounded population).
            if (room <= 0 && !survivor.penHerd) continue;
            HerdManager.mergeInto(level, survivor, absorbed, survivor.penHerd ? Integer.MAX_VALUE : room);
            if (HerdManager.get(self.id) != self) {
                return;
            }
            if (!self.penHerd && HerdManager.adultCount(level, self) < settings.maxSize()) continue;
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
            if (other.hasData(ModAttachments.HERD_DATA) || !other.isBaby()) {
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
            if (other2.hasData(ModAttachments.HERD_DATA)) {
                double distance;
                HerdManager.Herd herd = HerdManager.get(((HerdData)other2.getData(ModAttachments.HERD_DATA)).herdId());
                // Pen herds accept joiners past the cap — but ONLY from inside the enclosure:
                // an outsider lured by a penned member would trail the fence forever, since it
                // can never be led in. The member's pen region is the enclosure of record; an
                // unresolvable region (member itself not enclosed) rejects too.
                if (herd != null && herd.penHerd) {
                    LongSet targetPen = FenceDetection.pennedRegionOf(other2);
                    if (targetPen == null || FenceDetection.excludes((Level)level, targetPen, selfPos, selfPos.getY())) {
                        continue;
                    }
                }
                boolean capOk = herd.penHerd || HerdManager.adultCount(level, herd) < settings.maxSize();
                if (herd == null || !capOk || !((distance = other2.distanceToSqr((Entity)animal)) < bestDistance)) continue;
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
            animal.setData(ModAttachments.HERD_DATA, new HerdData(bestHerd.id, false));
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
        animal.setData(ModAttachments.HERD_DATA, new HerdData(herd.id, animal.getUUID().equals(alphaId)));
        for (Animal founder : founders) {
            founder.setData(ModAttachments.HERD_DATA, new HerdData(herd.id, founder.getUUID().equals(alphaId)));
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
            double d = Math.sqrt(alpha.distanceToSqr(Vec3.atCenterOf(entry.getValue())));
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
            double memberDist = Math.sqrt(alpha.distanceToSqr(Vec3.atCenterOf(memberPos)));
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
            if (departing.contains(memberId) || !((e = level.getEntity(memberId)) instanceof Animal) || !(baby = (Animal)e).isBaby() || !baby.hasData(ModAttachments.MOTHER) || !departing.contains(((MotherData)baby.getData(ModAttachments.MOTHER)).motherId())) continue;
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
            animal.setData(ModAttachments.HERD_DATA,new HerdData(newHerd.id, id.equals(newAlphaId)));
        }
        Entity alphaEntity = level.getEntity(newAlphaId);
        if (alphaEntity instanceof Animal newAlphaAnimal) {
            boolean nomadic = HomeSettingsManager.get(newAlphaAnimal.getType())
                    .map(SpeciesHomeSettings::nomadic)
                    .orElse(false);
            HomeData home = nomadic
                    ? new HomeData(newAlphaAnimal.blockPosition(), true)
                    : new HomeData(newAlphaAnimal.blockPosition());
            newAlphaAnimal.setData(ModAttachments.HOME, home);
            for (UUID id : departing) {
                Entity e = level.getEntity(id);
                if (id.equals(newAlphaId) || !(e instanceof Animal animal)) {
                    continue;
                }
                animal.setData(ModAttachments.HOME, home);
            }
            if (nomadic) {
                HerdEvents.prepareNomadicAlpha(level, newAlphaId);
            }
        }
        Ethological.LOGGER.debug("Ethological: fenced-off herd members {} split into new herd {} (alpha {})", new Object[]{departing.size(), newHerd.id, newAlphaId});
    }

    /**
     * Escape-driven fence secession. Called from FollowAlphaGoal's watchdog release branch,
     * i.e. only after ~30s of proven immobility with every escape fallback exhausted — the
     * strongest available evidence the animal is trapped. Confirms the trap with
     * FenceDetection (cached per pen region), then moves the animal and its pen-mates into a
     * single cap-free pen herd so the penned population stops fighting a follow leash it can
     * never satisfy.
     *
     * <p>Ordered cheap guards first: an animal whose herd is already flagged penHerd early-outs
     * in O(1) because a pen-mate already converted this pen; the per-animal rate limit keeps
     * repeated watchdog firings from re-probing; panic/alpha guards keep secession out of
     * fleeing or leaderless herds.</p>
     */
    public static void trySecedeIfFencedIn(Animal animal) {
        if (!(animal.level() instanceof ServerLevel serverLevel) || !animal.hasData(ModAttachments.HERD_DATA)) {
            return;
        }
        long now = serverLevel.getGameTime();
        Long cooldown = nextSecedeAttemptGameTime.get(animal.getUUID());
        if (cooldown != null && now < cooldown) {
            return;
        }
        nextSecedeAttemptGameTime.put(animal.getUUID(), now + SECEDE_RATE_LIMIT_TICKS);
        HerdData data = (HerdData)animal.getData(ModAttachments.HERD_DATA);
        HerdManager.Herd herd = HerdManager.get(data.herdId());
        // Already inside a converted pen herd: a pen-mate did the work earlier.
        if (herd != null && herd.penHerd) {
            return;
        }
        // Never secede mid-panic; never secede when our own herd is effectively leaderless
        // (reconciliation owns that case).
        if (herd == null || herd.alphaId == null || HerdManager.panicPhaseOf(animal, now) != HerdManager.PanicPhase.NONE || !HerdManager.isAlphaResolvable(serverLevel, herd)) {
            return;
        }
        if (animal.isBaby()) {
            return;
        }
        Entity alphaEntity = serverLevel.getEntity(herd.alphaId);
        if (!(alphaEntity instanceof Animal alphaAnimal)) {
            return;
        }
        // The pen check itself is the expensive part; FenceDetection shares one cached verdict
        // across every occupant of the same enclosure region.
        if (!FenceDetection.isFencedIn(animal)) {
            return;
        }
        LongSet penRegion = FenceDetection.regionOf(animal);
        HerdEvents.secedePen(serverLevel, animal, herd, penRegion);
    }

    /**
     * Moves {@code trigger} and every compatible pen-mate into one cap-free pen herd.
     * Pen-mates are: unherded adults in the region, members of herds whose alpha lies OUTSIDE
     * the region (their herd cannot reach them anyway), and their dependent babies. Members of
     * healthy herds whose alpha is INSIDE the region are left alone — that herd is together
     * and will be marked by its own alpha (section 2b).
     */
    private static void secedePen(ServerLevel level, Animal trigger, HerdManager.Herd oldHerd, LongSet penRegion) {
        BlockPos triggerPos = trigger.blockPosition();
        SpeciesHerdSettings settings = HerdSettingsManager.get(trigger.getType()).orElse(null);
        int radius = settings != null ? settings.joinRadius() : 32;
        List<Animal> nearby = level.getEntitiesOfClass(Animal.class,
                trigger.getBoundingBox().inflate(radius),
                other -> other.getType() == trigger.getType()
                        && FenceDetection.canRejoin(level, penRegion, other.blockPosition(), triggerPos.getY()));
        UUID newAlphaId = null;
        HerdManager.Herd targetPen = null;
        ArrayList<Animal> joining = new ArrayList<>();
        joining.add(trigger);
        for (Animal mate : nearby) {
            if (!mate.isAlive() || mate.isBaby()) {
                continue;
            }
            if (mate.hasData(ModAttachments.HERD_DATA)) {
                HerdData mateData = (HerdData)mate.getData(ModAttachments.HERD_DATA);
                HerdManager.Herd mateHerd = HerdManager.get(mateData.herdId());
                if (mateHerd == null) {
                    continue;
                }
                if (mateHerd.penHerd) {
                    // An existing pen herd in this pen absorbs everything below.
                    targetPen = mateHerd;
                    continue;
                }
                Entity mateAlpha = mateHerd.alphaId != null ? level.getEntity(mateHerd.alphaId) : null;
                boolean alphaInPen = mateAlpha instanceof Animal ma
                        && FenceDetection.canRejoin(level, penRegion, ma.blockPosition(), triggerPos.getY());
                if (alphaInPen) {
                    // Together herd led from inside the pen: leave it to section 2b marking.
                    continue;
                }
                // Orphaned member: its herd's leadership is outside the fence.
                joining.add(mate);
                continue;
            }
            // Unherded adult standing in the pen.
            joining.add(mate);
        }
        // Babies follow their mothers into the pen herd (never seed or lead it).
        HashSet<UUID> joiningIds = new HashSet<>();
        for (Animal j : joining) {
            joiningIds.add(j.getUUID());
        }
        ArrayList<Animal> babies = new ArrayList<>();
        for (Animal mate : nearby) {
            if (!mate.isAlive() || !mate.isBaby() || !mate.hasData(ModAttachments.MOTHER)) {
                continue;
            }
            UUID motherId = ((MotherData)mate.getData(ModAttachments.MOTHER)).motherId();
            if (joiningIds.contains(motherId)) {
                babies.add(mate);
            }
        }
        // Prefer adopting an existing pen herd; otherwise create one and elect from the joiners.
        if (targetPen == null) {
            ArrayList<Animal> adults = new ArrayList<>(joining);
            UUID elected = HerdManager.electAlpha(level, joiningIds);
            if (elected == null) {
                return;
            }
            targetPen = HerdManager.create(elected);
            newAlphaId = elected;
        } else {
            newAlphaId = targetPen.alphaId;
        }
        targetPen.penHerd = true;
        // Detach joiners from their old herds first (re-electing alphas there), then attach all.
        for (Animal j : joining) {
            if (j == trigger) {
                continue;
            }
            if (j.hasData(ModAttachments.HERD_DATA)) {
                HerdData jd = (HerdData)j.getData(ModAttachments.HERD_DATA);
                HerdManager.Herd oh = HerdManager.get(jd.herdId());
                if (oh != null && oh != targetPen) {
                    HerdManager.removeFromHerd(level, j, oh);
                }
            }
        }
        HerdManager.removeFromHerd(level, trigger, oldHerd);
        for (Animal j : joining) {
            j.setData(ModAttachments.HERD_DATA, new HerdData(targetPen.id, false));
        }
        for (Animal b : babies) {
            b.setData(ModAttachments.HERD_DATA, new HerdData(targetPen.id, false));
            targetPen.members.add(b.getUUID());
        }
        if (!targetPen.members.contains(trigger.getUUID())) {
            targetPen.members.add(trigger.getUUID());
        }
        if (!targetPen.members.contains(newAlphaId)) {
            targetPen.members.add(newAlphaId);
        }
        targetPen.alphaId = newAlphaId;
        HerdManager.syncAlphaFlags(level, targetPen);
        // Home anchor: mirror splitOffComponent's nomadic-aware home assignment so pen herds
        // behave like any locally-founded herd.
        Entity alphaEntity = level.getEntity(newAlphaId);
        if (alphaEntity instanceof Animal newAlphaAnimal) {
            boolean nomadic = HomeSettingsManager.get(newAlphaAnimal.getType())
                    .map(SpeciesHomeSettings::nomadic)
                    .orElse(false);
            HomeData home = nomadic
                    ? new HomeData(newAlphaAnimal.blockPosition(), true)
                    : new HomeData(newAlphaAnimal.blockPosition());
            newAlphaAnimal.setData(ModAttachments.HOME, home);
            for (UUID id : targetPen.members) {
                Entity e = level.getEntity(id);
                if (id.equals(newAlphaId) || !(e instanceof Animal a)) {
                    continue;
                }
                a.setData(ModAttachments.HOME, home);
            }
        }
        Ethological.LOGGER.debug(
                "Ethological: {} seceded into pen herd {} ({} members, alpha {}) — pen-mates absorbed",
                new Object[]{trigger.getType(), targetPen.id, targetPen.members.size(), newAlphaId});
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
        if (victim.hasData(ModAttachments.HERD_DATA) && (level = victim.level()) instanceof ServerLevel) {
            ServerLevel serverLevel = (ServerLevel)level;
            HerdManager.Herd herd = HerdManager.get(((HerdData)victim.getData(ModAttachments.HERD_DATA)).herdId());
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
        HerdManager.Herd herd = HerdManager.get(((HerdData)animal.getData(ModAttachments.HERD_DATA)).herdId());
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
            if (livingEntity instanceof Animal && (prey = (Animal)livingEntity).hasData(ModAttachments.HERD_DATA) && ((HerdData)prey.getData(ModAttachments.HERD_DATA)).herdId().equals(herd.id)) {
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
        target.setData(ModAttachments.SLEEP_DISTURBANCE,new SleepDisturbance(attacker.getUUID(), now));
        if (target.getData(ModAttachments.SLEEPING)) {
            SleepEvents.wake(target);
        }
    }

    /**
     * Orphan / expired-mother cleanup, plus copy mother's flee disturbance onto the baby.
     * Unloaded mothers are kept until {@link MotherData#followUntilGameTime()} expires.
     */
    private static void tickBabyMother(ServerLevel level, Animal baby, long now) {
        if (!baby.hasData(ModAttachments.MOTHER)) {
            return;
        }
        MotherData motherData = (MotherData)baby.getData(ModAttachments.MOTHER);
        if (!motherData.isActive(now)) {
            baby.removeData(ModAttachments.MOTHER);
            return;
        }
        Entity motherEntity = level.getEntity(motherData.motherId());
        if (motherEntity == null) {
            // Unloaded: keep attachment until followUntil expires.
            return;
        }
        if (!motherEntity.isAlive()) {
            baby.removeData(ModAttachments.MOTHER);
            return;
        }
        if (!(motherEntity instanceof Animal mother)) {
            return;
        }
        if (!mother.hasData(ModAttachments.SLEEP_DISTURBANCE)) {
            return;
        }
        SleepDisturbance disturbance = (SleepDisturbance)mother.getData(ModAttachments.SLEEP_DISTURBANCE);
        if (!baby.hasData(ModAttachments.SLEEP_DISTURBANCE)) {
            baby.setData(ModAttachments.SLEEP_DISTURBANCE, disturbance);
            if (baby.getData(ModAttachments.SLEEPING)) {
                SleepEvents.wake(baby);
            }
        }
    }
}

