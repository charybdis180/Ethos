/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.model.geom.ModelLayerLocation
 *  net.minecraft.resources.ResourceLocation
 */
package com.charybdis180.ethological.client.sleep;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.ResourceLocation;

public final class BabyModelLayers {
    public static final ModelLayerLocation BABY_CHICKEN = BabyModelLayers.register("baby_chicken");
    public static final ModelLayerLocation BABY_COW = BabyModelLayers.register("baby_cow");
    public static final ModelLayerLocation BABY_PIG = BabyModelLayers.register("baby_pig");
    public static final ModelLayerLocation BABY_SHEEP = BabyModelLayers.register("baby_sheep");
    public static final ModelLayerLocation BABY_SHEEP_WOOL = BabyModelLayers.register("baby_sheep_wool");

    private BabyModelLayers() {
    }

    private static ModelLayerLocation register(String name) {
        return new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)name), "main");
    }
}

