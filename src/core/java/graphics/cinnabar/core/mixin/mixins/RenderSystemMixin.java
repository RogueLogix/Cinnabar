package graphics.cinnabar.core.mixin.mixins;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import graphics.cinnabar.core.CinnabarCore;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.earlywindow.VulkanStartup;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.BiFunction;

@Mixin(RenderSystem.class)
public class RenderSystemMixin {
    
    @Shadow
    @Nullable
    private static GpuDevice DEVICE;
    @Shadow
    private static String apiDescription = "Unknown";
    @Shadow
    @Nullable
    private static GpuBuffer QUAD_VERTEX_BUFFER;
    @Shadow
    @Nullable
    private static DynamicUniforms dynamicUniforms;
    
    @Overwrite
    public static void initRenderer(long windowHandle, int debugLevel, boolean syncDebug, BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider, boolean debugLabels) {
        if (VulkanStartup.isSupported()) {
            DEVICE = new CinnabarDevice(windowHandle, debugLevel, syncDebug, shaderSourceProvider, debugLabels);
        } else {
            DEVICE = new GlDevice(windowHandle, debugLevel, syncDebug, shaderSourceProvider, debugLabels);
        }
        apiDescription = DEVICE.getImplementationInformation();
        dynamicUniforms = new DynamicUniforms();
        
        try (ByteBufferBuilder bytebufferbuilder = new ByteBufferBuilder(DefaultVertexFormat.POSITION.getVertexSize() * 4)) {
            BufferBuilder bufferbuilder = new BufferBuilder(bytebufferbuilder, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            bufferbuilder.addVertex(1.0F, 1.0F, 0.0F);
            bufferbuilder.addVertex(0.0F, 1.0F, 0.0F);
            bufferbuilder.addVertex(0.0F, 0.0F, 0.0F);
            bufferbuilder.addVertex(1.0F, 0.0F, 0.0F);
            
            try (MeshData meshdata = bufferbuilder.buildOrThrow()) {
                QUAD_VERTEX_BUFFER = RenderSystem.getDevice().createBuffer(() -> "Quad", GpuBuffer.USAGE_VERTEX, meshdata.vertexBuffer());
            }
        }
    }
    
    // this is set at the beginning of device init, and allows me to get the device within its own init phase
    // this is used for debug things
    @Overwrite
    public static GpuDevice getDevice() {
        if (DEVICE != null) {
            return DEVICE;
        }
        if (CinnabarCore.cinnabarDeviceSingleton != null){
            return CinnabarCore.cinnabarDeviceSingleton;
        }
        throw new IllegalStateException("Can't getDevice() before it was initialized");
    }
}
