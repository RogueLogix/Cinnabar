package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.exceptions.NotImplemented;
import graphics.cinnabar.api.hg.HgGraphicsPipeline;
import graphics.cinnabar.api.hg.HgUniformSet;
import graphics.cinnabar.api.hg.enums.HgUniformType;
import graphics.cinnabar.lib.ThreadGlobals;
import it.unimi.dsi.fastutil.ints.Int2ReferenceArrayMap;
import it.unimi.dsi.fastutil.ints.IntIntImmutablePair;
import it.unimi.dsi.fastutil.ints.IntReferenceImmutablePair;
import it.unimi.dsi.fastutil.longs.LongReferenceImmutablePair;
import it.unimi.dsi.fastutil.objects.*;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.SpvcReflectedResource;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spvc.*;
import static org.lwjgl.vulkan.VK10.*;

public class MercuryShaderSet extends MercuryObject implements HgGraphicsPipeline.ShaderSet {
    
    private static final ByteBuffer mainUTF8 = MemoryUtil.memUTF8("main");
    private final VkPipelineShaderStageCreateInfo.Buffer shaderStages;
    private final long[] shaders = new long[3];
    private final List<VertexAttrib> attribs;
    private final int attachmentCount;
    
    private final ReferenceArrayList<HgUniformSet.Layout.@Nullable CreateInfo> uniformSetCreateInfos = new ReferenceArrayList<>();
    private final long pushConstantsSize;
    
