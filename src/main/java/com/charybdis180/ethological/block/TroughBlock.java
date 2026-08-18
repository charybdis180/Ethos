/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.serialization.MapCodec
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Direction
 *  net.minecraft.util.StringRepresentable
 *  net.minecraft.world.InteractionResult
 *  net.minecraft.world.MenuProvider
 *  net.minecraft.world.entity.player.Player
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.Items
 *  net.minecraft.world.item.context.BlockPlaceContext
 *  net.minecraft.world.level.BlockGetter
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.level.block.Block
 *  net.minecraft.world.level.block.Blocks
 *  net.minecraft.world.level.block.entity.BlockEntity
 *  net.minecraft.world.level.block.state.BlockBehaviour$Properties
 *  net.minecraft.world.level.block.state.BlockState
 *  net.minecraft.world.level.block.state.StateDefinition$Builder
 *  net.minecraft.world.level.block.state.properties.EnumProperty
 *  net.minecraft.world.level.block.state.properties.Property
 *  net.minecraft.world.phys.BlockHitResult
 *  net.minecraft.world.phys.shapes.CollisionContext
 *  net.minecraft.world.phys.shapes.Shapes
 *  net.minecraft.world.phys.shapes.VoxelShape
 *  org.jetbrains.annotations.Nullable
 */
package com.charybdis180.ethological.block;

import com.charybdis180.ethological.block.FeederBlock;
import com.charybdis180.ethological.block.FeederBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class TroughBlock
extends FeederBlock {
    public static final MapCodec<TroughBlock> CODEC = TroughBlock.simpleCodec(TroughBlock::new);
    public static final EnumProperty<Part> PART = EnumProperty.create((String)"part", Part.class);
    private static final VoxelShape COLLISION = Shapes.box((double)0.0, (double)0.0, (double)0.0, (double)1.0, (double)1.0, (double)1.0);

    public TroughBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(FILL, FeederBlock.FillLevel.EMPTY).setValue(PART, Part.CENTER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(new Property[]{FACING, FILL, PART});
    }

    protected MapCodec<? extends TroughBlock> codec() {
        return CODEC;
    }

    @Override
    public boolean accepts(ItemStack stack) {
        return stack.is(Items.WHEAT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        for (Direction offset : TroughBlock.longAxisOffsets(facing)) {
            boolean free;
            BlockState neighbor = level.getBlockState(pos.relative(offset));
            boolean bl = free = neighbor.isAir() || neighbor.canBeReplaced() || neighbor.getBlock() instanceof TroughBlock;
            if (free) continue;
            return null;
        }
        return this.defaultBlockState().setValue(FACING, facing).setValue(FILL, FeederBlock.FillLevel.EMPTY).setValue(PART, Part.CENTER);
    }

    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide || !(state.getBlock() instanceof TroughBlock)) {
            return;
        }
        if (state.getValue(PART) != Part.CENTER) {
            return;
        }
        Direction facing = (Direction)state.getValue((Property)FACING);
        for (Direction offset : TroughBlock.longAxisOffsets(facing)) {
            BlockPos side = pos.relative(offset);
            BlockState neighbor = level.getBlockState(side);
            if (!neighbor.isAir() && !neighbor.canBeReplaced()) continue;
            level.setBlock(side, this.defaultBlockState().setValue(FACING, facing).setValue(FILL, FeederBlock.FillLevel.EMPTY).setValue(PART, Part.SIDE), 3);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && state.getBlock() instanceof TroughBlock && !level.isClientSide) {
            Direction facing = (Direction)state.getValue((Property)FACING);
            for (Direction offset : TroughBlock.longAxisOffsets(facing)) {
                BlockPos neighbor = pos.relative(offset);
                BlockState neighborState = level.getBlockState(neighbor);
                if (!(neighborState.getBlock() instanceof TroughBlock) || neighborState.getValue((Property)FACING) != facing) continue;
                level.setBlock(neighbor, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        if (state.getValue(PART) == Part.CENTER) {
            return super.newBlockEntity(pos, state);
        }
        return null;
    }

    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return COLLISION;
    }

    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return COLLISION;
    }

    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return COLLISION;
    }

    public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return 0;
    }

    public boolean isFlammable(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return true;
    }

    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 20;
    }

    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 5;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockEntity blockEntity;
        BlockPos guiPos;
        BlockPos center = TroughBlock.findCenter(level, pos, (Direction)state.getValue((Property)FACING));
        BlockPos blockPos = guiPos = center != null ? center : pos;
        if (!level.isClientSide && (blockEntity = level.getBlockEntity(guiPos)) instanceof FeederBlockEntity) {
            FeederBlockEntity feeder = (FeederBlockEntity)blockEntity;
            player.openMenu((MenuProvider)feeder, buf -> buf.writeBlockPos(guiPos));
            return InteractionResult.sidedSuccess((boolean)level.isClientSide);
        }
        return InteractionResult.sidedSuccess((boolean)level.isClientSide);
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        BlockPos center = TroughBlock.findCenter(level, pos, (Direction)state.getValue((Property)FACING));
        return super.getAnalogOutputSignal(state, level, center != null ? center : pos);
    }

    @Nullable
    public static BlockPos findCenter(Level level, BlockPos pos, Direction facing) {
        for (Direction offset : TroughBlock.longAxisOffsets(facing)) {
            BlockPos candidate = pos.relative(offset);
            BlockState candidateState = level.getBlockState(candidate);
            if (!(candidateState.getBlock() instanceof TroughBlock) || candidateState.getValue(PART) != Part.CENTER) continue;
            return candidate;
        }
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof TroughBlock && state.getValue(PART) == Part.CENTER ? pos : null;
    }

    private static final Direction[] LONG_AXIS_NS = { Direction.NORTH, Direction.SOUTH };
    private static final Direction[] LONG_AXIS_EW = { Direction.EAST, Direction.WEST };

    private static Direction[] longAxisOffsets(Direction facing) {
        return facing == Direction.EAST || facing == Direction.WEST ? LONG_AXIS_NS : LONG_AXIS_EW;
    }

    public static enum Part implements StringRepresentable
    {
        CENTER("center"),
        SIDE("side");

        private final String name;

        private Part(String name) {
            this.name = name;
        }

        public String getSerializedName() {
            return this.name;
        }
    }
}

