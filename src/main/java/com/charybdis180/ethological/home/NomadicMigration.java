package com.charybdis180.ethological.home;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.hunger.GrazePatches;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.hunger.HungerSettingsManager;
import com.charybdis180.ethological.hunger.SpeciesHungerSettings;
import com.charybdis180.ethological.thirst.Thirst;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared nomadic migration schedule (travel legs vs pause windows). The herd alpha
 * owns the pause timer so the whole herd marches and rests together.
 */
public final class NomadicMigration {
    public static final int LEG_DISTANCE_MIN = 16;
    public static final int LEG_DISTANCE_MAX = 32;
    public static final int PAUSE_TICKS_MIN = 200;
    public static final int PAUSE_TICKS_MAX = 400;
    /** Short refocus between legs when nobody needs to eat or drink. */
    public static final int REFOCUS_TICKS_MIN = 40;
    public static final int REFOCUS_TICKS_MAX = 80;
    /** Minimum ticks between committed heading turns so reversals cannot cascade. */
    public static final int TURN_COOLDOWN_TICKS = 600;
    /** Needs-pause length re-asserted each tick while the herd still wants food/drink,
     * so the pause cannot expire mid-meal and let MigrateGoal preempt a half-eaten bite. */
    public static final int NEEDS_PAUSE_SEGMENT = 200;
    /** Cap on a needs-pause: after this long without satisfying food/drink, resume
     * marching so a foodless/waterless region does not strand the herd in place. */
    public static final int NEEDS_HOLD_MAX_TICKS = 1200;
    /** March window granted after the needs-pause cap so the herd keeps searching
     * new areas instead of oscillating between hold and a single tick of marching. */
    public static final int NEEDS_MARCH_WINDOW = 2400;
    /** TTL for {@link #canSatisfyUrgentFoodNow}: the probe runs a patch scan plus a
     * reachability query on miss, so its verdict is memoized a couple of seconds. */
    private static final long URGENT_FOOD_MEMO_TICKS = 40L;
    private static final Map<UUID, long[]> URGENT_FOOD_MEMO = new ConcurrentHashMap<>();
    /** TTL for {@link #canSatisfyUrgentThirstNow}; see the food memo above. */
    private static final long URGENT_THIRST_MEMO_TICKS = 40L;
    private static final Map<UUID, long[]> URGENT_THIRST_MEMO = new ConcurrentHashMap<>();

    private NomadicMigration() {
    }

    public static boolean isNomadicHomeless(Animal animal) {
        return HomeSettingsManager.get(animal.getType())
                .map(SpeciesHomeSettings::nomadic)
                .orElse(false)
                && Homes.effectiveHome(animal).isEmpty();
    }

    /** True between migration legs — eat/drink may run if needed. */
    public static boolean isPaused(Animal animal) {
        if (!isNomadicHomeless(animal)) {
            return false;
        }
        long pauseUntil = pauseUntil(scheduleOwner(animal));
        long now = animal.level().getGameTime();
        return pauseUntil > 0L && now < pauseUntil;
    }

    /** True while marching a leg (non-urgent eat/drink should yield). */
    public static boolean isTraveling(Animal animal) {
        return isNomadicHomeless(animal) && !isPaused(animal);
    }

    public static boolean blocksNeedsGoals(Animal animal) {
        return isTraveling(animal);
    }

    /**
     * True when the herd should fully pause between legs to eat or drink rather
     * than just briefly refocusing. The alpha is the schedule owner, so its
     * state represents the whole herd's needs.
     */
    public static boolean shouldPauseForNeeds(Animal alpha) {
        boolean food = Hunger.hasHungerData(alpha) && Hunger.herdWantsFood(alpha);
        boolean thirst = Thirst.hasThirstData(alpha)
                && (Thirst.isUrgentlyThirsty(alpha) || Thirst.isDrinkDue(alpha));
        return food || thirst;
    }

    /** True when the schedule owner is urgently hungry or urgently thirsty —
     * migration must yield so the eat/drink goals can run instead of marching. */
    public static boolean urgentNeedsYield(Animal animal) {
        Animal owner = scheduleOwner(animal);
        if (Hunger.hasHungerData(owner) && Hunger.isUrgentlyHungry(owner)) {
            return true;
        }
        return Thirst.hasThirstData(owner) && Thirst.isUrgentlyThirsty(owner);
    }

