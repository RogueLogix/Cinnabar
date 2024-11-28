package graphics.cinnabar.internal.extensions.minecraft.renderer;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.extensions.blaze3d.shaders.CinnabarProgram;
import graphics.cinnabar.internal.extensions.minecraft.renderer.texture.CinnabarAbstractTexture;
import graphics.cinnabar.internal.statemachine.CinnabarBlendState;
import graphics.cinnabar.internal.statemachine.CinnabarGeneralState;
import graphics.cinnabar.internal.vulkan.MagicNumbers;
import graphics.cinnabar.internal.vulkan.memory.HostMemoryVkBuffer;
import graphics.cinnabar.internal.vulkan.memory.VulkanBuffer;
import graphics.cinnabar.internal.vulkan.util.CinnabarDescriptorSets;
import graphics.cinnabar.internal.vulkan.util.VulkanQueueHelper;
import graphics.cinnabar.internal.vulkan.util.VulkanSampler;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.roguelogix.phosphophyllite.util.Util;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.libc.LibCString;
import org.lwjgl.vulkan.*;

import java.io.IOException;

import static graphics.cinnabar.internal.vulkan.exceptions.VkException.throwFromCode;
import static org.lwjgl.vulkan.EXTExtendedDynamicState2.VK_DYNAMIC_STATE_LOGIC_OP_EXT;
import static org.lwjgl.vulkan.EXTExtendedDynamicState3.*;
import static org.lwjgl.vulkan.VK13.*;

public class CinnabarEffectInstance extends EffectInstance implements ICinnabarShader{
    
    private CinnabarProgram vertexProgram;
    private CinnabarProgram fragmentProgram;
    
    private final long pipelineLayout;
    private final long pipeline;
    private final long UBOSize;
    private final long UBODescriptorSetLayout;
    private final long SamplerDescriptorSetLayout;
    
    // TODO: multiple of these, so that the GPU can do multiple draws at once
    @Nullable
    private final VulkanBuffer UBOGPUBuffer;
    
    private final long UBODescriptorSet;
    private VulkanBuffer.CPU uboStagingBuffer;

