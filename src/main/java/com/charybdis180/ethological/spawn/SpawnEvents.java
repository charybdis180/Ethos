package com.charybdis180.ethological.spawn;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.Ethological;
import com.charybdis180.ethological.config.EthologicalConfig;
import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.HerdSettingsManager;
import com.charybdis180.ethological.herd.MotherData;
import com.charybdis180.ethological.herd.SpeciesHerdSettings;
import com.charybdis180.ethological.home.HomeSettingsManager;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.hunger.GrazePatches;
import com.charybdis180.ethological.hunger.HungerSettingsManager;
import com.charybdis180.ethological.hunger.SpeciesHungerSettings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.living.SpawnClusterSizeEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

public final class SpawnEvents {
    /** Adults may scatter this far from the pack leader. */
    private static final int ADULT_SCATTER_RADIUS = 8;
    /** Babies spawn this close to their chosen mother so the link stays loaded with her. */
    private static final int BABY_SCATTER_RADIUS = 3;
    private static final int PACK_POSITION_ATTEMPTS = 16;
    /** Cap extras spawned per server tick so pack expansion cannot stall chunk loading. */
    private static final int EXTRAS_PER_TICK = 2;

    /**
     * Pack extras must not be added during chunk-gen FinalizeSpawn — that mutates the
     * entity list mid-iteration (CME). Leaders record intent here; expansion is drained
     * on the server tick (not via server.execute / join-time work).
     */
    private static final Map<UUID, PendingPack> PENDING_PACKS = new ConcurrentHashMap<>();
    private static final ArrayDeque<PackExpansion> EXPANSION_QUEUE = new ArrayDeque<>();

    private static final ThreadLocal<Boolean> MANUAL_EXTRA = ThreadLocal.withInitial(() -> false);

    private SpawnEvents() {
    }

    /**
     * Ethological owns natural/chunk-gen pack composition for supported species.
     * Vanilla pack follow-ups (non-null group data) are always cancelled; the leader
     * schedules a pending pack completed on the server tick queue.
     */
    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        MobSpawnType spawnType = event.getSpawnType();
        if (spawnType != MobSpawnType.NATURAL && spawnType != MobSpawnType.CHUNK_GENERATION) {
            return;
        }
        if (!(event.getEntity() instanceof Animal animal)) {
            return;
        }
        Optional<SpeciesHerdSettings> settingsOpt = HerdSettingsManager.get(animal.getType());
        if (settingsOpt.isEmpty()) {
            return;
        }

        // Our own extras: skip pack-leader logic (caller already configured age/mother).
        if (Boolean.TRUE.equals(MANUAL_EXTRA.get())) {
            return;
        }

        SpawnGroupData data = event.getSpawnData();
        // Vanilla pack continuation — always block. Ethological replaces these members.
        if (data != null) {
            event.setSpawnCancelled(true);
            return;
        }

