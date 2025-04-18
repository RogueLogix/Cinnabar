package graphics.cinnabar.core.b3d.pipeline;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.LogicOp;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.VertexFormat;
import graphics.cinnabar.api.util.Pair;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.util.MagicNumbers;
import graphics.cinnabar.core.vk.VulkanObject;
import graphics.cinnabar.core.vk.descriptors.UBOBinding;
import graphics.cinnabar.core.vk.shaders.ShaderProcessing;
import graphics.cinnabar.core.vk.shaders.ShaderSet;
import graphics.cinnabar.core.vk.shaders.vertex.VertexInputState;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static graphics.cinnabar.core.CinnabarCore.CINNABAR_CORE_LOG;
import static org.lwjgl.vulkan.VK13.*;

public class CinnabarPipeline implements CompiledRenderPipeline, VulkanObject {
    
    private final CinnabarDevice device;
    
    public final RenderPipeline info;
    
    public final ShaderSet shaderSet;
    public final long pipelineHandle;
    
    // anything not specified here will be dumped into a single UBO
    private static final List<List<String>> ubos = List.of(
            RenderPipelines.MATRICES_SNIPPET.uniforms().get().stream().map(RenderPipeline.UniformDescription::name).toList(),
            RenderPipelines.FOG_SNIPPET.uniforms().get().stream().map(RenderPipeline.UniformDescription::name).toList()
    );
    
    private static final List<String> pushConstants = List.of("ModelOffset");
    
    @Override
    public long handle() {
        return pipelineHandle;
    }
    
    @Override
    public int objectType() {
        return VK_OBJECT_TYPE_PIPELINE;
    }
    
    record ShaderSourceCacheKey(ResourceLocation location, ShaderType type) {
    }
    
    // TODO: clear this when asked to
    private static final Map<ShaderSourceCacheKey, String> shaderSourceCache = new Object2ReferenceOpenHashMap<>();
    