    public CinnabarEffectInstance(ResourceProvider resourceProvider, String name) throws IOException {
        super(resourceProvider, name);
        this.vertexProgram = (CinnabarProgram) getVertexProgram();
        this.fragmentProgram = (CinnabarProgram) getFragmentProgram();
        
        final var device = CinnabarRenderer.device();
        
        {
            int currentUBOSize = 0;
            int largestAlignmentRequirement = 1;
            
            for (int i = 0; i < this.samplerNames.size(); i++) {
                // samplers are always at sequential bind indices
                this.samplerLocations.add(i);
            }
            
            for (Uniform uniform : super.uniforms) {
                // uniforms are always only float or int based, no bytes/etc
                final var uniformByteSize = uniform.getCount() * 4;
                // alignments are always a power of 2, but size can be npot for (i)vec3 uniforms
                final var uniformAlignment = Util.roundUpPo2(uniformByteSize);
                if (uniformAlignment > largestAlignmentRequirement) {
                    largestAlignmentRequirement = uniformAlignment;
                }
                // round up size to next alignment that works
                currentUBOSize = (currentUBOSize + (uniformAlignment - 1)) & -uniformAlignment;
                // uniform location now means the byte offset in the UBO for it, rather than GL style location
                uniformLocations.add(currentUBOSize);
                uniform.setLocation(currentUBOSize);
                currentUBOSize += uniformByteSize;
                this.uniformMap.put(uniform.getName(), uniform);
            }
            UBOSize = (currentUBOSize + (largestAlignmentRequirement - 1)) & -largestAlignmentRequirement;
        }
        
        try (final var stack = MemoryStack.stackPush()) {
            final var longPtr = stack.callocLong(1);
            
            if (UBOSize != 0) {
                final var bindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
                bindings.binding(0);
                bindings.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
                bindings.descriptorCount(1);
                bindings.stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
                
                final var createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
                createInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
                createInfo.pBindings(bindings);
                
                vkCreateDescriptorSetLayout(device, createInfo, null, longPtr);
                UBOGPUBuffer = new VulkanBuffer(UBOSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
                UBODescriptorSet = CinnabarDescriptorSets.allocDescriptorSet(longPtr.get(0));
                
                final var descriptorWrites = VkWriteDescriptorSet.calloc(1, stack).sType$Default();
                final var UBOBufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                UBOBufferInfo.buffer(UBOGPUBuffer.handle);
                UBOBufferInfo.offset(0);
                UBOBufferInfo.range(VK_WHOLE_SIZE);
                descriptorWrites.dstSet(UBODescriptorSet);
                descriptorWrites.dstBinding(0);
                descriptorWrites.dstArrayElement(0);
                descriptorWrites.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
                descriptorWrites.descriptorCount(1);
                descriptorWrites.pBufferInfo(UBOBufferInfo);
                vkUpdateDescriptorSets(device, descriptorWrites, null);
            } else {
                UBODescriptorSet = 0;
                UBOGPUBuffer = null;
            }
            UBODescriptorSetLayout = longPtr.get(0);
            
            longPtr.put(0, 0);
            if (!samplerNames.isEmpty()) {
                final var bindings = VkDescriptorSetLayoutBinding.calloc(samplerNames.size(), stack);
                for (int i = 0; i < samplerNames.size(); i++) {
                    bindings.position(i);
                    bindings.binding(i);
                    bindings.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                    bindings.descriptorCount(1);
                    // vertex uses samplers for lightmap
                    bindings.stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
                }
                bindings.position(0);
                
                final var createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
                createInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
                createInfo.pBindings(bindings);
                
                vkCreateDescriptorSetLayout(device, createInfo, null, longPtr);
            }
            SamplerDescriptorSetLayout = longPtr.get(0);
        }
        
        
        try (final var stack = MemoryStack.stackPush()) {
            final var longPtr = stack.callocLong(1);
            
            final var pipelineLayoutCreateInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            if (UBODescriptorSetLayout != VK_NULL_HANDLE) {
                if (SamplerDescriptorSetLayout != VK_NULL_HANDLE) {
                    pipelineLayoutCreateInfo.pSetLayouts(stack.longs(UBODescriptorSetLayout, SamplerDescriptorSetLayout));
                } else {
                    pipelineLayoutCreateInfo.pSetLayouts(stack.longs(UBODescriptorSetLayout));
                }
            } else {
                if (SamplerDescriptorSetLayout != VK_NULL_HANDLE) {
                    pipelineLayoutCreateInfo.pSetLayouts(stack.longs(SamplerDescriptorSetLayout));
                }
            }
            
            throwFromCode(vkCreatePipelineLayout(device, pipelineLayoutCreateInfo, null, longPtr));
            pipelineLayout = longPtr.get(0);
            
            
            final var mainUTF8 = stack.UTF8("main");
            final var shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            shaderStages.position(0);
            shaderStages.sType$Default();
            shaderStages.stage(VK_SHADER_STAGE_VERTEX_BIT);
            shaderStages.module(vertexProgram.handle);
            shaderStages.pName(mainUTF8);
            shaderStages.position(1);
            shaderStages.sType$Default();
            shaderStages.stage(VK_SHADER_STAGE_FRAGMENT_BIT);
            shaderStages.module(fragmentProgram.handle);
            shaderStages.pName(mainUTF8);
            shaderStages.position(0);
            
            // Post passes always use position
            final var vertexFormat = DefaultVertexFormat.POSITION;
            
            // there is always exactly one vertex buffer
            final var bufferBindings = VkVertexInputBindingDescription.calloc(1, stack);
            bufferBindings.binding(0);
            bufferBindings.stride(vertexFormat.getVertexSize());
            bufferBindings.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            
            final var vertexFormatElements = vertexFormat.getElements();
            final var attribBindings = VkVertexInputAttributeDescription.calloc(vertexFormatElements.size(), stack);
            for (int i = 0; i < vertexFormatElements.size(); i++) {
                // the glsl transformer always binds inputs in declaration order
                VertexFormatElement element = vertexFormatElements.get(i);
                attribBindings.position(i);
                attribBindings.location(i);
                attribBindings.binding(0);
                // vulkan packs what was component type, component count, and normalized into a single attrib format
                attribBindings.format(mapTypeAndCountToVkFormat(element.type(), element.count(), element.usage()));
                attribBindings.offset(vertexFormat.getOffset(element));
            }
            attribBindings.position(0);
            
            final var vertexInputState = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
            vertexInputState.pNext(0);
            vertexInputState.pVertexBindingDescriptions(bufferBindings);
            vertexInputState.pVertexAttributeDescriptions(attribBindings);
            
            final var assemblyState = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default();
            assemblyState.pNext(0);
            assemblyState.flags(0);
            // all draws are triangle class, even "lines" are triangle class
            assemblyState.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            assemblyState.primitiveRestartEnable(false);
            
            final var viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default();
            viewportState.pNext(0);
            viewportState.flags(0);
            viewportState.viewportCount(1);
            viewportState.pViewports(null);
            viewportState.scissorCount(1);
            viewportState.pScissors(null);
            
            final var rasterizationState = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default();
            rasterizationState.pNext(0);
            rasterizationState.flags(0);
            rasterizationState.depthClampEnable(false);
            rasterizationState.rasterizerDiscardEnable(false);
            
            rasterizationState.polygonMode(VK_POLYGON_MODE_FILL);
            rasterizationState.cullMode(VK_CULL_MODE_BACK_BIT);
            // negative viewport height, this is fine
            rasterizationState.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
            rasterizationState.depthBiasEnable(false);
            rasterizationState.depthBiasConstantFactor(0);
            rasterizationState.depthBiasClamp(0);
            rasterizationState.depthBiasSlopeFactor(0);
            rasterizationState.lineWidth(1.0f);
            
            final var multiSampleState = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default();
            multiSampleState.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            multiSampleState.sampleShadingEnable(false);
            
            final var depthStencilState = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default();
            depthStencilState.sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO);
            depthStencilState.depthBoundsTestEnable(false);
            depthStencilState.stencilTestEnable(false);
            // set at draw time, but anyway
            depthStencilState.depthTestEnable(true);
            depthStencilState.depthWriteEnable(true);
            depthStencilState.depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);
            
            // blend info set dynamically
            final var blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blendAttachment.blendEnable(false);
            
            final var blendState = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default();
            blendState.logicOpEnable(true);
            blendState.pAttachments(blendAttachment);
            
            // lots of dynamic state, some of this could potentially be done ahead of time
            final var dynamicStatesBuffer = stack.ints(
                    VK_DYNAMIC_STATE_VIEWPORT,
                    VK_DYNAMIC_STATE_SCISSOR,
                    VK_DYNAMIC_STATE_CULL_MODE,
                    VK_DYNAMIC_STATE_PRIMITIVE_TOPOLOGY,
                    VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE,
                    VK_DYNAMIC_STATE_DEPTH_COMPARE_OP,
                    VK_DYNAMIC_STATE_DEPTH_WRITE_ENABLE,
                    VK_DYNAMIC_STATE_LOGIC_OP_EXT,
                    VK_DYNAMIC_STATE_LOGIC_OP_ENABLE_EXT,
                    VK_DYNAMIC_STATE_COLOR_BLEND_ENABLE_EXT,
                    VK_DYNAMIC_STATE_COLOR_BLEND_EQUATION_EXT,
                    VK_DYNAMIC_STATE_COLOR_WRITE_MASK_EXT
            );
            
            final var dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default();
            dynamicState.pDynamicStates(dynamicStatesBuffer);
            
            final var renderingCreateInfo = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default();
            renderingCreateInfo.colorAttachmentCount(1);
            renderingCreateInfo.pColorAttachmentFormats(stack.ints(MagicNumbers.FramebufferColorFormat));
            renderingCreateInfo.depthAttachmentFormat(MagicNumbers.FramebufferDepthFormat);
            
            final var pipelineCreateInfos = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default();
            pipelineCreateInfos.pNext(renderingCreateInfo);
            pipelineCreateInfos.flags(0);
            pipelineCreateInfos.pStages(shaderStages);
            pipelineCreateInfos.pVertexInputState(vertexInputState);
            pipelineCreateInfos.pInputAssemblyState(assemblyState);
            pipelineCreateInfos.pTessellationState(null);
            pipelineCreateInfos.pViewportState(viewportState); // despite being dynamic, it still needs to know the count, which is 1
            pipelineCreateInfos.pRasterizationState(rasterizationState);
            pipelineCreateInfos.pMultisampleState(multiSampleState);
            pipelineCreateInfos.pDepthStencilState(depthStencilState);
            pipelineCreateInfos.pColorBlendState(blendState);
            pipelineCreateInfos.pDynamicState(dynamicState);
            pipelineCreateInfos.layout(pipelineLayout);
            pipelineCreateInfos.renderPass(VK_NULL_HANDLE);
            pipelineCreateInfos.subpass(0);
            pipelineCreateInfos.basePipelineHandle(0);
            pipelineCreateInfos.basePipelineIndex(0);
            
            throwFromCode(vkCreateGraphicsPipelines(device, 0, pipelineCreateInfos, null, longPtr));
            pipeline = longPtr.get(0);
        }
        vertexProgram.attachToEffect(this);
        fragmentProgram.attachToEffect(this);
    }
    
    @Override
    public void close() {
        CinnabarRenderer.waitIdle();
        if (UBOGPUBuffer != null) {
            UBOGPUBuffer.destroy();
        }
        vertexProgram.close();
        fragmentProgram.close();
        for (Uniform uniform : this.uniforms) {
            uniform.close();
        }
        final var device = CinnabarRenderer.device();
        CinnabarDescriptorSets.free(UBODescriptorSet);
        vkDestroyDescriptorSetLayout(device, UBODescriptorSetLayout, null);
        vkDestroyDescriptorSetLayout(device, SamplerDescriptorSetLayout, null);
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        
    }
    
    @Override
    protected void updateLocations() {
        // done a bit later in this class's constructor
    }
    
    @Override
    public void apply() {
        apply(CinnabarRenderer.queueHelper.getImplicitCommandBuffer(VulkanQueueHelper.QueueType.MAIN_GRAPHICS));
    }
    
    public void apply(VkCommandBuffer commandBuffer) {
        long activeSamplerSet = VK_NULL_HANDLE;
        try (final var stack = MemoryStack.stackPush()) {
            final var device = CinnabarRenderer.device();
            final var descriptorWrites = VkWriteDescriptorSet.calloc(samplerNames.size());
            activeSamplerSet = CinnabarRenderer.queueHelper.descriptorPoolsForSubmit().allocSamplerSet(SamplerDescriptorSetLayout);
            for (int i = 0; i < samplerNames.size(); i++) {
                final var mapValue = samplerMap.get(samplerNames.get(i));
                long imageViewHandle = CinnabarAbstractTexture.imageViewHandleFromID(mapValue.getAsInt());
                if (imageViewHandle == VK_NULL_HANDLE) {
                    throw new UnsupportedOperationException();
                }
                final var imageInfo = VkDescriptorImageInfo.calloc(1, stack);
                imageInfo.sampler(VulkanSampler.DEFAULT.vulkanHandle);
                imageInfo.imageView(imageViewHandle);
                imageInfo.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                
                descriptorWrites.position(i);
                descriptorWrites.descriptorCount(1);
                descriptorWrites.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                descriptorWrites.dstSet(activeSamplerSet);
                descriptorWrites.dstBinding(i);
                descriptorWrites.dstArrayElement(0);
                descriptorWrites.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                descriptorWrites.pImageInfo(imageInfo);
            }
            descriptorWrites.position(0);
            vkUpdateDescriptorSets(device, descriptorWrites, null);

            if (this.dirty && UBOGPUBuffer != null) {
                this.dirty = false;
                uboStagingBuffer = CinnabarRenderer.queueHelper.cpuBufferAllocatorForSubmit().alloc(UBOSize);
                CinnabarRenderer.queueDestroyEndOfGPUSubmit(uboStagingBuffer);
                for (Uniform uniform : this.uniforms) {
                    uniform.upload();
                }

                final var depInfo = VkDependencyInfo.calloc(stack).sType$Default();
                final var bufferDepInfo = VkBufferMemoryBarrier2.calloc(1, stack).sType$Default();
                depInfo.pBufferMemoryBarriers(bufferDepInfo);
                bufferDepInfo.buffer(UBOGPUBuffer.handle);
                bufferDepInfo.size(UBOGPUBuffer.size);
                bufferDepInfo.offset(0);

                bufferDepInfo.srcStageMask(VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
                bufferDepInfo.srcAccessMask(VK_ACCESS_UNIFORM_READ_BIT);
                bufferDepInfo.dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT);
                bufferDepInfo.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                vkCmdPipelineBarrier2(commandBuffer, depInfo);

                final var copyRegion = VkBufferCopy.calloc(1, stack);
                copyRegion.srcOffset(0);
                copyRegion.dstOffset(0);
                copyRegion.size(UBOSize);
                copyRegion.limit(1);
                vkCmdCopyBuffer(commandBuffer, uboStagingBuffer.handle, UBOGPUBuffer.handle, copyRegion);

                bufferDepInfo.srcStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT);
                bufferDepInfo.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                bufferDepInfo.dstStageMask(VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
                bufferDepInfo.dstAccessMask(VK_ACCESS_UNIFORM_READ_BIT);
                vkCmdPipelineBarrier2(commandBuffer, depInfo);

                uboStagingBuffer = null;

            }
        }
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vkCmdSetDepthWriteEnable(commandBuffer, CinnabarGeneralState.depthWrite);
        vkCmdSetCullMode(commandBuffer, CinnabarGeneralState.cull ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE);
        if (UBODescriptorSet != VK_NULL_HANDLE) {
            if (SamplerDescriptorSetLayout != VK_NULL_HANDLE) {
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, new long[]{UBODescriptorSet, activeSamplerSet}, null);
            } else {
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, new long[]{UBODescriptorSet}, null);
            }
        } else {
            if (SamplerDescriptorSetLayout != VK_NULL_HANDLE) {
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, new long[]{activeSamplerSet}, null);
            }
        }
        CinnabarBlendState.apply(commandBuffer);
    }
    
    public void clear() {
    
    }
    
    public void writeUniform(long UBOOffset, long cpuMemAddress, long size) {
        if(uboStagingBuffer != null){
            LibCString.nmemcpy(uboStagingBuffer.hostPtr.pointer() + UBOOffset, cpuMemAddress, size);
        }
    }
    
    private static int mapTypeAndCountToVkFormat(VertexFormatElement.Type type, int count, VertexFormatElement.Usage usage) {
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
                case 1 -> normalized ? VK_FORMAT_R8_SNORM : VK_FORMAT_R8_SINT;
                case 2 -> normalized ? VK_FORMAT_R8G8_SNORM : VK_FORMAT_R8G8_SINT;
                case 3 -> normalized ? VK_FORMAT_R8G8B8A8_SNORM : VK_FORMAT_R8G8B8_SINT; // yes this violates spec, this is to compensate for mojang
                case 4 -> normalized ? VK_FORMAT_R8G8B8A8_SNORM : VK_FORMAT_R8G8B8A8_SINT;
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
                case 1 -> normalized ? VK_FORMAT_R16_SNORM : VK_FORMAT_R16_SINT;
                case 2 -> normalized ? VK_FORMAT_R16G16_SNORM : VK_FORMAT_R16G16_SINT;
                case 3 -> normalized ? VK_FORMAT_R16G16B16_SNORM : VK_FORMAT_R16G16B16_SINT;
                case 4 -> normalized ? VK_FORMAT_R16G16B16A16_SNORM : VK_FORMAT_R16G16B16A16_SINT;
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
