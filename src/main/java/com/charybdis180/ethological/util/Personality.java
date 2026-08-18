package com.charybdis180.ethological.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-system personality salts so one UUID is not consistently early-to-bed,
 * slow-metabolism, and first-in-every-throttle-slot.
 */
public final class Personality {
    private Personality() {
    }

    /** Raw (id, system) hash memo, keyed by a mix of the UUID halves and the system hash.
     *  The four hot sleep/need systems are string constants, so a 2^-32 system-hash collision
     *  is the only theoretical cross-system alias — harmless for per-mob pacing. */
    private static final Map<Long, Integer> SALT_MEMO = new ConcurrentHashMap<Long, Integer>();
    private static final int SALT_MEMO_LIMIT = 8192;

    /** Deterministic int in {@code [0, bound)} for {@code id} + {@code system}. */
    public static int salt(UUID id, String system, int bound) {
        if (bound <= 0) {
            return 0;
        }
        // Manual Arrays.hashCode over {msb, lsb, system} — identical to Objects.hash, no boxing.
        int h = 1;
        h = 31 * h + Long.hashCode(id.getMostSignificantBits());
        h = 31 * h + Long.hashCode(id.getLeastSignificantBits());
        h = 31 * h + system.hashCode();
        return Math.floorMod(h, bound);
    }

    /** Like {@link #salt}, but caches the raw (id, system) hash so per-entity tick handlers
     *  that consult the same system every tick (bedtime, wake intervals) stop re-hashing the
     *  UUID on every call. The bound is applied on top of the cached hash, so results are
     *  identical to {@link #salt}. Cleared wholesale at {@link #SALT_MEMO_LIMIT}. */
    public static int memoizedSalt(UUID id, String system, int bound) {
        if (bound <= 0) {
            return 0;
        }
        long key = id.getMostSignificantBits() ^ (id.getLeastSignificantBits() * 0x9E3779B97F4A7C15L)
                ^ ((long)system.hashCode() * 0x100000001B3L);
        Integer cached = SALT_MEMO.get(key);
        if (cached != null) {
            return Math.floorMod(cached.intValue(), bound);
        }
        int h = 1;
        h = 31 * h + Long.hashCode(id.getMostSignificantBits());
        h = 31 * h + Long.hashCode(id.getLeastSignificantBits());
        h = 31 * h + system.hashCode();
        if (SALT_MEMO.size() >= SALT_MEMO_LIMIT) {
            SALT_MEMO.clear();
        }
        SALT_MEMO.put(key, h);
        return Math.floorMod(h, bound);
    }

    /** True when {@code (salt(id,system) + now) % interval == 0}. */
    public static boolean tickGate(UUID id, String system, long now, long interval) {
        if (interval <= 0L) {
            return true;
        }
        long salt = Personality.salt(id, system, Integer.MAX_VALUE);
        return Math.floorMod(salt + now, interval) == 0L;
    }

    public static float unit(UUID id, String system) {
        return Personality.salt(id, system, 1000) / 1000.0f;
    }

    public static double angleRadians(UUID id, String system) {
        return Math.toRadians(Personality.salt(id, system, 360));
    }

    public static float clampPace(UUID id, String system) {
        return 0.9f + Personality.unit(id, system) * 0.2f;
    }

    public static int bounded(UUID id, String system, int minInclusive, int maxExclusive) {
        int span = Math.max(1, maxExclusive - minInclusive);
        return minInclusive + Personality.salt(id, system, span);
    }

    public static long boundedLong(UUID id, String system, long minInclusive, long maxExclusive) {
        long span = Math.max(1L, maxExclusive - minInclusive);
        return minInclusive + Math.floorMod((long)Personality.salt(id, system, Integer.MAX_VALUE), span);
    }
}
