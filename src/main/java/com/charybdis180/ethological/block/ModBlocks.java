/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.registries.Registries
 *  net.minecraft.resources.ResourceKey
 *  net.minecraft.world.item.BlockItem
 *  net.minecraft.world.item.CreativeModeTabs
 *  net.minecraft.world.item.Item
 *  net.minecraft.world.item.Item$Properties
 *  net.minecraft.world.level.ItemLike
 *  net.minecraft.world.level.block.Block
 *  net.minecraft.world.level.block.SoundType
 *  net.minecraft.world.level.block.state.BlockBehaviour$Properties
 *  net.minecraft.world.level.block.state.properties.NoteBlockInstrument
 *  net.minecraft.world.level.material.MapColor
 *  net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
 *  net.neoforged.neoforge.registries.DeferredRegister
 */
package com.charybdis180.ethological.block;

import com.charybdis180.ethological.block.ChickenFeederBlock;
import com.charybdis180.ethological.block.TroughBlock;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create((ResourceKey)Registries.BLOCK, (String)"ethological");
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create((ResourceKey)Registries.ITEM, (String)"ethological");
    public static final Supplier<TroughBlock> TROUGH = BLOCKS.register("trough", () -> new TroughBlock(ModBlocks.troughProperties()));
    public static final Supplier<ChickenFeederBlock> CHICKEN_FEEDER = BLOCKS.register("chicken_feeder", () -> new ChickenFeederBlock(ModBlocks.chickenFeederProperties()));
    public static final Supplier<BlockItem> TROUGH_ITEM = ITEMS.register("trough", () -> new BlockItem((Block)TROUGH.get(), new Item.Properties()));
    public static final Supplier<BlockItem> CHICKEN_FEEDER_ITEM = ITEMS.register("chicken_feeder", () -> new BlockItem((Block)CHICKEN_FEEDER.get(), new Item.Properties()));

    private ModBlocks() {
    }

    private static BlockBehaviour.Properties troughProperties() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).instrument(NoteBlockInstrument.BASS).strength(0.6f).sound(SoundType.WOOD).ignitedByLava().isViewBlocking((state, level, pos) -> false).noOcclusion();
    }

    private static BlockBehaviour.Properties chickenFeederProperties() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).forceSolidOn().requiresCorrectToolForDrops().strength(3.0f).sound(SoundType.LANTERN).isViewBlocking((state, level, pos) -> false).noOcclusion();
    }

    public static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept((ItemLike)TROUGH_ITEM.get());
            event.accept((ItemLike)CHICKEN_FEEDER_ITEM.get());
        }
    }
}

