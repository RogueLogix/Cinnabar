package graphics.cinnabar.core.vk.shaders;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.Constant;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.memory.MemoryRange;
import graphics.cinnabar.api.threading.IWorkQueue;
import graphics.cinnabar.api.threading.WorkFuture;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.b3d.texture.CinnabarGpuTexture;
import graphics.cinnabar.core.vk.descriptors.*;
import it.unimi.dsi.fastutil.objects.*;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static graphics.cinnabar.api.CinnabarAPI.Internals.CINNABAR_API_LOG;
import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static graphics.cinnabar.core.CinnabarCore.CINNABAR_CORE_LOG;
import static org.lwjgl.vulkan.VK10.*;

public class ShaderSet {
    
    // TODO: dont leak this
    private static final ByteBuffer mainUTF8 = MemoryUtil.memUTF8("main");
    
    private final CinnabarDevice device;
    
    public final long pipelineLayout;
    public final ShaderModule vertexShader;
    public final ShaderModule fragmentShader;
    public final VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2);
    
    private final Object2IntMap<String> attribLocationMap = new Object2IntArrayMap<>();
    private final Object2ObjectMap<String, String> attribTypeMap = new Object2ObjectArrayMap<>();
    public final List<DescriptorSetLayout> descriptorSetLayouts;
    @Nullable
    private final PushConstants pushConstants;
    
    @ThreadSafety.Many
    public static ShaderSet create(CinnabarDevice device, RenderPipeline pipeline, String pipelineName, String vertexShaderName, String fragmentShaderName, String vertexShaderGLSL, String fragmentShaderGLSL, List<String> pushConstants, List<List<String>> dedicatedUBOs) {
        long start = System.nanoTime();
        
        CINNABAR_CORE_LOG.debug("Reprocessing shaders ({}, {}) for pipeline {}", vertexShaderName, fragmentShaderName, pipelineName);
        final var processedShaders = ShaderProcessing.processShaders(vertexShaderGLSL, fragmentShaderGLSL, vertexShaderName, fragmentShaderName, pushConstants, dedicatedUBOs);
        CINNABAR_CORE_LOG.debug("Reprocessed shaders ({}, {}) for pipeline {}", vertexShaderName, fragmentShaderName, pipelineName);
        final var vkVertexGLSL = processedShaders.first();
        final var vkFragmentGLSL = processedShaders.second();
        
        long end = System.nanoTime();
        long time = end - start;
        CINNABAR_API_LOG.debug("{}ns taken to reprocess shaders for pipeline {}", time, pipelineName);
        
        return create(device, pipeline, pipelineName, vertexShaderName, fragmentShaderName, vkVertexGLSL, vkFragmentGLSL);
    }
    
    @ThreadSafety.Many
    public static WorkFuture<ShaderSet> createDeferred(CinnabarDevice device, RenderPipeline pipeline, String pipelineName, String vertexShaderName, String fragmentShaderName, String vertexShaderGLSL, String fragmentShaderGLSL) {
        return new WorkFuture<>(index -> create(device, pipeline, pipelineName, vertexShaderName, fragmentShaderName, vertexShaderGLSL, fragmentShaderGLSL)).enqueue(IWorkQueue.BACKGROUND_THREADS);
    }
    
    @ThreadSafety.Many
    public static ShaderSet create(CinnabarDevice device, RenderPipeline pipeline, String pipelineName, String vertexShaderName, String fragmentShaderName, String vertexShaderGLSL, String fragmentShaderGLSL) {
        return new ShaderSet(device, pipeline, pipelineName, vertexShaderName, fragmentShaderName, vertexShaderGLSL, fragmentShaderGLSL);
    }
    
    @ThreadSafety.Many
    @SuppressWarnings("unchecked")
    private ShaderSet(CinnabarDevice device, RenderPipeline pipeline, String pipelineName, String vertexShaderName, String fragmentShaderName, String vertexShaderGLSL, String fragmentShaderGLSL) {
        this.device = device;
        
        // TODO: dont leak stuff if compilation fails
        //       failed compilation may be recoverable
        final var vertexSPV = ShaderProcessing.compileVulkanGLSL(vertexShaderName, vertexShaderGLSL, true);
        final var fragmentSPV = ShaderProcessing.compileVulkanGLSL(fragmentShaderName, fragmentShaderGLSL, false);
        
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
        
        final var vertexReflection = ShaderProcessing.reflectSPV(vertexSPV.right().asIntBuffer());
        final var fragmentReflection = ShaderProcessing.reflectSPV(fragmentSPV.right().asIntBuffer());
        
        Shaderc.shaderc_result_release(vertexSPV.firstLong());
        Shaderc.shaderc_result_release(fragmentSPV.firstLong());
        
        // TODO: SSBOs?
        int highestDescriptorSetIndex = -1;
        final var uboNames = new ObjectArraySet<String>();
        final var texelBufferNames = new ObjectArraySet<String>();
        final var samplerNames = new ObjectArraySet<String>();
        @Nullable
        final var vertexUBOs = (List<Map<String, Object>>) vertexReflection.get("ubos");
        @Nullable
        final var fragmentUBOs = (List<Map<String, Object>>) fragmentReflection.get("ubos");
        @Nullable
        final var vertexTexelBuffers = (List<Map<String, Object>>) vertexReflection.get("separate_images");
        @Nullable
        final var fragmentTexelBuffers = (List<Map<String, Object>>) fragmentReflection.get("separate_images");
        @Nullable
        final var vertexSamplers = (List<Map<String, Object>>) vertexReflection.get("textures");
        @Nullable
        final var fragmentSamplers = (List<Map<String, Object>>) fragmentReflection.get("textures");
        if (vertexUBOs != null) {
            for (final var ubo : vertexUBOs) {
                uboNames.add((String) ubo.get("name"));
                final var setIndex = (Integer) ubo.get("set");
                highestDescriptorSetIndex = Math.max(highestDescriptorSetIndex, setIndex);
            }
        }
        if (fragmentUBOs != null) {
            for (final var ubo : fragmentUBOs) {
                uboNames.add((String) ubo.get("name"));
                final var setIndex = (Integer) ubo.get("set");
                highestDescriptorSetIndex = Math.max(highestDescriptorSetIndex, setIndex);
            }
        }
        if (vertexTexelBuffers != null) {
            for (final var texelBuffer : vertexTexelBuffers) {
                texelBufferNames.add((String) texelBuffer.get("name"));
                final var setIndex = (Integer) texelBuffer.get("set");
                highestDescriptorSetIndex = Math.max(highestDescriptorSetIndex, setIndex);
            }
        }
        if (fragmentTexelBuffers != null) {
            for (final var texelBuffer : fragmentTexelBuffers) {
                texelBufferNames.add((String) texelBuffer.get("name"));
                final var setIndex = (Integer) texelBuffer.get("set");
                highestDescriptorSetIndex = Math.max(highestDescriptorSetIndex, setIndex);
            }
        }
        
        if (vertexSamplers != null) {
            for (final var sampler : vertexSamplers) {
                samplerNames.add((String) sampler.get("name"));
                final var setIndex = (Integer) sampler.get("set");
                highestDescriptorSetIndex = Math.max(highestDescriptorSetIndex, setIndex);
            }
        }
        if (fragmentSamplers != null) {
            for (final var sampler : fragmentSamplers) {
                samplerNames.add((String) sampler.get("name"));
                final var setIndex = (Integer) sampler.get("set");
                highestDescriptorSetIndex = Math.max(highestDescriptorSetIndex, setIndex);
            }
        }
        
        final var vertexTypes = (Map<String, Map<String, Object>>) vertexReflection.get("types");
        final var fragmentTypes = (Map<String, Map<String, Object>>) fragmentReflection.get("types");
        
        final var setBindings = new ReferenceArrayList<ReferenceArrayList<DescriptorSetBinding>>();
        for (int i = 0; i <= highestDescriptorSetIndex; i++) {
            setBindings.add(new ReferenceArrayList<>());
        }
        
        for (final var uboName : uboNames) {
            final var uboData = Stream.concat(vertexUBOs != null ? vertexUBOs.stream() : Stream.empty(), fragmentUBOs != null ? fragmentUBOs.stream() : Stream.empty()).filter(ubo -> ubo.get("name").equals(uboName)).findFirst().get();
            
            final var set = (int) uboData.get("set");
            final var size = (int) uboData.get("block_size");
            final var binding = (int) uboData.get("binding");
            
            final var uboBinding = new UBOBinding(uboName, binding, size);
            setBindings.get(set).add(uboBinding);
        }
        
        for (final var texelBufferName : texelBufferNames) {
            final var description = pipeline.getUniforms().stream().filter(uniformDescription -> uniformDescription.name().equals(texelBufferName)).findFirst().get();
            final var samplerData = Stream.concat(vertexTexelBuffers != null ? vertexTexelBuffers.stream() : Stream.empty(), fragmentTexelBuffers != null ? fragmentTexelBuffers.stream() : Stream.empty()).filter(sampler -> sampler.get("name").equals(texelBufferName)).findFirst().get();
            final var set = (int) samplerData.get("set");
            final var binding = (int) samplerData.get("binding");
            final var texelBinding = new TexelBufferBinding(texelBufferName, binding, CinnabarGpuTexture.toVk(Objects.requireNonNull(description.textureFormat())));
            setBindings.get(set).add(texelBinding);
        }
        
        for (final var samplerName : samplerNames) {
            final var samplerData = Stream.concat(vertexSamplers != null ? vertexSamplers.stream() : Stream.empty(), fragmentSamplers != null ? fragmentSamplers.stream() : Stream.empty()).filter(sampler -> sampler.get("name").equals(samplerName)).findFirst().get();
            final var set = (int) samplerData.get("set");
            final var binding = (int) samplerData.get("binding");
            final var samplerBinding = new SamplerBinding(samplerName, binding);
            setBindings.get(set).add(samplerBinding);
        }
        
        final var sets = new ReferenceArrayList<DescriptorSetLayout>();
        for (final var bindings : setBindings) {
            sets.add(new DescriptorSetLayout(device, bindings));
        }
        this.descriptorSetLayouts = new ReferenceImmutableList<>(sets);
        
        final var pushConstantMembers = new ReferenceArrayList<UBOMember>();
        
        final var vertexPushConstantRanges = new ReferenceArrayList<MemoryRange>();
        @Nullable
        final var vertexPushConstantDeclarations = (List<Map<String, Object>>) vertexReflection.get("push_constants");
        if (vertexPushConstantDeclarations != null) {
            for (final var declaration : vertexPushConstantDeclarations) {
                @Nullable
                final var typeName = (String) declaration.get("type");
                if (typeName == null) {
                    continue;
                }
                final var typeMembers = (List<Map<String, Object>>) vertexTypes.get(typeName).get("members");
                long start = Long.MAX_VALUE;
                long end = 0;
                for (final var member : typeMembers) {
                    final var uboMember = new UBOMember((String) member.get("name"), (String) member.get("type"), ((Number) member.get("offset")).longValue());
                    pushConstantMembers.add(uboMember);
                    start = Math.min(start, uboMember.offset);
                    end = Math.max(end, uboMember.offset + uboMember.size);
                }
                // round down to multiple of 4
                start &= ~3;
                // round up to multiple of 4
                end = (end + 3) & ~3;
                if (end != 0) {
                    vertexPushConstantRanges.add(new MemoryRange(start, end - start));
                }
            }
        }
        final var fragmentPushConstantRanges = new ReferenceArrayList<MemoryRange>();
        @Nullable
        final var fragmentPushConstantDeclarations = (List<Map<String, Object>>) fragmentReflection.get("push_constants");
        if (fragmentPushConstantDeclarations != null) {
            for (final var declaration : fragmentPushConstantDeclarations) {
                @Nullable
                final var typeName = (String) declaration.get("type");
                if (typeName == null) {
                    continue;
                }
                final var typeMembers = (List<Map<String, Object>>) fragmentTypes.get(typeName).get("members");
                long start = Long.MAX_VALUE;
                long end = 0;
                for (final var member : typeMembers) {
                    final var uboMember = new UBOMember((String) member.get("name"), (String) member.get("type"), ((Number) member.get("offset")).longValue());
                    start = Math.min(start, uboMember.offset);
                    end = Math.max(end, uboMember.offset + uboMember.size);
                }
                // round down to multiple of 4
                start &= ~3;
                // round up to multiple of 4
                end = (end + 3) & ~3;
                if (end != 0) {
                    fragmentPushConstantRanges.add(new MemoryRange(start, end - start));
                }
            }
        }
        if (!vertexPushConstantRanges.isEmpty() || !fragmentPushConstantRanges.isEmpty()) {
            pushConstants = new PushConstants(this, pushConstantMembers, vertexPushConstantRanges, fragmentPushConstantRanges);
        } else {
            pushConstants = null;
        }
        
        
        try (final var stack = MemoryStack.stackPush()) {
            final var pipelineLayoutCreateInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            if (!sets.isEmpty()) {
                final var descriptorSetLayoutHandles = stack.callocLong(sets.size());
                for (int i = 0; i < sets.size(); i++) {
                    descriptorSetLayoutHandles.put(i, sets.get(i).handle());
                }
                pipelineLayoutCreateInfo.pSetLayouts(descriptorSetLayoutHandles);
            }
            
            if (!vertexPushConstantRanges.isEmpty() || fragmentPushConstantRanges.isEmpty()) {
                final var ranges = VkPushConstantRange.calloc(vertexPushConstantRanges.size() + fragmentPushConstantRanges.size());
                pipelineLayoutCreateInfo.pPushConstantRanges(ranges);
                for (MemoryRange range : vertexPushConstantRanges) {
                    ranges.stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
                    ranges.offset((int) range.offset());
                    ranges.size((int) range.size());
                    ranges.position(ranges.position() + 1);
                }
                for (MemoryRange range : fragmentPushConstantRanges) {
                    ranges.stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
                    ranges.offset((int) range.offset());
                    ranges.size((int) range.size());
                    ranges.position(ranges.position() + 1);
                }
                ranges.position(0);
            }
            
            final var longPtr = stack.callocLong(1);
            checkVkCode(vkCreatePipelineLayout(device.vkDevice, pipelineLayoutCreateInfo, null, longPtr));
            pipelineLayout = longPtr.get(0);
        }
        
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
        
        @Nullable final var vertexInputs = (List<Map<String, Object>>) vertexReflection.get("inputs");
        if (vertexInputs != null) {
            for (final var vertexInput : vertexInputs) {
                final var name = (String) vertexInput.get("name");
                final var location = (int) vertexInput.get("location");
                final var type = (String) vertexInput.get("type");
                attribLocationMap.put(name, location);
                attribTypeMap.put(name, type);
            }
        }
    }
    
    public void destroy() {
        shaderStages.free();
        vkDestroyPipelineLayout(device.vkDevice, pipelineLayout, null);
        vertexShader.destroy();
        fragmentShader.destroy();
        descriptorSetLayouts.forEach(DescriptorSetLayout::destroy);
    }
    
    @API
    @Constant
    @ThreadSafety.Many
    public VkPipelineShaderStageCreateInfo.Buffer stages() {
        return shaderStages;
    }
    
    @API
    @Constant
    @ThreadSafety.Many
    public long pipelineLayout() {
        return pipelineLayout;
    }
    
    @API
    @Constant
    @ThreadSafety.Many
    public int attribLocation(String name) {
        return attribLocationMap.getOrDefault(name, -1);
    }
    
    @API
    @Constant
    @Nullable
    @ThreadSafety.Many
    public PushConstants pushConstants() {
        return pushConstants;
    }
    
    @API
    @Constant
    @ThreadSafety.Many
    public List<DescriptorSetLayout> descriptorSetLayouts() {
        return descriptorSetLayouts;
    }
    
    @API
    @Constant
    @ThreadSafety.Many
    public Map<String, String> attribTypes() {
        return Collections.unmodifiableMap(attribTypeMap);
    }
}
