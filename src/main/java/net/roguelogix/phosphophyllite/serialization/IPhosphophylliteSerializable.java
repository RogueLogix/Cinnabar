package net.roguelogix.phosphophyllite.serialization;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface IPhosphophylliteSerializable {
    @Nullable
    PhosphophylliteCompound save();
    
    void load(@Nonnull PhosphophylliteCompound compound);
}