    // TODO: soft fail, also deferred loading
    public CinnabarPipeline(CinnabarDevice device, BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider, RenderPipeline pipeline) {
        this.device = device;
        this.info = pipeline;
        
        // TODO: cache the processed/compiled shaders. if possible maybe even the entire pipeline binary?
        final var glVertexGLSL = GlslPreprocessor.injectDefines(shaderSourceCache.computeIfAbsent(new ShaderSourceCacheKey(pipeline.getVertexShader(), ShaderType.VERTEX), key -> shaderSourceProvider.apply(key.location, key.type)), pipeline.getShaderDefines());
        final var glFragmentGLSL = GlslPreprocessor.injectDefines(shaderSourceCache.computeIfAbsent(new ShaderSourceCacheKey(pipeline.getFragmentShader(), ShaderType.FRAGMENT), key -> shaderSourceProvider.apply(key.location, key.type)), pipeline.getShaderDefines());
        
        final var pipelineName = pipeline.getLocation().toString();
        final var vertexShaderName = pipeline.getVertexShader().toString();
        final var fragmentShaderName = pipeline.getFragmentShader().toString();
        
        CINNABAR_CORE_LOG.debug("Reprocessing shaders ({}, {}) for pipeline {}", vertexShaderName, fragmentShaderName, pipelineName);
        final Pair<String, String> processedShaders;
        {
            long start = System.nanoTime();
            processedShaders = ShaderProcessing.processShaders(glVertexGLSL, glFragmentGLSL, vertexShaderName, fragmentShaderName, pushConstants, ubos);
            long end = System.nanoTime();
            long time = end - start;
            CINNABAR_CORE_LOG.debug("{}us taken to process shaders for {}", time / (1_000), pipelineName);
        }
        CINNABAR_CORE_LOG.debug("Reprocessed shaders ({}, {}) for pipeline {}", vertexShaderName, fragmentShaderName, pipelineName);
        final var vkVertexGLSL = processedShaders.first();
        final var vkFragmentGLSL = processedShaders.second();
        
        {
            long start = System.nanoTime();
            shaderSet = ShaderSet.create(device, pipelineName, vertexShaderName, fragmentShaderName, vkVertexGLSL, vkFragmentGLSL);
            long end = System.nanoTime();
            long time = end - start;
            CINNABAR_CORE_LOG.debug("{}us taken to compile shaders for {}", time / (1_000), pipelineName);
        }
        
        try (final var stack = MemoryStack.stackPush()) {
            
            final var viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default();
            viewportState.pNext(0);
            viewportState.flags(0);
            viewportState.viewportCount(1);
            viewportState.pViewports(null);
            viewportState.scissorCount(1);
            viewportState.pScissors(null);
            
            final var renderingCreateInfo = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default();
            renderingCreateInfo.colorAttachmentCount(1);
            final var colorFormat = stack.callocInt(1);
            colorFormat.put(0, MagicNumbers.FramebufferColorFormat);
            renderingCreateInfo.pColorAttachmentFormats(colorFormat);
            renderingCreateInfo.depthAttachmentFormat(MagicNumbers.FramebufferDepthFormat); // TODO: this may not be D32, it may be D32_S8
            
            final var dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default();
            final var dynamicStates = stack.callocInt(2);
            dynamicStates.put(0, VK_DYNAMIC_STATE_VIEWPORT);
            dynamicStates.put(1, VK_DYNAMIC_STATE_SCISSOR);
            dynamicState.pDynamicStates(dynamicStates);
            
            final var multiSampleState = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default();
            multiSampleState.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            multiSampleState.sampleShadingEnable(false);
            
            var inputState = VertexInputState.forVertexFormat(pipeline.getVertexFormat());
            if (pipeline.getVertexShader().getPath().equals("core/terrain_instanced")) {
                // special bullshit thats done with terrain rendering
                final var offsetBuffer = new VertexInputState.Buffer(1, 12, true);
                final var attribs = new ReferenceArrayList<>(inputState.attribs());
                attribs.add(new VertexInputState.Attrib(shaderSet.attribLocation("ModelOffset"), 1, VK_FORMAT_R32G32B32_SFLOAT, 0));
                inputState = new VertexInputState(List.of(inputState.buffers().getFirst(), offsetBuffer), attribs);
            }
            
            final var bufferBindings = VkVertexInputBindingDescription.calloc(inputState.buffers().size(), stack);
            final var buffers = inputState.buffers();
            for (int i = 0; i < buffers.size(); i++) {
                final var buffer = buffers.get(i);
                bufferBindings.position(i);
                bufferBindings.binding(buffer.bindingIndex());
                bufferBindings.stride(buffer.stride());
                bufferBindings.inputRate(buffer.perInstance() ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX);
            }
            bufferBindings.position(0);
            
            final var attribBindings = VkVertexInputAttributeDescription.calloc(inputState.attribs().size(), stack);
            final var attribs = inputState.attribs();
            for (int i = 0; i < attribs.size(); i++) {
                final var attrib = attribs.get(i);
                if (attrib.name() == null) {
                    attribBindings.location(attrib.location());
                } else {
                    final var location = shaderSet.attribLocation(attrib.name());
                    if (location == -1) {
                        // skip inputs not used by the shader
                        continue;
                    }
                    attribBindings.location(location);
                }
                attribBindings.binding(attrib.bufferBinding());
                attribBindings.format(attrib.format());
                attribBindings.offset(attrib.offset());
                attribBindings.position(attribBindings.position() + 1);
            }
            attribBindings.limit(attribBindings.position());
            attribBindings.position(0);
            
            final var vertexInputState = VkPipelineVertexInputStateCreateInfo.calloc().sType$Default();
            vertexInputState.pVertexBindingDescriptions(bufferBindings);
            vertexInputState.pVertexAttributeDescriptions(attribBindings);
            
            final var inputAssemblyState = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default();
            inputAssemblyState.topology(primitiveTopology(pipeline.getVertexFormatMode()));
            
            final var rasterizationState = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default();
            rasterizationState.pNext(0);
            rasterizationState.flags(0);
            rasterizationState.depthClampEnable(false);
            rasterizationState.rasterizerDiscardEnable(false);
            rasterizationState.polygonMode(switch (pipeline.getPolygonMode()) {
                case FILL -> VK_POLYGON_MODE_FILL;
                case WIREFRAME -> VK_POLYGON_MODE_LINE;
            });
            rasterizationState.cullMode(pipeline.isCull() ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE);
            // negative viewport height, this is fine
            rasterizationState.frontFace(VK_FRONT_FACE_CLOCKWISE);
            rasterizationState.depthBiasEnable(pipeline.getDepthBiasConstant() != 0.0f || pipeline.getDepthBiasScaleFactor() != 0.0f);
            rasterizationState.depthBiasConstantFactor(pipeline.getDepthBiasConstant());
            rasterizationState.depthBiasClamp(0);
            rasterizationState.depthBiasSlopeFactor(pipeline.getDepthBiasScaleFactor());
            rasterizationState.lineWidth(1.0f);
            
            final var depthTestEnable = pipeline.getDepthTestFunction() != DepthTestFunction.NO_DEPTH_TEST || pipeline.isWriteDepth();
            
            final var depthStencilState = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default();
            // TODO: stencil support, once its in the Neo3D pipeline
            depthStencilState.depthBoundsTestEnable(false);
            depthStencilState.stencilTestEnable(false);
            depthStencilState.depthTestEnable(depthTestEnable);
            depthStencilState.depthWriteEnable(pipeline.isWriteDepth());
            depthStencilState.depthCompareOp(switch (pipeline.getDepthTestFunction()) {
                // TODO: finish out this enum (depth funcs)
                case NO_DEPTH_TEST -> VK_COMPARE_OP_ALWAYS;
                case EQUAL_DEPTH_TEST -> VK_COMPARE_OP_EQUAL;
                case LEQUAL_DEPTH_TEST -> VK_COMPARE_OP_LESS_OR_EQUAL;
                case LESS_DEPTH_TEST -> VK_COMPARE_OP_LESS;
                case GREATER_DEPTH_TEST -> VK_COMPARE_OP_GREATER;
            });
            
            
            final var blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            final var blendFuncOptional = pipeline.getBlendFunction();
            blendAttachment.blendEnable(blendFuncOptional.isPresent());
            if (blendFuncOptional.isPresent()) {
                final var blendFunc = blendFuncOptional.get();
                blendAttachment.srcColorBlendFactor(vkFactor(blendFunc.sourceColor()));
                blendAttachment.dstColorBlendFactor(vkFactor(blendFunc.destColor()));
                // TODO: add blend equation to Neo3D, and bug Dinnerbone for it
                blendAttachment.colorBlendOp(VK_BLEND_OP_ADD);
                blendAttachment.srcAlphaBlendFactor(vkFactor(blendFunc.sourceAlpha()));
                blendAttachment.dstAlphaBlendFactor(vkFactor(blendFunc.destAlpha()));
                blendAttachment.alphaBlendOp(VK_BLEND_OP_ADD);
            }
            blendAttachment.colorWriteMask((pipeline.isWriteColor() ? (VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT) : 0) | (pipeline.isWriteAlpha() ? VK_COLOR_COMPONENT_A_BIT : 0));
            
            final var blendState = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default();
            blendState.logicOpEnable(pipeline.getColorLogic() != LogicOp.NONE);
            blendState.logicOp(switch (pipeline.getColorLogic()) {
                // TODO: finish out this enum (color logic ops)
                case NONE -> VK_LOGIC_OP_COPY;
                case OR_REVERSE -> VK_LOGIC_OP_OR_REVERSE;
            });
            blendState.pAttachments(blendAttachment);
            
            final var createInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            createInfo.sType$Default();
            createInfo.pNext(renderingCreateInfo);
            createInfo.flags(0);
            createInfo.pStages(shaderSet.stages());
            createInfo.pVertexInputState(vertexInputState);
            createInfo.pInputAssemblyState(inputAssemblyState);
            createInfo.pTessellationState(null);
            createInfo.pViewportState(viewportState);
            createInfo.pRasterizationState(rasterizationState);
            createInfo.pMultisampleState(multiSampleState);
            createInfo.pDepthStencilState(depthStencilState);
            createInfo.pColorBlendState(blendState);
            createInfo.pDynamicState(dynamicState);
            createInfo.layout(shaderSet.pipelineLayout());
            createInfo.renderPass(VK_NULL_HANDLE);
            createInfo.subpass(0);
            createInfo.basePipelineHandle(VK_NULL_HANDLE);
            createInfo.basePipelineIndex(-1);
            
            final var handle = stack.callocLong(1);
            long start = System.nanoTime();
            checkVkCode(vkCreateGraphicsPipelines(device.vkDevice, 0, createInfo, null, handle));
            long end = System.nanoTime();
            long time = end - start;
            CINNABAR_CORE_LOG.debug("{}us taken to build graphics pipeline for {}", time / 1000, pipelineName);
            pipelineHandle = handle.get(0);
        }
        
        setVulkanName(pipeline.getLocation().toString());
    }
    
