package graphics.cinnabar.api.b3dext.vertex;

import com.mojang.blaze3d.vertex.VertexFormat;

public record VertexInputBuffer(int bindingIndex, VertexFormat bufferFormat, InputRate inputRate) {
    public enum InputRate {
        VERTEX,
        INSTANCE,
    }
}
