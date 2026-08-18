package com.charybdis180.ethological.avoidance;

import com.charybdis180.ethological.avoidance.goal.AvoidHazardGoal;
import com.charybdis180.ethological.avoidance.goal.YieldGoal;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.home.HomeSettingsManager;
import com.charybdis180.ethological.sleep.SleepAttachments;
import com.charybdis180.ethological.sleep.SleepEvents;
import com.charybdis180.ethological.social.SocialAttachments;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Soft (water) and hard (lava/fire) avoidance for Ethological herd animals.
 */
public final class AvoidanceEvents {
    /** Per-mob {lastScanX, lastScanZ, lastScanGameTime, lastScanFound(0/1)} so an animal that
     *  already scanned an empty area and has barely moved defers the next spiral scan. */
    private static final Map<UUID, long[]> HAZARD_SCAN_STATE = new ConcurrentHashMap<UUID, long[]>();
    private static final int HAZARD_SCAN_STATE_LIMIT = 4096;
    private static final long HAZARD_SCAN_SKIP_DIST_SQR = 2L * 2L;
    private static final long HAZARD_SCAN_SKIP_TICKS = 80L;

    private AvoidanceEvents() {
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof Animal animal)) {
            return;
        }
        if (HomeSettingsManager.get(animal.getType()).isEmpty()) {
            return;
        }
        applyPathfindingMaluses(animal);
        animal.goalSelector.addGoal(2, (Goal)new AvoidHazardGoal(animal));
        animal.goalSelector.addGoal(4, (Goal)new YieldGoal(animal));
    }

    /** Soft water preference + hard fire/lava avoidance for navigation. */
    public static void applyPathfindingMaluses(Animal animal) {
        animal.setPathfindingMalus(PathType.WATER, Avoidance.WATER_MALUS);
        animal.setPathfindingMalus(PathType.WATER_BORDER, Avoidance.WATER_BORDER_MALUS);
        animal.setPathfindingMalus(PathType.LAVA, Avoidance.LAVA_MALUS);
        animal.setPathfindingMalus(PathType.DAMAGE_FIRE, Avoidance.DAMAGE_FIRE_MALUS);
        animal.setPathfindingMalus(PathType.DANGER_FIRE, Avoidance.DANGER_FIRE_MALUS);
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Animal animal)) {
            return;
        }
        Level level = animal.level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (HomeSettingsManager.get(animal.getType()).isEmpty()) {
            return;
        }
        long now = serverLevel.getGameTime();
        // Register occupancy every tick (one map put) so every animal is a pathing blocker,
        // including stationary ones. Must be before the hazard_reval gate below, which only
        // runs ~once a second.
        CrowdGrid.register(animal, now);
        // Revalidating the attached hazard (a pathfinder/world probe) is only worthwhile every
        // ~second — a burning portal block does not put itself out faster than that.
        if (com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "hazard_reval", now, 20L)) {
            expireHazardIfNeeded(animal, serverLevel, now);
        }

        long crowdInterval = CrowdGrid.SEPARATE_INTERVAL_MIN
                + com.charybdis180.ethological.util.Personality.salt(animal.getUUID(), "crowd_sep", CrowdGrid.SEPARATE_INTERVAL_SPAN);
        if (com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "crowd_sep", now, crowdInterval)) {
            CrowdGrid.separate(animal, serverLevel, now);
        }

        // Stuck-mover yield detection on a 5-tick gate. A panicking/fleeing/playing or
        // sleeping animal is never itself a "stuck mover"; only an awake, calm animal
        // with an active path tracks progress.
        if (com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "yield_detect", now, 5L)
                && !animal.getData(SleepAttachments.SLEEPING).booleanValue()
                && !animal.hasData(SleepAttachments.SLEEP_DISTURBANCE)
                && HerdManager.panicPhaseOf(animal, now) == HerdManager.PanicPhase.NONE
                && !animal.hasData(SocialAttachments.PLAY)
                && !animal.hasData(SocialAttachments.STARTLE)) {
            CrowdYield.tick(animal, serverLevel, now);
        }

        long interval = Avoidance.SCAN_INTERVAL_MIN
                + com.charybdis180.ethological.util.Personality.salt(animal.getUUID(), "avoid_scan_iv", Avoidance.SCAN_INTERVAL_SPAN);
        if (!com.charybdis180.ethological.util.Personality.tickGate(animal.getUUID(), "avoid_scan", now, interval)) {
            return;
        }
        scanAndAttachHazard(animal, serverLevel, now);
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        com.charybdis180.ethological.home.Homes.invalidateSurfaceStandMemo();
        BlockState placed = event.getPlacedBlock();
        if (!Avoidance.isHardHazard(placed)) {
            return;
        }
        BlockPos pos = event.getPos();
        long now = level.getGameTime();
        Avoidance.rememberHazard(level, pos);
        AABB box = new AABB(pos).inflate(Avoidance.HARD_RADIUS);
        for (Animal animal : level.getEntitiesOfClass(Animal.class, box, a ->
                HomeSettingsManager.get(a.getType()).isPresent())) {
            notifyHazard(animal, pos, now);
        }
    }

    /** A hazard block was removed (portal broken, fire put out) — stop avoiding it. */
    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        com.charybdis180.ethological.home.Homes.invalidateSurfaceStandMemo();
        if (Avoidance.isHardHazard(event.getState())) {
            Avoidance.invalidateHazardMemory();
        }
    }

    private static void expireHazardIfNeeded(Animal animal, ServerLevel level, long now) {
        if (!animal.hasData(AvoidanceAttachments.HAZARD)) {
            return;
        }
        AvoidanceHazard hazard = animal.getData(AvoidanceAttachments.HAZARD);
        if (!Avoidance.hazardStillValid(level, hazard, animal.blockPosition(), now)) {
            animal.removeData(AvoidanceAttachments.HAZARD);
        }
    }

    private static void scanAndAttachHazard(Animal animal, ServerLevel level, long now) {
        BlockPos pos = animal.blockPosition();
        // A previous scan of this area found nothing and the animal has barely moved: the next
        // scan would probe the same empty terrain, so defer it until it moves or 80 ticks pass.
        long[] state = HAZARD_SCAN_STATE.get(animal.getUUID());
        if (state != null && state[3] == 0L) {
            long dx = (long)pos.getX() - state[0];
            long dz = (long)pos.getZ() - state[1];
            if (dx * dx + dz * dz < HAZARD_SCAN_SKIP_DIST_SQR && now - state[2] < HAZARD_SCAN_SKIP_TICKS) {
                return;
            }
        }
        BlockPos found = Avoidance.findNearestHardHazard(level, pos);
        HAZARD_SCAN_STATE.put(animal.getUUID(), new long[] {pos.getX(), pos.getZ(), now, found != null ? 1L : 0L});
        if (HAZARD_SCAN_STATE.size() >= HAZARD_SCAN_STATE_LIMIT) {
            HAZARD_SCAN_STATE.clear();
        }
        if (found == null) {
            return;
        }
        Avoidance.rememberHazard(level, found);
        notifyHazard(animal, found, now);
    }

    /** An animal caught in lava/fire discovers the hazard even if the periodic scan has
     * not fired yet — remember the burning block so future paths avoid it, and attach the
     * hazard so it flees immediately. */
    @SubscribeEvent
    public static void onLivingHurt(LivingDamageEvent.Pre event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Animal animal)) {
            return;
        }
        Level level = animal.level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (HomeSettingsManager.get(animal.getType()).isEmpty()) {
            return;
        }
        if (event.getNewDamage() <= 0.0f) {
            return;
        }
        if (!event.getSource().is(DamageTypeTags.IS_FIRE)) {
            return;
        }
        BlockPos found = Avoidance.findNearestHardHazard(serverLevel, animal.blockPosition());
        BlockPos memoryPos = found != null ? found : animal.blockPosition();
        Avoidance.rememberHazard(serverLevel, memoryPos);
        if (found != null) {
            notifyHazard(animal, found, serverLevel.getGameTime());
        }
    }

    private static void notifyHazard(Animal animal, BlockPos hazardPos, long now) {
        Optional<AvoidanceHazard> existing = animal.hasData(AvoidanceAttachments.HAZARD)
                ? Optional.of(animal.getData(AvoidanceAttachments.HAZARD))
                : Optional.empty();
        if (existing.isPresent()
                && existing.get().pos().equals(hazardPos)
                && now - existing.get().detectedGameTime() < 40L) {
            return;
        }
        animal.setData(AvoidanceAttachments.HAZARD, new AvoidanceHazard(hazardPos.immutable(), now));
        if (Boolean.TRUE.equals(animal.getData(SleepAttachments.SLEEPING))) {
            SleepEvents.wake(animal);
        }
    }
}
