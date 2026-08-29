package com.charybdis180.ethological.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soft compatibility with the Better Days mod (variable day-night length). When Better Days
 * slows or speeds the passage of day-ticks, Ethological's hunger/thirst drain intervals scale
 * by the same factor so animals eat and drink the same number of times per day-cycle no matter
 * how long a day lasts.
 *
 * <p>Resolved reflectively so neither mod is a hard dependency: the only stable surface needed
 * is {@code betterdays.time.TimeServiceManager.service} and
 * {@code TimeService.getTimeSpeed(Time)}, which returns the current effective day-tick speed
 * ratio (1.0 = vanilla, 0.5 = 40-minute days, includes interpolated schedules and the sleep
 * time-acceleration). Resolved once; any shape mismatch permanently disables the bridge rather
 * than retrying per call.</p>
 */
public final class BetterDaysCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("ethological");
    private static final String SERVICE_MANAGER_CLASS = "betterdays.time.TimeServiceManager";
    private static final String SERVICE_CLASS = "betterdays.time.TimeService";
    private static final String TIME_CLASS = "betterdays.time.Time";
    /** BD allows night speeds up to 24000x; unclamped scaling would starve animals instantly. */
    private static final float MIN_SCALE = 0.05f;
    private static final float MAX_SCALE = 20.0f;

    // Resolved once; nulls when Better Days is absent or its API changed shape.
    private static volatile boolean initialized;
    private static Object timeService;
    private static Method getTimeSpeedMethod;
    private static Constructor<?> timeCtor;

    private BetterDaysCompat() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            Class<?> serviceClass = Class.forName(SERVICE_CLASS);
            Class<?> managerClass = Class.forName(SERVICE_MANAGER_CLASS);
            Field serviceField = managerClass.getField("service");
            timeService = serviceField.get(null);
            Class<?> timeClass = Class.forName(TIME_CLASS);
            getTimeSpeedMethod = serviceClass.getMethod("getTimeSpeed", timeClass);
            timeCtor = timeClass.getConstructor(long.class);
        } catch (Throwable t) {
            timeService = null;
            LOGGER.info("[Ethological] Better Days not detected — day-length drain sync disabled.");
            return;
        }
        if (timeService == null) {
            LOGGER.info("[Ethological] Better Days present but its time service is not managing this level — drain sync idle.");
        } else {
            LOGGER.info("[Ethological] Better Days integration active — hunger/thirst drain follows day length.");
        }
    }

    /**
     * Drain-rate scale matching the current day length. Returns 1.0 when Better Days is absent,
     * not managing the level, or lookup fails, leaving vanilla-rate behavior untouched.
     */
    public static float drainScale(Level level) {
        init();
        if (timeService == null || getTimeSpeedMethod == null || timeCtor == null || level == null) {
            return 1.0f;
        }
        try {
            Object now = timeCtor.newInstance(level.getDayTime());
            Object speed = getTimeSpeedMethod.invoke(timeService, now);
            float f = ((Number)speed).floatValue();
            if (Float.isNaN(f) || Float.isInfinite(f)) {
                return 1.0f;
            }
            return Math.clamp(f, MIN_SCALE, MAX_SCALE);
        } catch (Throwable t) {
            return 1.0f;
        }
    }
}
