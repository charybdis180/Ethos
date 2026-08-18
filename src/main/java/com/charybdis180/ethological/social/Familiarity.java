package com.charybdis180.ethological.social;

import com.charybdis180.ethological.config.EthologicalConfig;
import java.util.UUID;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;

/** Cheap trust helpers. Gains / thresholds come from comfort config. */
public final class Familiarity {
    private static final double BASE_ACTIVITY_WAKE = 5.0;
    private static final double FAMILIAR_ACTIVITY_WAKE = 3.0;
    private static final double BASE_CURIOUS_WATCH = 9.0;
    private static final double FAMILIAR_CURIOUS_WATCH = 5.5;
    private static final double BASE_CURIOUS_NOTICE = 20.0;
    private static final double FAMILIAR_CURIOUS_NOTICE = 24.0;
    private static final long BASE_PROXIMITY_VIGILANCE = 80L;
    private static final long FAMILIAR_PROXIMITY_VIGILANCE = 40L;
    private static final double STARTLE_REDUCTION = 0.55;

    private Familiarity() {
    }

    public static FamiliarityData data(Animal animal) {
        return animal.hasData(SocialAttachments.FAMILIARITY)
                ? animal.getData(SocialAttachments.FAMILIARITY)
                : FamiliarityData.DEFAULT;
    }

    private static int handFeedGain() {
        return EthologicalConfig.CONFIG.comfort.familiarityHandFeedGain.get();
    }

    private static int maxScore() {
        return EthologicalConfig.CONFIG.comfort.familiarityMaxScore.get();
    }

    private static int trustThreshold() {
        return EthologicalConfig.CONFIG.comfort.familiarityTrustThreshold.get();
    }

    public static void handFed(Animal animal, Player player) {
        FamiliarityData next = Familiarity.data(animal).bump(player.getUUID(), handFeedGain(), maxScore());
        animal.setData(SocialAttachments.FAMILIARITY, next);
    }

    public static boolean trusts(Animal animal, Player player) {
        return Familiarity.data(animal).trusts(player.getUUID(), trustThreshold());
    }

    public static boolean trusts(Animal animal, UUID playerId) {
        return Familiarity.data(animal).trusts(playerId, trustThreshold());
    }

    public static double activityWakeDistanceSqr(Animal animal, Player player) {
        double d = Familiarity.trusts(animal, player) ? FAMILIAR_ACTIVITY_WAKE : BASE_ACTIVITY_WAKE;
        return d * d;
    }

    public static double curiousWatchRange(Animal animal, Player player) {
        return Familiarity.trusts(animal, player) ? FAMILIAR_CURIOUS_WATCH : BASE_CURIOUS_WATCH;
    }

    public static double curiousNoticeRange(Animal animal, Player player) {
        return Familiarity.trusts(animal, player) ? FAMILIAR_CURIOUS_NOTICE : BASE_CURIOUS_NOTICE;
    }

    public static long proximityVigilanceTicks(Animal animal, Player player, boolean onlyNearbyHuman) {
        if (Familiarity.trusts(animal, player) && onlyNearbyHuman) {
            return FAMILIAR_PROXIMITY_VIGILANCE;
        }
        return BASE_PROXIMITY_VIGILANCE;
    }

    /** Scale startle duration when the animal trusts its familiar player. */
    public static long scaleStartleTicks(Animal animal, long ticks) {
        FamiliarityData data = Familiarity.data(animal);
        if (data.isEmpty() || data.score() < trustThreshold()) {
            return ticks;
        }
        return Math.max(8L, Math.round(ticks * STARTLE_REDUCTION));
    }
}