    public MercuryShaderSet(MercuryDevice device, CreateInfo createInfo) {
        super(device);
        if (createInfo.vertexStage().left().isPresent()) {
            throw new NotImplemented("Mesh shaders TBD");
        }
        if (createInfo.vertexStage().right().isEmpty()) {
            throw new IllegalArgumentException();
        }
        
        final var globals = ThreadGlobals.get();
        
        final var fragmentGLSL = createInfo.fragmentStage().fragment();
        final var vertexGLSL = createInfo.vertexStage().right().get().vertex();
        
        final var compilerOptions = createInfo.rebind() ? globals.ShaderCCompilerGLOptions : globals.ShaderCCompilerVKOptions;
        final var vertexCompileResult = shaderc_compile_into_spv(globals.ShaderCCompiler, vertexGLSL, shaderc_vertex_shader, "vertex", "main", compilerOptions);
        final var vertexCompileStatus = shaderc_result_get_compilation_status(vertexCompileResult);
        if (vertexCompileStatus != shaderc_compilation_status_success) {
            @Nullable final var errorMessage = shaderc_result_get_error_message(vertexCompileResult);
            shaderc_result_release(vertexCompileResult);
            throw new RuntimeException(errorMessage);
        }
        final var fragmentCompileResult = shaderc_compile_into_spv(globals.ShaderCCompiler, fragmentGLSL, shaderc_fragment_shader, "fragment", "main", compilerOptions);
        final var fragmentCompileStatus = shaderc_result_get_compilation_status(fragmentCompileResult);
        if (fragmentCompileStatus != shaderc_compilation_status_success) {
            @Nullable final var errorMessage = shaderc_result_get_error_message(fragmentCompileResult);
            shaderc_result_release(vertexCompileResult);
            shaderc_result_release(fragmentCompileResult);
            throw new RuntimeException(errorMessage);
        }
        
        final var vertexSpvCode = Objects.requireNonNull(shaderc_result_get_bytes(vertexCompileResult));
        final var fragmentSpvCode = Objects.requireNonNull(shaderc_result_get_bytes(fragmentCompileResult));
        
        long spvcContext = 0;
        try (final var stack = MemoryStack.stackPush()) {
            final var intReturn = stack.ints(0);
            final var ptrReturn = stack.pointers(0);
            spvc_context_create(ptrReturn);
            spvcContext = ptrReturn.get(0);
            
            final var vertexSpvIntBuffer = vertexSpvCode.asIntBuffer();
            spvc_context_parse_spirv(spvcContext, vertexSpvIntBuffer, vertexSpvIntBuffer.remaining(), ptrReturn);
            final var parsedVtxIR = ptrReturn.get(0);
            final var fragmentSpvIntBuffer = fragmentSpvCode.asIntBuffer();
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
            
            // vertex attribs
            {
                final var attribList = new ReferenceArrayList<VertexAttrib>();
                spvc_resources_get_resource_list_for_type(vtxResources, SPVC_RESOURCE_TYPE_STAGE_INPUT, resourcePtr, ptrReturn);
                final var vtxAttribs = SpvcReflectedResource.create(resourcePtr.get(0), (int) ptrReturn.get(0));
                {
                    for (SpvcReflectedResource vtxAttrib : vtxAttribs) {
                        final var attribName = vtxAttrib.nameString();
                        final var attribLocation = spvc_compiler_get_decoration(spvcVtxCompiler, vtxAttrib.id(), Spv.SpvDecorationLocation);
                        final var type = spvc_compiler_get_type_handle(spvcVtxCompiler, vtxAttrib.type_id());
                        final var baseType = spvc_type_get_basetype(type);
                        final var vectorWidth = spvc_type_get_vector_size(type);
                        attribList.add(new VertexAttrib(attribLocation, attribName, baseType, vectorWidth));
                    }
                }
                attribs = Collections.unmodifiableList(attribList);
            }
            
            // fragment attachments
            {
                spvc_resources_get_resource_list_for_type(fragResources, SPVC_RESOURCE_TYPE_STAGE_OUTPUT, resourcePtr, ptrReturn);
                final var fraAttachments = SpvcReflectedResource.create(resourcePtr.get(0), (int) ptrReturn.get(0));
                // don't really care about the details, that's on you to make sure are correct, just need to know the count
                attachmentCount = fraAttachments.remaining();
            }
            
            if (createInfo.rebind()) {
                // OpenGL shader, things will need to be rebound
                
                // stage to stage bindings
                {
                    spvc_resources_get_resource_list_for_type(vtxResources, SPVC_RESOURCE_TYPE_STAGE_OUTPUT, resourcePtr, ptrReturn);
                    final var vtxOutputs = SpvcReflectedResource.create(resourcePtr.get(0), (int) ptrReturn.get(0));
                    spvc_resources_get_resource_list_for_type(fragResources, SPVC_RESOURCE_TYPE_STAGE_INPUT, resourcePtr, ptrReturn);
                    final var fraInputs = SpvcReflectedResource.create(resourcePtr.get(0), (int) ptrReturn.get(0));
                    
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
//                        final var vectorWidth = spvc_type_get_vector_size(type);
                        
                        var inputLocation = spvc_compiler_get_decoration(spvcFraCompiler, fraInput.id(), Spv.SpvDecorationLocation);
                        @Nullable final var vtxOutput = vtxOutputsByName.get(inputName);
                        if (vtxOutput == null) {
                            // no name match, check for a location match
                            @Nullable final var outputType = vtxOutputsByLocation.get(inputLocation);
                            if (outputType == null) {
                                throw new IllegalArgumentException(String.format("Unable to find output for fragment input %s in pipeline %s", inputName, "TODO: replace me"));
                            }
                            if (outputType.leftInt() != baseType) {
                                throw new IllegalArgumentException(String.format("Fragment input %s does not match vertex output type in pipeline %s", inputName, "TODO: replace me"));
                            }
                        } else {
                            if (vtxOutput.right().leftInt() != baseType) {
                                throw new IllegalArgumentException(String.format("Fragment input %s does not match vertex output type in pipeline %s", inputName, "TODO: replace me"));
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
                
                // descriptor set reflection
                {
                    final var descriptorBindings = new ReferenceArrayList<HgUniformSet.Layout.Binding>();
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
                            // TODO: find a way to report the errors correctly here
                            //       maybe that has to be done at a higher level where Hg3D looks at the HgUniformSet.Layout for errors
                            descriptorBindings.add(
                                    switch (resourceType) {
                                        case SPVC_RESOURCE_TYPE_UNIFORM_BUFFER -> {
                                            final var type = spvc_compiler_get_type_handle(resource.leftLong(), resource.right().type_id());
                                            spvc_compiler_get_declared_struct_size(resource.leftLong(), type, ptrReturn);
                                            // TODO: fix the pipelines, for now, these are always bound by RenderSystem.bindDefaultUniforms 
//                                            if (!"Projection".equals(resourceName) && !"Fog".equals(resourceName) && !"Globals".equals(resourceName) && !"Lighting".equals(resourceName)
//                                                        && pipeline.getUniforms().stream().noneMatch(uniformDescription -> uniformDescription.type() == UniformType.UNIFORM_BUFFER && uniformDescription.name().equals(resourceName))) {
//                                                throw new IllegalArgumentException(String.format("UBO (%s) found in shader without matching definition in pipeline %s", resourceName, pipelineName));
//                                            }
                                            yield new HgUniformSet.Layout.Binding(resourceName, bindingLocation, HgUniformType.UNIFORM_BUFFER, 1, false, false, ptrReturn.get(0));
                                        }
                                        case SPVC_RESOURCE_TYPE_STORAGE_BUFFER -> {
                                            final var type = spvc_compiler_get_type_handle(resource.leftLong(), resource.right().type_id());
                                            spvc_compiler_type_struct_member_array_stride(resource.leftLong(), type, 0, intReturn);
//                                            if (pipeline.getUniforms().stream().noneMatch(uniformDescription -> uniformDescription.type() == UniformType.UNIFORM_BUFFER && uniformDescription.name().equals(resourceName))) {
//                                                throw new IllegalArgumentException(String.format("SSBO (%s) found in shader without matching definition in pipeline %s", resourceName, pipelineName));
//                                            }
                                            yield new HgUniformSet.Layout.Binding(resourceName, bindingLocation, HgUniformType.STORAGE_BUFFER, 1, false, false, intReturn.get(0));
                                        }
                                        case SPVC_RESOURCE_TYPE_SEPARATE_IMAGE -> {
//                                            if (pipeline.getUniforms().stream().noneMatch(uniformDescription -> uniformDescription.type() == UniformType.TEXEL_BUFFER && uniformDescription.name().equals(resourceName))) {
//                                                throw new IllegalArgumentException(String.format("UTB (%s) found in shader without matching definition in pipeline %s", resourceName, pipelineName));
//                                            }
                                            yield new HgUniformSet.Layout.Binding(resourceName, bindingLocation, HgUniformType.UNIFORM_TEXEL_BUFFER, 1, false, false, 0);
                                        }
                                        case SPVC_RESOURCE_TYPE_SAMPLED_IMAGE -> {
//                                            if (pipeline.getSamplers().stream().noneMatch(samplerName -> samplerName.equals(resourceName))) {
//                                                throw new IllegalArgumentException(String.format("Sampler (%s) found in shader without matching definition in pipeline %s", resourceName, pipelineName));
//                                            }
                                            yield new HgUniformSet.Layout.Binding(resourceName, bindingLocation, HgUniformType.COMBINED_IMAGE_SAMPLER, 1, false, false, 0);
                                        }
                                        default -> throw new IllegalStateException("Unexpected value: " + resourceType);
                                    }
                            );
                        }
                    }
                    
                    uniformSetCreateInfos.add(new HgUniformSet.Layout.CreateInfo(Collections.unmodifiableList(descriptorBindings)));
                    pushConstantsSize = 0;
                }
            } else {
                // Vulkan shader, can expect that things are bound correctly already, just need to reflect it for the descriptor sets
                // this may also have multiple, because it was explicitly specified
                
                final var descriptorBindings = new Int2ReferenceArrayMap<ReferenceArrayList<HgUniformSet.Layout.Binding>>();
                final var resourceTypes = new int[]{SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, SPVC_RESOURCE_TYPE_STORAGE_BUFFER, SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE};
                for (int resourceType : resourceTypes) {
                    final var hgUniformType = switch (resourceType) {
                        case SPVC_RESOURCE_TYPE_UNIFORM_BUFFER -> HgUniformType.UNIFORM_BUFFER;
                        case SPVC_RESOURCE_TYPE_STORAGE_BUFFER -> HgUniformType.STORAGE_BUFFER;
                        case SPVC_RESOURCE_TYPE_SEPARATE_IMAGE -> HgUniformType.UNIFORM_TEXEL_BUFFER;
                        case SPVC_RESOURCE_TYPE_SAMPLED_IMAGE -> HgUniformType.COMBINED_IMAGE_SAMPLER;
                        default -> throw new IllegalStateException("Unexpected value: " + resourceType);
                    };
                    
                    spvc_resources_get_resource_list_for_type(vtxResources, resourceType, resourcePtr, ptrReturn);
                    final var vtxResourceList = SpvcReflectedResource.create(resourcePtr.get(0), (int) ptrReturn.get(0));
                    spvc_resources_get_resource_list_for_type(fragResources, resourceType, resourcePtr, ptrReturn);
                    final var fragResourceList = SpvcReflectedResource.create(resourcePtr.get(0), (int) ptrReturn.get(0));
                    
                    final var resourceNames = new ObjectArraySet<String>();
                    final var resourceLocations = new Object2ObjectArrayMap<String, IntIntImmutablePair>();
                    final var resourceSizes = new Object2LongArrayMap<String>();
                    for (SpvcReflectedResource resource : vtxResourceList) {
                        final var resourceName = resource.nameString();
                        resourceNames.add(resourceName);
                        spvc_compiler_get_binary_offset_for_decoration(spvcVtxCompiler, resource.id(), Spv.SpvDecorationBinding, intReturn);
                        final var currentResourceSet = spvc_compiler_get_decoration(spvcVtxCompiler, resource.id(), Spv.SpvDecorationDescriptorSet);
                        final var currentResourceBinding = spvc_compiler_get_decoration(spvcVtxCompiler, resource.id(), Spv.SpvDecorationBinding);
                        if (vertexSpvIntBuffer.get(intReturn.get(0)) != currentResourceBinding) {
                            throw new IllegalStateException();
                        }
                        resourceLocations.put(resourceName, new IntIntImmutablePair(currentResourceSet, currentResourceBinding));
                        final var type = spvc_compiler_get_type_handle(spvcVtxCompiler, resource.type_id());
                        if (resourceType == SPVC_RESOURCE_TYPE_UNIFORM_BUFFER) {
                            spvc_compiler_get_declared_struct_size(spvcVtxCompiler, type, ptrReturn);
                            resourceSizes.put(resourceName, ptrReturn.get(0));
                        } else {
                            spvc_compiler_type_struct_member_array_stride(spvcVtxCompiler, type, 0, intReturn);
                            resourceSizes.put(resourceName, intReturn.get(0));
                        }
                    }
                    for (SpvcReflectedResource resource : fragResourceList) {
                        final var resourceName = resource.nameString();
                        resourceNames.add(resourceName);
                        spvc_compiler_get_binary_offset_for_decoration(spvcFraCompiler, resource.id(), Spv.SpvDecorationBinding, intReturn);
                        final var currentResourceSet = spvc_compiler_get_decoration(spvcFraCompiler, resource.id(), Spv.SpvDecorationDescriptorSet);
                        final var currentResourceBinding = spvc_compiler_get_decoration(spvcFraCompiler, resource.id(), Spv.SpvDecorationBinding);
                        if (fragmentSpvIntBuffer.get(intReturn.get(0)) != currentResourceBinding) {
                            throw new IllegalStateException();
                        }
                        resourceLocations.put(resourceName, new IntIntImmutablePair(currentResourceSet, currentResourceBinding));
                        final var type = spvc_compiler_get_type_handle(spvcFraCompiler, resource.type_id());
                        if (resourceType == SPVC_RESOURCE_TYPE_UNIFORM_BUFFER) {
                            spvc_compiler_get_declared_struct_size(spvcFraCompiler, type, ptrReturn);
                            resourceSizes.put(resourceName, ptrReturn.get(0));
                        } else {
                            spvc_compiler_type_struct_member_array_stride(spvcFraCompiler, type, 0, intReturn);
                            resourceSizes.put(resourceName, intReturn.get(0));
                        }
                    }
                    
                    for (String resourceName : resourceNames) {
                        final var resource = resourceLocations.get(resourceName);
                        descriptorBindings.computeIfAbsent(resource.leftInt(), k -> new ReferenceArrayList<>()).add(new HgUniformSet.Layout.Binding(resourceName, resource.rightInt(), hgUniformType, 1, false, false, resourceSizes.getLong(resourceName)));
                    }
                }
                
                if (!descriptorBindings.isEmpty()) {
                    //noinspection OptionalGetWithoutIsPresent
                    final var maxSetIndex = descriptorBindings.keySet().intStream().max().getAsInt();
                    uniformSetCreateInfos.size(maxSetIndex);
                    for (final var set : descriptorBindings.int2ReferenceEntrySet()) {
                        uniformSetCreateInfos.set(set.getIntKey(), new HgUniformSet.Layout.CreateInfo(Collections.unmodifiableList(set.getValue())));
                    }
                }
                
                pushConstantsSize = 0;
            }
        } finally {
            if (spvcContext != 0) {
                spvc_context_destroy(spvcContext);
            }
        }
        
        final long vertexShader;
        final long fragmentShader;
        try (final var stack = MemoryStack.stackPush()) {
            final var shaderModuleCreateInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default();
            shaderModuleCreateInfo.pCode(vertexSpvCode);
            final var handleReturn = stack.longs(0);
            vkCreateShaderModule(device.vkDevice(), shaderModuleCreateInfo, null, handleReturn);
            vertexShader = handleReturn.get(0);
            shaderModuleCreateInfo.pCode(fragmentSpvCode);
            vkCreateShaderModule(device.vkDevice(), shaderModuleCreateInfo, null, handleReturn);
            fragmentShader = handleReturn.get(0);
        }
        shaders[0] = vertexShader;
        shaders[1] = fragmentShader;
        
        // TODO: this leaks if something throws earlier, fix that
        shaderc_result_release(vertexCompileResult);
        shaderc_result_release(fragmentCompileResult);
        
        shaderStages = VkPipelineShaderStageCreateInfo.calloc(2);
        
        shaderStages.position(0).sType$Default();
        shaderStages.stage(VK_SHADER_STAGE_FRAGMENT_BIT);
        shaderStages.module(fragmentShader);
        shaderStages.pName(mainUTF8);
        shaderStages.position(0);
        
        shaderStages.position(1);
        shaderStages.sType$Default();
        shaderStages.stage(VK_SHADER_STAGE_VERTEX_BIT);
        shaderStages.module(vertexShader);
        shaderStages.pName(mainUTF8);
        
        shaderStages.position(0);
    }
    
    @Override
    public void destroy() {
        shaderStages.free();
        for (int i = 0; i < shaders.length; i++) {
            if (shaders[i] != 0) {
                vkDestroyShaderModule(device.vkDevice(), shaders[i], null);
            }
        }
    }
    
    public VkPipelineShaderStageCreateInfo.Buffer vkStages() {
        return shaderStages;
    }
    
    @Nullable
    @Override
    public List<VertexAttrib> attribs() {
        return attribs;
    }
    
    @Override
    public int attachmentCount() {
        return attachmentCount;
    }
    
    @Override
    public int maximumUniformSetIndex() {
        return uniformSetCreateInfos.size();
    }
    
    @Override
    public HgUniformSet.Layout.CreateInfo uniformSetLayoutCreateInfo(int setIndex) {
        return uniformSetCreateInfos.get(setIndex);
    }
    
    @Override
    public long pushConstantsSize() {
        return pushConstantsSize;
    }
}
