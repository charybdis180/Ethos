package com.charybdis180.ethological.client.sleep;

import com.charybdis180.ethological.config.EthologicalClientConfig;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

/**
 * Pack-aware texture selection for the sleep/baby model variants.
 *
 * <p>The sleeping renderers need dedicated textures (open-eye sleep poses, baby proportions),
 * which ship under the {@code ethological} namespace. That clashes with resource packs: any
 * pack that retextures animals was ignored the instant an animal slept. This resolver keeps
 * Ethological's fallbacks but prefers the active pack's art when it can:</p>
 *
 * <p>Selection hierarchy, gated first by the pack-override toggle then by the Fresh Animations
 * mode:</p>
 *
 * <ol>
 *   <li>{@code packOverride} OFF — Ethological never overrides active packs: every state uses the
 *       pack's own art ({@code _sleep}/{@code _baby} variants when the pack ships them, otherwise
 *       the pack's standing texture). Ethological-owned copies are never substituted.</li>
 *   <li>{@code packOverride} ON + Fresh Animations enabled — FA's packs repaint the vanilla texture
 *       and blank its baked eyes (redrawing them as CEM overlays that vanish on the locked vanilla
 *       model), so Ethological's local copies must always be used; the legacy selection is
 *       preserved verbatim.</li>
 *   <li>{@code packOverride} ON + FA disabled — Ethological's renderers provide the visuals and a
 *       regular retexture pack is respected: the pack's variant if it ships one, otherwise the
 *       pack's standing art when it retextures the vanilla path, otherwise Ethological's copies.</li>
 * </ol>
 *
 * <p>Results are cached per resource reload; the reload listener registered by the client entry
 * point calls {@link #invalidate()} so pack changes take effect immediately.</p>
 */
public final class TextureResolver {
    private static final ConcurrentHashMap<String, TexSet> PACK_RESOLVED = new ConcurrentHashMap<>();
    private static volatile boolean stale = true;

    private TextureResolver() {
    }

    /** Called on every client resource reload; the next lookup re-resolves against the new packs. */
    public static void invalidate() {
        stale = true;
    }

    /** One texture per visual state the sleeping renderers pick between. */
    public record TexSet(ResourceLocation standing, ResourceLocation resting, ResourceLocation asleep,
                         ResourceLocation baby, ResourceLocation babyAsleep) {
    }

    /**
     * Resolves the five state textures for one species. {@code standing} must be the
     * {@code minecraft:}-namespace vanilla path ({@code textures/entity/cow/cow.png}); variant
     * candidates are derived from its directory and base name.
     *
     * <p>Pack-aware selection only runs when Fresh Animations integration is OFF — i.e. when
     * Ethological's renderers provide the visuals and a regular resource pack is the active art
     * source. When FA is enabled its packs repaint the vanilla texture and blank its baked eyes,
     * so our dedicated local textures must always be used; the legacy selection is preserved
     * verbatim in that case.</p>
     */
    public static TexSet resolve(String cacheKey, ResourceLocation standing, ResourceLocation ethoResting,
                                 ResourceLocation ethoAsleep, ResourceLocation ethoBaby, ResourceLocation ethoBabyAsleep) {
        if (!EthologicalClientConfig.CONFIG.packOverrideEnabled()) {
            // Player asked Ethological not to override active packs' resting/sleeping visuals: let
            // the pack's own art show for every state. Only the vanilla standing path is meaningful
            // here; sleep/baby variants are picked when the pack ships them, otherwise the pack's
            // standing art is reused so no Ethological-owned texture ever replaces the pack's art.
            if (stale) {
                PACK_RESOLVED.clear();
                stale = false;
            }
            return PACK_RESOLVED.computeIfAbsent(cacheKey.toLowerCase(Locale.ROOT),
                    k -> resolvePacksOnly(standing));
        }
        if (EthologicalClientConfig.CONFIG.freshAnimationsEnabled()) {
            return legacy(standing, ethoResting, ethoAsleep, ethoBaby, ethoBabyAsleep);
        }
        if (stale) {
            PACK_RESOLVED.clear();
            stale = false;
        }
        return PACK_RESOLVED.computeIfAbsent(cacheKey.toLowerCase(Locale.ROOT),
                k -> resolvePackAware(standing, ethoResting, ethoAsleep, ethoBaby, ethoBabyAsleep));
    }

