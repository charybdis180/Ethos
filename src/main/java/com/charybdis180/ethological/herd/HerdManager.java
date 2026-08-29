package com.charybdis180.ethological.herd;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.Ethological;
import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.herd.MotherData;
import com.charybdis180.ethological.home.HomeData;
import com.charybdis180.ethological.hunger.FoodTargetData;
import com.charybdis180.ethological.thirst.Thirst;
import com.charybdis180.ethological.thirst.WaterTargetData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class HerdManager {
    public static final long SCATTER_TICKS = 60L;
    public static final long GATHER_TICKS = 400L;
    public static final long WARY_TICKS = 1200L;
    public static final float TOO_CLOSE_DISTANCE = 14.0f;
    /** How long a shared follow route stays usable before it must be refreshed. */
    public static final long ROUTE_TTL_TICKS = 240L;
    /** Max distance the alpha may drift from the route end before the route is stale. */
    public static final double ROUTE_STALE_DIST = 24.0D;
    /** Waypoints a member skips ahead when reusing the herd trail. */
    private static final int ROUTE_WAYPOINT_STEP = 6;
    private static final Map<UUID, Herd> HERDS = new HashMap<UUID, Herd>();

    private HerdManager() {
    }

    public static Herd get(UUID herdId) {
        return HERDS.get(herdId);
    }

    public static Herd getOrCreate(UUID herdId, UUID alphaFallback) {
        Herd herd = HERDS.get(herdId);
        if (herd == null) {
            herd = new Herd(herdId, alphaFallback);
            HERDS.put(herdId, herd);
        }
        return herd;
    }

    public static Herd create(UUID alphaId) {
        Herd herd = new Herd(UUID.randomUUID(), alphaId);
        HERDS.put(herd.id, herd);
        return herd;
    }

    public static void clear() {
        HERDS.clear();
    }

    /**
     * Store a validated follow path as the herd's shared trail so members can reuse it
     * instead of scattering onto their own routes.
     */
    public static void shareRoute(ServerLevel level, Herd herd, Path path) {
        if (herd == null || path == null || path.getNodeCount() == 0) {
            return;
        }
        ArrayList<BlockPos> nodes = new ArrayList<BlockPos>(path.getNodeCount());
        for (int i = 0; i < path.getNodeCount(); ++i) {
            nodes.add(path.getNodePos(i));
        }
        herd.followRoute = nodes;
        herd.routeStamp = level.getGameTime();
    }

    /**
     * True when the herd's shared route is fresh and still ends near the alpha, so it
     * can be trusted as a trail toward the alpha's current position.
     */
    public static boolean isRouteUsable(ServerLevel level, Herd herd) {
        if (herd == null || herd.followRoute == null || herd.followRoute.isEmpty()) {
            return false;
        }
        if (level.getGameTime() - herd.routeStamp > ROUTE_TTL_TICKS) {
            return false;
        }
        Entity alpha = herd.alphaId != null ? level.getEntity(herd.alphaId) : null;
        if (alpha == null || !alpha.isAlive()) {
            return false;
        }
        BlockPos end = herd.followRoute.get(herd.followRoute.size() - 1);
        return alpha.blockPosition().distSqr(end) <= ROUTE_STALE_DIST * ROUTE_STALE_DIST;
    }

    /**
     * Next waypoint along the herd's shared trail, a few steps ahead of the mob's current
     * position, so members follow the same route toward the alpha instead of diving into
     * caves. Reachability is validated by the caller's pathfinder.
     */
    @Nullable
    public static BlockPos routeWaypointAhead(ServerLevel level, Herd herd, PathfinderMob mob) {
        if (!HerdManager.isRouteUsable(level, herd)) {
            return null;
        }
        List<BlockPos> route = herd.followRoute;
        int bestIdx = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); ++i) {
            BlockPos waypoint = route.get(i);
            double dx = (double)(waypoint.getX() + 0.5) - mob.getX();
            double dz = (double)(waypoint.getZ() + 0.5) - mob.getZ();
            double d = dx * dx + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }
        int ahead = Math.min(bestIdx + ROUTE_WAYPOINT_STEP, route.size() - 1);
        return route.get(ahead);
    }

    /** Drop herds that currently have no members left (after death cleanup). Called on a slow level tick. */
    public static void evictEmptyHerds(ServerLevel level) {
        HERDS.entrySet().removeIf(entry -> entry.getValue().members.isEmpty());
    }

    public static PanicPhase panicPhaseOf(Animal animal, long gameTime) {
        Level level;
        if (!animal.hasData(ModAttachments.HERD_DATA) || !((level = animal.level()) instanceof ServerLevel)) {
            return PanicPhase.NONE;
        }
        ServerLevel serverLevel = (ServerLevel)level;
        Herd herd = HerdManager.get(((HerdData)animal.getData(ModAttachments.HERD_DATA)).herdId());
        return herd != null ? herd.phaseAt(serverLevel, gameTime, animal) : PanicPhase.NONE;
    }

    /** The herd this animal belongs to, or null when un-herded / unresolvable. */
    public static Herd herdOf(Animal animal) {
        if (!animal.hasData(ModAttachments.HERD_DATA)) {
            return null;
        }
        return HerdManager.get(animal.getData(ModAttachments.HERD_DATA).herdId());
    }

    /**
     * True when any loaded, alive adult herd-mate (excluding {@code self}) is urgently thirsty.
     * Cheap by construction: iterates the herd's member UUID list and reads an attachment per
     * member; callers throttle it so a large herd pays this at most every few seconds.
     */
    public static boolean hasDehydratingMate(Animal self) {
        if (!(self.level() instanceof ServerLevel serverLevel) || !self.hasData(ModAttachments.HERD_DATA)) {
            return false;
        }
        HerdManager.Herd herd = HerdManager.get(self.getData(ModAttachments.HERD_DATA).herdId());
        if (herd == null) {
            return false;
        }
        for (UUID id : herd.members) {
            if (id.equals(self.getUUID())) {
                continue;
            }
            Entity entity = serverLevel.getEntity(id);
            if (entity instanceof Animal mate && mate.isAlive() && !mate.isBaby() && Thirst.isUrgentlyThirsty(mate)) {
                return true;
            }
        }
        return false;
    }

    public static Set<BlockPos> waterTargetsOfHerdMates(Animal self) {        Level level;
        if (!self.hasData(ModAttachments.HERD_DATA) || !((level = self.level()) instanceof ServerLevel)) {
            return Set.of();
        }
        ServerLevel serverLevel = (ServerLevel)level;
        Herd herd = HerdManager.get(((HerdData)self.getData(ModAttachments.HERD_DATA)).herdId());
        if (herd == null) {
            return Set.of();
        }
        Set<BlockPos> claims = herd.waterClaims(serverLevel, serverLevel.getGameTime());
        if (!self.hasData(ModAttachments.WATER_TARGET)) {
            return claims;
        }
        BlockPos mine = ((WaterTargetData)self.getData(ModAttachments.WATER_TARGET)).pos();
        if (!claims.contains(mine)) {
            return claims;
        }
        HashSet<BlockPos> filtered = new HashSet<BlockPos>(claims);
        filtered.remove(mine);
        return filtered;
    }

    public static Set<BlockPos> drinkStandsOfHerdMates(Animal self) {
        Level level;
        if (!self.hasData(ModAttachments.HERD_DATA) || !((level = self.level()) instanceof ServerLevel)) {
            return Set.of();
        }
        ServerLevel serverLevel = (ServerLevel)level;
        Herd herd = HerdManager.get(((HerdData)self.getData(ModAttachments.HERD_DATA)).herdId());
        if (herd == null) {
            return Set.of();
        }
        Set<BlockPos> claims = herd.drinkStandClaims(serverLevel, serverLevel.getGameTime());
        if (claims.contains(self.blockPosition())) {
            HashSet<BlockPos> filtered = new HashSet<BlockPos>(claims);
            filtered.remove(self.blockPosition());
            if (self.hasData(ModAttachments.WATER_TARGET)) {
                filtered.remove(((WaterTargetData)self.getData(ModAttachments.WATER_TARGET)).shore());
            }
            return filtered;
        }
        return claims;
    }

    /** Number of loaded herd mates (excluding {@code self}) whose WATER_TARGET sits on
     *  {@code water}. Cheap O(members) scan used by the drink-lane gate; called only when a
     *  member is already drink-due, so no memoization is warranted. */
    public static int activeDrinkersOnWater(Animal self, BlockPos water) {
        Level level;
        if (!self.hasData(ModAttachments.HERD_DATA) || !((level = self.level()) instanceof ServerLevel)) {
            return 0;
        }
        ServerLevel serverLevel = (ServerLevel)level;
        Herd herd = HerdManager.get(((HerdData)self.getData(ModAttachments.HERD_DATA)).herdId());
        if (herd == null) {
            return 0;
        }
        int count = 0;
        for (UUID memberId : herd.members) {
            if (memberId.equals(self.getUUID())) {
                continue;
            }
            Entity member = serverLevel.getEntity(memberId);
            if (member instanceof Animal mate && mate.hasData(ModAttachments.WATER_TARGET)
                    && ((WaterTargetData)mate.getData(ModAttachments.WATER_TARGET)).pos().equals(water)) {
                ++count;
            }
        }
        return count;
    }

    public static void propagateHomeFromAlpha(Animal alpha, Optional<HomeData> home) {
        if (!alpha.hasData(ModAttachments.HERD_DATA) || !((HerdData)alpha.getData(ModAttachments.HERD_DATA)).alpha()) {
            return;
        }
        Level level = alpha.level();
        if (!(level instanceof ServerLevel)) {
            return;
        }
        ServerLevel serverLevel = (ServerLevel)level;
        Herd herd = HerdManager.get(((HerdData)alpha.getData(ModAttachments.HERD_DATA)).herdId());
        if (herd == null) {
            return;
        }
        for (UUID memberId : herd.members) {
            Entity member;
            if (memberId.equals(alpha.getUUID()) || !((member = serverLevel.getEntity(memberId)) instanceof Animal)) continue;
            Animal mate = (Animal)member;
            if (home.isPresent()) {
                mate.setData(ModAttachments.HOME,home.get());
                continue;
            }
            mate.removeData(ModAttachments.HOME);
        }
    }

    public static Set<BlockPos> foodTargetsOfHerdMates(Animal self) {
        Level level;
        if (!self.hasData(ModAttachments.HERD_DATA) || !((level = self.level()) instanceof ServerLevel)) {
            return Set.of();
        }
        ServerLevel serverLevel = (ServerLevel)level;
        Herd herd = HerdManager.get(((HerdData)self.getData(ModAttachments.HERD_DATA)).herdId());
        if (herd == null) {
            return Set.of();
        }
        Set<BlockPos> claims = herd.foodClaims(serverLevel, serverLevel.getGameTime());
        if (!self.hasData(ModAttachments.FOOD_TARGET)) {
            return claims;
        }
        BlockPos mine = ((FoodTargetData)self.getData(ModAttachments.FOOD_TARGET)).pos();
        if (!claims.contains(mine) && !claims.contains(mine.above())) {
            return claims;
        }
        HashSet<BlockPos> filtered = new HashSet<BlockPos>(claims);
        filtered.remove(mine);
        filtered.remove(mine.above());
        return filtered;
    }

    /** Snapshot of the positions of herd mates that currently have a food target; used to hoist per-candidate herd iteration out of block scans. */
    public static List<Vec3> positionsOfFeedingHerdMates(Animal self) {
        Level level;
        if (!self.hasData(ModAttachments.HERD_DATA) || !((level = self.level()) instanceof ServerLevel)) {
            return List.of();
        }
        ServerLevel serverLevel = (ServerLevel)level;
        Herd herd = HerdManager.get(((HerdData)self.getData(ModAttachments.HERD_DATA)).herdId());
        if (herd == null) {
            return List.of();
        }
        ArrayList<Vec3> positions = new ArrayList<Vec3>();
        for (UUID memberId : herd.members) {
            Animal mate;
            Entity member;
            if (memberId.equals(self.getUUID()) || !((member = serverLevel.getEntity(memberId)) instanceof Animal) || !(mate = (Animal)member).hasData(ModAttachments.FOOD_TARGET)) continue;
            positions.add(mate.position());
        }
        return positions;
    }

    public static boolean isMateTooCloseToStand(Animal self, BlockPos stand, double minDist) {
        Level level;
        if (!self.hasData(ModAttachments.HERD_DATA) || !((level = self.level()) instanceof ServerLevel)) {
            return false;
        }
        ServerLevel serverLevel = (ServerLevel)level;
        Herd herd = HerdManager.get(((HerdData)self.getData(ModAttachments.HERD_DATA)).herdId());
        if (herd == null) {
            return false;
        }
        double minSqr = minDist * minDist;
        Vec3 standCenter = Vec3.atCenterOf(stand);
        for (UUID memberId : herd.members) {
            Animal mate;
            Entity member;
            if (memberId.equals(self.getUUID()) || !((member = serverLevel.getEntity(memberId)) instanceof Animal) || !(mate = (Animal)member).hasData(ModAttachments.FOOD_TARGET) || !(mate.distanceToSqr(standCenter) < minSqr)) continue;
            return true;
        }
        return false;
    }

    public static int adultCount(ServerLevel level, Herd herd) {
        long now = level.getGameTime();
        if (herd.adultCountStamp >= now - Herd.ADULT_COUNT_MEMO_TICKS && herd.cachedAdultCount >= 0) {
            return herd.cachedAdultCount;
        }
        int count = 0;
        for (UUID id : herd.members) {
            Animal animal;
            Entity entity = level.getEntity(id);
            // Unloaded members count as adults for capacity (conservative); loaded babies do not.
            if (entity instanceof Animal && (animal = (Animal)entity).isBaby()) continue;
            ++count;
        }
        herd.cachedAdultCount = count;
        herd.adultCountStamp = now;
        return count;
    }

    /** Distance of the farthest loaded member from the alpha, used to decide whether
     * a migrating alpha should hold for the herd. Only loaded members count; unloaded
     * members are skipped so a distant unloaded chunk cannot freeze the whole herd. */
    public static double maxMemberDistance(ServerLevel level, Herd herd, Animal alpha) {
        if (herd == null || alpha == null) {
            return 0.0;
        }
        double max = 0.0;
        for (UUID memberId : herd.members) {
            Entity member = level.getEntity(memberId);
            if (member == null || member == alpha) {
                continue;
            }
            max = Math.max(max, member.distanceTo(alpha));
        }
        return max;
    }

    /** True when the herd's recorded alpha is currently loaded and still a valid member. */
    public static boolean isAlphaResolvable(ServerLevel level, Herd herd) {
        if (herd.alphaId == null) {
            return false;
        }
        Entity alpha = level.getEntity(herd.alphaId);
        if (!(alpha instanceof Animal)) {
            return false;
        }
        Animal alphaAnimal = (Animal)alpha;
        if (!alphaAnimal.isAlive()
                || !alphaAnimal.hasData(ModAttachments.HERD_DATA)
                || !((HerdData)alphaAnimal.getData(ModAttachments.HERD_DATA)).herdId().equals(herd.id)) {
            return false;
        }
        // A baby holding the alpha seat is not a valid alpha once any adult is in the herd.
        if (alphaAnimal.isBaby()) {
            for (UUID id : herd.members) {
                Entity member = level.getEntity(id);
                if (member instanceof Animal other && other.isAlive() && !other.isBaby()) {
                    return false;
                }
            }
        }
        return true;
    }

    public static void onMemberDied(ServerLevel level, Animal dead) {
        if (!dead.hasData(ModAttachments.HERD_DATA)) {
            return;
        }
        Herd herd = HerdManager.get(((HerdData)dead.getData(ModAttachments.HERD_DATA)).herdId());
        if (herd == null) {
            return;
        }
        herd.members.remove(dead.getUUID());
        if (herd.members.isEmpty()) {
            HERDS.remove(herd.id);
            return;
        }
        if (dead.getUUID().equals(herd.alphaId) || !HerdManager.isAlphaResolvable(level, herd)) {
            herd.alphaId = HerdManager.electAlpha(level, herd.members);
            Ethological.LOGGER.debug("Ethological: herd {} elected a new alpha {} after death", herd.id, herd.alphaId);
            HerdManager.syncAlphaFlags(level, herd);
        }
    }

    /**
     * Drop members that are loaded but no longer part of this herd (wrong attachment / dead).
     * Unloaded members are kept — they rejoin when their chunk loads again.
     */
    public static void reconcile(ServerLevel level, Herd herd, int maxSize) {
        // Single pass: drop loaded-but-invalid members while counting loaded adults and unloaded members.
        int loadedAdults = 0;
        int unloaded = 0;
        boolean membersRemoved = false;
        Iterator<UUID> it = herd.members.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Entity entity = level.getEntity(id);
            if (entity == null) {
                ++unloaded; // unloaded — keep; counts as adult-capable for capacity
                continue;
            }
            if (!(entity instanceof Animal) || !entity.isAlive()) {
                it.remove();
                membersRemoved = true;
                continue;
            }
            Animal animal = (Animal)entity;
            if (!animal.hasData(ModAttachments.HERD_DATA)
                    || !((HerdData)animal.getData(ModAttachments.HERD_DATA)).herdId().equals(herd.id)) {
                it.remove();
                membersRemoved = true;
                continue;
            }
            if (!animal.isBaby()) {
                ++loadedAdults;
            }
        }
        if (herd.members.isEmpty()) {
            HERDS.remove(herd.id);
            return;
        }
        Entity alpha = herd.alphaId != null ? level.getEntity(herd.alphaId) : null;
        // An unloaded alpha is still valid; only re-elect when the alpha is loaded-and-invalid, or missing entirely with a loaded alternative.
        boolean alphaLoadedInvalid = false;
        if (alpha instanceof Animal) {
            Animal alphaAnimal = (Animal)alpha;
            alphaLoadedInvalid = !alphaAnimal.isAlive()
                    || !alphaAnimal.hasData(ModAttachments.HERD_DATA)
                    || !((HerdData)alphaAnimal.getData(ModAttachments.HERD_DATA)).herdId().equals(herd.id)
                    || alphaAnimal.isBaby() && loadedAdults > 0;
        }
        boolean alphaMissing = herd.alphaId == null || alpha == null && loadedAdults > 0;
        boolean alphaValid = !alphaLoadedInvalid && !alphaMissing && herd.alphaId != null;
        boolean alphaChanged = false;
        if (!alphaValid) {
            UUID elected = HerdManager.electAlpha(level, herd.members);
            if (elected != null) {
                UUID oldAlphaId = herd.alphaId;
                herd.alphaId = elected;
                alphaChanged = true;
                Ethological.LOGGER.debug("Ethological: herd {} elected a new alpha {}",herd.id,herd.alphaId);
            }
        }
        // Flags only drift when the alpha or membership changes — joining members are always written with the
        // correct flag, so skip the full re-read in the common no-op case.
        if (alphaChanged || membersRemoved) {
            HerdManager.syncAlphaFlags(level, herd);
        }
        // Pen herds (formed inside an enclosure by escape-driven secession) are exempt from
        // the over-cap split: the whole point of a pen herd is that a large fenced population
        // stays cohesive. The flag is cleared by HerdEvents when the enclosure opens.
        if (loadedAdults + unloaded > maxSize && !herd.penHerd) {
            HerdManager.split(level, herd, maxSize);
        }
    }

    /**
     * Prefer a loaded adult. Never elect a loaded baby while any non-baby candidate exists
     * (including unloaded member UUIDs, which are assumed adult-capable). Baby alphas are
     * only a last resort for a baby-only member set.
     */
    public static UUID electAlpha(ServerLevel level, Set<UUID> memberIds) {
        ArrayList<Animal> adults = new ArrayList<Animal>();
        ArrayList<UUID> unloadedOrUnknown = new ArrayList<UUID>();
        ArrayList<Animal> babies = new ArrayList<Animal>();
        for (UUID id : memberIds) {
            Entity entity = level.getEntity(id);
            if (entity == null) {
                unloadedOrUnknown.add(id);
                continue;
            }
            if (!(entity instanceof Animal) || !entity.isAlive()) continue;
            Animal animal = (Animal)entity;
            if (animal.isBaby()) {
                babies.add(animal);
                continue;
            }
            adults.add(animal);
        }
        if (!adults.isEmpty()) {
            return adults.stream().max(Comparator
                    .comparingDouble((Animal a) -> a.getHealth())
                    .thenComparingInt(a -> a.tickCount)
                    .thenComparing(a -> a.getUUID())).orElseThrow().getUUID();
        }
        // Prefer an unloaded member over promoting a baby — unloaded packmates are usually adults.
        if (!unloadedOrUnknown.isEmpty()) {
            return unloadedOrUnknown.stream().min(Comparator.naturalOrder()).orElseThrow();
        }
        if (!babies.isEmpty()) {
            return babies.stream().max(Comparator.comparingInt(AgeableMob::getAge).thenComparing(a -> a.getUUID())).orElseThrow().getUUID();
        }
        return memberIds.stream().min(Comparator.naturalOrder()).orElse(null);
    }

    public static void syncAlphaFlags(ServerLevel level, Herd herd) {
        for (UUID id : herd.members) {
            Animal animal;
            Entity entity = level.getEntity(id);
            if (!(entity instanceof Animal) || !(animal = (Animal)entity).hasData(ModAttachments.HERD_DATA)) continue;
            // Babies must never carry the alpha flag, even if electAlpha had no adult choice.
            boolean shouldBeAlpha = id.equals(herd.alphaId) && !animal.isBaby();
            if (((HerdData)animal.getData(ModAttachments.HERD_DATA)).alpha() == shouldBeAlpha) continue;
            animal.setData(ModAttachments.HERD_DATA,new HerdData(herd.id, shouldBeAlpha));
        }
    }

    /** Detaches one animal from its herd: clears the attachment and removes the membership
     *  entry. Used by escape-driven secession before the animal joins/forms its pen herd. */
    public static void removeFromHerd(ServerLevel level, Animal animal, Herd herd) {
        herd.members.remove(animal.getUUID());
        animal.removeData(ModAttachments.HERD_DATA);
        if (animal.getUUID().equals(herd.alphaId)) {
            herd.alphaId = HerdManager.electAlpha(level, herd.members);
            HerdManager.syncAlphaFlags(level, herd);
        }
    }

    public static void mergeInto(ServerLevel level, Herd survivor, Herd absorbed, int room) {
        Animal animal;
        Entity entity;
        Entity survivorAlpha;
        if (room <= 0 || survivor.id.equals(absorbed.id)) {
            return;
        }
        if (survivor.isPanicking() || absorbed.isPanicking()) {
            return;
        }
        Entity entity2 = survivorAlpha = survivor.alphaId != null ? level.getEntity(survivor.alphaId) : null;
        if (survivorAlpha == null) {
            return;
        }
        List<UUID> candidates = absorbed.members.stream().filter(id -> {
            Entity memberEntity = level.getEntity(id);
            return !(memberEntity instanceof Animal memberAnimal) || !memberAnimal.isBaby();
        }).sorted(Comparator.comparingDouble(id -> {
            Entity memberEntity = level.getEntity(id);
            return memberEntity != null ? memberEntity.distanceToSqr(survivorAlpha) : Double.MAX_VALUE;
        })).limit(room).toList();
        if (candidates.isEmpty()) {
            return;
        }
        LinkedHashSet<UUID> moving = new LinkedHashSet<UUID>(candidates);
        for (UUID id2 : absorbed.members) {
            if (moving.contains(id2) || !((entity = level.getEntity(id2)) instanceof Animal) || !(animal = (Animal)entity).isBaby() || !animal.hasData(ModAttachments.MOTHER) || !moving.contains(((MotherData)animal.getData(ModAttachments.MOTHER)).motherId())) continue;
            moving.add(id2);
        }
        for (UUID id2 : moving) {
            absorbed.members.remove(id2);
            survivor.members.add(id2);
            entity = level.getEntity(id2);
            if (!(entity instanceof Animal)) continue;
            animal = (Animal)entity;
            animal.setData(ModAttachments.HERD_DATA,new HerdData(survivor.id, false));
        }
        if (absorbed.members.isEmpty()) {
            HERDS.remove(absorbed.id);
        } else {
            absorbed.alphaId = HerdManager.electAlpha(level, absorbed.members);
            HerdManager.syncAlphaFlags(level, absorbed);
        }
        UUID oldSurvivorAlpha = survivor.alphaId;
        // Preserve the survivor's alpha across a merge. The old code re-elected on EVERY
        // merge via electAlpha, which prefers max health — so a starved/hurt alpha was
        // demoted, and syncAlphaFlags persisted the demotion. Across world reloads (where
        // herds re-merge while clustered at spawn) this kept flipping the same two cows
        // between alpha and member. Only re-elect when the alpha is actually invalid:
        // dead / wrong herd / baby-with-adults. Unloaded alphas are untouched (the caller
        // already bailed earlier when the survivor's alpha entity was unloaded).
        if (!HerdManager.isAlphaResolvable(level, survivor)) {
            survivor.alphaId = HerdManager.electAlpha(level, survivor.members);
        }
        HerdManager.syncAlphaFlags(level, survivor);
        Ethological.LOGGER.debug("Ethological: herd {} absorbed {} members from {} (now {} / leftover {})", new Object[]{survivor.id, moving.size(), absorbed.id, survivor.members.size(), absorbed.members.isEmpty() ? 0 : absorbed.members.size()});
    }

    private static void split(ServerLevel level, Herd herd, int maxSize) {
        Animal animal;
        Entity entity;
        Entity alpha = level.getEntity(herd.alphaId);
        if (alpha == null || maxSize < 1 || HerdManager.adultCount(level, herd) <= maxSize) {
            return;
        }
        List<UUID> sorted = herd.members.stream().sorted(Comparator.comparingDouble(id -> {
            Entity memberEntity = level.getEntity(id);
            return memberEntity != null ? memberEntity.distanceToSqr(alpha) : Double.MAX_VALUE;
        })).toList();
        ArrayList<UUID> departing = new ArrayList<UUID>();
        LinkedHashSet<UUID> departingSet = new LinkedHashSet<UUID>();
        int keptAdults = 0;
        for (UUID id2 : sorted) {
            entity = level.getEntity(id2);
            if (entity instanceof Animal && (animal = (Animal)entity).isBaby()) continue;
            if (keptAdults < maxSize) {
                ++keptAdults;
                continue;
            }
            departing.add(id2);
            departingSet.add(id2);
        }
        if (departing.isEmpty()) {
            return;
        }
        for (UUID id2 : sorted) {
            if (departingSet.contains(id2) || !((entity = level.getEntity(id2)) instanceof Animal) || !(animal = (Animal)entity).isBaby() || !animal.hasData(ModAttachments.MOTHER) || !departingSet.contains(((MotherData)animal.getData(ModAttachments.MOTHER)).motherId())) continue;
            departing.add(id2);
            departingSet.add(id2);
        }
        UUID newAlphaId = HerdManager.electAlpha(level, departingSet);
        Herd newHerd = HerdManager.create(newAlphaId);
        newHerd.members.addAll(departing);
        herd.members.removeAll(departing);
        for (UUID id3 : departing) {
            Entity entity2 = level.getEntity(id3);
            if (!(entity2 instanceof Animal)) continue;
            Animal animal2 = (Animal)entity2;
            animal2.setData(ModAttachments.HERD_DATA,new HerdData(newHerd.id, id3.equals(newAlphaId)));
        }
        HerdManager.syncAlphaFlags(level, herd);
        Ethological.LOGGER.debug("Ethological: herd {} peeled to {} \u2014 new herd {} with {} members", new Object[]{herd.id, herd.members.size(), newHerd.id, newHerd.members.size()});
    }

    public static final class Herd {
        public final UUID id;
        public final Set<UUID> members = new LinkedHashSet<UUID>();
        public UUID alphaId;
        /** True when this herd was formed/marked inside an enclosure (escape-driven secession
         *  or a penned alpha). Pen herds are exempt from the over-cap split and cap-gated
         *  joins/merges; HerdEvents clears the flag once FenceDetection reports the pen open. */
        public boolean penHerd;
        /** Shared validated follow route (surface stands) toward the alpha; null when unused. */
        public List<BlockPos> followRoute;
        /** Game time when {@link #followRoute} was last shared. */
        public long routeStamp;
        private UUID threatId;
        private long panicStartGameTime;
        private boolean gatheredEarly;
        private final Map<UUID, Long> memberScatterStart = new HashMap<UUID, Long>();
        private final Map<UUID, CachedPhase> phaseCache = new HashMap<UUID, CachedPhase>();
        /** Shared water source found by a herd-mate, so a thirsty herd pays ~1 scan
         * plus per-member shore/path checks instead of a full ring scan each. */
        private BlockPos lastWaterSurface;
        private long lastWaterGameTime = Long.MIN_VALUE;
        /** Shared dry escape stand found by a herd-mate, so separated members follow the
         * same proven route instead of each probing the full circle from the pit floor. */
        private BlockPos lastEscapeStand;
        private long lastEscapeGameTime = Long.MIN_VALUE;
        /** Memoized adult count (loaded adult members); advisory for capacity, so the 100-tick
         * staleness window is invisible (herd maintenance only re-reads it every herd tick). */
        private int cachedAdultCount = -1;
        private long adultCountStamp = Long.MIN_VALUE;
        private static final long ADULT_COUNT_MEMO_TICKS = 100L;
        /** Memoized herd-mate claim sets; advisory spacing, so ~1s staleness is invisible. */
        private Set<BlockPos> cachedWaterClaims;
        private long waterClaimsStamp = Long.MIN_VALUE;
        private Set<BlockPos> cachedDrinkStandClaims;
        private long drinkStandClaimsStamp = Long.MIN_VALUE;
        private Set<BlockPos> cachedFoodClaims;
        private long foodClaimsStamp = Long.MIN_VALUE;
        private static final long CLAIM_MEMO_TICKS = 20L;
        /** Sleep spots each member is actively settling toward; used so settling members
         *  avoid columns already being walked to by herd-mates. Claims are stamped with the
         *  game time they were written; the getter drops entries older than
         *  {@link #SLEEP_CLAIM_TTL_TICKS} so a crashed/interrupted settle cannot block a good
         *  spot indefinitely, and every stop() still clears the owner's entry immediately. */
        private final Map<UUID, SleepClaim> sleepSpotClaims = new HashMap<UUID, SleepClaim>();
        private static final long SLEEP_CLAIM_TTL_TICKS = 600L;

        /** A settle target plus the game time it was claimed, for TTL enforcement. */
        private record SleepClaim(BlockPos pos, long stamp) {
        }

        /** Freshness windows for the shared water result. */
        public static final long WATER_CACHE_TTL_TICKS = 600L;
        public static final long WATER_CACHE_FRESH_TICKS = 20L;

        /** @return the cached water share for {@code now}, or null when cold/stale. */
        public BlockPos waterShare(long now) {
            if (this.lastWaterSurface == null || now - this.lastWaterGameTime > WATER_CACHE_TTL_TICKS) {
                return null;
            }
            return this.lastWaterSurface;
        }

        /** @return true when a herd-mate refreshed the water share very recently. */
        public boolean waterShareFresh(long now) {
            return this.lastWaterSurface != null && now - this.lastWaterGameTime <= WATER_CACHE_FRESH_TICKS;
        }

        public void setWaterShare(BlockPos surface, long now) {
            this.lastWaterSurface = surface == null ? null : surface.immutable();
            this.lastWaterGameTime = now;
        }

        /** Freshness windows for the shared escape result. Escapes are rarer and slower to
         * re-derive (a full 64-probe scan), so the share lasts longer than the water share,
         * but not so long that a migrating herd keeps walking back to a stand the alpha has
         * long since left behind (that produced a herd pile-up on a stale stand). */
        public static final long ESCAPE_CACHE_TTL_TICKS = 600L;
        public static final long ESCAPE_CACHE_FRESH_TICKS = 20L;

        /** @return the cached escape stand for {@code now}, or null when cold/stale. */
        public BlockPos escapeShare(long now) {
            if (this.lastEscapeStand == null || now - this.lastEscapeGameTime > ESCAPE_CACHE_TTL_TICKS) {
                return null;
            }
            return this.lastEscapeStand;
        }

        /** @return true when a herd-mate refreshed the escape share very recently. */
        public boolean escapeShareFresh(long now) {
            return this.lastEscapeStand != null && now - this.lastEscapeGameTime <= ESCAPE_CACHE_FRESH_TICKS;
        }

        public void setEscapeShare(BlockPos stand, long now) {
            // null is the "cleared" sentinel (clearSharedEscape passes it); the getters
            // treat a null stand as no share, so don't dereference it here.
            this.lastEscapeStand = stand == null ? null : stand.immutable();
            this.lastEscapeGameTime = now;
        }

        /** Union of every member's water target, memoized ~20t; callers filter self. */
        private Set<BlockPos> waterClaims(ServerLevel level, long now) {
            if (this.cachedWaterClaims == null || now - this.waterClaimsStamp >= CLAIM_MEMO_TICKS) {
                HashSet<BlockPos> claims = new HashSet<BlockPos>();
                for (UUID memberId : this.members) {
                    Entity member = level.getEntity(memberId);
                    if (member instanceof Animal mate && mate.hasData(ModAttachments.WATER_TARGET)) {
                        claims.add(((WaterTargetData)mate.getData(ModAttachments.WATER_TARGET)).pos());
                    }
                }
                this.cachedWaterClaims = java.util.Set.copyOf(claims);
                this.waterClaimsStamp = now;
            }
            return this.cachedWaterClaims;
        }

        /** Union of every member's drink stand (position + shore), memoized ~20t. */
        private Set<BlockPos> drinkStandClaims(ServerLevel level, long now) {
            if (this.cachedDrinkStandClaims == null || now - this.drinkStandClaimsStamp >= CLAIM_MEMO_TICKS) {
                HashSet<BlockPos> claims = new HashSet<BlockPos>();
                for (UUID memberId : this.members) {
                    Entity member = level.getEntity(memberId);
                    if (!(member instanceof Animal mate)) {
                        continue;
                    }
                    claims.add(mate.blockPosition());
                    if (mate.hasData(ModAttachments.WATER_TARGET)) {
                        claims.add(((WaterTargetData)mate.getData(ModAttachments.WATER_TARGET)).shore());
                    }
                }
                this.cachedDrinkStandClaims = java.util.Set.copyOf(claims);
                this.drinkStandClaimsStamp = now;
            }
            return this.cachedDrinkStandClaims;
        }

        /** Union of every member's food target (stand + above), memoized ~20t. */
        private Set<BlockPos> foodClaims(ServerLevel level, long now) {
            if (this.cachedFoodClaims == null || now - this.foodClaimsStamp >= CLAIM_MEMO_TICKS) {
                HashSet<BlockPos> claims = new HashSet<BlockPos>();
                for (UUID memberId : this.members) {
                    Entity member = level.getEntity(memberId);
                    if (member instanceof Animal mate && mate.hasData(ModAttachments.FOOD_TARGET)) {
                        BlockPos stand = ((FoodTargetData)mate.getData(ModAttachments.FOOD_TARGET)).pos();
                        claims.add(stand);
                        claims.add(stand.above());
                    }
                }
                this.cachedFoodClaims = java.util.Set.copyOf(claims);
                this.foodClaimsStamp = now;
            }
            return this.cachedFoodClaims;
        }

        /** Records the spot a member is settling toward (or clears it when {@code pos} is null). */
        public void setSleepSpotClaim(UUID memberId, BlockPos pos, long now) {
            if (pos == null) {
                this.sleepSpotClaims.remove(memberId);
                return;
            }
            this.sleepSpotClaims.put(memberId, new SleepClaim(pos.immutable(), now));
        }

        /** Removes a member's pending sleep spot claim (called when the settling walk ends). */
        public void clearSleepSpotClaim(UUID memberId) {
            this.sleepSpotClaims.remove(memberId);
        }

        /** Spots other members are actively settling toward; stale claims are dropped. */
        public Set<BlockPos> sleepSpotClaimsOf(Animal self, long now) {
            HashSet<BlockPos> spots = new HashSet<BlockPos>();
            Iterator<Map.Entry<UUID, SleepClaim>> it = this.sleepSpotClaims.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, SleepClaim> entry = it.next();
                SleepClaim claim = entry.getValue();
                if (claim == null || now - claim.stamp() > SLEEP_CLAIM_TTL_TICKS) {
                    it.remove();
                    continue;
                }
                if (entry.getKey().equals(self.getUUID())) {
                    continue;
                }
                spots.add(claim.pos());
            }
            return spots;
        }

        Herd(UUID id, UUID alphaId) {
            this.id = id;
            this.alphaId = alphaId;
        }

        public void startPanic(UUID threatId, long gameTime) {
            this.threatId = threatId;
            this.panicStartGameTime = gameTime;
            this.gatheredEarly = false;
            this.memberScatterStart.clear();
            this.phaseCache.clear();
            this.followRoute = null;
            this.sleepSpotClaims.clear();
        }

        public void clearPanic() {
            this.threatId = null;
            this.gatheredEarly = false;
            this.memberScatterStart.clear();
            this.phaseCache.clear();
            this.followRoute = null;
            this.sleepSpotClaims.clear();
        }

        public boolean isPanicking() {
            return this.threatId != null;
        }

        public void startMemberScatter(UUID memberId, long gameTime) {
            this.memberScatterStart.put(memberId, gameTime);
            this.phaseCache.clear();
        }

        public void markGathered() {
            this.gatheredEarly = true;
            this.phaseCache.clear();
        }

        public UUID threatId() {
            return this.threatId;
        }

        public PanicPhase phaseAt(ServerLevel level, long gameTime, Animal forAnimal) {
            CachedPhase cached = this.phaseCache.get(forAnimal.getUUID());
            if (cached != null && cached.gameTime() == gameTime) {
                return cached.phase();
            }
            PanicPhase phase;
            if (this.threatId == null) {
                phase = PanicPhase.NONE;
            } else {
                Long memberScatter = this.memberScatterStart.get(forAnimal.getUUID());
                if (memberScatter != null && gameTime - memberScatter < 60L) {
                    phase = PanicPhase.SCATTER;
                } else {
                    if (memberScatter != null) {
                        this.memberScatterStart.remove(forAnimal.getUUID());
                    }
                    phase = this.computePanicPhase(level, gameTime, forAnimal);
                }
            }
            this.phaseCache.put(forAnimal.getUUID(), new CachedPhase(gameTime, phase));
            return phase;
        }

        private PanicPhase computePanicPhase(ServerLevel level, long gameTime, Animal forAnimal) {
            long elapsed;
            if ((elapsed = gameTime - this.panicStartGameTime) >= 1200L && !this.threatInRange(level)) {
                this.clearPanic();
                return PanicPhase.NONE;
            }
            long scatterTicks = Herd.scatterTicksFor(forAnimal.getUUID());
            if (elapsed < scatterTicks) {
                return PanicPhase.SCATTER;
            }
            boolean nearMe = this.threatNear(level, forAnimal);
            if (!this.gatheredEarly && elapsed < scatterTicks + 400L && !nearMe) {
                return PanicPhase.GATHER;
            }
            if (elapsed >= 1200L && !nearMe) {
                return PanicPhase.NONE;
            }
            return PanicPhase.WARY;
        }

        private boolean threatNear(ServerLevel level, Animal member) {
            if (this.threatId == null) {
                return false;
            }
            Entity threat = level.getEntity(this.threatId);
            return threat instanceof LivingEntity && threat.isAlive() && member.distanceTo(threat) <= 36.0f;
        }

        private static long scatterTicksFor(UUID memberId) {
            return 40L + (long)Math.floorMod(memberId.hashCode(), 41);
        }

        private boolean threatInRange(ServerLevel level) {
            if (this.threatId == null) {
                return false;
            }
            Entity threat = level.getEntity(this.threatId);
            if (!(threat instanceof LivingEntity) || !threat.isAlive()) {
                return false;
            }
            for (UUID memberId : this.members) {
                Entity member = level.getEntity(memberId);
                if (member == null || !(member.distanceTo(threat) <= 36.0f)) continue;
                return true;
            }
            return false;
        }
    }

    /** One-tick memo of {@link Herd#phaseAt} so repeated per-goal phase queries in the same tick don't re-scan the herd. */
    private record CachedPhase(long gameTime, PanicPhase phase) {
    }

    public static enum PanicPhase {
        NONE,
        SCATTER,
        GATHER,
        WARY;

    }
}

