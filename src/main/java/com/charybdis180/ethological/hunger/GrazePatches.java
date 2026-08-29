package com.charybdis180.ethological.hunger;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.herd.HerdData;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.hunger.GrazePatchData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class GrazePatches {
    public static final int PATCH_GRAZE_RADIUS = 8;
    public static final int PATCH_SEARCH_RADIUS = 32;
    public static final int PATCH_NEIGHBOR_RADIUS = 3;
    public static final int EATS_PER_PATCH = 3;
    public static final int FULL_LINGER_TICKS_MIN = 40;
    public static final int FULL_LINGER_TICKS_SPAN = 40;
    public static final long PATCH_BLACKLIST_TICKS = 6000L;
    /** Per-chunk-region food presence cache for {@link #hasFoodNearby}, mirroring the water
     * miss/hit caches in {@link Homes}. Spawn gates probe foodless areas repeatedly; a cached
     * miss lets those probes short-circuit without a block scan. Shorter TTL than water because
     * grazing and grass regrowth change food availability faster than water sources. */
    private static final long FOOD_CACHE_TICKS = 1200L;
    private static final int FOOD_CACHE_LIMIT = 8192;
    private static final Map<Long, FoodScanRecord> FOOD_SCAN_CACHE = new ConcurrentHashMap<Long, FoodScanRecord>();

    private GrazePatches() {
    }

    public static boolean isPatchLeader(Animal animal) {
        if (!animal.hasData(ModAttachments.HERD_DATA)) {
            return true;
        }
        return ((HerdData)animal.getData(ModAttachments.HERD_DATA)).alpha();
    }

    public static Optional<BlockPos> effectivePatch(Animal animal) {
        if (GrazePatches.isPatchLeader(animal)) {
            return animal.hasData(ModAttachments.GRAZE_PATCH) ? Optional.of(((GrazePatchData)animal.getData(ModAttachments.GRAZE_PATCH)).center()) : Optional.empty();
        }
        return Homes.herdAlpha(animal).filter(alpha -> alpha.hasData(ModAttachments.GRAZE_PATCH)).map(alpha -> ((GrazePatchData)alpha.getData(ModAttachments.GRAZE_PATCH)).center());
    }

    public static boolean isFoodAt(LevelReader level, BlockPos pos, Set<Block> foodBlocks) {
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        BlockState above = level.getBlockState(pos.above());
        return foodBlocks.contains(state.getBlock()) || foodBlocks.contains(above.getBlock());
    }

    public static boolean isStandForFood(LevelReader level, BlockPos pos, Set<Block> foodBlocks) {
        BlockState state = level.getBlockState(pos);
        Block atPos = state.getBlock();
        Block above = level.getBlockState(pos.above()).getBlock();
        boolean posIsFood = foodBlocks.contains(atPos);
        boolean aboveIsFood = foodBlocks.contains(above);
        // The animal occupies pos.above() in both conventions: grass food sits AT pos (mob
        // stands on top of it), and a crop sits ABOVE pos (mob stands in the cell the crop
        // grows in). A water-immersed stand would force the mob to swim to graze — reject
        // shoreline-flooded grass and crops. Bare LevelReader presence probes fall through.
        BlockPos stand = pos.above();
        if (level instanceof Level realLevel && !Homes.isDryLand(realLevel, stand)) {
            return false;
        }
        if (posIsFood && atPos == Blocks.GRASS_BLOCK) {
            return true;
        }
        if (aboveIsFood && above != Blocks.GRASS_BLOCK) {
            return !posIsFood || atPos == Blocks.GRASS_BLOCK;
        }
        return false;
    }

    public static Block foodBlockAtStand(LevelReader level, BlockPos stand, Set<Block> foodBlocks) {
        Block above = level.getBlockState(stand.above()).getBlock();
        if (foodBlocks.contains(above) && above != Blocks.GRASS_BLOCK) {
            return above;
        }
        return level.getBlockState(stand).getBlock();
    }

    public static int foodDensity(LevelReader level, BlockPos center, Set<Block> foodBlocks, int radius) {
        int count = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; ++dx) {
            block1: for (int dz = -radius; dz <= radius; ++dz) {
                for (int dy = -1; dy <= 1; ++dy) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!GrazePatches.isFoodAt(level, (BlockPos)cursor, foodBlocks)) continue;
                    ++count;
                    continue block1;
                }
            }
        }
        return count;
    }

    /**
     * Cheap yes/no variant of {@link #foodDensity}: returns true as soon as any food block is
     * found instead of counting every block in the radius. Good for spawn gates and quick probes.
     * Results are cached per (chunk region, Y-bucket, radius) so repeated probes of foodless
     * areas skip the block scan entirely. Only {@link Level} readers are cached; bare
     * {@link LevelReader} usage (no game time) falls through to a raw scan.
     */
    public static boolean hasFoodNearby(LevelReader level, BlockPos center, Set<Block> foodBlocks, int radius) {
        long key = GrazePatches.foodPresenceKey(level, center, radius);
        FoodScanRecord rec = FOOD_SCAN_CACHE.get(key);
        if (rec != null) {
            if (level instanceof Level && ((Level)level).getGameTime() >= rec.expiryGameTime) {
                FOOD_SCAN_CACHE.remove(key);
                rec = null;
            } else if (rec.found.containsAll(foodBlocks)) {
                return true;
            } else if (rec.missed.containsAll(foodBlocks)) {
                return false;
            }
        }
        Block firstFood = GrazePatches.scanFirstFood(level, center, foodBlocks, radius);
        if (!(level instanceof Level)) {
            return firstFood != null;
        }
        long gameTime = ((Level)level).getGameTime();
        if (rec == null) {
            if (FOOD_SCAN_CACHE.size() > FOOD_CACHE_LIMIT) {
                FOOD_SCAN_CACHE.clear();
            }
            rec = new FoodScanRecord(gameTime + FOOD_CACHE_TICKS);
            FOOD_SCAN_CACHE.put(key, rec);
        }
        if (firstFood != null) {
            rec.found.add(firstFood);
        } else {
            rec.missed.addAll(foodBlocks);
        }
        return firstFood != null;
    }

    /** Scans the ring around {@code center} and returns the first food block found, or null. */
    private static Block scanFirstFood(LevelReader level, BlockPos center, Set<Block> foodBlocks, int radius) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                for (int dy = -1; dy <= 1; ++dy) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!GrazePatches.isFoodAt(level, (BlockPos)cursor, foodBlocks)) continue;
                    BlockState state = level.getBlockState((BlockPos)cursor);
                    if (foodBlocks.contains(state.getBlock())) {
                        return state.getBlock();
                    }
                    return level.getBlockState(cursor.above()).getBlock();
                }
            }
        }
        return null;
    }

    /** Key on the block's chunk (XZ), a Y-bucket, the search radius, and the dimension. */
    private static long foodPresenceKey(LevelReader level, BlockPos center, int radius) {
        long key = (long)(center.getX() >> 4) << 32 ^ (long)(center.getZ() >> 4) & 0xFFFFFFFFL;
        key = key * 31L + (long)(center.getY() >> 4);
        key = key * 31L + (long)radius;
        return key * 31L + (long)level.hashCode();
    }

    /** Clears the food presence cache so freshly grown/broken food is found promptly. */
    public static void invalidateFoodCaches() {
        FOOD_SCAN_CACHE.clear();
    }

    /** Accumulates which food blocks are known present/absent in a chunk region, so overlapping
     * species food sets (e.g. cow and sheep both eating grass) reuse the same cached scan. */
    private static final class FoodScanRecord {
        final long expiryGameTime;
        final Set<Block> found;
        final Set<Block> missed;

        FoodScanRecord(long expiryGameTime) {
            this.expiryGameTime = expiryGameTime;
            this.found = new HashSet<Block>();
            this.missed = new HashSet<Block>();
        }
    }

    /**
     * Two-phase patch search: the cheap ring scan collects only the nearest {@code CANDIDATE_LIMIT}
     * food positions (ring order = nearest first), then the expensive density probe runs on just those.
     * Since {@code ranked = density*1000 - ring} is density-dominated, the nearest dense patch still wins.
     */
    public static Optional<BlockPos> findPatch(Level level, BlockPos origin, int searchRadius, Set<Block> foodBlocks, Predicate<BlockPos> allow) {
        final int candidateLimit = 5;
        ArrayList<BlockPos> candidates = new ArrayList<BlockPos>();
        int[] candidateRings = new int[candidateLimit];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean done = false;
        for (int ring = 0; ring <= searchRadius && !done; ++ring) {
            for (int dy = -2; dy <= 2; ++dy) {
                for (int dx = -ring; dx <= ring; ++dx) {
                    for (int dz = -ring; dz <= ring; ++dz) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                        if (!allow.test((BlockPos)pos) || !GrazePatches.isFoodAt((LevelReader)level, (BlockPos)pos, foodBlocks)) continue;
                        candidates.add(pos.immutable());
                        candidateRings[candidates.size() - 1] = ring;
                        if (candidates.size() >= candidateLimit) {
                            done = true;
                            break;
                        }
                    }
                    if (done) break;
                }
                if (done) break;
            }
        }
        BlockPos best = null;
        int bestScore = 0;
        for (int i = 0; i < candidates.size(); ++i) {
            BlockPos candidate = candidates.get(i);
            int score = GrazePatches.foodDensity((LevelReader)level, candidate, foodBlocks, 3);
            int ranked = score * 1000 - candidateRings[i];
            if (best != null && ranked <= bestScore) continue;
            bestScore = ranked;
            best = candidate;
        }
        return Optional.ofNullable(best);
    }

    public static BlockPos resolveFoodBlock(Level level, BlockPos standOrPlant, Set<Block> foodBlocks) {
        BlockState above = level.getBlockState(standOrPlant.above());
        if (foodBlocks.contains(above.getBlock()) && !above.is(Blocks.GRASS_BLOCK)) {
            return standOrPlant.above();
        }
        return standOrPlant;
    }
}

