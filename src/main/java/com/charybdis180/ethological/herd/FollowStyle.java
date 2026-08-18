/*
 * Decompiled with CFR 0.152.
 */
package com.charybdis180.ethological.herd;

import java.util.Locale;

public enum FollowStyle {
    FOLLOW,
    SURROUND;


    public static FollowStyle fromString(String value) {
        try {
            return FollowStyle.valueOf(value.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException | NullPointerException e) {
            return SURROUND;
        }
    }
}

