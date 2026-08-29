package com.charybdis180.ethological.client;

import com.charybdis180.ethological.config.EthologicalClientConfig;
import com.charybdis180.ethological.hunger.Hunger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges Ethological behavior state into Fresh Animations (via EMF), reflectively so neither
 * mod is a hard dependency.
 *
 * <p>Eating: FA's grazing animation ({@code var.NAeat} / {@code var.Aeat}) is driven by the
 * OptiFine-style {@code rule_index} signal ("standing on grass/wheat/hay"), so animals idle on
 * grass chew constantly while animals actually eating from feeders never do. The companion
 * resource pack extends FA's gate expressions to also accept {@code varb.etho_eating}; this class
 * supplies that variable from the mod's eat pulse. Because EMF re-registers variables parsed from
 * packs on every resource reload (evicting external registrations), the supplier self-heals by
 * re-inserting itself whenever EMF has replaced it.</p>
 *
 * <p>Sleeping: when EMF renders its CEM model, the vanilla-model sleep pose applied by the
 * {@code Sleeping*Model} classes has no effect, so FA animals stand up while asleep. EMF's API
 * lets us lock an entity back to its vanilla model, restoring our pose control; we unlock as soon
 * as the animal wakes so FA resumes.</p>
 */
public final class FaCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("ethological");
    private static final String API_CLASS = "traben.entity_model_features.EMFAnimationApi";
    private static final String REGISTRY_CLASS = "traben.entity_model_features.models.animation.math.variables.VariableRegistry";
    private static final String EMF_ENTITY_CLASS = "traben.entity_model_features.utils.EMFEntity";

    // Resolved once; nulls when EMF is absent or its API changed shape.
    private static volatile boolean initialized;
    private static Method getCurrentEntityMethod;
    private static Method emfEntityOfMethod;
    private static Method lockMethod;
    private static Method unlockMethod;
    private static Map<String, Object> singletonBoolVariables;

    private static final BooleanSupplier ETHO_EATING = FaCompat::ethoEatingSupplier;

    private FaCompat() {
    }

    public static void registerIfLoaded() {
        init();
        if (singletonBoolVariables == null) {
            LOGGER.info("[Ethological] EMF not detected — Fresh Animations integration disabled.");
            return;
        }
        patchVariableMap();
        LOGGER.info("[Ethological] Fresh Animations integration active (etho_eating + sleep override).");
    }

    /** True while the mod's eat-animation pulse is active (graze bite, feeder bite, or hand-feed). */
    public static boolean isEating(Entity entity) {
        return entity instanceof Animal animal && Hunger.isEating(animal);
    }

    /**
     * While {@code sleepingOrResting} is true, ask EMF to render this entity with its vanilla
     * model so the mod's sleep/rest pose takes effect over Fresh Animations.
     */
    public static void lockToVanillaModelWhileSleeping(Entity entity, boolean sleepingOrResting) {
        if (!initialized) {
            init();
        }
        if (emfEntityOfMethod == null) {
            return;
        }
        boolean overrideActive = EthologicalClientConfig.CONFIG.packOverrideEnabled()
                && EthologicalClientConfig.CONFIG.sleepModelsEnabled()
                && EthologicalClientConfig.CONFIG.sleepOverride.get();
        if (overrideActive && sleepingOrResting) {
            try {
                Object emfEntity = emfEntityOfMethod.invoke(null, entity);
                if (emfEntity == null) {
                    return;
                }
                lockMethod.invoke(null, emfEntity);
            } catch (Throwable ex) {
                LOGGER.debug("[Ethological] FA sleep override failed for {}: {}", entity.getType(), ex.toString());
            }
            return;
        }
        // Override inactive or the animal is up: hand control back to FA/EMF immediately so a
        // runtime toggle off (or an awake entity) never leaves the vanilla model locked in place.
        try {
            Object emfEntity = emfEntityOfMethod.invoke(null, entity);
            if (emfEntity == null) {
                return;
            }
            unlockMethod.invoke(null, emfEntity);
        } catch (Throwable ex) {
            LOGGER.debug("[Ethological] FA unlock failed for {}: {}", entity.getType(), ex.toString());
        }
    }

    /** Value handed to EMF whenever any entity evaluates {@code varb.etho_eating}. */
    private static boolean ethoEatingSupplier() {
        healVariableMap();
        if (!EthologicalClientConfig.CONFIG.syncEatingAnimation.get()) {
            return false;
        }
        try {
            Object current = getCurrentEntityMethod == null ? null : getCurrentEntityMethod.invoke(null);
            return current instanceof Entity entity && isEating(entity);
        } catch (Throwable ex) {
            return false;
        }
    }

    /** Re-claim our slot if a resource reload made EMF re-parse packs and evict our supplier. */
    private static void healVariableMap() {
        Map<String, Object> map = singletonBoolVariables;
        if (map != null && map.get("etho_eating") != ETHO_EATING) {
            patchVariableMap();
        }
    }

    private static void patchVariableMap() {
        Map<String, Object> map = singletonBoolVariables;
        if (map == null) {
            return;
        }
        try {
            Object previous = map.put("etho_eating", ETHO_EATING);
            if (previous != ETHO_EATING) {
                LOGGER.info("[Ethological] Supplied varb.etho_eating to EMF{}",
                        previous != null ? " (replaced default registration)" : "");
            }
        } catch (Throwable ex) {
            LOGGER.warn("[Ethological] Could not insert etho_eating into EMF variable registry: {}", ex.toString());
        }
    }

    private static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            Class<?> api = Class.forName(API_CLASS);
            Class<?> emfEntityClass = Class.forName(EMF_ENTITY_CLASS);
            emfEntityOfMethod = api.getMethod("emfEntityOf", Entity.class);
            getCurrentEntityMethod = api.getMethod("getCurrentEntity");
            lockMethod = api.getMethod("lockEntityToVanillaModel", emfEntityClass);
            unlockMethod = api.getMethod("unlockEntityToVanillaModel", emfEntityClass);
            Class<?> registryClass = Class.forName(REGISTRY_CLASS);
            Object registry = registryClass.getMethod("getInstance").invoke(null);
            Field boolField = registryClass.getField("singletonASMVariablesBool");
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) boolField.get(registry);
            singletonBoolVariables = map;
        } catch (Throwable ex) {
            getCurrentEntityMethod = null;
            emfEntityOfMethod = null;
            lockMethod = null;
            unlockMethod = null;
            singletonBoolVariables = null;
        }
    }
}