    @Override
    public void destroy() {
        vkDestroyPipeline(device.vkDevice, pipelineHandle, null);
        shaderSet.destroy();
    }
    
    @Override
    public boolean containsUniform(String uniformName) {
        // TODO: dont use streams for this
        @Nullable
        final var pushConstants = shaderSet.pushConstants();
        return (pushConstants != null && pushConstants.members.stream().anyMatch(uboMember -> uboMember.name.equals(uniformName))) || shaderSet.descriptorSetLayouts.stream().anyMatch(descriptorSetLayout -> descriptorSetLayout.bindings.stream().filter(binding -> binding instanceof UBOBinding).map(binding -> (UBOBinding) binding).anyMatch(uboBinding -> uboBinding.members.stream().anyMatch(uboMember -> uboMember.name.equals(uniformName))));
    }
    
    @Override
    public boolean isValid() {
        return true;
    }
    
    private static int vkFactor(SourceFactor sourceFactor) {
        return switch (sourceFactor) {
            case CONSTANT_ALPHA -> VK_BLEND_FACTOR_CONSTANT_ALPHA;
            case CONSTANT_COLOR -> VK_BLEND_FACTOR_CONSTANT_COLOR;
            case DST_ALPHA -> VK_BLEND_FACTOR_DST_ALPHA;
            case DST_COLOR -> VK_BLEND_FACTOR_DST_COLOR;
            case ONE -> VK_BLEND_FACTOR_ONE;
            case ONE_MINUS_CONSTANT_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            case ONE_MINUS_CONSTANT_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case ONE_MINUS_DST_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case ONE_MINUS_DST_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case ONE_MINUS_SRC_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case ONE_MINUS_SRC_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case SRC_ALPHA -> VK_BLEND_FACTOR_SRC_ALPHA;
            case SRC_ALPHA_SATURATE -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            case SRC_COLOR -> VK_BLEND_FACTOR_SRC_COLOR;
            case ZERO -> VK_BLEND_FACTOR_ZERO;
        };
    }
    
