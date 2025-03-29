package graphics.cinnabar.core.vk.descriptors;

import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.memory.PointerWrapper;

@API
public interface UBOWriter {
    
    @ThreadSafety.Any
    void write(UBOMember member, PointerWrapper newData);
}
