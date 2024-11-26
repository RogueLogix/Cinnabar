package graphics.cinnabar.internal.extensions.minecraft.renderer;

public interface ICinnabarShader {
    void writeUniform(long UBOOffset, long cpuMemAddress, long size);
}
