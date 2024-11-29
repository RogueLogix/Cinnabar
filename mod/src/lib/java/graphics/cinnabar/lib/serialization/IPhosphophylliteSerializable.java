package graphics.cinnabar.lib.serialization;

import org.jetbrains.annotations.Nullable;

public interface IPhosphophylliteSerializable {
    @Nullable
    PhosphophylliteCompound save();
    
    void load(PhosphophylliteCompound compound);
}
