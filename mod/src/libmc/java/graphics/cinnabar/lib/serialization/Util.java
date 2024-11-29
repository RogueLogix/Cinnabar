package graphics.cinnabar.lib.serialization;

import graphics.cinnabar.lib.serialization.PhosphophylliteCompound;
import net.minecraft.nbt.CompoundTag;

public class Util {
    public static CompoundTag toCompoundTag(PhosphophylliteCompound compound) {
        final var nbt = new CompoundTag();
        nbt.putByteArray("asROBN", compound.toROBN());
        return nbt;
    }
    
    public static PhosphophylliteCompound toPhosphophylliteCompound(CompoundTag nbt) {
        return new PhosphophylliteCompound(nbt.getByteArray("asROBN"));
    }
}
