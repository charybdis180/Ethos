/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.particles.ParticleType
 *  net.minecraft.core.particles.SimpleParticleType
 *  net.minecraft.core.registries.Registries
 *  net.minecraft.resources.ResourceKey
 *  net.neoforged.neoforge.registries.DeferredRegister
 */
package com.charybdis180.ethological;

import java.util.function.Supplier;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES = DeferredRegister.create((ResourceKey)Registries.PARTICLE_TYPE, (String)"ethological");
    public static final Supplier<SimpleParticleType> Z = PARTICLE_TYPES.register("z", () -> new SimpleParticleType(false));

    private ModParticles() {
    }
}

