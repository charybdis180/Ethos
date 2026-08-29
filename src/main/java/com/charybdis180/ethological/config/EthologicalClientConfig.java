package com.charybdis180.ethological.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public final class EthologicalClientConfig {
    public static final EthologicalClientConfig CONFIG;
    public static final ModConfigSpec SPEC;

    public enum FreshAnimationsMode {
        /** FA owns the models; Ethological only forces vanilla models while sleeping/resting. */
        ON,
        /** FA is never overridden; Ethological renderers stay out of the way entirely. */
        OFF,
        /** Use Ethological's own renderers unless a Fresh Animations pack is active; still sync eating. */
        AUTO
    }

    public final ModConfigSpec.EnumValue<FreshAnimationsMode> freshAnimationsMode;
    public final ModConfigSpec.BooleanValue syncEatingAnimation;
    public final ModConfigSpec.BooleanValue sleepOverride;
    public final ModConfigSpec.BooleanValue packOverride;
    public final ModConfigSpec.BooleanValue sleepModels;
    public final ModConfigSpec.BooleanValue modernBabyModels;

    private EthologicalClientConfig(ModConfigSpec.Builder builder) {
        builder.comment("Integration with other mods").translation(EthologicalClientConfig.key("compat")).push("compat");
        this.freshAnimationsMode = builder.comment(
                "ON lets Fresh Animations own entity models (with Ethological syncing behavior),",
                "OFF disables all Fresh Animations integration,",
                "AUTO uses Ethological's own renderers when no FA pack is active.")
                .translation(EthologicalClientConfig.key("compat", "freshAnimationsMode"))
                .defineEnum("freshAnimationsMode", FreshAnimationsMode.ON);
        this.syncEatingAnimation = builder.comment(
                "Make FA's grazing animation trigger from Ethological's eat behavior instead of only",
                "when standing on grass/wheat/hay. Requires the companion resource pack to be enabled.")
                .translation(EthologicalClientConfig.key("compat", "syncEatingAnimation"))
                .define("syncEatingAnimation", true);
        this.sleepOverride = builder.comment(
                "Force FA animals into Ethological's lying-down pose while they are sleeping or resting,",
                "instead of letting FA keep them standing up.")
                .translation(EthologicalClientConfig.key("compat", "sleepOverride"))
                .define("sleepOverride", true);
        this.packOverride = builder.comment(
                "Let Ethological override other resource packs' animal models and textures for resting",
                "and sleeping animals, showing its own lying-down pose and dedicated sleep/rest textures.",
                "Turn off to let Fresh Animations and other packs keep their own resting/sleeping",
                "visuals (Ethological behavior and eating sync still run).")
                .translation(EthologicalClientConfig.key("compat", "packOverride"))
                .define("packOverride", true);
        builder.pop();
        this.sleepModels = builder.comment(
                "Show Ethological's lying-down sleeping and resting models for cow/sheep/pig/chicken",
                "while they sleep or rest. Turn off to show the default standing models for these",
                "states (animals are still put to sleep by the mod's behavior, but the model stays up).")
                .translation(EthologicalClientConfig.key("sleepModels"))
                .define("sleepModels", true);
        this.modernBabyModels = builder.comment(
                "Replace vanilla 1.21.1 baby cow/sheep/pig/chicken models and textures",
                "with the updated baby proportions from newer Minecraft versions.",
                "Set to false to keep vanilla baby animals (renderers fall back to scaled adult models).")
                .translation(EthologicalClientConfig.key("modernBabyModels"))
                .define("modernBabyModels", true);
    }

    public boolean freshAnimationsEnabled() {
        return this.freshAnimationsMode.get() != FreshAnimationsMode.OFF;
    }

    /** True when Ethological should override active packs' resting/sleeping visuals (model lock + textures). */
    public boolean packOverrideEnabled() {
        return this.packOverride.get();
    }

    /** True when Ethological's lying-down sleep/rest models should be shown for sleeping/resting animals. */
    public boolean sleepModelsEnabled() {
        return this.sleepModels.get();
    }

    public static boolean useModernBabyModels() {
        return CONFIG.modernBabyModels.get();
    }

    private static String key(String... parts) {
        return "ethological.configuration." + String.join(".", parts);
    }

    static {
        Pair<EthologicalClientConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(EthologicalClientConfig::new);
        CONFIG = pair.getLeft();
        SPEC = pair.getRight();
    }
}
