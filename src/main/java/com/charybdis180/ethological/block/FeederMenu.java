package com.charybdis180.ethological.block;

import com.charybdis180.ethological.block.FeederBlockEntity;
import com.charybdis180.ethological.block.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

public class FeederMenu
extends AbstractContainerMenu {
    private final IItemHandler itemHandler;
    @Nullable
    private final Level level;
    @Nullable
    private final BlockPos pos;

    public FeederMenu(int id, Inventory playerInventory, FeederBlockEntity blockEntity) {
        this(ModMenus.FEEDER.get(), id, playerInventory, blockEntity.getInventory(), blockEntity.getLevel(), blockEntity.getBlockPos());
    }

    public FeederMenu(@Nullable MenuType<?> type, int id, Inventory playerInventory, IItemHandler itemHandler) {
        this(type, id, playerInventory, itemHandler, null, null);
    }

    public FeederMenu(@Nullable MenuType<?> type, int id, Inventory playerInventory, IItemHandler itemHandler, @Nullable Level level, @Nullable BlockPos pos) {
        super(type, id);
        this.itemHandler = itemHandler;
        this.level = level;
        this.pos = pos;
        for (int i = 0; i < 5; ++i) {
            this.addSlot((Slot)new SlotItemHandler(itemHandler, i, 44 + i * 18, 20));
        }
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot((Container)playerInventory, col + row * 9 + 9, 8 + col * 18, 51 + row * 18));
            }
        }
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot((Container)playerInventory, col, 8 + col * 18, 109));
        }
    }

    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = (Slot)this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < 5 ? !this.moveItemStackTo(stack, 5, this.slots.size(), true) : !this.moveItemStackTo(stack, 0, 5, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return result;
    }

    public boolean stillValid(Player player) {
        if (this.level == null || this.pos == null) {
            return false;
        }
        return this.level.getBlockState(this.pos).getBlock() instanceof FeederBlock
                && player.distanceToSqr(Vec3.atCenterOf(this.pos)) <= 64.0;
    }

    protected boolean moveItemStackTo(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection) {
        int i;
        boolean changed = false;
        int n = i = reverseDirection ? endIndex - 1 : startIndex;
        while (!(!reverseDirection ? i >= endIndex : i < startIndex)) {
            int existing;
            int toInsert;
            int maxSize;
            Slot slot = (Slot)this.slots.get(i);
            if (!slot.hasItem() && slot.mayPlace(stack)) {
                maxSize = Math.min(slot.getMaxStackSize(stack), stack.getMaxStackSize());
                int toInsert2 = Math.min(maxSize, stack.getCount());
                if (toInsert2 > 0) {
                    slot.setByPlayer(stack.split(toInsert2));
                    slot.setChanged();
                    changed = true;
                }
            } else if (slot.hasItem() && ItemStack.isSameItemSameComponents((ItemStack)slot.getItem(), (ItemStack)stack) && (toInsert = Math.min((maxSize = Math.min(slot.getMaxStackSize(stack), stack.getMaxStackSize())) - (existing = slot.getItem().getCount()), stack.getCount())) > 0) {
                stack.shrink(toInsert);
                slot.getItem().grow(toInsert);
                slot.setChanged();
                changed = true;
            }
            if (stack.isEmpty()) break;
            i += reverseDirection ? -1 : 1;
        }
        return changed;
    }
}

