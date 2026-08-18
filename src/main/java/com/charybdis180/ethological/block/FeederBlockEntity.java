/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.HolderLookup$Provider
 *  net.minecraft.nbt.CompoundTag
 *  net.minecraft.nbt.Tag
 *  net.minecraft.network.chat.Component
 *  net.minecraft.world.Container
 *  net.minecraft.world.MenuProvider
 *  net.minecraft.world.SimpleContainer
 *  net.minecraft.world.entity.player.Inventory
 *  net.minecraft.world.entity.player.Player
 *  net.minecraft.world.inventory.AbstractContainerMenu
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.level.block.Block
 *  net.minecraft.world.level.block.entity.BlockEntity
 *  net.minecraft.world.level.block.state.BlockState
 *  net.neoforged.neoforge.items.ItemStackHandler
 *  org.jetbrains.annotations.Nullable
 */
package com.charybdis180.ethological.block;

import com.charybdis180.ethological.block.Containers;
import com.charybdis180.ethological.block.FeederBlock;
import com.charybdis180.ethological.block.FeederMenu;
import com.charybdis180.ethological.block.ModBlockEntities;
import com.charybdis180.ethological.block.TroughBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

public class FeederBlockEntity
extends BlockEntity
implements MenuProvider {
    public static final int SLOT_COUNT = 5;
    private final ItemStackHandler inventory = new ItemStackHandler(5){

        protected void onContentsChanged(int slot) {
            super.onContentsChanged(slot);
            FeederBlockEntity.this.updateFillLevel();
            FeederBlockEntity.this.setChanged();
        }

        public boolean isItemValid(int slot, ItemStack stack) {
            Block block = FeederBlockEntity.this.getBlockState().getBlock();
            if (!(block instanceof FeederBlock)) {
                return false;
            }
            FeederBlock feederBlock = (FeederBlock)block;
            return feederBlock.accepts(stack) && super.isItemValid(slot, stack);
        }
    };

    public FeederBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FEEDER.get(), pos, state);
    }

    public ItemStackHandler getInventory() {
        return this.inventory;
    }

    public void updateFillLevel() {
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        int total = 0;
        for (int i = 0; i < this.inventory.getSlots(); ++i) {
            total += this.inventory.getStackInSlot(i).getCount();
        }
        FeederBlock.FillLevel fill = FeederBlock.FillLevel.of(total);
        BlockState state = this.getBlockState();
        if (state.getValue(FeederBlock.FILL) != fill) {
            this.level.invalidateCapabilities(this.worldPosition);
            this.level.setBlock(this.worldPosition, state.setValue(FeederBlock.FILL, fill), 3);
        }
    }

    public boolean consumeItem() {
        for (int i = 0; i < this.inventory.getSlots(); ++i) {
            if (this.inventory.getStackInSlot(i).isEmpty()) continue;
            this.inventory.extractItem(i, 1, false);
            return true;
        }
        return false;
    }

    public boolean hasFood() {
        for (int i = 0; i < this.inventory.getSlots(); ++i) {
            if (this.inventory.getStackInSlot(i).isEmpty()) continue;
            return true;
        }
        return false;
    }

    public ItemStack tryInsert(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remainder = stack.copy();
        for (int i = 0; i < this.inventory.getSlots() && !(remainder = this.inventory.insertItem(i, remainder, false)).isEmpty(); ++i) {
        }
        return remainder;
    }

    public void dropContents(Level level, BlockPos pos) {
        Containers.dropContents(level, pos, (Container)new SimpleContainer(this.inventory.getSlots()){

            public ItemStack getItem(int index) {
                return FeederBlockEntity.this.inventory.getStackInSlot(index);
            }

            public ItemStack removeItem(int index, int count) {
                return FeederBlockEntity.this.inventory.extractItem(index, count, false);
            }

            public ItemStack removeItemNoUpdate(int index) {
                ItemStack stack = FeederBlockEntity.this.inventory.getStackInSlot(index).copy();
                FeederBlockEntity.this.inventory.setStackInSlot(index, ItemStack.EMPTY);
                return stack;
            }

            public void setItem(int index, ItemStack stack) {
                FeederBlockEntity.this.inventory.setStackInSlot(index, stack);
            }

            public int getContainerSize() {
                return FeederBlockEntity.this.inventory.getSlots();
            }

            public boolean isEmpty() {
                for (int i = 0; i < FeederBlockEntity.this.inventory.getSlots(); ++i) {
                    if (FeederBlockEntity.this.inventory.getStackInSlot(i).isEmpty()) continue;
                    return false;
                }
                return true;
            }

            public void clearContent() {
                for (int i = 0; i < FeederBlockEntity.this.inventory.getSlots(); ++i) {
                    FeederBlockEntity.this.inventory.setStackInSlot(i, ItemStack.EMPTY);
                }
            }
        });
    }

    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("inventory", (Tag)this.inventory.serializeNBT(registries));
    }

    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.inventory.deserializeNBT(registries, tag.getCompound("inventory"));
    }

    public Component getDisplayName() {
        if (this.getBlockState().getBlock() instanceof TroughBlock) {
            return Component.translatable((String)"container.ethological.trough");
        }
        return Component.translatable((String)"container.ethological.chicken_feeder");
    }

    @Nullable
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new FeederMenu(id, inv, this);
    }
}