    private static TexSet legacy(ResourceLocation standing, ResourceLocation ethoResting,
                                 ResourceLocation ethoAsleep, ResourceLocation ethoBaby, ResourceLocation ethoBabyAsleep) {
        return new TexSet(standing, ethoResting, ethoAsleep, ethoBaby, ethoBabyAsleep);
    }

    /**
     * Override OFF: the active pack's art wins for every state. {@code _sleep}/{@code _baby} variants
     * are used only when the pack itself ships them; otherwise the pack's standing texture is reused,
     * so no Ethological-owned texture ever replaces a pack's art.
     */
    private static TexSet resolvePacksOnly(ResourceLocation standing) {
        int slash = standing.getPath().lastIndexOf('/');
        int dot = standing.getPath().lastIndexOf('.');
        if (slash < 0 || dot <= slash) {
            return legacy(standing, standing, standing, standing, standing);
        }
        String dir = standing.getPath().substring(0, slash);
        String base = standing.getPath().substring(slash + 1, dot);
        ResourceLocation packSleep = minecraftVariant(dir, base + "_sleep.png");
        ResourceLocation packBaby = minecraftVariant(dir, base + "_baby.png");
        ResourceLocation packBabySleep = minecraftVariant(dir, base + "_baby_sleep.png");
        ResourceLocation sleeping = exists(packSleep) ? packSleep : standing;
        ResourceLocation baby = exists(packBaby) ? packBaby : standing;
        // Resting keeps open eyes: there is no pack-side resting texture, so show the pack's standing art.
        return new TexSet(standing, standing, sleeping, baby,
                exists(packBabySleep) ? packBabySleep : sleeping);
    }

    private static TexSet resolvePackAware(ResourceLocation standing, ResourceLocation ethoResting,
                                           ResourceLocation ethoAsleep, ResourceLocation ethoBaby, ResourceLocation ethoBabyAsleep) {
        String path = standing.getPath();
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (slash < 0 || dot <= slash) {
            return legacy(standing, ethoResting, ethoAsleep, ethoBaby, ethoBabyAsleep);
        }
        String dir = path.substring(0, slash);
        String base = path.substring(slash + 1, dot);
        ResourceLocation packSleep = minecraftVariant(dir, base + "_sleep.png");
        ResourceLocation packBaby = minecraftVariant(dir, base + "_baby.png");
        ResourceLocation packBabySleep = minecraftVariant(dir, base + "_baby_sleep.png");

        boolean packRetexturesAnimal = overridesVanilla(standing);

        // Resting keeps open eyes: prefer the pack's awake art over our local repaint.
        ResourceLocation resting = packRetexturesAnimal ? standing : ethoResting;
        ResourceLocation asleep = exists(packSleep) ? packSleep
                : (packRetexturesAnimal ? standing : ethoAsleep);
        ResourceLocation baby = exists(packBaby) ? packBaby
                : (packRetexturesAnimal ? standing : ethoBaby);
        ResourceLocation babyAsleep = exists(packSleep) ? packSleep
                : exists(packBabySleep) ? packBabySleep
                : (packRetexturesAnimal ? standing : ethoBabyAsleep);
        return new TexSet(standing, resting, asleep, baby, babyAsleep);
    }

    private static ResourceLocation minecraftVariant(String dir, String fileName) {
        return ResourceLocation.withDefaultNamespace(dir + "/" + fileName);
    }

    private static boolean exists(ResourceLocation rl) {
        return Minecraft.getInstance().getResourceManager().getResource(rl).isPresent();
    }

    /** True when a non-vanilla pack (or mod assets) supplies the given path. */
    private static boolean overridesVanilla(ResourceLocation rl) {
        return Minecraft.getInstance().getResourceManager().getResource(rl)
                .map(Resource::sourcePackId)
                .map(id -> !"vanilla".equals(id))
                .orElse(false);
    }
}
