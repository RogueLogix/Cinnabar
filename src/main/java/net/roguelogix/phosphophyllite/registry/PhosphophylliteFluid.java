package net.roguelogix.phosphophyllite.registry;

import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;

import javax.annotation.Nonnull;

public class PhosphophylliteFluid extends BaseFlowingFluid {
    
    protected PhosphophylliteFluid(Properties properties) {
        super(properties);
        registerDefaultState(defaultFluidState().setValue(LEVEL, 8));
    }
    
    @Override
    protected void createFluidStateDefinition(@Nonnull StateDefinition.Builder<Fluid, FluidState> builder) {
        super.createFluidStateDefinition(builder);
        builder.add(LEVEL);
    }
    
    boolean isSource = false;
    PhosphophylliteFluid flowingVariant;
    
    
    @Override
    public boolean isSource(FluidState state) {
        return isSource;
    }
    
    @Override
    public int getAmount(FluidState state) {
        return state.getValue(LEVEL);
    }
}
