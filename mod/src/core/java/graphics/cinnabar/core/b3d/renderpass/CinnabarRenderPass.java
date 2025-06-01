package graphics.cinnabar.core.b3d.renderpass;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import graphics.cinnabar.api.exceptions.NotImplemented;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.b3d.buffers.CinnabarGpuBuffer;
import graphics.cinnabar.core.b3d.pipeline.CinnabarPipeline;
import graphics.cinnabar.core.b3d.texture.CinnabarGpuTexture;
import graphics.cinnabar.core.b3d.texture.CinnabarGpuTextureView;
import graphics.cinnabar.core.vk.descriptors.*;
import graphics.cinnabar.core.vk.memory.VkBuffer;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.util.ARGB;
import net.neoforged.neoforge.client.stencil.StencilTest;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK13.*;

public class CinnabarRenderPass implements RenderPass {
    
    private final CinnabarDevice device;
    private final VkCommandBuffer commandBuffer;
    private final MemoryStack memoryStack;
    
    private final int renderWidth;
    private final int renderHeight;
    
    @Nullable
    private CinnabarPipeline boundPipeline;
    
    protected final Map<String, GpuBufferSlice> uniforms = new Object2ObjectOpenHashMap<>();
    protected final Map<String, GpuTextureView> samplers = new Object2ObjectOpenHashMap<>();
    protected final Set<String> dirtyUniforms = new ObjectArraySet<>();
    protected final Set<String> dirtySamplers = new ObjectArraySet<>();
    
    private final VkWriteDescriptorSet.Buffer descriptorWrites;
    private final VkDescriptorBufferInfo.Buffer descriptorBufferInfos;
    private final VkDescriptorImageInfo.Buffer descriptorSamplerInfos;
    
    public CinnabarRenderPass(CinnabarDevice device, VkCommandBuffer commandBuffer, MemoryStack memoryStack, Supplier<String> debugGroup, CinnabarGpuTextureView colorAttachment, OptionalInt colorClear, @Nullable CinnabarGpuTextureView depthAttachment, OptionalDouble depthClear) {
        this.device = device;
        this.commandBuffer = commandBuffer;
        this.memoryStack = memoryStack;
        memoryStack.push();
        descriptorWrites = VkWriteDescriptorSet.calloc(16, memoryStack);
        descriptorBufferInfos = VkDescriptorBufferInfo.calloc(16, memoryStack);
        descriptorSamplerInfos = VkDescriptorImageInfo.calloc(16, memoryStack);
        
        pushDebugGroup(debugGroup);
        
        try (final var stack = memoryStack.push()) {
            final var renderingInfo = VkRenderingInfo.calloc(stack).sType$Default();
            
            
            final var renderArea = renderingInfo.renderArea();
            final var renderOffset = renderArea.offset();
            final var renderExtent = renderArea.extent();
            renderOffset.set(0, 0);
            renderWidth = colorAttachment.getWidth(0);
            renderHeight = colorAttachment.getHeight(0);
            renderExtent.set(renderWidth, renderHeight);
            renderingInfo.layerCount(1);
            renderingInfo.viewMask(0);
            
            
            {
                final var colorAttachmentInfo = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default();
                renderingInfo.pColorAttachments(colorAttachmentInfo);
                colorAttachmentInfo.imageView(colorAttachment.imageViewHandle);
                // TODO: transition to color attachment optimal and then back again
                //       invalid to use as a source while in a renderpass drawing to the same texture
                colorAttachmentInfo.imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                colorAttachmentInfo.resolveMode(VK_RESOLVE_MODE_NONE);
                if (colorClear.isPresent()) {
                    colorAttachmentInfo.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
                    int clearRGBA = colorClear.getAsInt();
                    final var vkClearColor = VkClearColorValue.calloc(stack);
                    vkClearColor.float32(0, ARGB.redFloat(clearRGBA));
                    vkClearColor.float32(1, ARGB.greenFloat(clearRGBA));
                    vkClearColor.float32(2, ARGB.blueFloat(clearRGBA));
                    vkClearColor.float32(3, ARGB.alphaFloat(clearRGBA));
                    colorAttachmentInfo.clearValue().color(vkClearColor);
                } else {
                    colorAttachmentInfo.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
                }
            }
            
            if (depthAttachment != null) {
                // TODO: transition to depth stencil attachment optimal and then back again
                //       invalid to use as a source while in a renderpass drawing to the same texture
                
                final var depthAttachmentInfo = VkRenderingAttachmentInfo.calloc(stack).sType$Default();
                renderingInfo.pDepthAttachment(depthAttachmentInfo);
                depthAttachmentInfo.imageView(depthAttachment.imageViewHandle);
                depthAttachmentInfo.imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                depthAttachmentInfo.resolveMode(VK_RESOLVE_MODE_NONE);
                if (depthClear.isPresent()) {
                    depthAttachmentInfo.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
                    final var vkDepthClear = VkClearDepthStencilValue.calloc(stack);
                    vkDepthClear.depth((float) depthClear.getAsDouble());
                    depthAttachmentInfo.clearValue().depthStencil(vkDepthClear);
                } else {
                    depthAttachmentInfo.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
                }
                
                if (depthAttachment.texture().getFormat().hasStencilAspect()) {
                    final var stencilAttachmentInfo = VkRenderingAttachmentInfo.calloc(stack).sType$Default();
                    renderingInfo.pStencilAttachment(stencilAttachmentInfo);
                    stencilAttachmentInfo.imageView(depthAttachment.imageViewHandle);
                    stencilAttachmentInfo.imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                    stencilAttachmentInfo.resolveMode(VK_RESOLVE_MODE_NONE);
                    // TODO: extend createRenderPass to add a stencil clear value
                    stencilAttachmentInfo.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
                }
            }
            
            vkCmdBeginRendering(commandBuffer, renderingInfo);
            
            final var viewport = VkViewport.calloc(1, stack);
            viewport.x(0);
            viewport.y(0);
            viewport.width(renderWidth);
            // flips the view, consistent with OpenGL
            viewport.height(renderHeight);
            viewport.minDepth(0.0f);
            viewport.maxDepth(1.0f);
            vkCmdSetViewport(commandBuffer, 0, viewport);
            
            disableScissor();
        }
        
    }
    
