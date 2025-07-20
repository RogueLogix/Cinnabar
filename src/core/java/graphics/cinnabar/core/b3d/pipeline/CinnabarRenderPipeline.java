package graphics.cinnabar.core.b3d.pipeline;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.LogicOp;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import graphics.cinnabar.api.b3dext.pipeline.ExtRenderPipeline;
import graphics.cinnabar.api.b3dext.vertex.VertexInputBuffer;
import graphics.cinnabar.api.cvk.pipeline.CVKCompiledRenderPipeline;
import graphics.cinnabar.api.threading.WorkFuture;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.b3d.texture.CinnabarGpuTexture;
import graphics.cinnabar.core.vk.VkConst;
import graphics.cinnabar.core.vk.descriptors.*;
import graphics.cinnabar.core.vk.shaders.ShaderModule;
import graphics.cinnabar.core.vk.shaders.ThreadGlobals;
import graphics.cinnabar.lib.threading.WorkQueue;
import it.unimi.dsi.fastutil.ints.Int2ReferenceArrayMap;
import it.unimi.dsi.fastutil.ints.IntIntImmutablePair;
import it.unimi.dsi.fastutil.ints.IntReferenceImmutablePair;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongObjectImmutablePair;
import it.unimi.dsi.fastutil.longs.LongReferenceImmutablePair;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.SpvcReflectedResource;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST;
import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static graphics.cinnabar.core.CinnabarCore.CINNABAR_CORE_LOG;
import static graphics.cinnabar.core.CinnabarCore.device;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spvc.*;
import static org.lwjgl.vulkan.VK12.*;

public class CinnabarRenderPipeline implements CVKCompiledRenderPipeline, Destroyable {
    
    public final RenderPipeline info;
    
    private record PipelineBase(CinnabarDevice device, ShaderModule vertexShader, ShaderModule fragmentShader, DescriptorSetLayout descriptorSetLayout, long pipelineLayout, VkGraphicsPipelineCreateInfo.Buffer createInfo, MemoryStack stack) implements Destroyable {
        
        @Override
        public void destroy() {
            vkDestroyPipelineLayout(device.vkDevice, pipelineLayout, null);
            descriptorSetLayout.destroy();
            fragmentShader.destroy();
            vertexShader.destroy();
        }
    }
    
    
    private record BuiltPipeline(PipelineBase base, long pipelineHandle, long renderPass) implements Destroyable {
        @Override
        public void destroy() {
            vkDestroyPipeline(base.device.vkDevice, pipelineHandle, null);
        }
    }
    
    private final Future<PipelineBase> pipelineBase;
    
    private final Long2ReferenceMap<BuiltPipeline> builtPipelines = new Long2ReferenceOpenHashMap<>();
    
    record ShaderSourceCacheKey(ResourceLocation location, ShaderType type) {
    }
    
    private static final Map<ShaderSourceCacheKey, String> shaderSourceCache = new Object2ReferenceOpenHashMap<>();
    
    public static void clearSourceCache() {
        shaderSourceCache.clear();
    }
    
    public CinnabarRenderPipeline(CinnabarDevice device, ExtRenderPipeline pipeline, BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider) {
        this.info = pipeline;
        
        final var vertexSource = shaderSourceCache.computeIfAbsent(new ShaderSourceCacheKey(pipeline.getVertexShader(), ShaderType.VERTEX), key -> shaderSourceProvider.apply(key.location, key.type));
        final var fragmentSource = shaderSourceCache.computeIfAbsent(new ShaderSourceCacheKey(pipeline.getFragmentShader(), ShaderType.FRAGMENT), key -> shaderSourceProvider.apply(key.location, key.type));
        pipelineBase = new WorkFuture<>(index -> {
            CINNABAR_CORE_LOG.debug("Building pipeline base: {}", pipeline.getLocation());
            final var result = buildPipelineBase(device, pipeline, vertexSource, fragmentSource);
            CINNABAR_CORE_LOG.debug("Built pipeline base: {}", pipeline.getLocation());
            return result;
        }).enqueue(WorkQueue.BACKGROUND_THREADS);
    }
    
