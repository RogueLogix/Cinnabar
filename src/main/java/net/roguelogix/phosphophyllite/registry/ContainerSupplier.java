package net.roguelogix.phosphophyllite.registry;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import javax.annotation.Nonnull;

public interface ContainerSupplier {
    @Nonnull
    AbstractContainerMenu create(int windowId, BlockPos blockPos, Player player);
}