    @Override
    public void close() {
        popDebugGroup();
        unsetPipeline();
        memoryStack.pop();
        vkCmdEndRendering(commandBuffer);
    }
    
    @Override
    public void pushDebugGroup(Supplier<String> supplier) {
        if (device.debugMarkerEnabled()) {
            try (final var stack = memoryStack.push()) {
                final var markerInfo = VkDebugMarkerMarkerInfoEXT.calloc(stack).sType$Default();
                markerInfo.pMarkerName(stack.UTF8(supplier.get()));
                markerInfo.color(0, 1);
                markerInfo.color(1, 1);
                markerInfo.color(2, 1);
                markerInfo.color(3, 1);
                EXTDebugMarker.vkCmdDebugMarkerBeginEXT(commandBuffer, markerInfo);
            }
        }
    }
    
    @Override
    public void popDebugGroup() {
        if (device.debugMarkerEnabled()) {
            EXTDebugMarker.vkCmdDebugMarkerEndEXT(commandBuffer);
        }
    }
    
    @Override
    public void setPipeline(RenderPipeline pipeline) {
        unsetPipeline();
        
        // dirty all uniforms, any pipeline layout change will have screwed with this, which is guaranteed if it's a different pipeline
        if (this.boundPipeline == null || this.boundPipeline.info != pipeline) {
            this.dirtyUniforms.addAll(this.uniforms.keySet());
            this.dirtySamplers.addAll(this.samplers.keySet());
        }
        
        boundPipeline = device.getPipeline(pipeline);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, boundPipeline.pipelineHandle);
        
    }
    
    public void unsetPipeline() {
        this.boundPipeline = null;
    }
    
    @Override
    public void bindSampler(String uniformName, @Nullable GpuTextureView texture) {
        this.samplers.put(uniformName, texture);
        this.dirtySamplers.add(uniformName);
    }
    
    @Override
    public void setUniform(String uboName, GpuBuffer buffer) {
        setUniform(uboName, buffer.slice());
    }
    
    @Override
    public void setUniform(String uboName, GpuBufferSlice bufferSlice) {
        uniforms.put(uboName, bufferSlice);
        dirtyUniforms.add(uboName);
    }
    
    @Override
    public void enableScissor(int x, int y, int width, int height) {
        try (final var stack = memoryStack.push()) {
            final var scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(x, y);
            scissor.extent().set(width, height);
            vkCmdSetScissor(commandBuffer, 0, scissor);
        }
    }
    
    @Override
    public void disableScissor() {
        // reset scissor, really
        enableScissor(0, 0, renderWidth, renderHeight);
    }
    
    @Override
    public void enableStencilTest(StencilTest stencilTest) {
        throw new NotImplemented("Stencil cannot be set dynamically in Cinnabar");
    }
    
    @Override
    public void disableStencilTest() {
        throw new NotImplemented("Stencil cannot be set dynamically in Cinnabar");
    }
    
    @Override
    public void setVertexBuffer(int bindingIndex, GpuBuffer buffer) {
        final var cinnabarBuffer = (CinnabarGpuBuffer) buffer;
        final var backingSlice = cinnabarBuffer.backingSlice();
        // TODO: binding offset
        vkCmdBindVertexBuffers(commandBuffer, bindingIndex, new long[]{backingSlice.buffer().handle}, new long[]{backingSlice.range.offset()});
    }
    
    @Override
    public void setIndexBuffer(GpuBuffer buffer, VertexFormat.IndexType indexType) {
        final var cinnabarBuffer = (CinnabarGpuBuffer) buffer;
        final var backingSlice = cinnabarBuffer.backingSlice();
        
        // TODO: index buffer offset (the draw command can also do this though)
        vkCmdBindIndexBuffer(commandBuffer, backingSlice.buffer().handle, backingSlice.range.offset(), switch (indexType) {
            case SHORT -> VK_INDEX_TYPE_UINT16;
            case INT -> VK_INDEX_TYPE_UINT32;
        });
    }
    
    private void updateUniforms() {
        assert boundPipeline != null;
        
        if (dirtyUniforms.isEmpty() && dirtySamplers.isEmpty()) {
            return;
        }
        
        for (int i = 0; i < boundPipeline.shaderSet.descriptorSetLayouts.size(); i++) {
            for (DescriptorSetBinding binding : boundPipeline.shaderSet.descriptorSetLayouts.get(i).bindings) {
                if (binding instanceof UBOBinding(String name, int bindingPoint, int size)) {
                    if (!dirtyUniforms.remove(name)) {
                        continue;
                    }
                    @Nullable
                    final var bufferSlice = uniforms.get(name);
                    if (bufferSlice == null) {
                        throw new IllegalStateException("Any set sampler must be found in the shader");
                    }
                    
                    CinnabarGpuBuffer cinnabarBuffer = (CinnabarGpuBuffer) bufferSlice.buffer();
                    cinnabarBuffer.accessed();
                    final var backingSlice = cinnabarBuffer.backingSlice();
                    descriptorBufferInfos.buffer(backingSlice.buffer().handle);
                    descriptorBufferInfos.offset(backingSlice.range.offset() + bufferSlice.offset());
                    descriptorBufferInfos.range(bufferSlice.length());
                    
                    descriptorWrites.sType$Default();
                    descriptorWrites.dstBinding(bindingPoint);
                    descriptorWrites.descriptorCount(1);
                    descriptorWrites.descriptorType(binding.type());
                    descriptorWrites.pBufferInfo(descriptorBufferInfos);
                    descriptorWrites.pImageInfo(null);
                    
                    descriptorWrites.position(descriptorWrites.position() + 1);
                    descriptorBufferInfos.position(descriptorBufferInfos.position() + 1);
                } else if (binding instanceof SSBOBinding(String name, int bindingPoint, int arrayStride)) {
                    if (!dirtyUniforms.remove(name)) {
                        continue;
                    }
                    @Nullable
                    final var bufferSlice = uniforms.get(name);
                    if (bufferSlice == null) {
                        throw new IllegalStateException("Any set sampler must be found in the shader");
                    }
                    
                    CinnabarGpuBuffer cinnabarBuffer = (CinnabarGpuBuffer) bufferSlice.buffer();
                    cinnabarBuffer.accessed();
                    final var backingSlice = cinnabarBuffer.backingSlice();
                    descriptorBufferInfos.buffer(backingSlice.buffer().handle);
                    descriptorBufferInfos.offset(backingSlice.range.offset() + bufferSlice.offset());
                    descriptorBufferInfos.range(bufferSlice.length());
                    
                    descriptorWrites.sType$Default();
                    descriptorWrites.dstBinding(bindingPoint);
                    descriptorWrites.descriptorCount(1);
                    descriptorWrites.descriptorType(binding.type());
                    descriptorWrites.pBufferInfo(descriptorBufferInfos);
                    descriptorWrites.pImageInfo(null);
                    
                    descriptorWrites.position(descriptorWrites.position() + 1);
                    descriptorBufferInfos.position(descriptorBufferInfos.position() + 1);
                } else if (binding instanceof TexelBufferBinding(String name, int bindingPoint, int format)) {
                    if (!dirtyUniforms.remove(name)) {
                        continue;
                    }
                    @Nullable
                    final var texelBuffer = uniforms.get(name);
                    if (texelBuffer == null) {
                        throw new IllegalStateException("Any set texelBuffer must be found in the shader");
                    }
                    
                    CinnabarGpuBuffer cinnabarBuffer = (CinnabarGpuBuffer) texelBuffer.buffer();
                    cinnabarBuffer.accessed();
                    
                    try (final var stack = memoryStack.push()) {
                        final var bufferViewCreateInfo = VkBufferViewCreateInfo.calloc().sType$Default();
                        final var backingSlice = cinnabarBuffer.backingSlice();
                        bufferViewCreateInfo.buffer(backingSlice.buffer().handle);
                        bufferViewCreateInfo.format(format);
                        bufferViewCreateInfo.offset(backingSlice.range.offset() + texelBuffer.offset());
                        bufferViewCreateInfo.range(texelBuffer.length());
                        
                        final var ret = stack.longs(0);
                        vkCreateBufferView(device.vkDevice, bufferViewCreateInfo, null, ret);
                        final var viewHandle = ret.get(0);
                        device.destroyEndOfFrame(() -> {
                            vkDestroyBufferView(device.vkDevice, viewHandle, null);
                        });
                        
                        descriptorWrites.sType$Default();
                        descriptorWrites.dstBinding(bindingPoint);
                        descriptorWrites.descriptorCount(1);
                        descriptorWrites.descriptorType(binding.type());
                        descriptorWrites.pBufferInfo(null);
                        descriptorWrites.pImageInfo(null);
                        descriptorWrites.pTexelBufferView(ret);
                    }
                    
                    descriptorWrites.position(descriptorWrites.position() + 1);
                    descriptorBufferInfos.position(descriptorBufferInfos.position() + 1);
                } else if (binding instanceof SamplerBinding(String name, int bindingPoint)) {
                    if (!dirtySamplers.remove(name)) {
                        continue;
                    }
                    @Nullable
                    final var textureView = samplers.get(name);
                    if (textureView == null) {
                        throw new IllegalStateException("Any set sampler must be found in the shader");
                    }
                    
                    descriptorSamplerInfos.sampler(((CinnabarGpuTexture) textureView.texture()).sampler().vulkanHandle);
                    descriptorSamplerInfos.imageView(((CinnabarGpuTextureView) textureView).imageViewHandle);
                    descriptorSamplerInfos.imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                    
                    descriptorWrites.sType$Default();
                    descriptorWrites.dstBinding(bindingPoint);
                    descriptorWrites.descriptorCount(1);
                    descriptorWrites.descriptorType(binding.type());
                    descriptorWrites.pBufferInfo(null);
                    descriptorWrites.pImageInfo(descriptorSamplerInfos);
                    
                    descriptorWrites.position(descriptorWrites.position() + 1);
                    descriptorSamplerInfos.position(descriptorSamplerInfos.position() + 1);
                }
            }
            if (descriptorWrites.position() != 0) {
                descriptorWrites.limit(descriptorWrites.position());
                descriptorWrites.position(0);
                vkCmdPushDescriptorSetKHR(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, boundPipeline.shaderSet.pipelineLayout, i, descriptorWrites);
            }
            descriptorWrites.position(0);
            descriptorBufferInfos.position(0);
            descriptorSamplerInfos.position(0);
            descriptorWrites.limit(descriptorWrites.capacity());
            descriptorBufferInfos.limit(descriptorBufferInfos.capacity());
            descriptorSamplerInfos.limit(descriptorSamplerInfos.capacity());
        }
        
        dirtySamplers.clear();
        dirtyUniforms.clear();
    }
    
    @Override
    public void drawIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount) {
        updateUniforms();
        vkCmdDrawIndexed(commandBuffer, indexCount, instanceCount, firstIndex, baseVertex, 0);
    }
    
    
    @Override
    public <T> void drawMultipleIndexed(Collection<Draw<T>> draws, @Nullable GpuBuffer indexBuffer, @Nullable VertexFormat.IndexType indexType, Collection<String> dynamicUniforms, T userData) {
        assert boundPipeline != null;
        
        if (dynamicUniforms.size() == 1) {
            final var dynamicUniformName = dynamicUniforms.stream().findFirst().get();
            boolean isSSBO = false;
            for (int i = 0; i < boundPipeline.shaderSet.descriptorSetLayouts.size(); i++) {
                for (DescriptorSetBinding binding : boundPipeline.shaderSet.descriptorSetLayouts.get(i).bindings) {
                    if (binding instanceof SSBOBinding(String name, int bindingPoint, int size) && name.equals(dynamicUniformName)) {
                        isSSBO = true;
                        break;
                    }
                }
                if (isSSBO) {
                    break;
                }
            }
            if (isSSBO) {
                fastDrawMultipleIndexed(draws, indexBuffer, indexType, dynamicUniforms, userData);
            }
            return;
        }
        
        fallbackDrawMultipleIndexed(draws, indexBuffer, indexType, dynamicUniforms, userData);
    }
    
    public <T> void fallbackDrawMultipleIndexed(Collection<Draw<T>> draws, @Nullable GpuBuffer indexBuffer, @Nullable VertexFormat.IndexType indexType, Collection<String> dynamicUniforms, T userData) {
        @Nullable
        GpuBuffer lastIndexBuffer = null;
        @Nullable
        VertexFormat.IndexType lastIndexType = null;
        for (Draw<T> draw : draws) {
            @Nullable
            final var indexTypeToUse = draw.indexType() == null ? indexType : draw.indexType();
            @Nullable
            final var indexBufferToUse = draw.indexBuffer() == null ? indexBuffer : draw.indexBuffer();
            assert indexTypeToUse != null;
            assert indexBufferToUse != null;
            if (indexBufferToUse != lastIndexBuffer || indexTypeToUse != lastIndexType) {
                lastIndexBuffer = indexBufferToUse;
                lastIndexType = indexTypeToUse;
                setIndexBuffer(indexBufferToUse, indexTypeToUse);
            }
            if (draw.uniformUploaderConsumer() instanceof BiConsumer<?, ?> consumer) {
                //noinspection unchecked
                ((BiConsumer<T, UniformUploader>) consumer).accept(userData, this::setUniform);
            }
            setVertexBuffer(draw.slot(), draw.vertexBuffer());
            updateUniforms();
            vkCmdDrawIndexed(commandBuffer, draw.indexCount(), 1, draw.firstIndex(), 0, 0);
        }
    }
    
    public <T> void fastDrawMultipleIndexed(Collection<Draw<T>> draws, @Nullable GpuBuffer indexBuffer, @Nullable VertexFormat.IndexType indexType, Collection<String> dynamicUniforms, T userData) {
        // the single largest cost is in the descriptor set updates, if those can be avoided (they can) thats a large win (and a step toward multidraw)
        if (dynamicUniforms.size() != 1) {
            // for memory reasons, its assumed there is only a single dynamic uniform in this path, this is currently always the case
            throw new IllegalStateException();
        }
        final var dynamicUniformName = dynamicUniforms.stream().findFirst().get();
        assert !draws.isEmpty();
        assert boundPipeline != null;
        
        boolean isSSBO = false;
        int ssboArrayStride = 0;
        for (int i = 0; i < boundPipeline.shaderSet.descriptorSetLayouts.size(); i++) {
            for (DescriptorSetBinding binding : boundPipeline.shaderSet.descriptorSetLayouts.get(i).bindings) {
                if (binding instanceof SSBOBinding(String name, int bindingPoint, int arrayStride) && name.equals(dynamicUniformName)) {
                    isSSBO = true;
                    ssboArrayStride = arrayStride;
                    break;
                }
            }
            if (isSSBO) {
                break;
            }
        }
        
        // TODO: dont assume that i can use the uniform uploader multiple times, someone will assume Vanilla B3D's behavior with it, which uses it exactly once
        //       fallbackDrawMultipleIndexed will use the uploader again
        
        final var orderedDraws = new ReferenceArrayList<>(draws);
        final var orderedDynamicUniformValues = new ReferenceArrayList<GpuBufferSlice>();
        UniformUploader uploader = (name, bufferSlice) -> {
            if (!name.equals(dynamicUniformName)) {
                throw new IllegalArgumentException();
            }
            orderedDynamicUniformValues.add(bufferSlice);
        };
        for (int i = 0; i < orderedDraws.size(); i++) {
            final var draw = orderedDraws.get(i);
            if (draw.uniformUploaderConsumer() != null) {
                draw.uniformUploaderConsumer().accept(userData, uploader);
                if (orderedDynamicUniformValues.get(i) == null) {
                    throw new IllegalStateException();
                }
            }
        }
        
        var canBatchVertexBuffer = true;
        var canBatchIndexBuffer = true;
        var canBatchIndexType = true;
        final var vertexSize = boundPipeline.info.getVertexFormat().getVertexSize();
        
        // if all draws dont share the same GPU buffer, i cant compact the descriptor updates
        // they all also must be at a multiple of the array stride
        final var expectedDynamicUniformGpuBuffer = orderedDynamicUniformValues.getFirst().buffer();
        final var firstDraw = orderedDraws.getFirst();
        final var expectedVertexBuffer = ((CinnabarGpuBuffer) firstDraw.vertexBuffer()).backingSlice().buffer();
        @Nullable
        final var expectedIndexB3DBuffer = firstDraw.indexBuffer() == null ? indexBuffer : firstDraw.indexBuffer();
        @Nullable
        final var expectedIndexCinnabarBuffer = expectedIndexB3DBuffer == null ? null : ((CinnabarGpuBuffer) expectedIndexB3DBuffer).backingSlice().buffer();
        @Nullable
        final var expectedIndexType = firstDraw.indexType() == null ? indexType : firstDraw.indexType();
        for (int i = 0; i < orderedDynamicUniformValues.size(); i++) {
            final var orderedDynamicUniformValue = orderedDynamicUniformValues.get(i);
            final var draw = orderedDraws.get(i);
            boolean canBatch;
            canBatch = expectedDynamicUniformGpuBuffer == orderedDynamicUniformValue.buffer();
            canBatch = canBatch && orderedDynamicUniformValue.offset() % ssboArrayStride == 0;
            canBatch = canBatch && orderedDynamicUniformValue.length() == ssboArrayStride;
            if (!canBatch) {
                // some check failed, can't batch them
                fallbackDrawMultipleIndexed(draws, indexBuffer, indexType, dynamicUniforms, userData);
                return;
            }
            
            final var vertexBufferBacking = ((CinnabarGpuBuffer) draw.vertexBuffer()).backingSlice();
            canBatchVertexBuffer = canBatchVertexBuffer && expectedVertexBuffer == vertexBufferBacking.buffer();
            canBatchVertexBuffer = canBatchVertexBuffer && vertexBufferBacking.range.offset() % vertexSize == 0;
            @Nullable
            final var indexB3DBuffer = draw.indexBuffer() == null ? indexBuffer : draw.indexBuffer();
            @Nullable
            final var indexCinnabarBuffer = indexB3DBuffer == null ? null : ((CinnabarGpuBuffer) indexB3DBuffer).backingSlice().buffer();
            canBatchIndexBuffer = canBatchIndexBuffer && expectedIndexCinnabarBuffer == indexCinnabarBuffer;
            canBatchIndexType = canBatchIndexType && expectedIndexType == (draw.indexType() == null ? indexType : draw.indexType());
        }
        
        setUniform(dynamicUniformName, expectedDynamicUniformGpuBuffer.slice());
        updateUniforms();
        
        if (canBatchVertexBuffer && canBatchIndexBuffer && canBatchIndexType) {
            // everything is batchable, MULTIDRAW TIME!
            
            vkCmdBindVertexBuffers(commandBuffer, 0, new long[]{expectedVertexBuffer.handle}, new long[]{0});
            assert expectedIndexCinnabarBuffer != null;
            assert indexType != null;
            vkCmdBindIndexBuffer(commandBuffer, expectedIndexCinnabarBuffer.handle, 0, switch (expectedIndexType) {
                case SHORT -> VK_INDEX_TYPE_UINT16;
                case INT -> VK_INDEX_TYPE_UINT32;
            });
            
            final var drawsCPUBuffer = new VkBuffer(device, (long) orderedDraws.size() * VkDrawIndexedIndirectCommand.SIZEOF, VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, device.hostMemoryType);
            device.destroyEndOfFrame(drawsCPUBuffer);
            final var drawCommands = VkDrawIndexedIndirectCommand.create(drawsCPUBuffer.allocationInfo.pMappedData(), orderedDraws.size());
            for (int i = 0; i < orderedDraws.size(); i++) {
                final var draw = orderedDraws.get(i);
                drawCommands.position(i);
                drawCommands.indexCount(draw.indexCount());
                drawCommands.instanceCount(1);
                @Nullable
                final var indexB3DBuffer = draw.indexBuffer() == null ? indexBuffer : draw.indexBuffer();
                assert indexB3DBuffer != null;
                drawCommands.firstIndex((int) (((CinnabarGpuBuffer)indexB3DBuffer).backingSlice().range.offset() / expectedIndexType.bytes));
                drawCommands.vertexOffset((int) (((CinnabarGpuBuffer)draw.vertexBuffer()).backingSlice().range.offset() / vertexSize));
                drawCommands.firstInstance(orderedDynamicUniformValues.get(i).offset() / ssboArrayStride);
            }
            
            vkCmdDrawIndexedIndirect(commandBuffer, drawsCPUBuffer.handle, 0, orderedDraws.size(), VkDrawIndexedIndirectCommand.SIZEOF);
        } else {
            @Nullable
            GpuBuffer lastIndexBuffer = null;
            @Nullable
            VertexFormat.IndexType lastIndexType = null;
            for (int i = 0; i < orderedDraws.size(); i++) {
                final var draw = orderedDraws.get(i);
                @Nullable
                final var indexTypeToUse = draw.indexType() == null ? indexType : draw.indexType();
                @Nullable
                final var indexBufferToUse = draw.indexBuffer() == null ? indexBuffer : draw.indexBuffer();
                assert indexTypeToUse != null;
                assert indexBufferToUse != null;
                if (indexBufferToUse != lastIndexBuffer || indexTypeToUse != lastIndexType) {
                    lastIndexBuffer = indexBufferToUse;
                    lastIndexType = indexTypeToUse;
                    setIndexBuffer(indexBufferToUse, indexTypeToUse);
                }
                setVertexBuffer(draw.slot(), draw.vertexBuffer());
                final var arrayIndex = orderedDynamicUniformValues.get(i).offset() / ssboArrayStride;
                vkCmdDrawIndexed(commandBuffer, draw.indexCount(), 1, draw.firstIndex(), 0, arrayIndex);
            }
        }
    }
    
    @Override
    public void draw(int first, int count) {
        updateUniforms();
        vkCmdDraw(commandBuffer, count, 1, first, 0);
    }
}
