package graphics.cinnabar.core.b3d.pipeline;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.LogicOp;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import graphics.cinnabar.api.b3dext.pipeline.ExtRenderPipeline;
import graphics.cinnabar.api.b3dext.vertex.VertexInputBuffer;
import graphics.cinnabar.api.cvk.pipeline.CVKCompiledRenderPipeline;
import graphics.cinnabar.api.util.Pair;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.util.MagicNumbers;
import graphics.cinnabar.core.vk.VulkanObject;
import graphics.cinnabar.core.vk.shaders.ShaderProcessing;
import graphics.cinnabar.core.vk.shaders.ShaderSet;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
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

public class CinnabarPipeline implements CVKCompiledRenderPipeline, VulkanObject {
    
    private final CinnabarDevice device;
    
    public final RenderPipeline info;
    
    public final ShaderSet shaderSet;
    public final long pipelineHandle;
    
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
    public CinnabarPipeline(CinnabarDevice device, BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider, ExtRenderPipeline pipeline) {
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
            processedShaders = ShaderProcessing.processShaders(glVertexGLSL, glFragmentGLSL, vertexShaderName, fragmentShaderName, List.of(), List.of());
            long end = System.nanoTime();
            long time = end - start;
            CINNABAR_CORE_LOG.debug("{}us taken to process shaders for {}", time / (1_000), pipelineName);
        }
        CINNABAR_CORE_LOG.debug("Reprocessed shaders ({}, {}) for pipeline {}", vertexShaderName, fragmentShaderName, pipelineName);
        final var vkVertexGLSL = processedShaders.first();
        final var vkFragmentGLSL = processedShaders.second();
        
