package com.charybdis180.ethological;

import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(Registries.SOUND_EVENT, "ethological");
    public static final Supplier<SoundEvent> SHEEP_EAT = SOUND_EVENTS.register("entity.sheep.eat", () -> SoundEvent.createVariableRangeEvent((ResourceLocation)ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"entity.sheep.eat")));
    public static final Supplier<SoundEvent> COW_EAT = SOUND_EVENTS.register("entity.cow.eat", () -> SoundEvent.createVariableRangeEvent((ResourceLocation)ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"entity.cow.eat")));
    public static final Supplier<SoundEvent> PIG_EAT = SOUND_EVENTS.register("entity.pig.eat", () -> SoundEvent.createVariableRangeEvent((ResourceLocation)ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"entity.pig.eat")));
    public static final Supplier<SoundEvent> CHICKEN_EAT = SOUND_EVENTS.register("entity.chicken.eat", () -> SoundEvent.createVariableRangeEvent((ResourceLocation)ResourceLocation.fromNamespaceAndPath((String)"ethological", (String)"entity.chicken.eat")));

    private ModSounds() {
    }
}

