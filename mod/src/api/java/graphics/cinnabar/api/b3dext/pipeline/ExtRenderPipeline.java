package graphics.cinnabar.api.b3dext.pipeline;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.LogicOp;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.RewriteHierarchy;
import graphics.cinnabar.api.b3dext.vertex.VertexInputBuffer;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.ResourceLocation;
import org.codehaus.plexus.util.dag.Vertex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@RewriteHierarchy
public class ExtRenderPipeline extends RenderPipeline {
    private final List<VertexInputBuffer> vertexInputBuffers;
    
    protected ExtRenderPipeline(
            ResourceLocation location,
            ResourceLocation vertexShader,
            ResourceLocation fragmentShader,
            ShaderDefines shaderDefines,
            List<String> samplers,
            List<UniformDescription> uniforms,
            Optional<BlendFunction> blendFunction,
            DepthTestFunction depthTestFunction,
            PolygonMode polygonMode,
            boolean cull,
            boolean writeColor,
            boolean writeAlpha,
            boolean writeDepth,
            LogicOp colorLogic,
            List<VertexInputBuffer> vertexInputBuffers,
            VertexFormat.Mode vertexFormatMode,
            float depthBiasScaleFactor,
            float depthBiasConstant,
            int sortKey) {
        super(
                location,
                vertexShader,
                fragmentShader,
                shaderDefines,
                samplers,
                uniforms,
                blendFunction,
                depthTestFunction,
                polygonMode,
                cull,
                writeColor,
                writeAlpha,
                writeDepth,
                colorLogic,
                vertexInputBuffers.getFirst().bufferFormat(),
                vertexFormatMode,
                depthBiasScaleFactor,
                depthBiasConstant,
                sortKey);
        this.vertexInputBuffers = Collections.unmodifiableList(new ReferenceArrayList<>(vertexInputBuffers));
    }
    
    public List<VertexInputBuffer> getVertexInputBuffers() {
        return vertexInputBuffers;
    }
    
    @RewriteHierarchy
    public static class Builder extends RenderPipeline.Builder {
        private Optional<List<VertexInputBuffer>> vertexInputBuffers = Optional.empty();
        
        @Override
        public RenderPipeline.Builder withVertexFormat(VertexFormat vertexFormat, VertexFormat.Mode vertexFormatMode) {
            return withVertexInput(List.of(new VertexInputBuffer(0, vertexFormat, VertexInputBuffer.InputRate.VERTEX)), vertexFormatMode);
        }
        
        public RenderPipeline.Builder withVertexInput(List<VertexInputBuffer> vertexInput, VertexFormat.Mode vertexFormatMode) {
            super.withVertexFormat(vertexInput.getFirst().bufferFormat(), vertexFormatMode);
            vertexInputBuffers = Optional.of(new ReferenceArrayList<>(vertexInput));
            return this;
        }
        
        @Override
        protected void withSnippet(RenderPipeline.Snippet snippet) {
            super.withSnippet(snippet);
            if (snippet.vertexFormat().isPresent()) {
                this.vertexInputBuffers = Optional.of(List.of(new VertexInputBuffer(0, snippet.vertexFormat().get(), VertexInputBuffer.InputRate.VERTEX)));
            }
        }
        
        @Override
        public RenderPipeline build() {
            if (this.location.isEmpty()) {
                throw new IllegalStateException("Missing location");
            } else if (this.vertexShader.isEmpty()) {
                throw new IllegalStateException("Missing vertex shader");
            } else if (this.fragmentShader.isEmpty()) {
                throw new IllegalStateException("Missing fragment shader");
            } else if (this.vertexInputBuffers.isEmpty()) {
                throw new IllegalStateException("Missing vertex buffer format");
            } else if (this.vertexFormatMode.isEmpty()) {
                throw new IllegalStateException("Missing vertex mode");
            } else {
                return new ExtRenderPipeline(
                        this.location.get(),
                        this.vertexShader.get(),
                        this.fragmentShader.get(),
                        this.definesBuilder.orElse(ShaderDefines.builder()).build(),
                        List.copyOf(this.samplers.orElse(new ArrayList<>())),
                        this.uniforms.orElse(Collections.emptyList()),
                        this.blendFunction,
                        this.depthTestFunction.orElse(DepthTestFunction.LEQUAL_DEPTH_TEST),
                        this.polygonMode.orElse(PolygonMode.FILL),
                        this.cull.orElse(true),
                        this.writeColor.orElse(true),
                        this.writeAlpha.orElse(true),
                        this.writeDepth.orElse(true),
                        this.colorLogic.orElse(LogicOp.NONE),
                        this.vertexInputBuffers.get(),
                        this.vertexFormatMode.get(),
                        this.depthBiasScaleFactor,
                        this.depthBiasConstant,
                        nextPipelineSortKey++
                );
            }
        }
    }
}
