package com.charybdis180.ethological.social;

import java.util.UUID;

public record PlayData(UUID partnerId, long untilGameTime, PlayStyle style, boolean initiator, boolean swapped) {
    public static final PlayData DEFAULT = new PlayData(new UUID(0L, 0L), 0L, PlayStyle.CHASE, false, false);

    public static enum PlayStyle {
        CHASE,
        HEADBUTT;

    }
}