    private static int vkFactor(DestFactor destFactor) {
        return switch (destFactor) {
            case CONSTANT_ALPHA -> VK_BLEND_FACTOR_CONSTANT_ALPHA;
            case CONSTANT_COLOR -> VK_BLEND_FACTOR_CONSTANT_COLOR;
            case DST_ALPHA -> VK_BLEND_FACTOR_DST_ALPHA;
            case DST_COLOR -> VK_BLEND_FACTOR_DST_COLOR;
            case ONE -> VK_BLEND_FACTOR_ONE;
            case ONE_MINUS_CONSTANT_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            case ONE_MINUS_CONSTANT_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case ONE_MINUS_DST_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case ONE_MINUS_DST_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case ONE_MINUS_SRC_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case ONE_MINUS_SRC_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case SRC_ALPHA -> VK_BLEND_FACTOR_SRC_ALPHA;
            case SRC_COLOR -> VK_BLEND_FACTOR_SRC_COLOR;
            case ZERO -> VK_BLEND_FACTOR_ZERO;
        };
    }
    
    private static int primitiveTopology(VertexFormat.Mode mode) {
        return switch (mode) {
            case LINES -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case LINE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case DEBUG_LINES -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case DEBUG_LINE_STRIP -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            case TRIANGLES -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case TRIANGLE_FAN -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
            case QUADS -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        };
    }
}
