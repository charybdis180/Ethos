package com.charybdis180.ethological.hunger;

import com.charybdis180.ethological.config.EthologicalConfig;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Schedules dirt→grass restore after grazing. Caps pending restores per chunk. */
public final class PastureRecovery {
    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> PENDING = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long2IntOpenHashMap> CHUNK_COUNTS = new HashMap<>();

    private PastureRecovery() {
    }

    private static int maxPendingPerChunk() {
        return EthologicalConfig.CONFIG.comfort.pastureRegrowMaxPerChunk.get();
    }

    private static long regrowDelayTicks() {
        return EthologicalConfig.CONFIG.comfort.pastureRegrowDelayTicks.get().longValue();
    }

    public static void schedule(ServerLevel level, BlockPos pos) {
        long key = pos.asLong();
        ResourceKey<Level> dim = level.dimension();
        Long2LongOpenHashMap pending = PENDING.computeIfAbsent(dim, d -> new Long2LongOpenHashMap());
        if (pending.containsKey(key)) {
            return;
        }
        long chunkKey = chunkKey(pos);
        Long2IntOpenHashMap counts = CHUNK_COUNTS.computeIfAbsent(dim, d -> new Long2IntOpenHashMap());
        if (counts.get(chunkKey) >= maxPendingPerChunk()) {
            return;
        }
        pending.put(key, level.getGameTime() + regrowDelayTicks());
        counts.addTo(chunkKey, 1);
    }

    public static void tick(ServerLevel level) {
        ResourceKey<Level> dim = level.dimension();
        Long2LongOpenHashMap pending = PENDING.get(dim);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        int budget = 8;
        Iterator<Long2LongOpenHashMap.Entry> it = pending.long2LongEntrySet().iterator();
        while (it.hasNext() && budget > 0) {
            Long2LongOpenHashMap.Entry entry = it.next();
            if (entry.getLongValue() > now) {
                continue;
            }
            BlockPos pos = BlockPos.of(entry.getLongKey());
            it.remove();
            decrementChunk(dim, pos);
            --budget;
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.is(Blocks.DIRT)) {
                continue;
            }
            if (level.getMaxLocalRawBrightness(pos.above()) < 9) {
                schedule(level, pos);
                continue;
            }
            level.setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        }
        if (pending.isEmpty()) {
            PENDING.remove(dim);
            CHUNK_COUNTS.remove(dim);
        }
    }

    public static void clear() {
        PENDING.clear();
        CHUNK_COUNTS.clear();
    }

    private static long chunkKey(BlockPos pos) {
        return BlockPos.asLong(pos.getX() >> 4, 0, pos.getZ() >> 4);
    }

    private static void decrementChunk(ResourceKey<Level> dim, BlockPos pos) {
        Long2IntOpenHashMap counts = CHUNK_COUNTS.get(dim);
        if (counts == null) {
            return;
        }
        long key = chunkKey(pos);
        int next = counts.get(key) - 1;
        if (next <= 0) {
            counts.remove(key);
        } else {
            counts.put(key, next);
        }
    }
}