    /** Enter a needs pause (sets MIGRATION_PAUSE_UNTIL and arms the hold cap) so
     * EatFoodGoal/DrinkWaterGoal get a real window to run. No-op when already paused. */
    public static void beginNeedsPause(Animal animal) {
        Animal owner = scheduleOwner(animal);
        long now = owner.level().getGameTime();
        long current = owner.getData(ModAttachments.MIGRATION_PAUSE_UNTIL);
        if (current > now) {
            return;
        }
        owner.setData(ModAttachments.MIGRATION_PAUSE_UNTIL, now + NEEDS_PAUSE_SEGMENT);
        owner.setData(ModAttachments.NEEDS_PAUSE_UNTIL, now + NEEDS_HOLD_MAX_TICKS);
    }

    /** Re-assert a needs pause each tick while the herd still wants food/drink so a
     * half-eaten meal is not cut off the moment the initial window expires. Once the
     * hold cap is hit (food/water genuinely absent nearby), end the pause and grant a
     * march window so the herd keeps searching new ground instead of being pinned. */
    public static void refreshNeedsPause(Animal animal, long now) {
        Animal owner = scheduleOwner(animal);
        if (owner != animal) {
            // Only the schedule owner maintains the pause; members just observe it.
            return;
        }
        // During the post-cap march window the herd is deliberately searching fresh
        // ground. Do not re-assert a needs pause or re-arm the hold cap mid-window:
        // otherwise the first leg finished inside the window re-pins the herd and the
        // march window never yields more than a single leg.
        if (isInNeedsMarchWindow(owner, now)) {
            return;
        }
        long pauseUntil = owner.getData(ModAttachments.MIGRATION_PAUSE_UNTIL);
        if (pauseUntil <= now) {
            if (owner.getData(ModAttachments.NEEDS_PAUSE_UNTIL) != 0L) {
                owner.removeData(ModAttachments.NEEDS_PAUSE_UNTIL);
            }
            return;
        }
        if (!shouldPauseForNeeds(owner)) {
            return;
        }
        long cap = owner.getData(ModAttachments.NEEDS_PAUSE_UNTIL);
        if (cap == 0L) {
            // A needs pause should have armed the cap; do it now so the pause is
            // held long enough for a real meal (capped so a foodless area resumes).
            cap = now + NEEDS_HOLD_MAX_TICKS;
            owner.setData(ModAttachments.NEEDS_PAUSE_UNTIL, cap);
        } else if (now >= cap) {
            // Cap reached without satisfying the herd: end the needs pause and let
            // the herd resume marching to search fresh ground.
            owner.setData(ModAttachments.MIGRATION_PAUSE_UNTIL, 0L);
            owner.removeData(ModAttachments.NEEDS_PAUSE_UNTIL);
            owner.setData(ModAttachments.NEEDS_MARCH_UNTIL, now + NEEDS_MARCH_WINDOW);
            return;
        }
        owner.setData(ModAttachments.MIGRATION_PAUSE_UNTIL, now + NEEDS_PAUSE_SEGMENT);
    }

    /** True while the post-cap march window suppresses urgent-need migration interrupts,
     * so a foodless region is actually searched rather than re-pausing every few ticks. */
    public static boolean isInNeedsMarchWindow(Animal animal, long now) {
        Animal owner = scheduleOwner(animal);
        long until = owner.getData(ModAttachments.NEEDS_MARCH_UNTIL);
        if (until > now) {
            return true;
        }
        if (until != 0L) {
            owner.removeData(ModAttachments.NEEDS_MARCH_UNTIL);
        }
        return false;
    }

    /** True when the schedule owner is urgently hungry AND reachable food is nearby.
     * The post-cap march window only suppresses urgent migration interrupts while the
     * herd is actually searching foodless ground; once reachable food is available, a
     * starving alpha must break the march and eat. Mirrors EatFoodGoal's own patch +
     * reachability gates so the verdict matches what the eat goal would actually do. */
    public static boolean canSatisfyUrgentFoodNow(Animal owner) {
        if (!Hunger.hasHungerData(owner) || !Hunger.isUrgentlyHungry(owner)) {
            return false;
        }
        long now = owner.level().getGameTime();
        UUID key = owner.getUUID();
        long[] rec = URGENT_FOOD_MEMO.get(key);
        if (rec != null && now - rec[0] < URGENT_FOOD_MEMO_TICKS) {
            return rec[1] == 1L;
        }
        boolean result = false;
        Optional<SpeciesHungerSettings> s = HungerSettingsManager.get(owner.getType());
        if (s.isPresent()) {
            int radius = s.get().patchSearchRadius();
            if (GrazePatches.hasFoodNearby(owner.level(), owner.blockPosition(), s.get().foodBlocks(), radius)) {
                Optional<BlockPos> patch = GrazePatches.findPatch((Level) owner.level(), owner.blockPosition(),
                        Math.min(radius, 32), s.get().foodBlocks(), p -> true);
                result = patch.isPresent() && Homes.isReachable((PathfinderMob) owner, patch.get());
            }
        }
        if (URGENT_FOOD_MEMO.size() > 128) {
            URGENT_FOOD_MEMO.clear();
        }
        URGENT_FOOD_MEMO.put(key, new long[]{now, result ? 1L : 0L});
        return result;
    }

