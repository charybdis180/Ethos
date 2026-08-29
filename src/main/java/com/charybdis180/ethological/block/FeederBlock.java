package com.charybdis180.ethological.block;

import com.charybdis180.ethological.block.FeederBlockEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.items.ItemStackHandler;

public abstract class FeederBlock
extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<FillLevel> FILL = EnumProperty.create((String)"fill", FillLevel.class);

    protected FeederBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(FILL, FillLevel.EMPTY));
    }

    public abstract boolean accepts(ItemStack var1);

    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return List.of(new ItemStack((ItemLike)state.getBlock().asItem()));
    }

    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof FeederBlockEntity) {
            FeederBlockEntity feeder = (FeederBlockEntity)blockEntity;
            long total = 0L;
            ItemStackHandler inv = feeder.getInventory();
            for (int i = 0; i < inv.getSlots(); ++i) {
                total += (long)inv.getStackInSlot(i).getCount();
            }
            return (int)Math.max(0L, Math.min(15L, total * 15L / 320L));
        }
        return 0;
    }

    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(new Property[]{FACING, FILL});
    }

    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FeederBlockEntity(pos, state);
    }

    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockEntity blockEntity;
        if (!level.isClientSide && (blockEntity = level.getBlockEntity(pos)) instanceof FeederBlockEntity) {
            FeederBlockEntity feeder = (FeederBlockEntity)blockEntity;
            player.openMenu((MenuProvider)feeder, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.sidedSuccess((boolean)level.isClientSide);
    }

    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        BlockEntity blockEntity;
        if (!state.is(newState.getBlock()) && (blockEntity = level.getBlockEntity(pos)) instanceof FeederBlockEntity) {
            FeederBlockEntity feeder = (FeederBlockEntity)blockEntity;
            feeder.dropContents(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    public static enum FillLevel implements StringRepresentable
    {
        EMPTY("empty"),
        BARELY("barely"),
        HALF("half"),
        FULL("full");

        private final String name;

        private FillLevel(String name) {
            this.name = name;
        }

        public String getSerializedName() {
            return this.name;
        }

        public static FillLevel of(int totalCount) {
            if (totalCount >= 128) {
                return FULL;
            }
            if (totalCount >= 64) {
                return HALF;
            }
            if (totalCount >= 1) {
                return BARELY;
            }
            return EMPTY;
        }
    }
}

