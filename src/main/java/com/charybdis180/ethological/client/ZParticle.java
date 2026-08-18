/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.multiplayer.ClientLevel
 *  net.minecraft.client.particle.Particle
 *  net.minecraft.client.particle.ParticleProvider
 *  net.minecraft.client.particle.ParticleRenderType
 *  net.minecraft.client.particle.SpriteSet
 *  net.minecraft.client.particle.TextureSheetParticle
 *  net.minecraft.core.particles.SimpleParticleType
 */
package com.charybdis180.ethological.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

public class ZParticle
extends TextureSheetParticle {
    private final SpriteSet sprites;

    protected ZParticle(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
        super(level, x, y, z);
        this.sprites = sprites;
        this.lifetime = 80 + this.random.nextInt(41);
        this.gravity = 0.0f;
        this.xd = (this.random.nextDouble() - 0.5) * 0.01;
        this.yd = 0.02;
        this.zd = (this.random.nextDouble() - 0.5) * 0.01;
        this.quadSize = 0.1f;
        this.alpha = 0.0f;
        this.setSpriteFromAge(sprites);
    }

    public void tick() {
        super.tick();
        this.setSpriteFromAge(this.sprites);
        int fadeIn = 10;
        int fadeOut = this.lifetime / 3;
        this.alpha = this.age < fadeIn ? (float)this.age / (float)fadeIn : (this.age > this.lifetime - fadeOut ? Math.max(0.0f, (float)(this.lifetime - this.age) / (float)fadeOut) : 0.9f);
        this.xd += (this.random.nextDouble() - 0.5) * 0.001;
        this.zd += (this.random.nextDouble() - 0.5) * 0.001;
    }

    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static class Provider
    implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed) {
            return new ZParticle(level, x, y, z, this.sprites);
        }
    }
}

