/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.BlockPos$MutableBlockPos
 *  net.minecraft.core.Direction
 *  net.minecraft.core.Direction$Plane
 *  net.minecraft.core.Vec3i
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.tags.BlockTags
 *  net.minecraft.tags.FluidTags
 *  net.minecraft.util.Mth
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.PathfinderMob
 *  net.minecraft.world.entity.animal.Animal
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.level.LevelReader
 *  net.minecraft.world.level.biome.Biome
 *  net.minecraft.world.level.block.state.BlockState
 *  net.minecraft.world.level.levelgen.Heightmap$Types
 *  net.minecraft.world.level.pathfinder.Path
 *  net.minecraft.world.phys.AABB
 *  net.minecraft.world.phys.Vec3
 */
package com.charybdis180.ethological.home;

import com.charybdis180.ethological.avoidance.Avoidance;
import com.charybdis180.ethological.config.EthologicalConfig;
import com.charybdis180.ethological.herd.HerdAttachments;
import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.home.HomeAttachments;
import com.charybdis180.ethological.home.HomeData;
import com.charybdis180.ethological.home.SpeciesHomeSettings;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class Homes {
    public static final double ARRIVAL_RADIUS = 6.0;
    public static final long GRACE_TICKS = 2400L;
    public static final long SHELTER_UPGRADE_WINDOW_TICKS = 400L;
    /** Throttles the expensive fallback alpha entity scan; entries expire by game time. */
    private static final Map<UUID, AlphaScanResult> ALPHA_SCAN_CACHE = new ConcurrentHashMap<UUID, AlphaScanResult>();
    private static final long ALPHA_SCAN_INTERVAL_TICKS = 100L;
    private static final int ALPHA_SCAN_CACHE_LIMIT = 4096;
    /** Remembers failed water searches so repeat scans of dry areas are skipped for a while. */
    private static final Map<Long, Long> WATER_MISS_CACHE = new ConcurrentHashMap<Long, Long>();
    private static final long WATER_MISS_TICKS = 2400L;
    private static final int WATER_MISS_CACHE_LIMIT = 8192;
    /** Short-TTL miss for scans cut short by unloaded chunks: dedupes the burst of repeat
     *  scans while chunks load, yet expires fast enough that freshly-generated water is found
     *  promptly. Full dry-area scans keep the long 2400-tick miss; only inconclusive scans
     *  get the short entry. */
    private static final long WATER_INCOMPLETE_MISS_TICKS = 100L;
    /** Remembers successful water searches so wet areas are not re-scanned on every gate tick. */
    private static final Map<Long, Long> WATER_HIT_CACHE = new ConcurrentHashMap<Long, Long>();
    private static final long WATER_HIT_TICKS = 100L;
    private static final int WATER_HIT_CACHE_LIMIT = 8192;
    /** Caches the candidate list a {@link #findWaterCandidates} call returned, so repeat
     * drink searches in the same chunk reuse the scan instead of re-running the 128-radius
     * spiral (the spiral also runs a shore scan per candidate, which is where the multi-second
     * waterScan spikes came from). Keyed by (chunk, radius, limit); the surfaces are filtered
     * by each caller's exclusion set, so a slightly-stale list is safe. */
    private static final Map<Long, WaterCandidateResult> WATER_CANDIDATE_CACHE = new ConcurrentHashMap<Long, WaterCandidateResult>();
    private static final long WATER_CANDIDATE_TICKS = 300L;
    private static final int WATER_CANDIDATE_CACHE_LIMIT = 8192;
    /** Memo for {@link #surfaceStand}: per-level, keyed by 26-bit-packed column, valid for one
     *  game tick. A column's stand is a pure function of the world, and {@link
     *  #invalidateSurfaceStandMemo} clears it on any block change, so the memo cannot go stale.
     *  Null results are stored via the {@link #SURFACE_STAND_NULL} sentinel (ConcurrentHashMap
     *  forbids null values). Weak level keys so loaded worlds are not pinned in memory. */
    private static final Map<Level, Map<Long, BlockPos>> SURFACE_STAND_MEMO = new java.util.WeakHashMap<Level, Map<Long, BlockPos>>();
    private static final Map<Level, Long> SURFACE_STAND_STAMP = new java.util.WeakHashMap<Level, Long>();
    /** Sentinel marking "no stand at this column" in the memo map. */
    private static final BlockPos SURFACE_STAND_NULL = new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
    /**
     * Upper bound on the water-search spiral. Scanning every block of a 128+ radius column
     * costs millions of probes, so far rings are sampled sparsely instead of densely (see
     * {@link #MAX_DENSE_WATER_SCAN_RADIUS}). The drink goal still discovers water far away,
     * just without paying full grid price.
     */
    private static final int MAX_WATER_SCAN_RADIUS = 128;
    /** Rings within this radius are scanned block-by-block; beyond it the ring is sampled. */
    private static final int MAX_DENSE_WATER_SCAN_RADIUS = 32;

    private record AlphaScanResult(UUID alphaId, long expiryGameTime) {
    }

    /** Expiry plus the immutable candidate list a water scan returned. */
    private record WaterCandidateResult(long expiryGameTime, java.util.List<BlockPos> surfaces) {
    }

    /** Water-scan result plus a completeness flag: a scan that skipped unloaded chunks cannot
     *  prove absence of water, so callers must not cache its empty result as a miss. */
    private record WaterScanResult(boolean complete, java.util.List<BlockPos> surfaces) {
    }

    /**
     * Chunk-quantized center key so herd-mates standing in the same dry/wet cell share one
     * cached result. Y is intentionally dropped: a dry surface column is dry at every Y for
     * drinking, so walking up a slope no longer evicts the entry and forces a full re-scan.
     */
    private static long waterMissKey(LevelReader level, BlockPos center, int radius) {
        long key = (long)(center.getX() >> 4) << 32 ^ (long)(center.getZ() >> 4) & 0xFFFFFFFFL;
        key = key * 31L + (long)radius;
        return key * 31L + (long)level.hashCode();
    }

    /** Clears both water caches so a freshly built/lit water source is found promptly. */
    public static void invalidateWaterCaches() {
        WATER_MISS_CACHE.clear();
        WATER_HIT_CACHE.clear();
        WATER_CANDIDATE_CACHE.clear();
    }

    /** Clears the surface-stand memo. Any block change can alter a column's stand, so this is
     *  called from the block place/break handlers to keep the memo behavior-safe. */
    public static void invalidateSurfaceStandMemo() {
        SURFACE_STAND_MEMO.clear();
        SURFACE_STAND_STAMP.clear();
    }

    /** 26-bit XZ packing for the surface-stand memo, matching the {@code cellKey} style used by
     *  {@code avoidance.Avoidance}. 26 bits is ample for a world's loaded coordinates. */
    private static long surfaceStandKey(int x, int z) {
        return ((long)(x & 0x3FFFFFF) << 32) ^ (z & 0x3FFFFFFL);
    }

    /** Verdict cache for {@link #isReachable}/{@link #isStrictlyReachable}: per-mob, keyed by
     *  4-block-quantized target column, with a 100-tick TTL. Reachability is a pathfinder query
     *  that costs up to ~1 ms on the server thread, and the same home/water/graze target is
     *  re-probed by the same mob on successive ticks while it walks there. The world can change
     *  under a 5s TTL (player digs a wall), which is the accepted staleness trade-off. */
    private static final Map<UUID, Map<Long, ReachVerdict>> REACH_MEMO = new ConcurrentHashMap<UUID, Map<Long, ReachVerdict>>();
    private static final long REACH_MEMO_TICKS = 100L;
    private static final int REACH_MEMO_PER_MOB_LIMIT = 64;
    private static final int REACH_MEMO_TOTAL_LIMIT = 4096;

    /** Both flags are recorded whenever either reachability query runs, so a subsequent
     *  call for the same target reuses both verdicts from one pathfind. */
    private record ReachVerdict(boolean reachable, boolean strict, long gameTime) {
    }

    /** 4-block-quantized target column for the reachability memo. */
    private static long reachKey(BlockPos target) {
        return surfaceStandKey(target.getX() >> 2, target.getZ() >> 2);
    }

    private static ReachVerdict reachMemoGet(UUID mobId, long key, long now) {
        Map<Long, ReachVerdict> inner = REACH_MEMO.get(mobId);
        if (inner == null) {
            return null;
        }
        ReachVerdict verdict = inner.get(key);
        if (verdict != null && now - verdict.gameTime() < REACH_MEMO_TICKS) {
            return verdict;
        }
        return null;
    }

    private static void reachMemoPut(UUID mobId, long key, long now, boolean reachable, boolean strict) {
        Map<Long, ReachVerdict> inner = REACH_MEMO.computeIfAbsent(mobId, k -> new java.util.HashMap<Long, ReachVerdict>());
        if (inner.size() >= REACH_MEMO_PER_MOB_LIMIT) {
            inner.clear();
        }
        inner.put(key, new ReachVerdict(reachable, strict, now));
        long total = 0L;
        for (Map<Long, ReachVerdict> mobEntries : REACH_MEMO.values()) {
            total += (long)mobEntries.size();
        }
        if (total > (long)REACH_MEMO_TOTAL_LIMIT) {
            REACH_MEMO.clear();
        }
    }

    private static boolean isCachedWaterMiss(LevelReader level, long key) {
        if (!(level instanceof Level)) {
            return false;
        }
        Long expiry = WATER_MISS_CACHE.get(key);
        return expiry != null && ((Level)level).getGameTime() < expiry;
    }

    private static void cacheWaterMiss(LevelReader level, long key) {
        if (!(level instanceof Level)) {
            return;
        }
        if (WATER_MISS_CACHE.size() > WATER_MISS_CACHE_LIMIT) {
            WATER_MISS_CACHE.clear();
        }
        WATER_MISS_CACHE.put(key, ((Level)level).getGameTime() + WATER_MISS_TICKS);
    }

    private static void cacheWaterIncompleteMiss(LevelReader level, long key) {
        if (!(level instanceof Level)) {
            return;
        }
        if (WATER_MISS_CACHE.size() > WATER_MISS_CACHE_LIMIT) {
            WATER_MISS_CACHE.clear();
        }
        WATER_MISS_CACHE.put(key, ((Level)level).getGameTime() + WATER_INCOMPLETE_MISS_TICKS);
    }

    private static boolean isCachedWaterHit(LevelReader level, long key) {
        if (!(level instanceof Level)) {
            return false;
        }
        Long expiry = WATER_HIT_CACHE.get(key);
        return expiry != null && ((Level)level).getGameTime() < expiry;
    }

    private static void cacheWaterHit(LevelReader level, long key) {
        if (!(level instanceof Level)) {
            return;
        }
        if (WATER_HIT_CACHE.size() > WATER_HIT_CACHE_LIMIT) {
            WATER_HIT_CACHE.clear();
        }
        WATER_HIT_CACHE.put(key, ((Level)level).getGameTime() + WATER_HIT_TICKS);
    }

    private Homes() {
    }

    public static boolean isHotFor(Animal animal) {
        float temp = ((Biome)animal.level().getBiome(animal.blockPosition()).value()).getBaseTemperature();
        float hot = ((Double)EthologicalConfig.CONFIG.comfort.hotBiomeTemperature.get()).floatValue();
        float warm = ((Double)EthologicalConfig.CONFIG.comfort.warmBiomeTemperature.get()).floatValue();
        if (temp >= hot) {
            return true;
        }
        if (temp < warm) {
            return false;
        }
        long t = Homes.dayTick(animal.level().getDayTime());
        return t >= 4000L && t <= 11000L;
    }

    public static Optional<BlockPos> homeOf(Entity entity) {
        return entity.hasData(HomeAttachments.HOME) ? Optional.of(((HomeData)entity.getData(HomeAttachments.HOME)).pos()) : Optional.empty();
    }

    public static Optional<BlockPos> effectiveHome(Animal animal) {
        return Homes.effectiveHomeData(animal).map(HomeData::pos);
    }

    public static Optional<HomeData> effectiveHomeData(Animal animal) {
        Optional<Animal> alpha = Homes.herdAlpha(animal);
        if (alpha.isPresent() && alpha.get() != animal) {
            Animal a = alpha.get();
            return a.hasData(HomeAttachments.HOME) ? Optional.of((HomeData)a.getData(HomeAttachments.HOME)) : Optional.empty();
        }
        return animal.hasData(HomeAttachments.HOME) ? Optional.of((HomeData)animal.getData(HomeAttachments.HOME)) : Optional.empty();
    }

    public static boolean isHomeOwner(Animal animal) {
        if (!animal.hasData(HerdAttachments.HERD_DATA)) {
            return true;
        }
        return ((HerdData)animal.getData(HerdAttachments.HERD_DATA)).alpha();
    }

    public static Optional<Animal> herdAlpha(Animal animal) {
        if (!animal.hasData(HerdAttachments.HERD_DATA)) {
            return Optional.empty();
        }
        HerdData data = (HerdData)animal.getData(HerdAttachments.HERD_DATA);
        if (data.alpha()) {
            return Optional.of(animal);
        }
        Level level = animal.level();
        if (level instanceof ServerLevel) {
            Entity entity;
            ServerLevel serverLevel = (ServerLevel)level;
            HerdManager.Herd herd = HerdManager.get(data.herdId());
            if (herd != null && herd.alphaId != null && (entity = serverLevel.getEntity(herd.alphaId)) instanceof Animal) {
                Animal alphaAnimal = (Animal)entity;
                return Optional.of(alphaAnimal);
            }
        }
        return Homes.findAlphaAmongLoaded(animal, data.herdId());
    }

    private static Optional<Animal> findAlphaAmongLoaded(Animal self, UUID herdId) {
        long now = self.level().getGameTime();
        AlphaScanResult cached = ALPHA_SCAN_CACHE.get(self.getUUID());
        if (cached != null && now < cached.expiryGameTime()) {
            if (cached.alphaId() == null) {
                return Optional.empty();
            }
            Level level = self.level();
            if (level instanceof ServerLevel) {
                ServerLevel serverLevel = (ServerLevel)level;
                Entity entity = serverLevel.getEntity(cached.alphaId());
                if (entity instanceof Animal) {
                    Animal alpha = (Animal)entity;
                    if (alpha.hasData(HerdAttachments.HERD_DATA) && ((HerdData)alpha.getData(HerdAttachments.HERD_DATA)).herdId().equals(herdId)) {
                        return Optional.of(alpha);
                    }
                }
                return Optional.empty();
            }
        }
        AABB box = self.getBoundingBox().inflate(128.0);
        Animal found = null;
        for (Animal other : self.level().getEntitiesOfClass(Animal.class, box)) {
            HerdData otherData;
            if (other == self || !other.hasData(HerdAttachments.HERD_DATA) || !(otherData = (HerdData)other.getData(HerdAttachments.HERD_DATA)).alpha() || !otherData.herdId().equals(herdId)) continue;
            found = other;
            break;
        }
        if (ALPHA_SCAN_CACHE.size() > ALPHA_SCAN_CACHE_LIMIT) {
            ALPHA_SCAN_CACHE.clear();
        }
        ALPHA_SCAN_CACHE.put(self.getUUID(), new AlphaScanResult(found != null ? found.getUUID() : null, now + ALPHA_SCAN_INTERVAL_TICKS));
        return Optional.ofNullable(found);
    }

    public static Optional<Double> effectiveMigrationHeading(Animal animal) {
        Animal source = Homes.herdAlpha(animal).orElse(animal);
        if (!source.hasData(HomeAttachments.NOMAD_HEADING)) {
            return Optional.empty();
        }
        double heading = (Double)source.getData(HomeAttachments.NOMAD_HEADING);
        return Double.isNaN(heading) ? Optional.empty() : Optional.of(heading);
    }

    public static long dayTick(long dayTime) {
        return (dayTime % 24000L + 24000L) % 24000L;
    }

    /**
     * True when day tick {@code t} falls inside the half-open window {@code [start, end)},
     * wrapping past midnight when {@code start > end}. The "hold" window for a nomadic
     * camp is {@code [campTimeTick, sleepEndTick)}; anything outside it — including the
     * whole morning after the day counter wrapped past sleepEnd (e.g. the player slept
     * through the [sleepEnd, 24000) window) — counts as night over.
     */
    public static boolean inTickRange(long t, long start, long end) {
        if (start < end) {
            return t >= start && t < end;
        }
        return t >= start || t < end;
    }

    public static long ticksUntilSleepStart(long dayTime, int sleepStartTick) {
        long delta = (long)sleepStartTick - Homes.dayTick(dayTime);
        return delta < 0L ? delta + 24000L : delta;
    }

    public static long ticksIntoSleepWindow(long dayTime, SpeciesSleepSettings settings) {
        long delta = Homes.dayTick(dayTime) - (long)settings.sleepStartTick();
        return delta < 0L ? delta + 24000L : delta;
    }

    public static double allowedRadius(Animal animal, SpeciesHomeSettings settings, long dayTime, int sleepStartTick) {
        long until = Homes.ticksUntilSleepStart(dayTime, sleepStartTick);
        if (settings.leashShrinkTicks() <= 0 || until >= (long)settings.leashShrinkTicks()) {
            return settings.wanderRadius();
        }
        double fraction = (double)until / (double)settings.leashShrinkTicks();
        return 12.0 + ((double)settings.wanderRadius() - 12.0) * fraction;
    }

    public static boolean shouldTravelHome(Animal animal, long dayTime, SpeciesSleepSettings sleepSettings) {
        Optional<BlockPos> home = Homes.effectiveHome(animal);
        if (home.isEmpty()) {
            return false;
        }
        if (animal.distanceToSqr(Vec3.atCenterOf((Vec3i)((Vec3i)home.get()))) <= 36.0) {
            return false;
        }
        if (!sleepSettings.isSleepTime(dayTime)) {
            return true;
        }
        return Homes.ticksIntoSleepWindow(dayTime, sleepSettings) <= 2400L;
    }

    public static boolean hasAccessibleWater(LevelReader level, BlockPos center, int radius) {
        int scanRadius = Math.min(radius, MAX_WATER_SCAN_RADIUS);
        long hitKey = Homes.waterMissKey(level, center, scanRadius);
        if (Homes.isCachedWaterHit(level, hitKey)) {
            return true;
        }
        if (Homes.isCachedWaterMiss(level, hitKey)) {
            return false;
        }
        Homes.WaterScanResult scan = Homes.scanWaterColumns(level, center, scanRadius, Set.of(), 1);
        if (!scan.surfaces().isEmpty()) {
            Homes.cacheWaterHit(level, hitKey);
            return true;
        }
        // A scan that skipped unloaded chunks (world generation) is inconclusive — do not
        // cache its empty result as a long miss, or fresh water in newly-loaded chunks is ignored
        // for the whole miss-cache TTL. Cache it with a short TTL instead so a burst of repeat
        // probes of the same loading area (home validation, spawn gates, drink searches) share
        // one scan result instead of re-running the expensive spiral every call.
        if (scan.complete()) {
            Homes.cacheWaterMiss(level, hitKey);
        } else {
            Homes.cacheWaterIncompleteMiss(level, hitKey);
        }
        return false;
    }

    public static Optional<BlockPos> findWater(Level level, BlockPos center, int radius) {
        List<BlockPos> candidates = Homes.findWaterCandidates(level, center, radius, Set.of(), 1);
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    /**
     * Spiral water-column scan bounded for performance. Rings within
     * {@link #MAX_DENSE_WATER_SCAN_RADIUS} are checked block-by-block (nearby water is found
     * exactly and early). Beyond that the ring perimeter is sampled every {@code 1 + ring/12}
     * positions and only a narrow Y band is probed, so a 128-block radius costs roughly 150k
     * probes instead of 1.1M. Returns up to {@code limit} water surfaces, source columns first.
     */
    private static WaterScanResult scanWaterColumns(LevelReader level, BlockPos center, int radius, Set<BlockPos> excluded, int limit) {
        ArrayList<BlockPos> sources = new ArrayList<BlockPos>();
        ArrayList<BlockPos> flowing = new ArrayList<BlockPos>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean enough = false;
        int skippedUnloaded = 0;
        for (int ring = 0; ring <= radius && !enough; ++ring) {
            boolean dense = ring <= MAX_DENSE_WATER_SCAN_RADIUS;
            int step = dense ? 1 : 1 + ring / 12;
            int dyLimit = dense ? 8 : 4;
            for (int dyAbs = 0; dyAbs <= dyLimit && !enough; ++dyAbs) {
                for (int sign = 0; sign < (dyAbs == 0 ? 1 : 2); ++sign) {
                    int dy = dyAbs == 0 ? 0 : (sign == 0 ? -dyAbs : dyAbs);
                    if (dy < -8 || dy > 4) {
                        continue;
                    }
                    int idx = 0;
                    for (int dx = -ring; dx <= ring && !enough; ++dx) {
                        int stepZ = Math.abs(dx) == ring ? 1 : Math.max(1, 2 * ring);
                        for (int dz = -ring; dz <= ring; dz += stepZ) {
                            if (step > 1 && idx++ % step != 0) {
                                continue;
                            }
                            pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                            if (!Homes.chunkFullyLoaded(level, pos.getX(), pos.getZ())) {
                                ++skippedUnloaded;
                                continue;
                            }
                            if (excluded.contains(pos) || !Homes.isWater(level, pos)) {
                                continue;
                            }
                            BlockPos surface = Homes.waterSurface(level, pos.immutable());
                            if (excluded.contains(surface) || !Homes.isOutdoorSurfaceWater(level, surface)) {
                                continue;
                            }
                            if (Homes.findShoreStandNearWater(level, surface, center, Set.of()).isEmpty()) {
                                continue;
                            }
                            if (level.getBlockState(surface).getFluidState().isSource()) {
                                if (sources.size() < limit) {
                                    sources.add(surface);
                                }
                            } else if (flowing.size() < limit) {
                                flowing.add(surface);
                            }
                            if (sources.size() + flowing.size() >= limit) {
                                enough = true;
                                break;
                            }
                        }
                    }
                }
            }
        }
        ArrayList<BlockPos> result = new ArrayList<BlockPos>();
        for (BlockPos surface : sources) {
            if (result.size() >= limit) {
                break;
            }
            result.add(surface);
        }
        for (BlockPos surface : flowing) {
            if (result.size() >= limit) {
                break;
            }
            result.add(surface);
        }
        return new WaterScanResult(skippedUnloaded == 0, result);
    }

    /**
     * Single-scan water search returning candidate surfaces in the same preference order
     * {@link #findWater} would try them: source water in ring order, then flowing water in ring
     * order, bounded to {@code limit} candidates. One scan replaces the old per-attempt full rescans.
     * Dry-area misses are cached per (chunk, Y-bucket, radius) regardless of the exclusion set,
     * so repeat searches of the same dry area skip the scan entirely.
     */
    public static List<BlockPos> findWaterCandidates(Level level, BlockPos center, int radius, Set<BlockPos> excluded, int limit) {
        int scanRadius = Math.min(radius, MAX_WATER_SCAN_RADIUS);
        long missKey = Homes.waterMissKey(level, center, scanRadius);
        if (Homes.isCachedWaterMiss(level, missKey)) {
            return List.of();
        }
        // A recent scan of this chunk found water — reuse its candidates instead of re-running
        // the full spiral. Callers filter the returned surfaces against their own exclusion sets,
        // so sharing across members/limits is safe. The miss cache above is checked first.
        long candidateKey = missKey * 31L + (long)limit;
        WaterCandidateResult cached = WATER_CANDIDATE_CACHE.get(candidateKey);
        if (cached != null) {
            if (level.getGameTime() < cached.expiryGameTime()) {
                return cached.surfaces();
            }
            WATER_CANDIDATE_CACHE.remove(candidateKey);
        }
        Homes.WaterScanResult scan = Homes.scanWaterColumns(level, center, scanRadius, excluded, limit);
        List<BlockPos> result = scan.surfaces();
        if (result.isEmpty()) {
            // Only a complete scan (no unloaded-chunk skips) can prove the area is dry; a
            // scan cut short by unloaded chunks during world generation must not poison the
            // miss cache with a long miss, or freshly-generated water stays invisible for the
            // whole TTL. A short-TTL miss still dedupes the burst of repeat probes of the same
            // loading area while chunks are still streaming in.
            if (scan.complete()) {
                Homes.cacheWaterMiss(level, missKey);
            } else {
                Homes.cacheWaterIncompleteMiss(level, missKey);
            }
        } else {
            if (WATER_CANDIDATE_CACHE.size() > WATER_CANDIDATE_CACHE_LIMIT) {
                WATER_CANDIDATE_CACHE.clear();
            }
            WATER_CANDIDATE_CACHE.put(candidateKey, new WaterCandidateResult(level.getGameTime() + WATER_CANDIDATE_TICKS, List.copyOf(result)));
        }
        return result;
    }

    /**
     * True for ponds/rivers/lakes at the outdoor world surface — not cave aquifers or fully roofed indoor pools.
     */
    public static boolean isOutdoorSurfaceWater(LevelReader level, BlockPos waterPos) {
        BlockPos surface = Homes.waterSurface(level, waterPos);
        if (!Homes.chunkFullyLoaded(level, surface.getX(), surface.getZ()) || !Homes.isWater(level, surface)) {
            return false;
        }
        int mapY = Homes.heightIfLoaded(level, surface.getX(), surface.getZ());
        return mapY != Integer.MIN_VALUE && surface.getY() >= mapY - 3 && surface.getY() <= mapY + 1;
    }

    public static BlockPos waterSurface(LevelReader level, BlockPos waterPos) {
        BlockPos pos = waterPos;
        if (!Homes.isWater(level, pos)) {
            if (Homes.isWater(level, pos.below())) {
                pos = pos.below();
            } else {
                return waterPos;
            }
        }
        while (Homes.isWater(level, pos.above())) {
            pos = pos.above();
        }
        return pos;
    }

    public static Optional<BlockPos> findShoreStand(LevelReader level, BlockPos waterPos, BlockPos from) {
        return Homes.findShoreStand(level, waterPos, from, Set.of());
    }

    public static Optional<BlockPos> findShoreStand(LevelReader level, BlockPos waterPos, BlockPos from, Set<BlockPos> excluded) {
        BlockPos surface = Homes.waterSurface(level, waterPos);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int maxUp = Mth.clamp((int)(from.getY() - surface.getY() + 1), (int)1, (int)4);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= maxUp; ++dy) {
                BlockPos stand;
                BlockPos candidate = surface.relative(dir).offset(0, dy, 0);
                if (!Homes.isDryLand(level, candidate) && !Homes.isDryLand(level, candidate.above())) continue;
                BlockPos blockPos = stand = Homes.isDryLand(level, candidate) ? candidate : candidate.above();
                if (Homes.isWater(level, stand) || excluded.contains(stand)) continue;
                int dyFrom = Math.abs(stand.getY() - from.getY());
                double score = stand.distSqr((Vec3i)from) + (double)(dyFrom * dyFrom) * 16.0;
                if (!(score < bestScore)) continue;
                bestScore = score;
                best = stand;
            }
        }
        return Optional.ofNullable(best);
    }

    public static Optional<BlockPos> findShoreStandNearWater(LevelReader level, BlockPos waterPos, BlockPos from, Set<BlockPos> excluded) {
        BlockPos surface = Homes.waterSurface(level, waterPos);
        Optional<BlockPos> direct = Homes.findShoreStand(level, surface, from, excluded);
        if (direct.isPresent()) {
            return direct;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= 1; ++dy) {
                Optional<BlockPos> shore;
                BlockPos neighbor = surface.relative(dir).offset(0, dy, 0);
                if (!Homes.chunkFullyLoaded(level, neighbor.getX(), neighbor.getZ()) || !Homes.isWater(level, neighbor) || !(shore = Homes.findShoreStand(level, neighbor, from, excluded)).isPresent()) continue;
                return shore;
            }
        }
        return Optional.empty();
    }

    public static boolean isDryLand(LevelReader level, BlockPos pos) {
        if (!Homes.chunkFullyLoaded(level, pos.getX(), pos.getZ())) {
            return false;
        }
        if (Homes.isWater(level, pos)) {
            return false;
        }
        BlockState stand = level.getBlockState(pos);
        if (!stand.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        BlockPos ground = pos.below();
        if (Homes.isWater(level, ground)) {
            return false;
        }
        BlockState below = level.getBlockState(ground);
        return !below.isAir() && below.getFluidState().isEmpty();
    }

    /**
     * Non-blocking water test. {@code Level.getFluidState(BlockPos)} fetches the chunk with
     * {@code load=true}, which allocates a fresh empty chunk on every unloaded column — a single
     * 128-radius water scan can allocate hundreds of thousands of chunks and stall the server for
     * seconds. Reading the fluid off {@code getBlockState} uses {@code load=false} (shared empty
     * chunk, never blocks, never allocates) and reports the same water on loaded chunks.
     */
    private static boolean isWater(LevelReader level, BlockPos pos) {
        BlockState state = Homes.blockStateIfLoaded(level, pos);
        return state != null && state.getFluidState().is(FluidTags.WATER);
    }

    /**
     * True only when the chunk holding {@code pos} is fully generated and loaded. On the server,
     * {@link LevelReader#hasChunkAt} is true for a chunk holder that exists but is still
     * mid-generation, and reading a block from it then joins the generation future — a
     * multi-second server-thread stall during chunk loading. {@code getChunkNow} returns null for
     * any not-yet-loaded chunk, so callers can skip ungenerated columns non-blockingly.
     */
    private static boolean chunkFullyLoaded(LevelReader level, int x, int z) {
        if (level instanceof ServerLevel sl) {
            return sl.getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
        }
        return level.hasChunk(x >> 4, z >> 4);
    }

    /** Block state from the loaded chunk only; null when the chunk is unloaded or mid-generation. */
    @Nullable
    private static BlockState blockStateIfLoaded(LevelReader level, BlockPos pos) {
        if (level instanceof ServerLevel sl) {
            LevelChunk chunk = sl.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? null : chunk.getBlockState(pos);
        }
        return level.getBlockState(pos);
    }

    /** Heightmap height from the loaded chunk only; {@link Integer#MIN_VALUE} when not loaded. */
    private static int heightIfLoaded(LevelReader level, int x, int z) {
        if (level instanceof ServerLevel sl) {
            LevelChunk chunk = sl.getChunkSource().getChunkNow(x >> 4, z >> 4);
            return chunk == null ? Integer.MIN_VALUE : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    public static int shelterScore(Level level, BlockPos stand) {
        int score = 0;
        BlockState canopy = level.getBlockState(stand.above(2));
        if (!canopy.isAir() && (canopy.canOcclude() || canopy.is(BlockTags.LEAVES))) {
            ++score;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (!level.getBlockState(stand.relative(dir).above()).canOcclude()) continue;
            ++score;
            break;
        }
        return score;
    }

    public static boolean isSheltered(Level level, BlockPos stand) {
        return Homes.shelterScore(level, stand) > 0;
    }

    public static Optional<BlockPos> findBetterShelteredHome(Level level, BlockPos current, int searchRadius, int waterRadius, boolean requireWater) {
        if (level.isRaining() || level.isRainingAt(current)) {
            searchRadius += EthologicalConfig.CONFIG.comfort.rainShelterSearchBonus.get();
        }
        int currentScore = Homes.shelterScore(level, current);
        if (currentScore >= 2) {
            return Optional.empty();
        }
        // Collect improving candidates first; validate water only on winners so the
        // expensive water scan isn't paid for every sheltered spot in range.
        java.util.ArrayList<ShelterCandidate> candidates = new java.util.ArrayList<ShelterCandidate>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int ring = 0; ring <= searchRadius; ++ring) {
            for (int dy = -1; dy <= 2; ++dy) {
                for (int dx = -ring; dx <= ring; ++dx) {
                    int stepZ = Math.abs(dx) == ring ? 1 : Math.max(1, 2 * ring);
                    for (int dz = -ring; dz <= ring; dz += stepZ) {
                        int score;
                        pos.set(current.getX() + dx, current.getY() + dy, current.getZ() + dz);
                        if (!Homes.isDryLand((LevelReader)level, (BlockPos)pos) || (score = Homes.shelterScore(level, (BlockPos)pos)) <= currentScore) continue;
                        if (!Homes.isCliffSafe(level, pos)) {
                            continue;
                        }
                        candidates.add(new ShelterCandidate(pos.immutable(), score, pos.distSqr((Vec3i)current)));
                    }
                }
            }
        }
        candidates.sort((a, b) -> {
            if (a.score() != b.score()) {
                return Integer.compare(b.score(), a.score());
            }
            return Double.compare(a.distSqr(), b.distSqr());
        });
        for (ShelterCandidate candidate : candidates) {
            if (requireWater && !Homes.hasAccessibleWater((LevelReader)level, candidate.pos(), waterRadius)) continue;
            return Optional.of(candidate.pos());
        }
        return Optional.empty();
    }

    private record ShelterCandidate(BlockPos pos, int score, double distSqr) {
    }

    /** Horizontal reach (in blocks) of the cliff scan around a home/sleep stand. */
    private static final int CLIFF_SCAN_RADIUS = 3;
    /** Maximum drop (in blocks) allowed within the cliff scan before a spot counts as a cliff edge. */
    private static final int CLIFF_MAX_DROP = 3;

    /**
     * True when the ground within {@link #CLIFF_SCAN_RADIUS} blocks of {@code stand} does not
     * drop more than {@link #CLIFF_MAX_DROP} blocks — i.e. the spot isn't on a sheer cliff where
     * herd-mates could be pushed off. Water columns are compared against their surface, so
     * pond/river edges stay acceptable.
     */
    public static boolean isCliffSafe(Level level, BlockPos stand) {
        return Homes.isCliffSafe(level, stand, CLIFF_SCAN_RADIUS, CLIFF_MAX_DROP);
    }

    public static boolean isCliffSafe(Level level, BlockPos stand, int radius, int maxDrop) {
        int baseY = Homes.columnSurfaceY(level, stand.getX(), stand.getZ());
        if (baseY == Integer.MIN_VALUE) {
            return false;
        }
        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int neighborY = Homes.columnSurfaceY(level, stand.getX() + dx, stand.getZ() + dz);
                if (neighborY != Integer.MIN_VALUE && baseY - neighborY > maxDrop) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Nearest dry-land stand within {@code radius} that is not on a cliff edge. */
    public static Optional<BlockPos> findCliffSafeStand(Level level, BlockPos center, int radius) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int ring = 0; ring <= radius; ++ring) {
            for (int dx = -ring; dx <= ring; ++dx) {
                for (int dz = -ring; dz <= ring; ++dz) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    for (int dy = -1; dy <= 2; ++dy) {
                        pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        if (!Homes.isDryLand(level, pos) || !Homes.isCliffSafe(level, pos)) {
                            continue;
                        }
                        return Optional.of(pos.immutable());
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Best dry, cliff-safe stand within {@code radius} whose column is at least {@code spacing}+1
     * blocks away from every occupied column; ranked like {@link #findBetterShelteredHome}
     * (shelter score first, then distance). {@code spacing} 0 excludes only the exact occupied
     * columns, so no two animals share a block while adjacent spots stay allowed.
     */
    public static Optional<BlockPos> findUnoccupiedStand(Level level, BlockPos center, int radius,
                                                         Set<BlockPos> occupied, int spacing) {
        java.util.ArrayList<ShelterCandidate> candidates = new java.util.ArrayList<ShelterCandidate>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int ring = 0; ring <= radius; ++ring) {
            for (int dy = -1; dy <= 2; ++dy) {
                for (int dx = -ring; dx <= ring; ++dx) {
                    int stepZ = Math.abs(dx) == ring ? 1 : Math.max(1, 2 * ring);
                    for (int dz = -ring; dz <= ring; dz += stepZ) {
                        pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        if (!Homes.isDryLand(level, pos) || !Homes.isCliffSafe(level, pos)) {
                            continue;
                        }
                        boolean blocked = false;
                        for (BlockPos occ : occupied) {
                            if (Math.abs(pos.getX() - occ.getX()) <= spacing
                                    && Math.abs(pos.getZ() - occ.getZ()) <= spacing) {
                                blocked = true;
                                break;
                            }
                        }
                        if (blocked) {
                            continue;
                        }
                        candidates.add(new ShelterCandidate(pos.immutable(), Homes.shelterScore(level, pos), pos.distSqr((Vec3i)center)));
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        candidates.sort((a, b) -> {
            if (a.score() != b.score()) {
                return Integer.compare(b.score(), a.score());
            }
            return Double.compare(a.distSqr(), b.distSqr());
        });
        return Optional.of(candidates.get(0).pos());
    }

    /**
     * Nearest stand near {@code center} (preferring {@code center} itself) that is both
     * cliff-safe and has accessible water — used when establishing or relocating a home.
     */
    public static Optional<BlockPos> findSafeHomeStand(Level level, BlockPos center, int radius, int waterRadius) {
        if (Homes.isCliffSafe(level, center) && Homes.hasAccessibleWater(level, center, waterRadius)) {
            return Optional.of(center);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int ring = 1; ring <= radius; ++ring) {
            for (int dx = -ring; dx <= ring; ++dx) {
                for (int dz = -ring; dz <= ring; ++dz) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    for (int dy = -1; dy <= 2; ++dy) {
                        pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        if (!Homes.isDryLand(level, pos)
                                || !Homes.isCliffSafe(level, pos)
                                || !Homes.hasAccessibleWater(level, pos, waterRadius)) {
                            continue;
                        }
                        return Optional.of(pos.immutable());
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Standing surface Y at a column: the dry-land stand Y if present, otherwise the water
     * surface Y for water columns, otherwise {@link Integer#MIN_VALUE} (unloaded or impassable).
     */
    private static int columnSurfaceY(Level level, int x, int z) {
        BlockPos stand = Homes.surfaceStand(level, x, z);
        return stand != null ? stand.getY() : Integer.MIN_VALUE;
    }

    public static boolean isOpenWater(Level level, BlockPos pos, int radius) {
        if (!Homes.isWater(level, pos) && !Homes.isWater(level, pos.below())) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = -1; dy <= 1; ++dy) {
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (!Homes.isDryLand((LevelReader)level, (BlockPos)cursor)) continue;
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isOpenWater(Level level, BlockPos pos) {
        return Homes.isOpenWater(level, pos, 2);
    }

    public static boolean isReachable(PathfinderMob mob, BlockPos target) {
        long now = mob.level().getGameTime();
        long key = reachKey(target);
        ReachVerdict memo = reachMemoGet(mob.getUUID(), key, now);
        if (memo != null) {
            return memo.reachable();
        }
        Path path = mob.getNavigation().createPath(target, 0);
        boolean canReach = path != null && path.canReach();
        boolean reachable = canReach || path != null && path.getTarget().closerThan((Vec3i)target, 3.0);
        reachMemoPut(mob.getUUID(), key, now, reachable, canReach);
        return reachable;
    }

    /** Like {@link #isReachable}, but rejects "almost" paths that stop short of fences/walls. */
    public static boolean isStrictlyReachable(PathfinderMob mob, BlockPos target) {
        long now = mob.level().getGameTime();
        long key = reachKey(target);
        ReachVerdict memo = reachMemoGet(mob.getUUID(), key, now);
        if (memo != null) {
            return memo.strict();
        }
        Path path = mob.getNavigation().createPath(target, 0);
        boolean canReach = path != null && path.canReach();
        // Record both flags so a later isReachable for the same target is served correctly.
        boolean reachable = canReach || path != null && path.getTarget().closerThan((Vec3i)target, 3.0);
        reachMemoPut(mob.getUUID(), key, now, reachable, canReach);
        return canReach;
    }

    public static boolean pathCrossesOpenWater(Level level, Path path) {
        if (path == null) {
            return true;
        }
        // Sample every 3rd node: consecutive path nodes overlap the radius-2 sweep, and one missed
        // water node can't strand the animal — the path continues past it.
        for (int i = 0; i < path.getNodeCount(); i += 3) {
            if (!Homes.isOpenWater(level, path.getNodePos(i))) continue;
            return true;
        }
        return false;
    }

    public static boolean migrationCorridorOk(Level level, BlockPos from, double heading, double range) {
        if (range < 8.0) {
            return true;
        }
        float cos = Mth.cos((float) heading);
        float sin = Mth.sin((float) heading);
        int prevY = Homes.surfaceStandY(level, from.getX(), from.getZ());
        boolean havePrev = prevY != Integer.MIN_VALUE;
        int climb = 0;
        for (double dist = 8.0; dist <= range + 0.01; dist += 8.0) {
            int x = Mth.floor((double) from.getX() + 0.5 + (double) cos * dist);
            int z = Mth.floor((double) from.getZ() + 0.5 + (double) sin * dist);
            // Unloaded terrain ahead is unknown — do not treat it as a blockage that forces a turn.
            if (!Homes.chunkFullyLoaded(level, x, z)) {
                continue;
            }
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            BlockPos stand = surface.above();
            // A corridor crossing a hard hazard (lava, fire) is impassable — reject the
            // heading before the pathfinder is even asked.
            if (Avoidance.isHardHazard(level, stand) || Avoidance.isHardHazard(level, surface)) {
                return false;
            }
            BlockPos dry = Homes.isDryLand(level, stand) ? stand : (Homes.isDryLand(level, surface) ? surface : null);
            int y;
            if (dry != null) {
                y = dry.getY();
            } else if (Homes.isWaterColumn(level, surface)) {
                // Water is passable for nomadic herds — wade/swim across, don't turn back.
                y = Homes.waterLevelY(level, surface);
            } else {
                return false;
            }
            if (havePrev) {
                int dy = y - prevY;
                if (Math.abs(dy) > 6) {
                    return false;
                }
                if (dy > 0 && (climb += dy) > 12) {
                    return false;
                }
            }
            prevY = y;
            havePrev = true;
        }
        return true;
    }

    /** True when the heightmap column is a water column (river/lake/ocean) rather than dry land. */
    private static boolean isWaterColumn(Level level, BlockPos surface) {
        return Homes.isWater(level, surface)
                || Homes.isWater(level, surface.below())
                || Homes.isWater(level, surface.above());
    }

    /** Topmost water block Y in the column whose heightmap surface is given. */
    private static int waterLevelY(Level level, BlockPos surface) {
        BlockPos water = null;
        if (Homes.isWater(level, surface)) {
            water = surface;
        } else if (Homes.isWater(level, surface.above())) {
            water = surface.above();
        } else if (Homes.isWater(level, surface.below())) {
            water = surface.below();
        }
        if (water == null) {
            return surface.getY();
        }
        BlockPos top = water;
        while (Homes.isWater(level, top.above())) {
            top = top.above();
        }
        return top.getY();
    }

    private static int surfaceStandY(Level level, int x, int z) {
        BlockPos stand = Homes.surfaceStand(level, x, z);
        return stand != null ? stand.getY() : Integer.MIN_VALUE;
    }

    /**
     * Stand block at a column's surface: the dry-land stand if present, otherwise the topmost
     * water surface for water columns, otherwise {@code null} when the column is unloaded or
     * impassable. Used to anchor path targets on the real terrain instead of raw positions.
     */
    @Nullable
    public static BlockPos surfaceStand(Level level, int x, int z) {
        long now = level.getGameTime();
        Map<Long, BlockPos> inner = SURFACE_STAND_MEMO.get(level);
        Long stamp = SURFACE_STAND_STAMP.get(level);
        if (inner != null && stamp != null && stamp.longValue() == now) {
            BlockPos cached = inner.get(surfaceStandKey(x, z));
            if (cached != null) {
                return cached == SURFACE_STAND_NULL ? null : cached;
            }
        } else {
            // New tick for this level: reset the per-level memo.
            inner = new java.util.HashMap<Long, BlockPos>();
            SURFACE_STAND_MEMO.put(level, inner);
            SURFACE_STAND_STAMP.put(level, now);
        }
        BlockPos result;
        if (!Homes.chunkFullyLoaded(level, x, z)) {
            result = null;
        } else {
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            BlockPos stand = surface.above();
            if (Homes.isDryLand(level, stand)) {
                result = stand;
            } else if (Homes.isDryLand(level, surface)) {
                result = surface;
            } else if (Homes.isWaterColumn(level, surface)) {
                result = new BlockPos(surface.getX(), Homes.waterLevelY(level, surface), surface.getZ());
            } else {
                result = null;
            }
        }
        inner.put(surfaceStandKey(x, z), result != null ? result : SURFACE_STAND_NULL);
        return result;
    }

    public static boolean pathHasSteepStep(Path path, int maxStepY) {
        if (path == null || path.getNodeCount() < 2) {
            return false;
        }
        for (int i = 1; i < path.getNodeCount(); ++i) {
            int dy = Math.abs(path.getNodePos(i).getY() - path.getNodePos(i - 1).getY());
            if (dy <= maxStepY) continue;
            return true;
        }
        return false;
    }

    public static boolean pathHasHorizontalProgress(Path path, BlockPos start, double minHorizontal) {
        double dz;
        if (path == null || path.getNodeCount() <= 0) {
            return false;
        }
        BlockPos end = path.getNodePos(path.getNodeCount() - 1);
        double dx = (double)end.getX() + 0.5 - ((double)start.getX() + 0.5);
        return dx * dx + (dz = (double)end.getZ() + 0.5 - ((double)start.getZ() + 0.5)) * dz >= minHorizontal * minHorizontal;
    }
}

