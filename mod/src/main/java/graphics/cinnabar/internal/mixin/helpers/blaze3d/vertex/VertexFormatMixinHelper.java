package graphics.cinnabar.internal.mixin.helpers.blaze3d.vertex;

import com.mojang.blaze3d.vertex.VertexFormat;
import graphics.cinnabar.internal.vulkan.Destroyable;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;

public class VertexFormatMixinHelper {
    private static final ReferenceArrayList<Destroyable> vertexFormats = new ReferenceArrayList<>();
    
    public static void register(Destroyable format){
        vertexFormats.add(format);
    }
    
    public static void closeAll(){
        for (Destroyable vertexFormat : vertexFormats) {
            vertexFormat.destroy();
        }
    }
}