        SpeciesHerdSettings settings = settingsOpt.get();
        RandomSource random = event.getLevel().getRandom();
        int packSize = rollPackSize(settings, random);
        int babyCount = Math.min(rollBabyCount(settings, random), Math.max(0, packSize - 1));
        animal.setBaby(false);
        PENDING_PACKS.put(animal.getUUID(), new PendingPack(packSize, babyCount));
    }

    /**
     * Ethological fully owns pack size, so vanilla only needs to place the leader.
     */
    @SubscribeEvent
    public static void onSpawnClusterSize(SpawnClusterSizeEvent event) {
        HerdSettingsManager.get(event.getEntity().getType())
                .ifPresent(s -> event.setSize(1));
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getEntity() instanceof Animal leader)) {
            return;
        }
        PendingPack pending = PENDING_PACKS.remove(leader.getUUID());
        if (pending == null) {
            return;
        }
        if (HerdSettingsManager.get(leader.getType()).isEmpty() || !leader.isAlive()) {
            return;
        }

        int adultExtras = Math.max(0, pending.packSize - 1 - pending.babyCount);
        PackExpansion expansion = new PackExpansion(level, leader, adultExtras, pending.babyCount);
        expansion.members.add(leader);
        synchronized (EXPANSION_QUEUE) {
            EXPANSION_QUEUE.addLast(expansion);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        PackExpansion active;
        synchronized (EXPANSION_QUEUE) {
            active = EXPANSION_QUEUE.peekFirst();
        }
        if (active == null) {
            return;
        }
        if (advanceExpansion(active)) {
            synchronized (EXPANSION_QUEUE) {
                if (EXPANSION_QUEUE.peekFirst() == active) {
                    EXPANSION_QUEUE.removeFirst();
                }
            }
        }
    }

    /**
     * Spawns up to {@link #EXTRAS_PER_TICK} members for the active expansion.
     * @return true when the pack is finished (or abandoned)
     */
    private static boolean advanceExpansion(PackExpansion expansion) {
        Animal leader = expansion.leader;
        ServerLevel level = expansion.level;
        if (!leader.isAlive() || leader.isRemoved() || leader.level() != level) {
            return true;
        }

        DifficultyInstance difficulty = level.getCurrentDifficultyAt(leader.blockPosition());
        RandomSource random = level.getRandom();
        long now = level.getGameTime();
        long followTicks = EthologicalConfig.CONFIG.comfort.motherFollowTicks.get();
        int waterRadius = HomeSettingsManager.get(leader.getType())
                .map(s -> Mth.clamp(s.waterSearchRadius(), 1, 32))
                .orElse(32);
        int relocateRadius = HomeSettingsManager.get(leader.getType())
                .map(s -> Mth.clamp(s.waterSearchRadius(), 1, 128))
                .orElse(128);
        // The chunk-generation water gate cannot probe mid-generation chunks (a blocking read
        // would deadlock the server), so a CHUNK_GENERATION leader can land anywhere. If its
        // own stand has no water nearby, relocate the whole pack to the nearest shoreline
        // before scattering extras — otherwise every trySpawnNear probe fails the same water
        // gate and the "herd" is born as a lone leader. The gate is existence-based: at spawn
        // time the mob is fresh (not added to the world / navigation never ticked), so a
        // pathfinder reach probe can never succeed here.
        BlockPos leaderStand = leader.blockPosition();
        BlockPos packOrigin = leaderStand;
        boolean originHasWater = Homes.hasAccessibleWater(level, leaderStand, waterRadius);
        if (!originHasWater) {
            BlockPos shore = findPackShoreOrigin(level, leader, leaderStand, relocateRadius);
            if (shore != null) {
                packOrigin = leader.blockPosition();
                originHasWater = true;
            } else {
                leader.moveTo(leaderStand.getX() + 0.5, leaderStand.getY(), leaderStand.getZ() + 0.5,
                        leader.getYRot(), leader.getXRot());
            }
        }
        if (!originHasWater) {
            // No water at the pack origin (relocation failed): every trySpawnNear scatter
            // candidate would fail the same water gate, and each failure burns a full water
            // scan (plus a re-scan in the S4 diagnostic). With several dry packs loading at
            // once that became a burst of hundreds of expensive scans. The pack also should
            // not exist at all — a herd needs water. Fully block the spawn by removing the
            // leader instead of leaving a lone dry herd that can neither drink nor graze.
            leader.discard();
            return true;
        }
        int spawned = 0;

        while (spawned < EXTRAS_PER_TICK && expansion.adultsLeft > 0) {
            Animal adult = trySpawnNear(level, leader, packOrigin, ADULT_SCATTER_RADIUS, difficulty, random, false, null, now, followTicks, waterRadius);
            expansion.adultsLeft--;
            if (adult != null) {
                expansion.members.add(adult);
            }
            spawned++;
        }

        while (spawned < EXTRAS_PER_TICK && expansion.babiesLeft > 0) {
            List<Animal> mothers = adultsOf(expansion.members);
            if (mothers.isEmpty()) {
                mothers = List.of(leader);
            }
            Animal mother = mothers.get(random.nextInt(mothers.size()));
            Animal baby = trySpawnNear(level, leader, mother.blockPosition(), BABY_SCATTER_RADIUS, difficulty, random, true, mother, now, followTicks, waterRadius);
            if (baby == null) {
                baby = trySpawnNear(level, leader, packOrigin, BABY_SCATTER_RADIUS, difficulty, random, true, mother, now, followTicks, waterRadius);
            }
            expansion.babiesLeft--;
            if (baby != null) {
                expansion.members.add(baby);
            }
            spawned++;
        }

        if (expansion.adultsLeft > 0 || expansion.babiesLeft > 0) {
            return false;
        }

        PackContext ctx = new PackContext();
        for (Animal member : expansion.members) {
            if (member.isAlive()) {
                ctx.members.add(member);
            }
        }
        assignPackMothers(ctx, random);
        formPackHerd(level, ctx, random);
        return true;
    }

    @SubscribeEvent
    public static void onPotentialSpawns(LevelEvent.PotentialSpawns event) {
        if (event.getMobCategory() != MobCategory.CREATURE) {
            return;
        }
        for (MobSpawnSettings.SpawnerData spawnerData : List.copyOf(event.getSpawnerDataList())) {
            Optional<SpeciesHerdSettings> settingsOpt = HerdSettingsManager.get(spawnerData.type);
            if (settingsOpt.isEmpty()) {
                continue;
            }
            event.removeSpawnerData(spawnerData);
            // Min/max both 1: vanilla places the leader only; we expand on tick.
            event.addSpawnerData(new MobSpawnSettings.SpawnerData(
                    spawnerData.type,
                    spawnerData.getWeight().asInt(),
                    1,
                    1));
        }
    }

    @SubscribeEvent
    public static void onPositionCheck(MobSpawnEvent.PositionCheck event) {
        MobSpawnType type = event.getSpawnType();
        if (type != MobSpawnType.NATURAL && type != MobSpawnType.CHUNK_GENERATION) {
            return;
        }
        // Pack extras use MobSpawnType.MOB_SUMMONED and skip these strict biome checks.
        EntityType<?> entityType = event.getEntity().getType();
        if (HerdSettingsManager.get(entityType).isEmpty()) {
            return;
        }
        BlockPos pos = event.getEntity().blockPosition();
        int waterRadius = HomeSettingsManager.get(entityType)
                .map(s -> Mth.clamp(s.waterSearchRadius(), 1, 32))
                .orElse(32);
        if (type == MobSpawnType.CHUNK_GENERATION) {
            // Chunk-gen spawn checks run on chunk worker threads (NaturalSpawner
            // spawnMobsForChunkGeneration). ANY getChunk(..., ChunkStatus, ...) read can block
            // here: once a chunk holder exists (mid-generation) it joins the status future,
            // deadlocking the server. Only getChunkNow() (already-loaded chunks, null otherwise)
            // is safe. Water counts only at/above sea level (y=63) so cave water below never
            // gates a surface spawn. Food stays ungated: grass is ubiquitous and probing it
            // carries the same blocking-read risk.
            if (event.getEntity() instanceof Animal animal
                    && !chunkGenHasWaterNearby(animal, pos, waterRadius)) {
                event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
            } else if (avoidCliffSpawns()
                    && event.getEntity() instanceof Animal animal
                    && !chunkGenCliffSafe(animal, pos)) {
                event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
            }
            return;
        }
        ServerLevelAccessor level = event.getLevel();
        if (avoidCliffSpawns() && event.getEntity() instanceof Animal && !naturalCliffSafe(level, pos)) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
            return;
        }
        // Water gate is existence-based: MobSpawnEvent.PositionCheck probes a fresh entity
        // that is NEVER added to the world, so the pathfinder has no valid start node and a
        // strict pathfinding gate can never pass (that blocked every natural spawn). The
        // drink goal applies the strict reach check once the animal is alive and ticking.
        if (!(event.getEntity() instanceof Animal animal) || !Homes.hasAccessibleWater(level, pos, waterRadius)) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
            return;
        }
        Optional<SpeciesHungerSettings> hungerOpt = HungerSettingsManager.get(entityType);
        if (hungerOpt.isEmpty()) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
            return;
        }
        SpeciesHungerSettings hunger = hungerOpt.get();
        int foodRadius = Mth.clamp(hunger.patchSearchRadius(), 1, 32);
        if (!GrazePatches.hasFoodNearby(level, pos, hunger.foodBlocks(), foodRadius)) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        }
    }

    /**
     * Water-presence check for chunk-generation spawn checks (run on chunk worker threads).
     * Reads ONLY via {@code ServerChunkCache.getChunkNow} — it returns a chunk only when it is
     * already fully loaded, otherwise null, and never blocks on generation. A column counts
     * only when it has water at or above sea level (y=63); cave water below never counts. When
     * no nearby chunk is loaded yet the check is inconclusive and allows the spawn (the strict
     * reachability gate still applies to natural spawns on the server thread).
     */
    private static boolean avoidCliffSpawns() {
        return EthologicalConfig.CONFIG.comfort.avoidCliffSpawns.get();
    }

    /**
     * Cliff check for natural spawns on the server thread: same 3-block scan / 3-block
     * max-drop rule as {@code Homes.isCliffSafe}, but via {@code LevelReader#getHeight} so it
     * works with the {@code ServerLevelAccessor} the PositionCheck event provides. Heightmap
     * surface includes water columns at their surface, so pond/river edges stay acceptable.
     */
    private static boolean naturalCliffSafe(ServerLevelAccessor level, BlockPos center) {
        int baseY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
        int radius = 3;
        int maxDrop = 3;
        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int neighborY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        center.getX() + dx, center.getZ() + dz);
                if (baseY - neighborY > maxDrop) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Cliff check for chunk-generation spawn checks (chunk worker threads): same constraint as
     * {@link #chunkGenHasWaterNearby} — only {@code getChunkNow}, never a blocking read.
     * Scans the surface heightmap of loaded neighbor chunks for drops beyond
     * {@code CLIFF_MAX_DROP}; unloaded neighbors are inconclusive and don't fail the spot,
     * matching the water probe's permissiveness. Surface Y from the heightmap includes water
     * columns at their surface so pond edges stay acceptable, mirroring Homes.isCliffSafe.
     */
    private static boolean chunkGenCliffSafe(Animal mob, BlockPos center) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return true;
        }
        int baseY = center.getY();
        int radius = 3;
        int maxDrop = 3;
        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) {
                    continue;
                }
                int neighborY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
                if (baseY - neighborY > maxDrop) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean chunkGenHasWaterNearby(Animal mob, BlockPos center, int radius) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return true;
        }
        int scanRadius = Math.min(radius, 16);
        boolean scannedAny = false;
        for (int dx = -scanRadius; dx <= scanRadius; ++dx) {
            for (int dz = -scanRadius; dz <= scanRadius; ++dz) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) {
                    continue;
                }
                scannedAny = true;
                int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
                int top = Math.min(Math.max(63, surfaceY + 1), level.getMaxBuildHeight() - 1);
                for (int probeY = 63; probeY <= top; ++probeY) {
                    if (chunk.getBlockState(new BlockPos(x, probeY, z)).getFluidState().is(FluidTags.WATER)) {
                        return true;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Finds a dry, cliff-safe shoreline stand near {@code center} for a leader whose own
     * stand has no reachable water. The leader is temporarily moved onto each candidate
     * shore and the strict water probe re-run from there; returns the first shore that
     * verifies, or null when the area is genuinely dry (the pack stays solo there).
     */
    @Nullable
    private static BlockPos findPackShoreOrigin(ServerLevel level, Animal leader, BlockPos center, int radius) {
        for (BlockPos surface : Homes.findWaterCandidates(level, center, radius, java.util.Set.of(), 8)) {
            Optional<BlockPos> shoreOpt = Homes.findShoreStandNearWater(level, surface, center, java.util.Set.of());
            if (shoreOpt.isEmpty()) {
                continue;
            }
            BlockPos shore = shoreOpt.get();
            if (!Homes.isCliffSafe(level, shore)) {
                continue;
            }
            leader.moveTo(shore.getX() + 0.5, shore.getY(), shore.getZ() + 0.5, leader.getYRot(), leader.getXRot());
            if (Homes.hasAccessibleWater(level, shore, Math.min(radius, 32))) {
                return shore;
            }
        }
        return null;
    }

    @Nullable
    private static Animal trySpawnNear(
            ServerLevel level,
            Animal template,
            BlockPos origin,
            int radius,
            DifficultyInstance difficulty,
            RandomSource random,
            boolean baby,
            @Nullable Animal mother,
            long now,
            long followTicks,
            int waterRadius) {
        if (!(template.getType().create(level) instanceof Animal mate)) {
            return null;
        }
        // MOB_SUMMONED: extras inherit the leader's already-validated biome niche, but the
        // scatter stand itself must still be near water. The mate isn't in the world yet (it
        // is added after this probe), so a pathfinder reach check is impossible here; the
        // existence gate matches chunk-gen and the drink goal applies the strict reach check
        // once the member is alive.
        MobSpawnType spawnType = MobSpawnType.MOB_SUMMONED;
        BlockPos pos = null;
        for (int attempt = 0; attempt < PACK_POSITION_ATTEMPTS; ++attempt) {
            BlockPos candidate = findScatterPos(level, origin, mate.getType(), spawnType, random, radius);
            if (candidate == null) {
                break;
            }
            mate.moveTo(candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
            if (Homes.hasAccessibleWater(level, candidate, waterRadius)
                    && (!avoidCliffSpawns() || Homes.isCliffSafe(level, candidate))) {
                pos = candidate;
                break;
            }
        }
        if (pos == null) {
            return null;
        }
        mate.setBaby(baby);
        if (baby && mother != null) {
            mate.setData(ModAttachments.MOTHER, MotherData.create(mother.getUUID(), now, followTicks));
        }
        if (!EventHooks.checkSpawnPosition(mate, level, spawnType)) {
            return null;
        }
        MANUAL_EXTRA.set(true);
        try {
            EventHooks.finalizeMobSpawn(mate, level, difficulty, spawnType, null);
        } finally {
            MANUAL_EXTRA.set(false);
        }
        if (mate.isSpawnCancelled()) {
            return null;
        }
        mate.setBaby(baby);
        if (baby && mother != null) {
            mate.setData(ModAttachments.MOTHER, MotherData.create(mother.getUUID(), now, followTicks));
        }
        level.addFreshEntityWithPassengers(mate);
        return mate;
    }

    /** Ensure every baby has an active mother link to a pack adult. */
    private static void assignPackMothers(PackContext ctx, RandomSource random) {
        List<Animal> adults = adultsOf(ctx.members);
        if (adults.isEmpty()) {
            return;
        }
        long now = adults.get(0).level().getGameTime();
        long followTicks = EthologicalConfig.CONFIG.comfort.motherFollowTicks.get();
        for (Animal member : ctx.members) {
            if (!member.isAlive() || !member.isBaby()) {
                continue;
            }
            if (member.hasData(ModAttachments.MOTHER)
                    && ((MotherData)member.getData(ModAttachments.MOTHER)).isActive(now)) {
                continue;
            }
            Animal mother = adults.get(random.nextInt(adults.size()));
            member.setData(ModAttachments.MOTHER, MotherData.create(mother.getUUID(), now, followTicks));
        }
    }

    /**
     * Form the herd immediately at spawn so babies never race into tryJoinOrFormHerd
     * and accidentally become alphas before mother join logic runs.
     */
    private static void formPackHerd(ServerLevel level, PackContext ctx, RandomSource random) {
        List<Animal> living = new ArrayList<>();
        for (Animal member : ctx.members) {
            if (member.isAlive()) {
                living.add(member);
            }
        }
        if (living.isEmpty()) {
            return;
        }
        // Vanilla chunk-gen can roll the same species several times in one chunk; each roll
        // previously became its own full herd (the "herds spawn together" chaos — two leaders
        // ~6 blocks apart each leading a separate herd). Fold the new pack into an existing
        // nearby same-species herd when one has room instead of creating a parallel herd.
        Optional<SpeciesHerdSettings> settingsOpt = HerdSettingsManager.get(living.get(0).getType());
        if (settingsOpt.isPresent()) {
            HerdManager.Herd existing = findMergeablePackHerd(level, living.get(0), settingsOpt.get());
            if (existing != null) {
                for (Animal member : living) {
                    member.setData(ModAttachments.HERD_DATA, new HerdData(existing.id, false));
                    existing.members.add(member.getUUID());
                }
                return;
            }
        }
        HashSet<UUID> ids = new HashSet<>();
        for (Animal member : living) {
            ids.add(member.getUUID());
        }
        List<Animal> adults = adultsOf(ctx.members);
        UUID alphaId;
        if (!adults.isEmpty()) {
            alphaId = adults.get(random.nextInt(adults.size())).getUUID();
        } else {
            alphaId = HerdManager.electAlpha(level, ids);
            if (alphaId == null) {
                alphaId = living.get(0).getUUID();
            }
        }
        HerdManager.Herd herd = HerdManager.create(alphaId);
        herd.members.addAll(ids);
        for (Animal member : living) {
            boolean isAlpha = member.getUUID().equals(alphaId) && !member.isBaby();
            member.setData(ModAttachments.HERD_DATA, new HerdData(herd.id, isAlpha));
        }
        Ethological.LOGGER.debug(
                "Ethological: spawn pack formed herd {} with {} members (alpha {})",
                herd.id,
                herd.members.size(),
                alphaId);
    }

    /**
     * Finds an existing same-species herd (already formed and live in the world) within the
     * species join radius that can still accept more adults. Used to fold co-located spawn
     * packs into one herd instead of forming parallel herds for the same vanilla chunk roll.
     */
    @Nullable
    private static HerdManager.Herd findMergeablePackHerd(ServerLevel level, Animal origin, SpeciesHerdSettings settings) {
        for (Animal other : level.getEntitiesOfClass(Animal.class,
                origin.getBoundingBox().inflate(settings.joinRadius()),
                a -> a != origin && a.getType() == origin.getType() && a.hasData(ModAttachments.HERD_DATA))) {
            HerdManager.Herd herd = HerdManager.get(((HerdData)other.getData(ModAttachments.HERD_DATA)).herdId());
            if (herd == null || herd.isPanicking() || HerdManager.adultCount(level, herd) >= settings.maxSize()) {
                continue;
            }
            return herd;
        }
        return null;
    }

    private static List<Animal> adultsOf(List<Animal> members) {
        ArrayList<Animal> adults = new ArrayList<>();
        for (Animal member : members) {
            if (member.isAlive() && !member.isBaby()) {
                adults.add(member);
            }
        }
        return adults;
    }

    @Nullable
    private static BlockPos findScatterPos(
            ServerLevel level,
            BlockPos origin,
            EntityType<?> type,
            MobSpawnType spawnType,
            RandomSource random,
            int radius) {
        for (int attempt = 0; attempt < PACK_POSITION_ATTEMPTS; attempt++) {
            int x = origin.getX() + random.nextInt(radius * 2 + 1) - radius;
            int z = origin.getZ() + random.nextInt(radius * 2 + 1) - radius;
            BlockPos column = new BlockPos(x, origin.getY(), z);
            // Never force-load neighbor chunks while placing pack extras.
            if (!level.hasChunkAt(column)) {
                continue;
            }
            int y = level.getHeight(SpawnPlacements.getHeightmapType(type), x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (SpawnPlacements.isSpawnPositionOk(type, level, pos)
                    && SpawnPlacements.checkSpawnRules(type, level, spawnType, pos, random)) {
                return pos;
            }
        }
        // Last resort: stand on the origin column if it is a legal placement.
        if (!level.hasChunkAt(origin)) {
            return null;
        }
        int y = level.getHeight(SpawnPlacements.getHeightmapType(type), origin.getX(), origin.getZ());
        BlockPos fallback = new BlockPos(origin.getX(), y, origin.getZ());
        if (SpawnPlacements.isSpawnPositionOk(type, level, fallback)) {
            return fallback;
        }
        return null;
    }

    private static int rollPackSize(SpeciesHerdSettings settings, RandomSource random) {
        int min = settings.spawnGroupMin();
        int max = settings.spawnGroupMax();
        return min >= max ? min : min + random.nextInt(max - min + 1);
    }

    private static int rollBabyCount(SpeciesHerdSettings settings, RandomSource random) {
        int min = settings.spawnBabiesMin();
        int max = settings.spawnBabiesMax();
        if (max <= 0) {
            return 0;
        }
        return min >= max ? min : min + random.nextInt(max - min + 1);
    }

    private record PendingPack(int packSize, int babyCount) {
    }

    private static final class PackExpansion {
        final ServerLevel level;
        final Animal leader;
        final List<Animal> members = new ArrayList<>();
        int adultsLeft;
        int babiesLeft;

        PackExpansion(ServerLevel level, Animal leader, int adultsLeft, int babiesLeft) {
            this.level = level;
            this.leader = leader;
            this.adultsLeft = adultsLeft;
            this.babiesLeft = babiesLeft;
        }
    }

    private static final class PackContext {
        final List<Animal> members = new ArrayList<>();
    }
}
