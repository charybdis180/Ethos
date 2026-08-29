package com.charybdis180.ethological.social.goal;

import com.charybdis180.ethological.registry.ModAttachments;
import com.charybdis180.ethological.herd.HerdManager;
import com.charybdis180.ethological.herd.goal.FollowParentGoal;
import com.charybdis180.ethological.herd.goal.FollowPathing;
import com.charybdis180.ethological.home.Homes;
import com.charybdis180.ethological.home.NomadicMigration;
import com.charybdis180.ethological.hunger.GrazePatches;
import com.charybdis180.ethological.hunger.Hunger;
import com.charybdis180.ethological.sleep.SleepSettingsManager;
import com.charybdis180.ethological.sleep.SpeciesSleepSettings;
import com.charybdis180.ethological.social.SocialCalm;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class RestGoal extends Goal {
    private static final double SPEED = 1.0;
    private static final double ARRIVE_DIST = 1.5;
    private static final int SHADE_SEARCH_RADIUS = 10;
    private static final int MIN_REST_TICKS = 400;
    private static final int REST_TICKS_SPAN = 400;
    private static final int RETRY_MIN_TICKS = 300;
    private static final int RETRY_TICKS_SPAN = 300;
    private static final float ROLL_CHANCE = 0.4f;
    private static final float SHADE_WHIM_CHANCE = 0.3f;
    private static final double HUDDLE_RANGE = 8.0;
    private final Animal mob;
    private BlockPos restSpot;
    private long restUntilGameTime;
    private long nextRollGameTime;
    private boolean lying;

    public RestGoal(Animal mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    /**
     * False when it is sleep time and the member stands outside its herd rest circle —
     * exactly the state of a drinker that finished at a distant pond. FollowAlphaGoal's
     * night-rejoin mode owns MOVE in that state; resting here would pin the member at the
     * pond all night. Mirrors FollowPathing.herdRestRadius so the two gates never disagree.
     */
    private boolean nightRejoinOk() {
        Optional<SpeciesSleepSettings> sleepOpt = SleepSettingsManager.get(this.mob.getType());
        if (sleepOpt.isEmpty() || !sleepOpt.get().isSleepTime(this.mob.level().getDayTime())) {
            return true;
        }
        double dist = FollowPathing.distanceToAlpha(this.mob);
        if (dist < 0.0D) {
            return true;
        }
        return dist <= FollowPathing.herdRestRadius(this.mob);
    }

    @Override
    public boolean canUse() {
        long now = this.mob.level().getGameTime();
        if (now < this.nextRollGameTime) {
            return false;
        }
        this.nextRollGameTime = now + RETRY_MIN_TICKS + (long)this.mob.getRandom().nextInt(RETRY_TICKS_SPAN);
        if (this.mob.getRandom().nextFloat() >= ROLL_CHANCE || !SocialCalm.canIdle(this.mob)) {
            return false;
        }
        if (!this.nightRejoinOk()) {
            return false;
        }
        if (GrazePatches.isPatchLeader(this.mob) && Hunger.herdWantsFood(this.mob)) {
            return false;
        }
        if (NomadicMigration.isTraveling(this.mob)) {
            return false;
        }
        if (FollowParentGoal.isAwayFromMother(this.mob)) {
            return false;
        }
        if (this.wouldBeLastStandingAdult()) {
            return false;
        }
        BlockPos here = this.mob.blockPosition();
        // Never nap in or beside water: a resting body overhanging a pond reads as
        // "sitting in the water" (same complaint as the sleep-pose variant).
        if (!Homes.isDryBed(this.mob.level(), here)) {
            return false;
        }
        BlockPos huddle = this.nearestRestingMateSpot();
        boolean wantShade = Homes.isHotFor(this.mob) || this.mob.getRandom().nextFloat() < SHADE_WHIM_CHANCE;
        if (huddle != null) {
            this.restSpot = wantShade
                    ? Homes.findBetterShelteredHome(this.mob.level(), huddle, SHADE_SEARCH_RADIUS, 0, false).orElse(huddle)
                    : huddle;
        } else {
            this.restSpot = wantShade
                    ? Homes.findBetterShelteredHome(this.mob.level(), here, SHADE_SEARCH_RADIUS, 0, false).orElse(here)
                    : here;
        }
        // A huddle/shelter pick can land on a shoreline cell even though the mob's own
        // spot is dry — re-route those to the mob's validated dry cell.
        if (!Homes.isDryBed(this.mob.level(), this.restSpot)) {
            this.restSpot = here;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (FollowParentGoal.isAwayFromMother(this.mob)) {
            return false;
        }
        // Bedtime guard: during the sleep window a member OUTSIDE its rest circle must walk
        // home, not nap where it stands (e.g. beside the pond it just drank from). RestGoal
        // shares priority 5 with FollowAlphaGoal and wins ties by insertion order, so without
        // this gate it repeatedly claims MOVE for 20-40s bouts and the rejoin never happens.
        // Inside the circle (or outside the sleep window) resting stays as normal.
        if (!this.nightRejoinOk()) {
            return false;
        }
        if (this.mob.level().getGameTime() >= this.restUntilGameTime || !SocialCalm.stillCalm(this.mob)) {
            return false;
        }
        // Shoved into water mid-rest (a herdmate pushed past): standing up beats lying
        // in the pond. SeekShoreGoal beaches the animal; resting can resume after.
        // Folded into the 10-tick pattern so steady-state cost stays nil.
        if (this.mob.tickCount % 10 == 0
                && (this.mob.isInWaterOrBubble() || !Homes.isDryBed(this.mob.level(), this.mob.blockPosition()))) {
            return false;
        }
        return this.mob.tickCount % 10 != 0
                || !this.mob.level().isRainingAt(this.mob.blockPosition())
                || Homes.isSheltered(this.mob.level(), this.mob.blockPosition());
    }

    @Override
    public void start() {
        this.lying = false;
        this.restUntilGameTime = this.mob.level().getGameTime() + MIN_REST_TICKS
                + (long)this.mob.getRandom().nextInt(REST_TICKS_SPAN);
        BlockPos anchored = Homes.surfaceStandForMove(this.mob.level(), this.restSpot, this.mob);
        if (anchored == null) {
            // No reachable surface stand for the chosen spot: keep the original aim rather
            // than stranding the goal (the spot is near the mob, so this is rare).
            anchored = this.restSpot;
        }
        // A rest spot hugging a wall corner is unreachable (the AABB clips the corner and
        // the pathfinder re-issues the same node forever) — shift into the open side.
        anchored = Homes.offsetStandFromCorners(this.mob.level(), anchored);
        if (anchored.closerThan(this.mob.blockPosition(), ARRIVE_DIST)) {
            this.mob.getNavigation().stop();
        } else {
            this.mob.getNavigation().moveTo(
                    (double)anchored.getX() + 0.5,
                    (double)anchored.getY(),
                    (double)anchored.getZ() + 0.5,
                    SPEED);
        }
    }

    @Override
    public void tick() {
        if (this.lying) {
            this.mob.getNavigation().stop();
            return;
        }
        if (this.mob.distanceToSqr(Vec3.atBottomCenterOf(this.restSpot)) <= 2.25
                || this.mob.getNavigation().isDone()) {
            // Re-check last-adult rule right before lying down.
            if (this.wouldBeLastStandingAdult()) {
                this.restUntilGameTime = this.mob.level().getGameTime();
                return;
            }
            this.mob.getNavigation().stop();
            this.lying = true;
            this.mob.setData(ModAttachments.SLEEP_YAW, Float.valueOf(this.mob.yBodyRot));
            this.mob.setData(ModAttachments.RESTING, true);
        }
    }

    @Override
    public void stop() {
        if (this.lying) {
            this.mob.setData(ModAttachments.RESTING, false);
            this.lying = false;
        }
        this.mob.getNavigation().stop();
    }

    private BlockPos nearestRestingMateSpot() {
        if (!this.mob.hasData(ModAttachments.HERD_DATA) || !(this.mob.level() instanceof ServerLevel level)) {
            return null;
        }
        HerdManager.Herd herd = HerdManager.get(this.mob.getData(ModAttachments.HERD_DATA).herdId());
        if (herd == null) {
            return null;
        }
        Animal best = null;
        double bestDist = HUDDLE_RANGE * HUDDLE_RANGE;
        for (UUID id : herd.members) {
            if (id.equals(this.mob.getUUID())) {
                continue;
            }
            Entity entity = level.getEntity(id);
            if (!(entity instanceof Animal mate) || !mate.isAlive()) {
                continue;
            }
            if (!mate.getData(ModAttachments.RESTING)) {
                continue;
            }
            double dist = this.mob.distanceToSqr(mate);
            if (dist < bestDist) {
                bestDist = dist;
                best = mate;
            }
        }
        return best == null ? null : best.blockPosition();
    }

    /** Keep one adult sentinel standing in herds of size ≥ 3. */
    private boolean wouldBeLastStandingAdult() {
        if (this.mob.isBaby() || !this.mob.hasData(ModAttachments.HERD_DATA)) {
            return false;
        }
        Level level = this.mob.level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        HerdManager.Herd herd = HerdManager.get(this.mob.getData(ModAttachments.HERD_DATA).herdId());
        if (herd == null || herd.members.size() < 3) {
            return false;
        }
        int loadedAdults = 0;
        int standingAdults = 0;
        for (UUID id : herd.members) {
            Entity entity = serverLevel.getEntity(id);
            if (!(entity instanceof Animal mate) || !mate.isAlive() || mate.isBaby()) {
                continue;
            }
            ++loadedAdults;
            boolean resting = mate.getData(ModAttachments.RESTING);
            boolean sleeping = mate.getData(ModAttachments.SLEEPING);
            if (!resting && !sleeping) {
                ++standingAdults;
            }
        }
        if (loadedAdults < 3) {
            return false;
        }
        // If this mob is already resting/sleeping, it isn't the last standing.
        boolean selfDown = this.mob.getData(ModAttachments.RESTING)
                || this.mob.getData(ModAttachments.SLEEPING);
        if (selfDown) {
            return false;
        }
        return standingAdults <= 1;
    }
}
