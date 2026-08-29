package com.charybdis180.ethological.block;

import com.charybdis180.ethological.block.FeederBlockEntity;
import com.charybdis180.ethological.block.ModBlocks;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "ethological");
    public static final Supplier<BlockEntityType<FeederBlockEntity>> FEEDER = BLOCK_ENTITIES.register("feeder", () -> BlockEntityType.Builder.of(FeederBlockEntity::new, (Block[])new Block[]{(Block)ModBlocks.TROUGH.get(), (Block)ModBlocks.CHICKEN_FEEDER.get()}).build(null));

    private ModBlockEntities() {
    }
}