        {
            long start = System.nanoTime();
            shaderSet = ShaderSet.create(device, pipeline, pipelineName, vertexShaderName, fragmentShaderName, vkVertexGLSL, vkFragmentGLSL);
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
            
            final var attribTypes = shaderSet.attribTypes();
            final var inputBuffers = pipeline.getVertexInputBuffers();
            final var bufferBindings = VkVertexInputBindingDescription.calloc(inputBuffers.size(), stack);
            final var attribBindings = VkVertexInputAttributeDescription.calloc(inputBuffers.stream().mapToInt(binding -> binding.bufferFormat().getElements().size()).sum(), stack);
            for (VertexInputBuffer vertexInputBuffer : inputBuffers) {
                final var bufferFormat = vertexInputBuffer.bufferFormat();
                bufferBindings.binding(vertexInputBuffer.bindingIndex());
                bufferBindings.stride(bufferFormat.getVertexSize());
                bufferBindings.inputRate(vertexInputBuffer.inputRate().ordinal());
                
                for (VertexFormatElement element : vertexInputBuffer.bufferFormat().getElements()) {
                    final var name = bufferFormat.getElementName(element);
                    final var location = shaderSet.attribLocation(name);
                    if (location == -1) {
                        // skip inputs not used by the shader
                        continue;
                    }
                    attribBindings.location(location);
                    attribBindings.binding(vertexInputBuffer.bindingIndex());
                    attribBindings.format(mapTypeAndCountToVkFormat(element.type(), element.count(), element.usage(), isFloatInputType(attribTypes.get(name))));
                    attribBindings.offset(bufferFormat.getOffset(element));
                    attribBindings.position(attribBindings.position() + 1);
                }
            }
            bufferBindings.position(0);
            attribBindings.flip();
            
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
    
    @SuppressWarnings("DuplicateBranchesInSwitch")
    private static boolean isFloatInputType(@Nullable String input) {
        if (input == null) {
            // if null for a specific attrib set, then it can be treated as int, it wont actually be read by the shader
            return false;
        }
        return switch (input) {
            case "uint", "uvec2", "uvec3", "uvec4" -> false;
            case "int", "ivec2", "ivec3", "ivec4" -> false;
            case "float", "vec2", "vec3", "vec4" -> true;
            default -> throw new IllegalStateException("Unexpected value: " + input);
        };
    }
    
    // TODO: INT vs SCALED for float input types with integers in the buffers    
    private static int mapTypeAndCountToVkFormat(VertexFormatElement.Type type, int count, VertexFormatElement.Usage usage, boolean floatInput) {
        final boolean normalized = switch (usage) {
            case COLOR, NORMAL -> true;
            default -> false;
        };
        return switch (type) {
            case FLOAT -> switch (count) {
                case 1 -> VK_FORMAT_R32_SFLOAT;
                case 2 -> VK_FORMAT_R32G32_SFLOAT;
                case 3 -> VK_FORMAT_R32G32B32_SFLOAT;
                case 4 -> VK_FORMAT_R32G32B32A32_SFLOAT;
                default -> throw new IllegalStateException("Unexpected value: " + count);
            };
            case UBYTE -> switch (count) {
                case 1 -> normalized ? VK_FORMAT_R8_UNORM : VK_FORMAT_R8_UINT;
                case 2 -> normalized ? VK_FORMAT_R8G8_UNORM : VK_FORMAT_R8G8_UINT;
                case 3 -> normalized ? VK_FORMAT_R8G8B8_UNORM : VK_FORMAT_R8G8B8_UINT;
                case 4 -> normalized ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_R8G8B8A8_UINT;
                default -> throw new IllegalStateException("Unexpected value: " + count);
            };
            case BYTE -> switch (count) {
                case 1 -> normalized ? VK_FORMAT_R8_SNORM : (floatInput ? VK_FORMAT_R8_SSCALED : VK_FORMAT_R8_SINT);
                case 2 -> normalized ? VK_FORMAT_R8G8_SNORM : (floatInput ? VK_FORMAT_R8G8_SSCALED : VK_FORMAT_R8G8_SINT);
                case 3 -> normalized ? VK_FORMAT_R8G8B8_SNORM : (floatInput ? VK_FORMAT_R8G8B8_SSCALED : VK_FORMAT_R8G8B8_SINT);
                case 4 -> normalized ? VK_FORMAT_R8G8B8A8_SNORM : (floatInput ? VK_FORMAT_R8G8B8A8_SSCALED : VK_FORMAT_R8G8B8A8_SINT);
                default -> throw new IllegalStateException("Unexpected value: " + count);
            };
            case USHORT -> switch (count) {
                case 1 -> normalized ? VK_FORMAT_R16_UNORM : VK_FORMAT_R16_UINT;
                case 2 -> normalized ? VK_FORMAT_R16G16_UNORM : VK_FORMAT_R16G16_UINT;
                case 3 -> normalized ? VK_FORMAT_R16G16B16_UNORM : VK_FORMAT_R16G16B16_UINT;
                case 4 -> normalized ? VK_FORMAT_R16G16B16A16_UNORM : VK_FORMAT_R16G16B16A16_UINT;
                default -> throw new IllegalStateException("Unexpected value: " + count);
            };
            case SHORT -> switch (count) {
                case 1 -> normalized ? VK_FORMAT_R16_SNORM : (floatInput ? VK_FORMAT_R16_SSCALED : VK_FORMAT_R16_SINT);
                case 2 -> normalized ? VK_FORMAT_R16G16_SNORM : (floatInput ? VK_FORMAT_R16G16_SSCALED : VK_FORMAT_R16G16_SINT);
                case 3 -> normalized ? VK_FORMAT_R16G16B16_SNORM : (floatInput ? VK_FORMAT_R16G16B16_SSCALED : VK_FORMAT_R16G16B16_SINT);
                case 4 -> normalized ? VK_FORMAT_R16G16B16A16_SNORM : (floatInput ? VK_FORMAT_R16G16B16A16_SSCALED : VK_FORMAT_R16G16B16A16_SINT);
                default -> throw new IllegalStateException("Unexpected value: " + count);
            };
            case UINT -> switch (count) {
                case 1 -> VK_FORMAT_R32_UINT;
                case 2 -> VK_FORMAT_R32G32_UINT;
                case 3 -> VK_FORMAT_R32G32B32_UINT;
                case 4 -> VK_FORMAT_R32G32B32A32_UINT;
                default -> throw new IllegalStateException("Unexpected value: " + count);
            };
            case INT -> switch (count) {
                case 1 -> VK_FORMAT_R32_SINT;
                case 2 -> VK_FORMAT_R32G32_SINT;
                case 3 -> VK_FORMAT_R32G32B32_SINT;
                case 4 -> VK_FORMAT_R32G32B32A32_SINT;
                default -> throw new IllegalStateException("Unexpected value: " + count);
            };
        };
    }
}
