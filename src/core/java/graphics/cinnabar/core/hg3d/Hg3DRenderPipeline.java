package graphics.cinnabar.core.hg3d;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import graphics.cinnabar.api.hg.HgGraphicsPipeline;
import graphics.cinnabar.api.hg.HgRenderPass;
import graphics.cinnabar.api.hg.HgUniformSet;
import graphics.cinnabar.api.hg.enums.HgCompareOp;
import graphics.cinnabar.api.hg.enums.HgFormat;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.api.util.Pair;
import it.unimi.dsi.fastutil.objects.Object2ReferenceArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST;
import static org.lwjgl.vulkan.VK10.*;

public class Hg3DRenderPipeline implements Hg3DObject, CompiledRenderPipeline, Destroyable {
    
    private static final Map<ShaderSourceCacheKey, String> shaderSourceCache = new Object2ReferenceOpenHashMap<>();
    private final RenderPipeline info;
    private final Hg3DGpuDevice device;
    private final HgGraphicsPipeline.ShaderSet shaderSet;
    private final HgUniformSet.Layout uniformSetLayout;
    private final HgUniformSet.Pool uniformPool;
    private final HgGraphicsPipeline.Layout pipelineLayout;
    private final HgGraphicsPipeline.CreateInfo.State pipelineState;
    private final Map<HgRenderPass, HgGraphicsPipeline> pipelines = new Reference2ReferenceOpenHashMap<>();
    private final Map<String, HgFormat> texelBufferFormats = new Object2ReferenceArrayMap<>();
    public Hg3DRenderPipeline(Hg3DGpuDevice device, RenderPipeline pipeline, ShaderSource shaderSourceProvider) {
        this.info = pipeline;
        this.device = device;
        final var hgDevice = device.hgDevice();
        @Nullable
        var vertexSource = shaderSourceCache.computeIfAbsent(new ShaderSourceCacheKey(pipeline.getVertexShader(), ShaderType.VERTEX), key -> shaderSourceProvider.get(key.location, key.type));
        assert vertexSource != null;
        // TODO: remove this when the shader gets fixed
        if ("minecraft:core/entity".equals(pipeline.getVertexShader().toString())) {
            vertexSource = vertexSource.replace("overlayColor = texelFetch(Sampler1, UV1, 0);", """
                        #ifndef NO_OVERLAY
                        overlayColor = texelFetch(Sampler1, UV1, 0);
                        #endif
                    """);
        }
        @Nullable
        final var fragmentSource = shaderSourceCache.computeIfAbsent(new ShaderSourceCacheKey(pipeline.getFragmentShader(), ShaderType.FRAGMENT), key -> shaderSourceProvider.get(key.location, key.type));
        assert fragmentSource != null;
        final var glVertexGLSL = GlslPreprocessor.injectDefines(vertexSource, pipeline.getShaderDefines());
        final var glFragmentGLSL = GlslPreprocessor.injectDefines(fragmentSource, pipeline.getShaderDefines());
        
        final var alignment = device().getUniformOffsetAlignment();
        final var cinnabarStandardDefines = """
                #define CINNABAR_VK
                #define CINNABAR_UBO_ALIGNMENT %s
                #define gl_VertexID gl_VertexIndex
                #define gl_InstanceID gl_InstanceIndex
                #define samplerBuffer textureBuffer
                """.formatted(alignment);
        final var mojangsShaderAreBrokenReplacements = Map.of(
                // these attempt to use R16G16_SSCALED, which isn't supported on all devices
                "in vec2 UV1;", "in ivec2 UV1;",
                "in vec2 UV2;", "in ivec2 UV2;",
                "gl_VertexID", "gl_VertexIndex",
                "gl_InstanceID", "gl_InstanceIndex",
                "samplerBuffer", "textureBuffer",
                chunkSectionTarget, chunkSectionReplacement
        );
        
        var fixedUpVertexGLSL = glVertexGLSL;
        for (Map.Entry<String, String> fixup : mojangsShaderAreBrokenReplacements.entrySet()) {
            fixedUpVertexGLSL = fixedUpVertexGLSL.replace(fixup.getKey(), fixup.getValue());
        }
        var fixedUpFragmentGLSL = glFragmentGLSL;
        for (Map.Entry<String, String> fixup : mojangsShaderAreBrokenReplacements.entrySet()) {
            fixedUpFragmentGLSL = fixedUpFragmentGLSL.replace(fixup.getKey(), fixup.getValue());
        }
        
        final var versionRemovedVertexSource = fixedUpVertexGLSL.replace("#version", cinnabarStandardDefines + "\n#define CINNABAR_VERTEX_SHADER //");
        final var versionRemovedFragmentSource = fixedUpFragmentGLSL.replace("#version", cinnabarStandardDefines + "\n#define CINNABAR_FRAGMENT_SHADER //");
        
        shaderSet = hgDevice.createShaderSet(HgGraphicsPipeline.ShaderSet.CreateInfo.gl(versionRemovedVertexSource, versionRemovedFragmentSource));
        uniformSetLayout = hgDevice.createUniformSetLayout(Objects.requireNonNull(shaderSet.uniformSetLayoutCreateInfo(0)));
        uniformPool = uniformSetLayout.createPool(new HgUniformSet.Pool.CreateInfo());
        
        pipelineLayout = hgDevice.createPipelineLayout(new HgGraphicsPipeline.Layout.CreateInfo(List.of(uniformSetLayout), 0));
        
        final ImmutableMap<String, VertexFormatElement> vertexFormatElements;
        {
            final var elements = pipeline.getVertexFormat().getElements();
            final var names = pipeline.getVertexFormat().getElementAttributeNames();
            ImmutableMap.Builder<String, VertexFormatElement> builder = ImmutableMap.builder();
            for (int i = 0; i < elements.size(); i++) {
                builder.put(names.get(i), elements.get(i));
            }
            vertexFormatElements = builder.build();
        }
        final var shaderAttribs = Objects.requireNonNull(shaderSet.attribs());
        final var vertexInputBindings = new ReferenceArrayList<HgGraphicsPipeline.VertexInput.Binding>();
        for (int i = 0; i < shaderAttribs.size(); i++) {
            final var attrib = shaderAttribs.get(i);
            final var vertexFormatElement = Objects.requireNonNull(vertexFormatElements.get(attrib.name()));
            vertexInputBindings.add(new HgGraphicsPipeline.VertexInput.Binding(0, attrib.location(), Hg3DConst.vertexInputFormat(vertexFormatElement.type(), vertexFormatElement.count(), vertexFormatElement.normalized()), pipeline.getVertexFormat().getOffset(vertexFormatElement)));
        }
        final var vertexInput = new HgGraphicsPipeline.VertexInput(List.of(new HgGraphicsPipeline.VertexInput.Buffer(0, pipeline.getVertexFormat().getVertexSize(), HgGraphicsPipeline.VertexInput.Buffer.InputRate.VERTEX)), vertexInputBindings);
        
        final var rasterizer = new HgGraphicsPipeline.Rasterizer(switch (pipeline.getPolygonMode()) {
            case FILL -> HgGraphicsPipeline.Rasterizer.PolygonMode.FILL;
            case WIREFRAME -> HgGraphicsPipeline.Rasterizer.PolygonMode.LINE;
        }, pipeline.isCull(), pipeline.getDepthBiasConstant(), pipeline.getDepthBiasScaleFactor());
        
        @Nullable final HgGraphicsPipeline.DepthTest depthTest;
        if (pipeline.getDepthTestFunction() != NO_DEPTH_TEST || pipeline.isWriteDepth()) {
            depthTest = new HgGraphicsPipeline.DepthTest(switch (pipeline.getDepthTestFunction()) {
                case NO_DEPTH_TEST -> HgCompareOp.ALWAYS;
                case EQUAL_DEPTH_TEST -> HgCompareOp.EQUAL;
                case LEQUAL_DEPTH_TEST -> HgCompareOp.LESS_OR_EQUAL;
                case LESS_DEPTH_TEST -> HgCompareOp.LESS;
                case GREATER_DEPTH_TEST -> HgCompareOp.GREATER;
            }, pipeline.isWriteDepth());
        } else {
            depthTest = null;
        }
        
        @Nullable final HgGraphicsPipeline.Stencil stencil;
        #if NEO
        if (pipeline.getStencilTest().isPresent()) {
            final var stencilTest = pipeline.getStencilTest().get();
            final var frontTest = stencilTest.front();
            final var backTest = stencilTest.back();
            final var front = new HgGraphicsPipeline.Stencil.OpState(Hg3DConst.stencil(frontTest.fail()), Hg3DConst.stencil(frontTest.depthFail()), Hg3DConst.stencil(frontTest.pass()), Hg3DConst.stencil(frontTest.compare()), stencilTest.readMask(), stencilTest.writeMask(), stencilTest.referenceValue());
            final var back = new HgGraphicsPipeline.Stencil.OpState(Hg3DConst.stencil(backTest.fail()), Hg3DConst.stencil(backTest.depthFail()), Hg3DConst.stencil(backTest.pass()), Hg3DConst.stencil(backTest.compare()), stencilTest.readMask(), stencilTest.writeMask(), stencilTest.referenceValue());
            stencil = new HgGraphicsPipeline.Stencil(front, back);
        } else {
            stencil = null;
        }
        #elif FABRIC
        stencil = null;
        #endif
        
        @Nullable final HgGraphicsPipeline.Blend blend;
        if (pipeline.getBlendFunction().isPresent() || !pipeline.isWriteColor() || !pipeline.isWriteAlpha()) {
            @Nullable final Pair<HgGraphicsPipeline.Blend.Equation, HgGraphicsPipeline.Blend.Equation> blendEquations;
            if (pipeline.getBlendFunction().isPresent()) {
                final var blendFunc = pipeline.getBlendFunction().get();
                final var colorEquation = new HgGraphicsPipeline.Blend.Equation(Hg3DConst.factor(blendFunc.sourceColor()), Hg3DConst.factor(blendFunc.destColor()), HgGraphicsPipeline.Blend.Op.ADD);
                final var alphaEquation = new HgGraphicsPipeline.Blend.Equation(Hg3DConst.factor(blendFunc.sourceAlpha()), Hg3DConst.factor(blendFunc.destAlpha()), HgGraphicsPipeline.Blend.Op.ADD);
                blendEquations = new Pair<>(colorEquation, alphaEquation);
            } else {
                blendEquations = null;
            }
            final var attachment = new HgGraphicsPipeline.Blend.Attachment(blendEquations, (pipeline.isWriteColor() ? (VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT) : 0) | (pipeline.isWriteAlpha() ? VK_COLOR_COMPONENT_A_BIT : 0));
            blend = new HgGraphicsPipeline.Blend(List.of(attachment), new Vector4f());
        } else {
            blend = null;
        }
        
        pipelineState = new HgGraphicsPipeline.CreateInfo.State(vertexInput, Hg3DConst.topology(pipeline.getVertexFormatMode()), rasterizer, depthTest, stencil, blend);
        
        for (RenderPipeline.UniformDescription uniform : pipeline.getUniforms()) {
            switch (uniform.type()) {
                case UNIFORM_BUFFER -> {
                }
                case TEXEL_BUFFER -> {
                    assert uniform.textureFormat() != null;
                    texelBufferFormats.put(uniform.name(), Hg3DConst.format(uniform.textureFormat()));
                }
            }
        }
    }
    