    @Override
    public void destroy() {
        if (isValid()) {
            pipelineBase.resultNow().destroy();
        }
    }
    
    @Override
    public boolean isValid() {
        if (pipelineBase.state() == Future.State.RUNNING) {
            try {
                pipelineBase.get();
            } catch (InterruptedException | ExecutionException e) {
                CINNABAR_CORE_LOG.error(e.getCause().toString());
            }
        }
        return pipelineBase.state() == Future.State.SUCCESS;
    }
    
    public DescriptorSetLayout descriptorSetLayout() {
        return pipelineBase.resultNow().descriptorSetLayout;
    }
    
    public long pipelineLayout() {
        return pipelineBase.resultNow().pipelineLayout;
    }
    
    public long pipelineHandle(long renderPass) {
        final var pipeline = builtPipelines.get(renderPass);
        if (pipeline != null) {
            return pipeline.pipelineHandle();
        }
        final var newPipeline = buildPipeline(pipelineBase.resultNow(), renderPass);
        builtPipelines.put(renderPass, newPipeline);
        return newPipeline.pipelineHandle;
    }
    
    private static PipelineBase buildPipelineBase(CinnabarDevice device, ExtRenderPipeline pipeline, String vertexSource, String fragmentSource) {
        
        // TODO: remove this when the shader gets fixed
        if ("minecraft:core/entity".equals(pipeline.getVertexShader().toString())) {
            vertexSource = vertexSource.replace("overlayColor = texelFetch(Sampler1, UV1, 0);", """
                        #ifndef NO_OVERLAY
                        overlayColor = texelFetch(Sampler1, UV1, 0);
                        #endif
                    """);
        }
        
        final var glVertexGLSL = GlslPreprocessor.injectDefines(vertexSource, pipeline.getShaderDefines());
        final var glFragmentGLSL = GlslPreprocessor.injectDefines(fragmentSource, pipeline.getShaderDefines());
        
        final var alignment = device().getUniformOffsetAlignment();
        final var cinnabarStandardDefines = """
                #define CINNABAR_VK
                #define CINNABAR_UBO_ALIGNMENT %s
                """.formatted(alignment);
        final var versionRemovedVertexSource = glVertexGLSL.replace("#version", cinnabarStandardDefines + "\n#define CINNABAR_VERTEX_SHADER //").replace("gl_VertexID", "gl_VertexIndex").replace("gl_InstanceID", "gl_InstanceIndex").replace("samplerBuffer", "textureBuffer");
        final var versionRemovedFragmentSource = glFragmentGLSL.replace("#version", cinnabarStandardDefines + "\n#define CINNABAR_FRAGMENT_SHADER //").replace("samplerBuffer", "textureBuffer");
        
        final var pipelineName = pipeline.getLocation().toString();
        final var vertexShaderName = pipeline.getVertexShader().toString();
        final var fragmentShaderName = pipeline.getFragmentShader().toString();
        
        final var vertexSPV = compileVulkanGLSL(vertexShaderName, versionRemovedVertexSource, true);
        final var fragmentSPV = compileVulkanGLSL(fragmentShaderName, versionRemovedFragmentSource, false);
        
        final var attribInfo = new Object2ObjectArrayMap<String, @Nullable IntReferenceImmutablePair<IntIntImmutablePair>>();
        final var descriptorBindings = new ReferenceArrayList<DescriptorSetBinding>();
        
        final var uniformDescriptions = pipeline.getUniforms().stream().collect(Collectors.toMap(RenderPipeline.UniformDescription::name, Function.identity()));
        
        long spvcContext = 0;
        try (final var stack = MemoryStack.stackPush()) {
            final var intReturn = stack.ints(0);
            final var ptrReturn = stack.pointers(0);
            spvc_context_create(ptrReturn);
            spvcContext = ptrReturn.get(0);
            
            final var vertexSpvIntBuffer = vertexSPV.right().asIntBuffer();
            spvc_context_parse_spirv(spvcContext, vertexSpvIntBuffer, vertexSpvIntBuffer.remaining(), ptrReturn);
            final var parsedVtxIR = ptrReturn.get(0);
            final var fragmentSpvIntBuffer = fragmentSPV.right().asIntBuffer();
            spvc_context_parse_spirv(spvcContext, fragmentSpvIntBuffer, fragmentSpvIntBuffer.remaining(), ptrReturn);
            final var parsedFraIR = ptrReturn.get(0);
            
            spvc_context_create_compiler(spvcContext, SPVC_BACKEND_NONE, parsedVtxIR, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, ptrReturn);
            final var spvcVtxCompiler = ptrReturn.get(0);
            spvc_context_create_compiler(spvcContext, SPVC_BACKEND_NONE, parsedFraIR, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, ptrReturn);
            final var spvcFraCompiler = ptrReturn.get(0);
            
            spvc_compiler_create_shader_resources(spvcVtxCompiler, ptrReturn);
            final var vtxResources = ptrReturn.get(0);
            spvc_compiler_create_shader_resources(spvcFraCompiler, ptrReturn);
            final var fragResources = ptrReturn.get(0);
            
            final var resourcePtr = stack.pointers(0);
            
            spvc_resources_get_resource_list_for_type(vtxResources, SPVC_RESOURCE_TYPE_STAGE_INPUT, resourcePtr, ptrReturn);
            final var vtxAttribs = SpvcReflectedResource.create(resourcePtr.get(0), (int) ptrReturn.get(0));
            {
                for (SpvcReflectedResource vtxAttrib : vtxAttribs) {
                    final var attribName = vtxAttrib.nameString();
                    final var attribLocation = spvc_compiler_get_decoration(spvcVtxCompiler, vtxAttrib.id(), Spv.SpvDecorationLocation);
                    final var type = spvc_compiler_get_type_handle(spvcVtxCompiler, vtxAttrib.type_id());
                    final var baseType = spvc_type_get_basetype(type);
                    final var vectorWidth = spvc_type_get_vector_size(type);
                    attribInfo.put(attribName, new IntReferenceImmutablePair<>(attribLocation, new IntIntImmutablePair(baseType, vectorWidth)));
                }
            }
            
            spvc_resources_get_resource_list_for_type(vtxResources, SPVC_RESOURCE_TYPE_STAGE_OUTPUT, resourcePtr, ptrReturn);
            final var vtxOutputs = SpvcReflectedResource.create(resourcePtr.get(0), (int) ptrReturn.get(0));
            spvc_resources_get_resource_list_for_type(fragResources, SPVC_RESOURCE_TYPE_STAGE_INPUT, resourcePtr, ptrReturn);
            final var fraInputs = SpvcReflectedResource.create(resourcePtr.get(0), (int) ptrReturn.get(0));
            
            {
                final var vtxOutputsByName = new Object2ObjectArrayMap<String, @Nullable IntReferenceImmutablePair<IntIntImmutablePair>>();
                final var vtxOutputsByLocation = new Int2ReferenceArrayMap<@Nullable IntIntImmutablePair>();
                for (SpvcReflectedResource vtxOutput : vtxOutputs) {
                    final var outputName = vtxOutput.nameString();
                    final var outputLocation = spvc_compiler_get_decoration(spvcVtxCompiler, vtxOutput.id(), Spv.SpvDecorationLocation);
                    final var type = spvc_compiler_get_type_handle(spvcVtxCompiler, vtxOutput.type_id());
                    final var baseType = spvc_type_get_basetype(type);
                    final var vectorWidth = spvc_type_get_vector_size(type);
                    vtxOutputsByName.put(outputName, new IntReferenceImmutablePair<>(outputLocation, new IntIntImmutablePair(baseType, vectorWidth)));
                    vtxOutputsByLocation.put(outputLocation, new IntIntImmutablePair(baseType, vectorWidth));
                }
                
                for (SpvcReflectedResource fraInput : fraInputs) {
                    final var inputName = fraInput.nameString();
                    final var type = spvc_compiler_get_type_handle(spvcFraCompiler, fraInput.type_id());
                    final var baseType = spvc_type_get_basetype(type);
                    final var vectorWidth = spvc_type_get_vector_size(type);
                    
                    var inputLocation = spvc_compiler_get_decoration(spvcFraCompiler, fraInput.id(), Spv.SpvDecorationLocation);
                    @Nullable
                    final var vtxOutput = vtxOutputsByName.get(inputName);
                    if (vtxOutput == null) {
                        // no name match, check for a location match
                        @Nullable
                        final var outputType = vtxOutputsByLocation.get(inputLocation);
                        if (outputType == null) {
                            throw new IllegalArgumentException(String.format("Unable to find output for fragment input %s in pipeline %s", inputName, pipelineName));
                        }
                        if (outputType.leftInt() != baseType) {
                            throw new IllegalArgumentException(String.format("Fragment input %s does not match vertex output type in pipeline %s", inputName, pipelineName));
                        }
                    } else {
                        if (vtxOutput.right().leftInt() != baseType) {
                            throw new IllegalArgumentException(String.format("Fragment input %s does not match vertex output type in pipeline %s", inputName, pipelineName));
                        }
                        if (vtxOutput.leftInt() != inputLocation) {
                            // locations don't match, rebind the fragment input location
                            if (!spvc_compiler_get_binary_offset_for_decoration(spvcFraCompiler, fraInput.id(), Spv.SpvDecorationLocation, intReturn)) {
                                throw new IllegalStateException();
                            }
                            final var decorationLocation = intReturn.get(0);
                            if (fragmentSpvIntBuffer.get(decorationLocation) != inputLocation) {
                                throw new IllegalStateException();
                            }
                            inputLocation = vtxOutput.leftInt();
                            fragmentSpvIntBuffer.put(decorationLocation, inputLocation);
                        }
                    }
                }
            }
            
            spvc_resources_get_resource_list_for_type(fragResources, SPVC_BUILTIN_RESOURCE_TYPE_STAGE_OUTPUT, resourcePtr, ptrReturn);
            final var fraAttachments = SpvcReflectedResource.create(resourcePtr.get(0), (int) ptrReturn.get(0));
            
            final var resourceTypes = new int[]{SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, SPVC_RESOURCE_TYPE_STORAGE_BUFFER, SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE};
            for (int resourceType : resourceTypes) {
                spvc_resources_get_resource_list_for_type(vtxResources, resourceType, resourcePtr, ptrReturn);
                final var vtxResourceList = SpvcReflectedResource.create(resourcePtr.get(0), (int) ptrReturn.get(0));
                spvc_resources_get_resource_list_for_type(fragResources, resourceType, resourcePtr, ptrReturn);
                final var fragResourceList = SpvcReflectedResource.create(resourcePtr.get(0), (int) ptrReturn.get(0));
                
                final var resourceNames = new ObjectArraySet<String>();
                final var reflectedResourceByName = new Object2ObjectArrayMap<String, LongReferenceImmutablePair<SpvcReflectedResource>>();
                final var vtxDecorationLocations = new Object2IntArrayMap<String>();
                final var fraDecorationLocations = new Object2IntArrayMap<String>();
                for (SpvcReflectedResource resource : vtxResourceList) {
                    final var resourceName = resource.nameString();
                    resourceNames.add(resourceName);
                    spvc_compiler_get_binary_offset_for_decoration(spvcVtxCompiler, resource.id(), Spv.SpvDecorationBinding, intReturn);
                    final var currentResourceBinding = spvc_compiler_get_decoration(spvcVtxCompiler, resource.id(), Spv.SpvDecorationBinding);
                    if (vertexSpvIntBuffer.get(intReturn.get(0)) != currentResourceBinding) {
                        throw new IllegalStateException();
                    }
                    vtxDecorationLocations.put(resourceName, intReturn.get(0));
                    reflectedResourceByName.put(resourceName, new LongReferenceImmutablePair<>(spvcVtxCompiler, resource));
                }
                for (SpvcReflectedResource resource : fragResourceList) {
                    final var resourceName = resource.nameString();
                    resourceNames.add(resourceName);
                    spvc_compiler_get_binary_offset_for_decoration(spvcFraCompiler, resource.id(), Spv.SpvDecorationBinding, intReturn);
                    final var currentResourceBinding = spvc_compiler_get_decoration(spvcFraCompiler, resource.id(), Spv.SpvDecorationBinding);
                    if (fragmentSpvIntBuffer.get(intReturn.get(0)) != currentResourceBinding) {
                        throw new IllegalStateException();
                    }
                    fraDecorationLocations.put(resourceName, intReturn.get(0));
                    reflectedResourceByName.put(resourceName, new LongReferenceImmutablePair<>(spvcFraCompiler, resource));
                }
                
                for (String resourceName : resourceNames) {
                    final var bindingLocation = descriptorBindings.size();
                    final var resource = reflectedResourceByName.get(resourceName);
                    if (vtxDecorationLocations.containsKey(resourceName)) {
                        vertexSpvIntBuffer.put(vtxDecorationLocations.getInt(resourceName), bindingLocation);
                    }
                    if (fraDecorationLocations.containsKey(resourceName)) {
                        fragmentSpvIntBuffer.put(fraDecorationLocations.getInt(resourceName), bindingLocation);
                    }
                    descriptorBindings.add(
                            switch (resourceType) {
                                case SPVC_RESOURCE_TYPE_UNIFORM_BUFFER -> {
                                    final var type = spvc_compiler_get_type_handle(resource.leftLong(), resource.right().type_id());
                                    spvc_compiler_get_declared_struct_size(resource.leftLong(), type, ptrReturn);
                                    // TODO: fix the pipelines, for now, these are always bound by RenderSystem.bindDefaultUniforms 
                                    if (!"Projection".equals(resourceName) && !"Fog".equals(resourceName) && !"Globals".equals(resourceName) && !"Lighting".equals(resourceName)
                                                && pipeline.getUniforms().stream().noneMatch(uniformDescription -> uniformDescription.type() == UniformType.UNIFORM_BUFFER && uniformDescription.name().equals(resourceName))) {
                                        throw new IllegalArgumentException(String.format("UBO (%s) found in shader without matching definition in pipeline %s", resourceName, pipelineName));
                                    }
                                    yield new UBOBinding(resourceName, bindingLocation, Math.toIntExact(ptrReturn.get(0)));
                                }
                                case SPVC_RESOURCE_TYPE_STORAGE_BUFFER -> {
                                    final var type = spvc_compiler_get_type_handle(resource.leftLong(), resource.right().type_id());
                                    spvc_compiler_type_struct_member_array_stride(resource.leftLong(), type, 0, intReturn);
                                    if (pipeline.getUniforms().stream().noneMatch(uniformDescription -> uniformDescription.type() == UniformType.UNIFORM_BUFFER && uniformDescription.name().equals(resourceName))) {
                                        throw new IllegalArgumentException(String.format("SSBO (%s) found in shader without matching definition in pipeline %s", resourceName, pipelineName));
                                    }
                                    yield new SSBOBinding(resourceName, bindingLocation, intReturn.get(0));
                                }
                                case SPVC_RESOURCE_TYPE_SEPARATE_IMAGE -> {
                                    if (pipeline.getUniforms().stream().noneMatch(uniformDescription -> uniformDescription.type() == UniformType.TEXEL_BUFFER && uniformDescription.name().equals(resourceName))) {
                                        throw new IllegalArgumentException(String.format("UTB (%s) found in shader without matching definition in pipeline %s", resourceName, pipelineName));
                                    }
                                    yield new TexelBufferBinding(resourceName, bindingLocation, CinnabarGpuTexture.toVk(Objects.requireNonNull(uniformDescriptions.get(resourceName).textureFormat())));
                                }
                                case SPVC_RESOURCE_TYPE_SAMPLED_IMAGE -> {
                                    if (pipeline.getSamplers().stream().noneMatch(samplerName -> samplerName.equals(resourceName))) {
                                        throw new IllegalArgumentException(String.format("Sampler (%s) found in shader without matching definition in pipeline %s", resourceName, pipelineName));
                                    }
                                    yield new SamplerBinding(resourceName, bindingLocation);
                                }
                                default -> throw new IllegalStateException("Unexpected value: " + resourceType);
                            }
                    );
                }
            }
        } finally {
            if (spvcContext != 0) {
                spvc_context_destroy(spvcContext);
            }
        }
        
        final ShaderModule vertexShader;
        final ShaderModule fragmentShader;
        try (final var stack = MemoryStack.stackPush()) {
            final var createInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default();
            createInfo.pCode(vertexSPV.second());
            final var handleReturn = stack.longs(0);
            vkCreateShaderModule(device.vkDevice, createInfo, null, handleReturn);
            vertexShader = new ShaderModule(device, vertexShaderName, handleReturn.get(0));
            createInfo.pCode(fragmentSPV.second());
            vkCreateShaderModule(device.vkDevice, createInfo, null, handleReturn);
            fragmentShader = new ShaderModule(device, fragmentShaderName, handleReturn.get(0));
        }
        
        shaderc_result_release(vertexSPV.firstLong());
        shaderc_result_release(fragmentSPV.firstLong());
        
        final DescriptorSetLayout descriptorSetLayout;
        try {
            descriptorSetLayout = new DescriptorSetLayout(device, descriptorBindings);
        } catch (RuntimeException e) {
            vertexShader.destroy();
            fragmentShader.destroy();
            throw e;
        }
        
        final long pipelineLayout;
        try (final var stack = MemoryStack.stackPush()) {
            final var pipelineLayoutCreateInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            final var descriptorSetLayoutHandles = stack.callocLong(1);
            descriptorSetLayoutHandles.put(0, descriptorSetLayout.handle());
            pipelineLayoutCreateInfo.pSetLayouts(descriptorSetLayoutHandles);
            
            final var longPtr = stack.callocLong(1);
            checkVkCode(vkCreatePipelineLayout(device.vkDevice, pipelineLayoutCreateInfo, null, longPtr));
            pipelineLayout = longPtr.get(0);
        } catch (RuntimeException e) {
            vertexShader.destroy();
            fragmentShader.destroy();
            descriptorSetLayout.destroy();
            throw e;
        }
        
        final var stack = MemoryStack.create();
        
        final var mainUTF8 = stack.UTF8("main");
        final var shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        shaderStages.position(0);
        shaderStages.sType$Default();
        shaderStages.stage(VK_SHADER_STAGE_VERTEX_BIT);
        shaderStages.module(vertexShader.handle());
        shaderStages.pName(mainUTF8);
        shaderStages.position(1);
        shaderStages.sType$Default();
        shaderStages.stage(VK_SHADER_STAGE_FRAGMENT_BIT);
        shaderStages.module(fragmentShader.handle());
        shaderStages.pName(mainUTF8);
        shaderStages.position(0);
        
        final var viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default();
        viewportState.pNext(0);
        viewportState.flags(0);
        viewportState.viewportCount(1);
        viewportState.pViewports(null);
        viewportState.scissorCount(1);
        viewportState.pScissors(null);
        
        final var dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default();
        final var dynamicStates = stack.callocInt(2);
        dynamicStates.put(0, VK_DYNAMIC_STATE_VIEWPORT);
        dynamicStates.put(1, VK_DYNAMIC_STATE_SCISSOR);
        dynamicState.pDynamicStates(dynamicStates);
        
        final var multiSampleState = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default();
        multiSampleState.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
        multiSampleState.sampleShadingEnable(false);
        
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
                @Nullable
                final var info = attribInfo.get(name);
                if (info == null) {
                    continue;
                }
                
                attribBindings.location(info.leftInt());
                attribBindings.binding(vertexInputBuffer.bindingIndex());
                final var typeInfo = info.right();
                attribBindings.format(mapTypeAndCountToVkFormat(element.type(), element.count(), element.usage(), spvcBaseTypeToVertexFormatElementType(typeInfo.leftInt())));
                attribBindings.offset(bufferFormat.getOffset(element));
                attribBindings.position(attribBindings.position() + 1);
            }
        }
        bufferBindings.position(0);
        attribBindings.flip();
        
        final var vertexInputState = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
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
        
        final var depthTestEnable = pipeline.getDepthTestFunction() != NO_DEPTH_TEST || pipeline.isWriteDepth();
        
        final var depthStencilState = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default();
        depthStencilState.depthBoundsTestEnable(false);
        if(pipeline.getStencilTest().isPresent()){
            final var stencilTest = pipeline.getStencilTest().get();
            depthStencilState.stencilTestEnable(true);
            {
                final var frontOp = depthStencilState.front();
                final var frontTest = stencilTest.front();
                frontOp.failOp(VkConst.toVk(frontTest.fail()));
                frontOp.depthFailOp(VkConst.toVk(frontTest.depthFail()));
                frontOp.passOp(VkConst.toVk(frontTest.pass()));
                frontOp.compareOp(VkConst.toVk(frontTest.compare()));
                frontOp.compareMask(stencilTest.readMask());
                frontOp.writeMask(stencilTest.writeMask());
                frontOp.reference(stencilTest.referenceValue());
            }
            {
                final var backOp = depthStencilState.back();
                final var backTest = stencilTest.back();
                backOp.failOp(VkConst.toVk(backTest.fail()));
                backOp.depthFailOp(VkConst.toVk(backTest.depthFail()));
                backOp.passOp(VkConst.toVk(backTest.pass()));
                backOp.compareOp(VkConst.toVk(backTest.compare()));
                backOp.compareMask(stencilTest.readMask());
                backOp.writeMask(stencilTest.writeMask());
                backOp.reference(stencilTest.referenceValue());
            }
        } else {
            depthStencilState.stencilTestEnable(false);
        }
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
        if (pipeline.getColorLogic() != LogicOp.NONE) {
            throw new IllegalArgumentException();
        }
        blendState.pAttachments(blendAttachment);
        
        final var createInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
        createInfo.sType$Default();
        createInfo.flags(0);
        createInfo.pStages(shaderStages);
        createInfo.pVertexInputState(vertexInputState);
        createInfo.pInputAssemblyState(inputAssemblyState);
        createInfo.pTessellationState(null);
        createInfo.pViewportState(viewportState);
        createInfo.pRasterizationState(rasterizationState);
        createInfo.pMultisampleState(multiSampleState);
        createInfo.pDepthStencilState(depthStencilState);
        createInfo.pColorBlendState(blendState);
        createInfo.pDynamicState(dynamicState);
        createInfo.layout(pipelineLayout);
        createInfo.renderPass(VK_NULL_HANDLE);
        createInfo.subpass(0);
        createInfo.basePipelineHandle(VK_NULL_HANDLE);
        createInfo.basePipelineIndex(-1);
        
        return new PipelineBase(device, vertexShader, fragmentShader, descriptorSetLayout, pipelineLayout, createInfo, stack);
    }
    
    private static BuiltPipeline buildPipeline(PipelineBase pipelineBase, long renderPass) {
        try (final var stack = MemoryStack.stackPush()) {
            
            pipelineBase.createInfo.renderPass(renderPass);
            final var handle = stack.callocLong(1);
            checkVkCode(vkCreateGraphicsPipelines(pipelineBase.device.vkDevice, 0, pipelineBase.createInfo, null, handle));
            return new BuiltPipeline(pipelineBase, handle.get(0), renderPass);
        }
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
    
    static LongObjectImmutablePair<ByteBuffer> compileVulkanGLSL(String shaderName, String vulkanGLSL, boolean vertexShader) {
        final var globals = ThreadGlobals.get();
        final var compileResult = shaderc_compile_into_spv(globals.ShaderCCompiler, vulkanGLSL, vertexShader ? shaderc_vertex_shader : shaderc_fragment_shader, shaderName, "main", globals.ShaderCCompilerVKOptions);
        final var compileStatus = shaderc_result_get_compilation_status(compileResult);
        if (compileStatus != shaderc_compilation_status_success) {
            @Nullable
            final var errorMessage = shaderc_result_get_error_message(compileResult);
            shaderc_result_release(compileResult);
            throw new RuntimeException(errorMessage);
        }
        final var spvCode = Objects.requireNonNull(shaderc_result_get_bytes(compileResult));
        return new LongObjectImmutablePair<>(compileResult, spvCode);
    }
    
    private static VertexFormatElement.Type spvcBaseTypeToVertexFormatElementType(int spvcBaseType) {
        return switch (spvcBaseType) {
            case SPVC_BASETYPE_FP32 -> VertexFormatElement.Type.FLOAT;
            case SPVC_BASETYPE_UINT8 -> VertexFormatElement.Type.UBYTE;
            case SPVC_BASETYPE_INT8 -> VertexFormatElement.Type.BYTE;
            case SPVC_BASETYPE_UINT16 -> VertexFormatElement.Type.USHORT;
            case SPVC_BASETYPE_INT16 -> VertexFormatElement.Type.SHORT;
            case SPVC_BASETYPE_UINT32 -> VertexFormatElement.Type.UINT;
            case SPVC_BASETYPE_INT32 -> VertexFormatElement.Type.INT;
            default -> throw new IllegalStateException("Unexpected value: " + spvcBaseType);
        };
    }
    
    private static int mapTypeAndCountToVkFormat(VertexFormatElement.Type bufferType, int count, VertexFormatElement.Usage usage, VertexFormatElement.Type shaderType) {
        final boolean normalized = switch (usage) {
            case COLOR, NORMAL -> true;
            default -> false;
        };
        return switch (bufferType) {
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
                case 1 -> normalized ? VK_FORMAT_R8_SNORM : (shaderType == VertexFormatElement.Type.FLOAT ? VK_FORMAT_R8_SSCALED : VK_FORMAT_R8_SINT);
                case 2 -> normalized ? VK_FORMAT_R8G8_SNORM : (shaderType == VertexFormatElement.Type.FLOAT ? VK_FORMAT_R8G8_SSCALED : VK_FORMAT_R8G8_SINT);
                case 3 -> normalized ? VK_FORMAT_R8G8B8_SNORM : (shaderType == VertexFormatElement.Type.FLOAT ? VK_FORMAT_R8G8B8_SSCALED : VK_FORMAT_R8G8B8_SINT);
                case 4 -> normalized ? VK_FORMAT_R8G8B8A8_SNORM : (shaderType == VertexFormatElement.Type.FLOAT ? VK_FORMAT_R8G8B8A8_SSCALED : VK_FORMAT_R8G8B8A8_SINT);
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
                case 1 -> normalized ? VK_FORMAT_R16_SNORM : (shaderType == VertexFormatElement.Type.FLOAT ? VK_FORMAT_R16_SSCALED : VK_FORMAT_R16_SINT);
                case 2 -> normalized ? VK_FORMAT_R16G16_SNORM : (shaderType == VertexFormatElement.Type.FLOAT ? VK_FORMAT_R16G16_SSCALED : VK_FORMAT_R16G16_SINT);
                case 3 -> normalized ? VK_FORMAT_R16G16B16_SNORM : (shaderType == VertexFormatElement.Type.FLOAT ? VK_FORMAT_R16G16B16_SSCALED : VK_FORMAT_R16G16B16_SINT);
                case 4 -> normalized ? VK_FORMAT_R16G16B16A16_SNORM : (shaderType == VertexFormatElement.Type.FLOAT ? VK_FORMAT_R16G16B16A16_SSCALED : VK_FORMAT_R16G16B16A16_SINT);
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
