package graphics.cinnabar.lib.serialization;

import net.minecraft.nbt.CompoundTag;

public class Util {
    public static CompoundTag toCompoundTag(PhosphophylliteCompound compound) {
        final var nbt = new CompoundTag();
        nbt.putByteArray("asROBN", compound.toROBN().elements());
        return nbt;
    }
    
    public static PhosphophylliteCompound toPhosphophylliteCompound(CompoundTag nbt) {
        return new PhosphophylliteCompound(nbt.getByteArray("asROBN").orElseThrow());
    }
}
