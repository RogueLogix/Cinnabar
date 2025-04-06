package graphics.cinnabar.core.b3d.renderpass;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexFormat;
import graphics.cinnabar.api.exceptions.NotImplemented;
import graphics.cinnabar.api.memory.MagicMemorySizes;
import graphics.cinnabar.api.memory.PointerWrapper;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.b3d.buffers.CinnabarGpuBuffer;
import graphics.cinnabar.core.b3d.pipeline.CinnabarPipeline;
import graphics.cinnabar.core.b3d.texture.CinnabarGpuTexture;
import graphics.cinnabar.core.vk.descriptors.*;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.util.ARGB;
import net.neoforged.neoforge.client.stencil.StencilTest;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    
    protected final HashMap<String, Runnable> uniformWriteFunctions = new HashMap<>();
    protected final HashMap<String, Object> uniforms = new HashMap<>();
    protected final HashMap<String, GpuTexture> samplers = new HashMap<>();
    protected final Set<String> dirtyUniforms = new HashSet<>();
    protected final Set<String> dirtySamplers = new HashSet<>();
    
    private final VkWriteDescriptorSet.Buffer descriptorWrites;
    private final VkDescriptorBufferInfo.Buffer descriptorBufferInfos;
    private final VkDescriptorImageInfo.Buffer descriptorSamplerInfos;
    private final PointerWrapper uniformTempMemory;
    @Nullable
    private PushConstants pushConstants;
    @Nullable
    private PushConstants.Pusher pusher;
    private boolean anyUBODirty = false;
    private final ReferenceArrayList<@Nullable ReferenceArrayList<UBOBinding.@Nullable Staging>> uboStagings = new ReferenceArrayList<>();
    
    public CinnabarRenderPass(CinnabarDevice device, VkCommandBuffer commandBuffer, MemoryStack memoryStack, CinnabarGpuTexture colorAttachment, OptionalInt colorClear, @Nullable CinnabarGpuTexture depthAttachment, OptionalDouble depthClear) {
        this.device = device;
        this.commandBuffer = commandBuffer;
        this.memoryStack = memoryStack;
        memoryStack.push();
        final var tempSize = 16 * MagicMemorySizes.FLOAT_BYTE_SIZE; // a mat4x4 is the largest single write
        uniformTempMemory = new PointerWrapper(memoryStack.nmalloc(tempSize), tempSize).clear();
        descriptorWrites = VkWriteDescriptorSet.calloc(16, memoryStack);
        descriptorBufferInfos = VkDescriptorBufferInfo.calloc(16, memoryStack);
        descriptorSamplerInfos = VkDescriptorImageInfo.calloc(16, memoryStack);
        
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
                
                if (depthAttachment.getFormat().hasStencilAspect()) {
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
        unsetPipeline();
        memoryStack.pop();
        vkCmdEndRendering(commandBuffer);
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
        
        pushConstants = boundPipeline.shaderSet.pushConstants();
        if (pushConstants != null) {
            pusher = pushConstants.createPusher();
            for (UBOMember member : pushConstants.members) {
                dirtyUniforms.add(member.name);
                final var isMatrix = member.type.startsWith("mat");
                final Runnable writeFunc = () -> {
                    final var uniformData = uniforms.get(member.name);
                    if (uniformData instanceof int[] array) {
                        for (int i = 0; i < array.length; i++) {
                            uniformTempMemory.putIntIdx(i, array[i]);
                        }
                    } else if (uniformData instanceof float[] array) {
                        for (int i = 0; i < array.length; i++) {
                            uniformTempMemory.putFloatIdx(i, array[i]);
                        }
                    } else if (uniformData == null && isMatrix) {
                        // identity mat4
                        uniformTempMemory.putFloatIdx(0, 1);
                        uniformTempMemory.putFloatIdx(5, 1);
                        uniformTempMemory.putFloatIdx(10, 1);
                        uniformTempMemory.putFloatIdx(15, 1);
                    }
                    // write uses the member size, not the memory size, its fine that the source is oversized
                    pusher.write(member, uniformTempMemory);
                };
                if (uniformWriteFunctions.put(member.name, writeFunc) != null) {
                    throw new IllegalStateException();
                }
            }
        }
        
        uboStagings.clear();
        final var descriptorSetLayouts = boundPipeline.shaderSet.descriptorSetLayouts;
        uboStagings.size(descriptorSetLayouts.size() + 1);
        for (int j = 0; j < descriptorSetLayouts.size(); j++) {
            DescriptorSetLayout descriptorSetLayout = descriptorSetLayouts.get(j);
            for (DescriptorSetBinding binding : descriptorSetLayout.bindings) {
                if (binding instanceof UBOBinding uboBinding) {
                    if (uboStagings.get(j) == null) {
                        if (uboStagings.set(j, new ReferenceArrayList<>()) != null) {
                            throw new IllegalStateException();
                        }
                    }
                    final var stagingsList = Objects.requireNonNull(uboStagings.get(j));
                    if (stagingsList.size() <= binding.bindingPoint()) {
                        stagingsList.size(binding.bindingPoint() + 1);
                    }
                    final var staging = uboBinding.createStaging();
                    if (stagingsList.set(binding.bindingPoint(), staging) != null) {
                        throw new IllegalStateException();
                    }
                    for (UBOMember member : uboBinding.members) {
                        dirtyUniforms.add(member.name);
                        final var isMatrix = member.type.startsWith("mat");
                        final Runnable writeFunc = () -> {
                            final var uniformData = uniforms.get(member.name);
                            if (uniformData instanceof int[] array) {
                                for (int i = 0; i < array.length; i++) {
                                    uniformTempMemory.putIntIdx(i, array[i]);
                                }
                            } else if (uniformData instanceof float[] array) {
                                for (int i = 0; i < array.length; i++) {
                                    uniformTempMemory.putFloatIdx(i, array[i]);
                                }
                            } else if (uniformData == null && isMatrix) {
                                // identity mat4
                                uniformTempMemory.putFloatIdx(0, 1);
                                uniformTempMemory.putFloatIdx(5, 1);
                                uniformTempMemory.putFloatIdx(10, 1);
                                uniformTempMemory.putFloatIdx(15, 1);
                            }
                            // write uses the member size, not the memory size, its fine that the source is oversized
                            staging.write(member, uniformTempMemory);
                            anyUBODirty = true;
                        };
                        if (uniformWriteFunctions.put(member.name, writeFunc) != null) {
                            throw new IllegalStateException();
                        }
                    }
                }
            }
        }
        
        anyUBODirty = true;
    }
    
    public void unsetPipeline() {
        this.boundPipeline = null;
        pushConstants = null;
        if (pusher != null) {
            pusher.destroy();
        }
        pusher = null;
        for (@Nullable final var uboStaging : uboStagings) {
            if (uboStaging == null) {
                continue;
            }
            for (UBOBinding.@Nullable Staging staging : uboStaging) {
                if (staging != null) {
                    staging.destroy();
                }
            }
        }
        uboStagings.clear();
        uniformWriteFunctions.clear();
    }
    
    @Override
    public void bindSampler(String uniformName, GpuTexture texture) {
        this.samplers.put(uniformName, texture);
        this.dirtySamplers.add(uniformName);
    }
    
    @Override
    public void setUniform(String uniformName, int... value) {
        this.uniforms.put(uniformName, value);
        this.dirtyUniforms.add(uniformName);
    }
    
    @Override
    public void setUniform(String uniformName, float... value) {
        this.uniforms.put(uniformName, value);
        this.dirtyUniforms.add(uniformName);
    }
    
    @Override
    public void setUniform(String uniformName, Matrix4f value) {
        this.uniforms.put(uniformName, value.get(new float[16]));
        this.dirtyUniforms.add(uniformName);
    }
    
    
    @Override
    public void enableScissor(ScissorState scissorState) {
        if (!scissorState.isEnabled()) {
            disableScissor();
            return;
        }
        enableScissor(scissorState.getX(), scissorState.getY(), scissorState.getWidth(), scissorState.getHeight());
    }
    
    @Override
    public void enableScissor(int x, int y, int width, int height) {
        // VK defines scissor as top left, GL as bottom left
//        int y = renderHeight - (lowerY + height);
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
        // TODO: binding offset
        vkCmdBindVertexBuffers(commandBuffer, bindingIndex, new long[]{(cinnabarBuffer).getBufferForRead().handle}, new long[]{0});
    }
    
    @Override
    public void setIndexBuffer(GpuBuffer buffer, VertexFormat.IndexType indexType) {
        final var cinnabarBuffer = (CinnabarGpuBuffer) buffer;
        // TODO: index buffer offset (the draw command can also do this though)
        vkCmdBindIndexBuffer(commandBuffer, cinnabarBuffer.getBufferForRead().handle, 0, switch (indexType) {
            case SHORT -> VK_INDEX_TYPE_UINT16;
            case INT -> VK_INDEX_TYPE_UINT32;
        });
    }
    
    private void setupDraw() {
        assert boundPipeline != null;
        // these aren't set explicitly for, reasons?
        // asked Dinnerbone for the reasons: because TODO
        @SuppressWarnings("ConstantValue")
        @Nullable
        Window window = Minecraft.getInstance() == null ? null : Minecraft.getInstance().getWindow();
        FogParameters fogparameters = RenderSystem.getShaderFog();
        
        setUniform("ModelViewMat", RenderSystem.getModelViewMatrix());
        setUniform("ProjMat", RenderSystem.getProjectionMatrix());
        setUniform("ColorModulator", RenderSystem.getShaderColor());
        setUniform("GlintAlpha", RenderSystem.getShaderGlintAlpha());
        setUniform("FogStart", fogparameters.start());
        setUniform("FogEnd", fogparameters.end());
        setUniform("FogColor", fogparameters.red(), fogparameters.green(), fogparameters.blue(), fogparameters.alpha());
        setUniform("FogShape", fogparameters.shape().getIndex());
        setUniform("TextureMat", RenderSystem.getTextureMatrix());
        setUniform("GameTime", RenderSystem.getShaderGameTime());
        setUniform("ModelOffset", RenderSystem.getModelOffset().x, RenderSystem.getModelOffset().y, RenderSystem.getModelOffset().z);
        //noinspection ConstantValue
        setUniform("ScreenSize", window == null ? 0.0F : window.getWidth(), window == null ? 0.0F : window.getHeight());
        setUniform("LineWidth", RenderSystem.getShaderLineWidth());
        final var shaderLights = RenderSystem.getShaderLights();
        setUniform("Light0_Direction", shaderLights[0].x, shaderLights[0].y, shaderLights[0].z);
        setUniform("Light1_Direction", shaderLights[1].x, shaderLights[1].y, shaderLights[1].z);
        
        updateUniforms();
    }
    
    private void updateUniforms() {
        assert boundPipeline != null;
        
        dirtyUniforms.forEach(dirtyUniform -> uniformWriteFunctions.getOrDefault(dirtyUniform, () -> {
        }).run());
        if (pusher != null) {
            pusher.push(commandBuffer);
        }
        if (anyUBODirty || !dirtySamplers.isEmpty()) {
            for (int i = 0; i < boundPipeline.shaderSet.descriptorSetLayouts.size(); i++) {
                if (uboStagings.size() > i) {
                    @Nullable
                    final var stagingList = uboStagings.get(i);
                    if (stagingList != null) {
                        for (final var staging : stagingList) {
                            if (!staging.isDirty()) {
                                continue;
                            }
                            
                            // the uploader automatically destroys the buffers, no need to track them here
                            final var upload = staging.upload();
                            descriptorBufferInfos.buffer(upload.buffer.handle);
                            descriptorBufferInfos.offset(0);
                            descriptorBufferInfos.range(upload.buffer.size);
                            
                            descriptorWrites.sType$Default();
                            descriptorWrites.dstBinding(staging.binding().bindingPoint());
                            descriptorWrites.descriptorCount(1);
                            descriptorWrites.descriptorType(staging.binding().type());
                            descriptorWrites.pBufferInfo(descriptorBufferInfos);
                            descriptorWrites.pImageInfo(null);
                            
                            descriptorWrites.position(descriptorWrites.position() + 1);
                            descriptorBufferInfos.position(descriptorBufferInfos.position() + 1);
                        }
                    }
                }
                for (DescriptorSetBinding binding : boundPipeline.shaderSet.descriptorSetLayouts.get(i).bindings) {
                    if (binding instanceof SamplerBinding(String name, int bindingPoint)) {
                        if (!dirtySamplers.remove(name)) {
                            continue;
                        }
                        @Nullable
                        final var texture = samplers.get(name);
                        if (texture == null) {
                            throw new IllegalStateException("Any set sampler must be found in the shader");
                        }
                        
                        descriptorSamplerInfos.sampler(((CinnabarGpuTexture) texture).sampler().vulkanHandle);
                        descriptorSamplerInfos.imageView(((CinnabarGpuTexture) texture).imageViewHandle);
                        descriptorSamplerInfos.imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                        
                        descriptorWrites.sType$Default();
                        descriptorWrites.dstBinding(bindingPoint);
                        descriptorWrites.descriptorCount(1);
                        descriptorWrites.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
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
                descriptorBufferInfos.position(0);
                descriptorSamplerInfos.position(0);
            }
        }
        
        anyUBODirty = false;
        dirtySamplers.clear();
        dirtyUniforms.clear();
    }
    
    @Override
    public void drawIndexed(int firstIndex, int indexCount) {
        setupDraw();
        vkCmdDrawIndexed(commandBuffer, indexCount, 1, firstIndex, 0, 0);
    }
    
    @Override
    public void drawMultipleIndexed(Collection<Draw> draws, @Nullable GpuBuffer indexBuffer, @Nullable VertexFormat.IndexType indexType) {
        assert boundPipeline != null;
        setupDraw();
        
        @Nullable
        GpuBuffer lastIndexBuffer = null;
        @Nullable
        VertexFormat.IndexType lastIndexType = null;
        for (Draw draw : draws) {
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
            if (draw.uniformUploaderConsumer() instanceof Consumer<UniformUploader> consumer) {
                consumer.accept(this::setUniform);
            }
            setVertexBuffer(draw.slot(), draw.vertexBuffer());
            updateUniforms();
            vkCmdDrawIndexed(commandBuffer, draw.indexCount(), 1, draw.firstIndex(), 0, 0);
        }
    }
    
    @Override
    public void draw(int first, int count) {
        setupDraw();
        vkCmdDraw(commandBuffer, count, 1, first, 0);
    }
}