    @Override
    public void destroy() {
        pipelines.values().forEach(Destroyable::destroy);
        pipelineLayout.destroy();
        uniformSetLayout.destroy();
        shaderSet.destroy();
    }
    
    @Override
    public boolean isValid() {
        // TODO: better error checking
        return true;
    }
    
    @Override
    public Hg3DGpuDevice device() {
        return device;
    }
    
    private HgGraphicsPipeline createPipeline(HgRenderPass renderPass) {
        return device.hgDevice().createPipeline(new HgGraphicsPipeline.CreateInfo(renderPass, shaderSet, pipelineLayout, pipelineState));
    }
    
    public HgGraphicsPipeline getPipeline(HgRenderPass renderPass) {
        return pipelines.computeIfAbsent(renderPass, this::createPipeline);
    }
    
    public HgFormat texelBufferFormat(String name) {
        return texelBufferFormats.get(name);
    }
    
    public HgUniformSet.Pool uniformPool() {
        return uniformPool;
    }
    
    public RenderPipeline info() {
        return info;
    }
    
    record ShaderSourceCacheKey(Identifier location, ShaderType type) {
    }
    
    
    private static final String chunkSectionTarget = """
            layout(std140) uniform ChunkSection {
                mat4 ModelViewMat;
                float ChunkVisibility;
                ivec2 TextureSize;
                ivec3 ChunkPosition;
            };
            """;
    private static final String chunkSectionReplacement = """
            #ifdef CINNABAR_VK
            
            #if !defined(CINNABAR_VERTEX_SHADER) && !defined(CINNABAR_FRAGMENT_SHADER)
            #error CINNABAR_VERTEX_SHADER or CINNABAR_FRAGMENT_SHADER must be defined
            #endif
            
            #ifdef CINNABAR_VERTEX_SHADER
            #define CINNABAR_BETWEEN_STAGES out
            #else
            #define CINNABAR_BETWEEN_STAGES in
            #endif
            
            mat4 ModelViewMat;
            layout(location = 6) CINNABAR_BETWEEN_STAGES flat float ChunkVisibility;
            layout(location = 7) CINNABAR_BETWEEN_STAGES flat ivec2 TextureSize;
            ivec3 ChunkPosition;
            
            struct CnkSection {
                mat4 ModelViewMat;
                float ChunkVisibility;
                ivec2 TextureSize;
                ivec3 ChunkPosition;
                int padding1;
            // std140 92 bytes, align 16, 96 byte array stride
            #define REQUIRED_PADDING_VEC4S  ((((96 + (CINNABAR_UBO_ALIGNMENT - 1)) & ~(CINNABAR_UBO_ALIGNMENT - 1)) - 96) / 16)
            #if REQUIRED_PADDING_VEC4S > 0
                vec4[REQUIRED_PADDING_VEC4S] padding;
            #endif
            };
            
            #ifdef CINNABAR_VERTEX_SHADER
            layout(std140) buffer readonly ChunkSection {
                CnkSection cnkSections[];
            };
            #endif
            
            void loadCnkSection() {
                #ifdef CINNABAR_VERTEX_SHADER
                // even with multidraw, base_instance can be used to index into it
                // but this also works if im not doing multidraw
                int arrayIndex = gl_BaseInstance;
                CnkSection section = cnkSections[arrayIndex];
                ModelViewMat = section.ModelViewMat;
                ChunkVisibility = section.ChunkVisibility;
                TextureSize = section.TextureSize;
                ChunkPosition = section.ChunkPosition;
                #endif
            }
            
            // overwrite the main func
            void realMain();
            
            void main() {
                loadCnkSection();
                realMain();
            }
            
            #define main realMain
            
            #else
            
            layout(std140) uniform ChunkSection {
                mat4 ModelViewMat;
                float ChunkVisibility;
                ivec2 TextureSize;
                ivec3 ChunkPosition;
            };
            
            #endif
            """;
}
