package graphics.cinnabar.core.mixin.mixins;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import graphics.cinnabar.core.b3d.CinnabarDevice;
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
    
    @Overwrite
    public static void initRenderer(long windowHandle, int debugLevel, boolean syncDebug, BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider, boolean debugLabels) {
        DEVICE = new CinnabarDevice(windowHandle, debugLevel, syncDebug, shaderSourceProvider, debugLabels);
        apiDescription = RenderSystem.getDevice().getImplementationInformation();
        
        try (ByteBufferBuilder bytebufferbuilder = new ByteBufferBuilder(DefaultVertexFormat.POSITION.getVertexSize() * 4)) {
            BufferBuilder bufferbuilder = new BufferBuilder(bytebufferbuilder, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            bufferbuilder.addVertex(1.0F, 1.0F, 0.0F);
            bufferbuilder.addVertex(0.0F, 1.0F, 0.0F);
            bufferbuilder.addVertex(0.0F, 0.0F, 0.0F);
            bufferbuilder.addVertex(1.0F, 0.0F, 0.0F);
            
            try (MeshData meshdata = bufferbuilder.buildOrThrow()) {
                QUAD_VERTEX_BUFFER = RenderSystem.getDevice().createBuffer(() -> "Quad", BufferType.VERTICES, BufferUsage.STATIC_WRITE, meshdata.vertexBuffer());
            }
        }
    }
}
