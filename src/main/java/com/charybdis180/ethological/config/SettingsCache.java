package com.charybdis180.ethological.config;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.world.entity.EntityType;

/** Resolved per-species settings memo. Each settings manager owns one instance;
 * entries are computed lazily and dropped on invalidate (config change or datapack reload). */
public final class SettingsCache<T> {
    private final ConcurrentHashMap<EntityType<?>, Optional<T>> resolved = new ConcurrentHashMap<>();

    public Optional<T> get(EntityType<?> type, Supplier<Optional<T>> resolver) {
        return this.resolved.computeIfAbsent(type, key -> resolver.get());
    }

    public void invalidate() {
        this.resolved.clear();
    }
}
