package com.charybdis180.ethological.block;

import com.charybdis180.ethological.block.FeederBlock;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.Tags;

public class ChickenFeederBlock
extends FeederBlock
implements SimpleWaterloggedBlock {
    public static final MapCodec<ChickenFeederBlock> CODEC = ChickenFeederBlock.simpleCodec(ChickenFeederBlock::new);
    private static final VoxelShape SHAPE = Shapes.or((VoxelShape)Shapes.box((double)0.40625, (double)0.0, (double)0.40625, (double)0.59375, (double)0.0625, (double)0.59375), (VoxelShape[])new VoxelShape[]{Shapes.box((double)0.34375, (double)0.0625, (double)0.34375, (double)0.40625, (double)0.375, (double)0.65625), Shapes.box((double)0.59375, (double)0.0625, (double)0.34375, (double)0.65625, (double)0.375, (double)0.65625), Shapes.box((double)0.34375, (double)0.0625, (double)0.34375, (double)0.65625, (double)0.375, (double)0.40625), Shapes.box((double)0.34375, (double)0.0625, (double)0.59375, (double)0.65625, (double)0.375, (double)0.65625), Shapes.box((double)0.34375, (double)0.375, (double)0.34375, (double)0.65625, (double)0.4375, (double)0.65625)});

    public ChickenFeederBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(new Property[]{BlockStateProperties.WATERLOGGED});
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        FluidState fluidState = context.getLevel().getFluidState(context.getClickedPos());
        return (BlockState)super.getStateForPlacement(context).setValue((Property)BlockStateProperties.WATERLOGGED, (Comparable)Boolean.valueOf(fluidState.getType() == Fluids.WATER));
    }

    protected FluidState getFluidState(BlockState state) {
        return (Boolean)state.getValue((Property)BlockStateProperties.WATERLOGGED) != false ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    protected MapCodec<? extends ChickenFeederBlock> codec() {
        return CODEC;
    }

    @Override
    public boolean accepts(ItemStack stack) {
        return stack.is(Tags.Items.SEEDS);
    }

    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return 0;
    }
}