    /**
     * Water twin of {@link #canSatisfyUrgentFoodNow}: true when the schedule owner is
     * urgently thirsty AND a water surface stands within the species search radius. The
     * post-cap march window only suppresses urgent migration interrupts while the herd is
     * searching waterless ground; the moment reachable water appears (rain pool, player-
     * placed source), a dehydrating alpha must break the march and drink instead of
     * marching past it. Memoized like the food probe; the candidate list comes from the
     * shared WATER_CANDIDATE_CACHE so repeat probes cost one map read.
     */
    public static boolean canSatisfyUrgentThirstNow(Animal owner) {
        if (!Thirst.hasThirstData(owner) || !Thirst.isUrgentlyThirsty(owner)) {
            return false;
        }
        long now = owner.level().getGameTime();
        UUID key = owner.getUUID();
        long[] rec = URGENT_THIRST_MEMO.get(key);
        if (rec != null && now - rec[0] < URGENT_THIRST_MEMO_TICKS) {
            return rec[1] == 1L;
        }
        boolean result = false;
        Optional<BlockPos> home = com.charybdis180.ethological.home.Homes.effectiveHome(owner);
        int radius = HomeSettingsManager.get(owner.getType())
                .map(s -> s.waterSearchRadius())
                .orElse(com.charybdis180.ethological.thirst.goal.DrinkWaterGoal.HOMELESS_WATER_RADIUS);
        BlockPos center = home.isPresent() ? home.get() : owner.blockPosition();
        List<BlockPos> candidates = Homes.findWaterCandidates(
                (Level) owner.level(), center, radius, java.util.Set.of(), 4);
        for (BlockPos surface : candidates) {
            if (Homes.isReachable((PathfinderMob) owner, surface)) {
                result = true;
                break;
            }
        }
        if (URGENT_THIRST_MEMO.size() > 128) {
            URGENT_THIRST_MEMO.clear();
        }
        URGENT_THIRST_MEMO.put(key, new long[]{now, result ? 1L : 0L});
        return result;
    }

    public static void beginTravelLeg(Animal animal) {
        scheduleOwner(animal).setData(ModAttachments.MIGRATION_PAUSE_UNTIL, 0L);
    }

    public static void beginPause(Animal animal, int ticks) {
        long until = animal.level().getGameTime() + Math.max(1, ticks);
        scheduleOwner(animal).setData(ModAttachments.MIGRATION_PAUSE_UNTIL, until);
    }

    public static void ensureHeading(Animal animal) {
        Animal owner = scheduleOwner(animal);
        if (!owner.hasData(ModAttachments.NOMAD_HEADING)
                || Double.isNaN(owner.getData(ModAttachments.NOMAD_HEADING))) {
            owner.setData(ModAttachments.NOMAD_HEADING,
                    owner.getRandom().nextDouble() * (Math.PI * 2.0D));
        }
    }

    /** True while a recently committed heading turn is still cooling down. */
    public static boolean isTurnBlocked(Animal owner) {
        return owner.level().getGameTime() < owner.getData(ModAttachments.NOMAD_TURN_UNTIL);
    }

    public static void beginTurnBlock(Animal owner) {
        owner.setData(ModAttachments.NOMAD_TURN_UNTIL,
                owner.level().getGameTime() + TURN_COOLDOWN_TICKS);
    }

    /** Clears spawn-patch grazing so a nomadic alpha can march instead of camping on grass. */
    public static void resetGrazeAnchor(Animal alpha) {
        alpha.removeData(ModAttachments.GRAZE_PATCH);
        alpha.removeData(ModAttachments.FOOD_TARGET);
        alpha.removeData(ModAttachments.RUMINATE_UNTIL);
    }

    public static Animal scheduleOwner(Animal animal) {
        if (animal.hasData(ModAttachments.HERD_DATA)
                && !animal.getData(ModAttachments.HERD_DATA).alpha()
                && animal.level() instanceof ServerLevel serverLevel) {
            HerdManager.Herd herd = HerdManager.get(animal.getData(ModAttachments.HERD_DATA).herdId());
            if (herd != null && herd.alphaId != null) {
                Entity alpha = serverLevel.getEntity(herd.alphaId);
                if (alpha instanceof Animal alphaAnimal) {
                    return alphaAnimal;
                }
            }
        }
        return animal;
    }

    private static long pauseUntil(Animal owner) {
        return owner.getData(ModAttachments.MIGRATION_PAUSE_UNTIL);
    }
}
