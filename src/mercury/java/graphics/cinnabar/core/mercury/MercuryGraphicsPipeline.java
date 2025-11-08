package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgGraphicsPipeline;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.VK10.*;

public class MercuryGraphicsPipeline extends MercuryObject implements HgGraphicsPipeline {
    
    private final long handle;
    private final MercuryGraphicsPipelineLayout layout;
    
    public MercuryGraphicsPipeline(MercuryDevice device, HgGraphicsPipeline.CreateInfo createInfo) {
        super(device);
        this.layout = (MercuryGraphicsPipelineLayout) createInfo.layout();
        
        try (final var stack = MemoryStack.stackPush()) {
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
            
            final var pipelineState = createInfo.state();
            
            @Nullable final var vertexInput = pipelineState.vertexInput();
            final var vertexInputState = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
            if (vertexInput != null) {
                final var bufferBindings = VkVertexInputBindingDescription.calloc(vertexInput.buffers().size(), stack);
                for (int i = 0; i < vertexInput.buffers().size(); i++) {
                    final var buffer = vertexInput.buffers().get(i);
                    bufferBindings.binding(buffer.index());
                    bufferBindings.stride(buffer.stride());
                    bufferBindings.inputRate(buffer.inputRate().ordinal());
                }
                bufferBindings.position(0);
                final var attribBindings = VkVertexInputAttributeDescription.calloc(vertexInput.bindings().size(), stack);
                for (int i = 0; i < vertexInput.bindings().size(); i++) {
                    final var attrib = vertexInput.bindings().get(i);
                    attribBindings.position(i);
                    attribBindings.location(attrib.location());
                    attribBindings.binding(attrib.bufferIndex());
                    attribBindings.format(MercuryConst.vkFormat(attrib.format()));
                    attribBindings.offset(attrib.offset());
                }
                attribBindings.position(0);
                
                vertexInputState.pVertexBindingDescriptions(bufferBindings);
                vertexInputState.pVertexAttributeDescriptions(attribBindings);
            }
            
            final var inputAssemblyState = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default();
            inputAssemblyState.topology(pipelineState.topology().ordinal());
            
            final var rasterizer = pipelineState.rasterizer();
            final var rasterizationState = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default();
            rasterizationState.pNext(0);
            rasterizationState.flags(0);
            rasterizationState.depthClampEnable(false);
            rasterizationState.rasterizerDiscardEnable(false);
            rasterizationState.polygonMode(rasterizer.polyMode().ordinal());
            rasterizationState.cullMode(rasterizer.cull() ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE);
            // negative viewport height, this is fine
            rasterizationState.frontFace(VK_FRONT_FACE_CLOCKWISE);
            rasterizationState.depthBiasEnable(rasterizer.depthBiasConstant() != 0.0f || rasterizer.depthBiasScaleFactor() != 0.0f);
            rasterizationState.depthBiasConstantFactor(rasterizer.depthBiasConstant());
            rasterizationState.depthBiasClamp(0);
            rasterizationState.depthBiasSlopeFactor(rasterizer.depthBiasScaleFactor());
            rasterizationState.lineWidth(1.0f);
            
            final var depthStencilState = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default();
            depthStencilState.depthBoundsTestEnable(false);
            @Nullable final var stencilTest = pipelineState.stencil();
            if (stencilTest != null) {
                depthStencilState.stencilTestEnable(true);
                {
                    final var frontOp = depthStencilState.front();
                    final var frontTest = stencilTest.front();
                    frontOp.failOp(frontTest.fail().ordinal());
                    frontOp.depthFailOp(frontTest.depthFail().ordinal());
                    frontOp.passOp(frontTest.pass().ordinal());
                    frontOp.compareOp(frontTest.compareOp().ordinal());
                    frontOp.compareMask(frontTest.compareMask());
                    frontOp.writeMask(frontTest.writeMask());
                    frontOp.reference(frontTest.referenceValue());
                }
                {
                    final var backOp = depthStencilState.back();
                    final var backTest = stencilTest.back();
                    backOp.failOp(backTest.fail().ordinal());
                    backOp.depthFailOp(backTest.depthFail().ordinal());
                    backOp.passOp(backTest.pass().ordinal());
                    backOp.compareOp(backTest.compareOp().ordinal());
                    backOp.compareMask(backTest.compareMask());
                    backOp.writeMask(backTest.writeMask());
                    backOp.reference(backTest.referenceValue());
                }
            } else {
                depthStencilState.stencilTestEnable(false);
            }
            
            @Nullable final var depthTest = pipelineState.depthTest();
            if (depthTest != null) {
                depthStencilState.depthTestEnable(true);
                depthStencilState.depthWriteEnable(depthTest.write());
                depthStencilState.depthCompareOp(depthTest.compareOp().ordinal());
            }
            
            final var blendState = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default();
            
            final var blendAttachment = VkPipelineColorBlendAttachmentState.calloc(createInfo.renderpass().colorAttachmentCount(), stack);
            blendState.pAttachments(blendAttachment);
            
            @Nullable final var blend = pipelineState.blend();
            if (blend != null) {
                for (int i = 0; i < createInfo.renderpass().colorAttachmentCount(); i++) {
                    blendAttachment.position(i);
                    final var attachment = blend.attachments().get(i);
                    @Nullable final var equations = attachment.equations();
                    if (equations != null) {
                        blendAttachment.blendEnable(true);
                        final var color = equations.first();
                        blendAttachment.srcColorBlendFactor(color.srcFactor().ordinal());
                        blendAttachment.dstColorBlendFactor(color.dstFactor().ordinal());
                        blendAttachment.colorBlendOp(color.op().ordinal());
                        final var alpha = equations.second();
                        blendAttachment.srcAlphaBlendFactor(alpha.srcFactor().ordinal());
                        blendAttachment.dstAlphaBlendFactor(alpha.dstFactor().ordinal());
                        blendAttachment.alphaBlendOp(alpha.op().ordinal());
                    }
                    blendAttachment.colorWriteMask(attachment.writeMask());
                }
            } else {
                for (int i = 0; i < createInfo.renderpass().colorAttachmentCount(); i++) {
                    blendAttachment.position(i);
                    blendAttachment.colorWriteMask((VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT));
                }
            }
            
            final var vkCreateInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            vkCreateInfo.sType$Default();
            vkCreateInfo.flags(0);
            vkCreateInfo.pStages(((MercuryShaderSet) createInfo.shaderSet()).vkStages());
            vkCreateInfo.pVertexInputState(vertexInputState);
            vkCreateInfo.pInputAssemblyState(inputAssemblyState);
            vkCreateInfo.pTessellationState(null);
            vkCreateInfo.pViewportState(viewportState);
            vkCreateInfo.pRasterizationState(rasterizationState);
            vkCreateInfo.pMultisampleState(multiSampleState);
            vkCreateInfo.pDepthStencilState(depthStencilState);
            vkCreateInfo.pColorBlendState(blendState);
            vkCreateInfo.pDynamicState(dynamicState);
            vkCreateInfo.layout(layout.vkPipelineLayout());
            vkCreateInfo.renderPass(((MercuryRenderPass) createInfo.renderpass()).vkRenderPass());
            vkCreateInfo.subpass(0);
            vkCreateInfo.basePipelineHandle(VK_NULL_HANDLE);
            vkCreateInfo.basePipelineIndex(-1);
            
            final var handlePtr = stack.callocLong(1);
            checkVkCode(vkCreateGraphicsPipelines(device.vkDevice(), 0, vkCreateInfo, null, handlePtr));
            handle = handlePtr.get(0);
        }
        
    }
    
    @Override
    public void destroy() {
        vkDestroyPipeline(device.vkDevice(), handle, null);
    }
    
    public long vkPipeline() {
        return handle;
    }
    
    @Override
    public Layout layout() {
        return layout;
    }
}
