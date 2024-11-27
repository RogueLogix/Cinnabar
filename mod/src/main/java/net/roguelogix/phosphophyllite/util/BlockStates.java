package net.roguelogix.phosphophyllite.util;


import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

public class BlockStates {
    public static final BooleanProperty PORT_DIRECTION = BooleanProperty.create("port_direction");
    public static final BooleanProperty ACTIVITY = BooleanProperty.create("active");
    public static final DirectionProperty FACING = DirectionProperty.create("facing", Direction.values());
}
