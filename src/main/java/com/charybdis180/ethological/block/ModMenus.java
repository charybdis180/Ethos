package com.charybdis180.ethological.block;

import com.charybdis180.ethological.block.FeederBlockEntity;
import com.charybdis180.ethological.block.FeederMenu;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, "ethological");
    public static final Supplier<MenuType<FeederMenu>> FEEDER = MENUS.register("feeder", () -> IMenuTypeExtension.create((id, inv, buf) -> {
        BlockPos pos = buf.readBlockPos();
        Level level = inv.player.level();
        BlockEntity patt0$temp = level.getBlockEntity(pos);
        if (patt0$temp instanceof FeederBlockEntity) {
            FeederBlockEntity feeder = (FeederBlockEntity)patt0$temp;
            return new FeederMenu(id, inv, feeder);
        }
        return new FeederMenu(ModMenus.FEEDER.get(), id, inv, (net.neoforged.neoforge.items.IItemHandler)new ItemStackHandler(5));
    }));

    private ModMenus() {
    }
}

