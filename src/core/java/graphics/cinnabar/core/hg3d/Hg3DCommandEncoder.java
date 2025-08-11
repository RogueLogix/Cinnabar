package graphics.cinnabar.core.hg3d;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import graphics.cinnabar.api.hg.*;
import graphics.cinnabar.api.hg.enums.HgUniformType;
import graphics.cinnabar.api.memory.MagicMemorySizes;
import graphics.cinnabar.api.memory.PointerWrapper;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.api.util.Pair;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCString;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class Hg3DCommandEncoder implements CommandEncoder, Hg3DObject, Destroyable {
    
    private static final long UPLOAD_BUFFER_SIZE = 4 * MagicMemorySizes.MiB;
    private final Hg3DGpuDevice device;
    private final HgQueue queue;
    private final HgCommandBuffer.Pool commandPool;
    private final ReferenceArrayList<HgCommandBuffer> commandBuffers = new ReferenceArrayList<>();
    @Nullable
    private HgCommandBuffer earlyCommandBuffer;
    @Nullable
    private HgCommandBuffer mainCommandBuffer;
    @Nullable
    private HgCommandBuffer presentCommandBuffer;
    @Nullable
    private HgSemaphore presentSemaphore;
    @Nullable
    private HgBuffer uploadBuffer;
    private long uploadBufferAllocated = 0;
    private final ReferenceArrayList<HgBuffer> availableUploadBuffers = new ReferenceArrayList<>();
    @Nullable
    private Hg3DRenderPass continuedRenderPass;
    
    Hg3DCommandEncoder(Hg3DGpuDevice device) {
        this.device = device;
        queue = device.hgDevice().queue(HgQueue.Type.GRAPHICS);
        commandPool = queue.createCommandPool(true, true);
    }
    
    @Override
    public void destroy() {
        commandPool.destroy();
        if (uploadBuffer != null) {
            uploadBuffer.unmap();
            uploadBuffer.destroy();
        }
    }
    
    @Override
    public Hg3DGpuDevice device() {
        return device;
    }
    
    void endRenderPass() {
        if (continuedRenderPass != null) {
            continuedRenderPass.end();
        }
        continuedRenderPass = null;
    }
    
    HgCommandBuffer earlyCommandBuffer() {
        if (earlyCommandBuffer == null) {
            earlyCommandBuffer = commandPool.allocate().begin();
            earlyCommandBuffer.barrier();
            commandBuffers.add(earlyCommandBuffer);
        }
        return earlyCommandBuffer;
    }
    
    HgCommandBuffer mainCommandBuffer() {
        endRenderPass();
        earlyCommandBuffer();
        if (mainCommandBuffer == null) {
            mainCommandBuffer = commandPool.allocate().begin();
            mainCommandBuffer.barrier();
            commandBuffers.add(mainCommandBuffer);
        }
        return mainCommandBuffer;
    }
    
    void endCommandBuffers() {
        endRenderPass();
        if (earlyCommandBuffer != null) {
            earlyCommandBuffer.barrier();
            earlyCommandBuffer.end();
        }
        earlyCommandBuffer = null;
        if (mainCommandBuffer != null) {
            mainCommandBuffer.barrier();
            mainCommandBuffer.end();
        }
        mainCommandBuffer = null;
    }
    
    void setupTexture(Hg3DGpuTexture texture) {
        earlyCommandBuffer().initImages(List.of(texture.image()));
    }
    
    public void insertCommandBuffer(HgCommandBuffer commandBuffer) {
        endCommandBuffers();
        commandBuffers.add(commandBuffer);
    }
    
    HgBuffer.Slice uploadBufferSlice(long size) {
        if (size > UPLOAD_BUFFER_SIZE) {
            final var tempBuffer = device.hgDevice().createBuffer(HgBuffer.MemoryType.MAPPABLE, size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            device.destroyEndOfFrameAsync(tempBuffer);
            return tempBuffer.slice();
        }
        if (uploadBuffer == null || uploadBuffer.size() < uploadBufferAllocated + size) {
            if (uploadBuffer != null) {
                uploadBuffer.unmap();
                final var currentUploadBuffer = uploadBuffer;
                device.destroyEndOfFrame(() -> this.availableUploadBuffers.add(currentUploadBuffer));
                uploadBuffer = null;
            }
            if (availableUploadBuffers.isEmpty()) {
                uploadBuffer = device.hgDevice().createBuffer(HgBuffer.MemoryType.MAPPABLE, UPLOAD_BUFFER_SIZE, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            } else {
                uploadBuffer = availableUploadBuffers.pop();
            }
            uploadBuffer.map();
            uploadBufferAllocated = 0;
        }
        final var slice = uploadBuffer.slice(uploadBufferAllocated, size);
        uploadBufferAllocated += size;
        return slice;
    }
    
    public void flush() {
        endCommandBuffers();
        try (final var submission = queue.submit()) {
            submission.execute(commandBuffers);
            if (presentCommandBuffer != null) {
                assert presentSemaphore != null;
                submission.wait(presentSemaphore, 0, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR);
                submission.execute(presentCommandBuffer);
                submission.signal(presentSemaphore, 0, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR);
                commandBuffers.add(presentCommandBuffer);
                presentCommandBuffer = null;
                presentSemaphore = null;
            }
        }
        device.destroyEndOfFrame(commandBuffers);
        commandBuffers.clear();
    }
    
    public void resetUploadBuffer() {
        if (availableUploadBuffers.size() > 3) {
            while (availableUploadBuffers.size() > 2) {
                availableUploadBuffers.pop().destroy();
            }
        }
        if (uploadBuffer == null) {
            return;
        }
        final var currentUploadBuffer = uploadBuffer;
        device.destroyEndOfFrame(() -> this.availableUploadBuffers.add(currentUploadBuffer));
        uploadBuffer = null;
        uploadBufferAllocated = 0;
    }
    
    @Override
    public RenderPass createRenderPass(Supplier<String> debugGroup, GpuTextureView colorTexture, OptionalInt clearColor) {
        return createRenderPass(debugGroup, colorTexture, clearColor, null, OptionalDouble.empty());
    }
    
    @Override
    public Hg3DRenderPass createRenderPass(Supplier<String> debugGroup, GpuTextureView colorTexture, OptionalInt clearColor, @Nullable GpuTextureView depthTexture, OptionalDouble clearDepth) {
        final var renderpass = device.getRenderPass(Hg3DConst.format(colorTexture.texture().getFormat()), depthTexture != null ? Hg3DConst.format(depthTexture.texture().getFormat()) : null);
        @Nullable final var depthView = depthTexture != null ? ((Hg3DGpuTextureView) depthTexture).imageView() : null;
        final var framebuffer = ((Hg3DGpuTextureView) colorTexture).getFramebuffer(renderpass, depthView);
        final Hg3DRenderPass renderPass;
        if (continuedRenderPass != null && continuedRenderPass.renderPass == renderpass && continuedRenderPass.framebuffer == framebuffer) {
            // all render params are the same, except maybe clear, which i currently ignore, continue the pass
            renderPass = continuedRenderPass;
        } else {
            renderPass = new Hg3DRenderPass(renderpass, framebuffer);
        }
        if (continuedRenderPass != renderPass) {
            endRenderPass();
        }
        renderPass.begin(debugGroup);
        continuedRenderPass = renderPass;
        return continuedRenderPass;
    }
    
    @Override
    public void clearColorTexture(GpuTexture texture, int color) {
        assert texture instanceof Hg3DGpuTexture;
        mainCommandBuffer().clearColorImage(((Hg3DGpuTexture) texture).image().resourceRange(), color);
    }
    
    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, int clearColor, GpuTexture depthTexture, double clearDepth) {
        assert colorTexture instanceof Hg3DGpuTexture;
        assert depthTexture instanceof Hg3DGpuTexture;
        mainCommandBuffer().clearColorImage(((Hg3DGpuTexture) colorTexture).image().resourceRange(), clearColor);
        mainCommandBuffer().clearDepthStencilImage(((Hg3DGpuTexture) depthTexture).image().resourceRange(), clearDepth, -1);
    }
    
    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, int clearColor, GpuTexture depthTexture, double clearDepth, int scissorX, int scissorY, int scissorWidth, int scissorHeight) {
        try (
                // creating a renderpass needs texture views, but im only passed textures... amazing
                final var colorTextureView = device.createTextureView(colorTexture);
                final var depthTextureView = device.createTextureView(depthTexture);
                final var renderpass = createRenderPass(() -> "ClearColorDepthTextures", colorTextureView, OptionalInt.empty(), depthTextureView, OptionalDouble.empty())
        ) {
            renderpass.clearAttachments(clearColor, clearDepth, scissorX, scissorY, scissorWidth, scissorHeight);
        }
    }
    
    @Override
    public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
        assert depthTexture instanceof Hg3DGpuTexture;
        mainCommandBuffer().clearDepthStencilImage(((Hg3DGpuTexture) depthTexture).image().resourceRange(), clearDepth, -1);
    }
    
    public void clearStencilTexture(GpuTexture texture, int value) {
        assert texture instanceof Hg3DGpuTexture;
        mainCommandBuffer().clearDepthStencilImage(((Hg3DGpuTexture) texture).image().resourceRange(), -1, value);
    }
    
    @Override
    public void writeToBuffer(GpuBufferSlice slice, ByteBuffer buffer) {
        final var tempBuffer = uploadBufferSlice(buffer.remaining());
        final var ptr = tempBuffer.map();
        LibCString.nmemcpy(ptr.pointer(), MemoryUtil.memAddress(buffer), ptr.size());
        tempBuffer.unmap();
        final var earlyUpload = !((Hg3DGpuBuffer) slice.buffer()).usedThisFrame();
        final var cb = earlyUpload ? earlyCommandBuffer() : mainCommandBuffer();
        if (!earlyUpload) {
            cb.barrier();
        }
        final var dstSlice = ((Hg3DGpuBuffer) slice.buffer()).hgSlice().slice(slice.offset(), slice.length());
        cb.copyBufferToBuffer(tempBuffer, dstSlice);
        if (!earlyUpload) {
            cb.barrier();
        }
    }
    
    @Override
    public GpuBuffer.MappedView mapBuffer(GpuBuffer buffer, boolean read, boolean write) {
        return mapBuffer(buffer.slice(), read, write);
    }
    
    @Override
    public GpuBuffer.MappedView mapBuffer(GpuBufferSlice slice, boolean read, boolean write) {
        assert slice.buffer() instanceof Hg3DGpuBuffer;
        final var hgBufferSlice = ((Hg3DGpuBuffer) slice.buffer()).hgSlice().slice(slice.offset(), slice.length());
        return new GpuBuffer.MappedView() {
            final PointerWrapper ptr = hgBufferSlice.map();
            final ByteBuffer data = ptr.byteBuffer();
            
            @Override
            public ByteBuffer data() {
                return data;
            }
            
            @Override
            public void close() {
                hgBufferSlice.unmap();
            }
        };
    }
    
    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        final var cb = mainCommandBuffer();
        cb.barrier();
        final var srcSlice = ((Hg3DGpuBuffer) source.buffer()).hgSlice().slice(source.offset(), source.length());
        final var dstSlice = ((Hg3DGpuBuffer) target.buffer()).hgSlice().slice(target.offset(), target.length());
        cb.copyBufferToBuffer(srcSlice, dstSlice);
        cb.barrier();
    }
    
    @Override
    public void writeToTexture(GpuTexture texture, NativeImage image) {
        writeToTexture(texture, image, 0, 0, 0, 0, image.getWidth(), image.getHeight(), 0, 0);
    }
    
    @Override
    public void writeToTexture(GpuTexture texture, NativeImage image, int mipLevel, int depthOrLayer, int x, int y, int width, int height, int sourceX, int sourceY) {
        final var bufferSize = image.getWidth() * image.getHeight() * texture.getFormat().pixelSize();
        final var texelSize = texture.getFormat().pixelSize();
        final int skipTexels = sourceX + sourceY * image.getWidth();
        final long skipBytes = (long) skipTexels * texelSize;
        writeToTexture(texture, image.getPointer() + skipBytes, bufferSize - skipBytes, mipLevel, depthOrLayer, x, y, width, height, image.getWidth(), image.getHeight());
    }
    
    // 25w32a
    public void writeToTexture(GpuTexture texture, ByteBuffer buffer, NativeImage.Format format, int mipLevel, int depthOrLayer, int x, int y, int width, int height) {
        writeToTexture(texture, MemoryUtil.memAddress(buffer), buffer.remaining() * (long) MagicMemorySizes.INT_BYTE_SIZE, mipLevel, depthOrLayer, x, y, width, height, width, height);
    }
    
    // 1.21.8
    public void writeToTexture(GpuTexture texture, IntBuffer buffer, NativeImage.Format format, int mipLevel, int depthOrLayer, int x, int y, int width, int height) {
        writeToTexture(texture, MemoryUtil.memAddress(buffer), buffer.remaining() * (long) MagicMemorySizes.INT_BYTE_SIZE, mipLevel, depthOrLayer, x, y, width, height, width, height);
    }
    
    private void writeToTexture(GpuTexture texture, long buffer, long bufferSize, int mipLevel, int depthOrLayer, int x, int y, int width, int height, int srcWidth, int srcHeight) {
        final var tempBuffer = uploadBufferSlice(bufferSize);
        final var ptr = tempBuffer.map();
        LibCString.nmemcpy(ptr.pointer(), buffer, ptr.size());
        tempBuffer.unmap();
        
        final var hg3dTexture = (Hg3DGpuTexture) texture;
        final var hgImage = hg3dTexture.image();
        
        final var cb = mainCommandBuffer();
        cb.barrier();
        // B3D its currently always "layer", not depth
        cb.copyBufferToImage(tempBuffer.image(srcWidth, srcHeight), hgImage.transferRange(new Vector3i(x, y, 0), new Vector3i(width, height, 1), depthOrLayer, 1, mipLevel));
        cb.barrier();
    }
    
    @Override
    public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, int offset, Runnable task, int mipLevel) {
        this.copyTextureToBuffer(texture, buffer, offset, task, mipLevel, 0, 0, texture.getWidth(mipLevel), texture.getHeight(mipLevel));
    }
    
    @Override
    public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, int offset, Runnable task, int mipLevel, int x, int y, int width, int height) {
        
        final var hgImage = ((Hg3DGpuTexture) texture).image();
        final var hgBuffer = ((Hg3DGpuBuffer) buffer).hgSlice();
        
        final var cb = mainCommandBuffer();
        cb.barrier();
        cb.copyImageToBuffer(hgImage.transferRange(new Vector3i(x, y, 0), new Vector3i(width, height, 1), 0, 1, mipLevel), hgBuffer.imageSlice(offset, buffer.size() - offset, width, height));
        cb.barrier();
        device.destroyEndOfFrame(task::run);
    }
    
    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int x, int y, int sourceX, int sourceY, int width, int height) {
        final var srcHgImage = ((Hg3DGpuTexture) source).image();
        final var dstHgImage = ((Hg3DGpuTexture) destination).image();
        final var cb = mainCommandBuffer();
        cb.barrier();
        cb.copyImageToImage(srcHgImage.transferRange(new Vector3i(x, y, 0), new Vector3i(width, height, 1), 0, 1, mipLevel), dstHgImage.transferRange(new Vector3i(x, y, 0), new Vector3i(width, height, 1), 0, 1, mipLevel));
        cb.barrier();
    }
    
    @Override
    public void presentTexture(GpuTextureView texture) {
        final var window = (Hg3DWindow) Minecraft.getInstance().getWindow();
        final var swapchain = window.swapchain();
        
        final var commandBuffer = commandPool.allocate();
        commandBuffer.begin();
        commandBuffer.blitToSwapchain(((Hg3DGpuTextureView) texture).imageView(), swapchain);
        commandBuffer.end();
        presentCommandBuffer = commandBuffer;
        presentSemaphore = swapchain.currentSemaphore();
        flush();
    }
    
    @Override
    public GpuFence createFence() {
        flush();
        return new GpuFence() {
            
            final HgSemaphore semaphore = device.hgDevice().createSemaphore(0);
            
            {
                queue.submit(HgQueue.Item.signal(semaphore, 1, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR));
            }
            
            @Override
            public void close() {
                
            }
            
            @Override
            public boolean awaitCompletion(long timeout) {
                if (timeout > 0) {
                    semaphore.waitValue(1, timeout);
                }
                return semaphore.value() == 1;
            }
        };
    }
    
    private class Hg3DRenderPass implements RenderPass {
        
        protected final Map<String, @Nullable GpuBufferSlice> uniforms = new Object2ObjectOpenHashMap<>();
        protected final Map<String, @Nullable GpuTextureView> samplers = new Object2ObjectOpenHashMap<>();
        private final HgRenderPass renderPass;
        private final HgFramebuffer framebuffer;
        private final HgCommandBuffer commandBuffer;
        protected boolean uniformsDirty = false;
        @Nullable
        private Hg3DRenderPipeline boundPipeline;
        @Nullable
        private HgGraphicsPipeline hgPipeline;
        
        private Hg3DRenderPass(HgRenderPass renderPass, HgFramebuffer framebuffer) {
            this.renderPass = renderPass;
            this.framebuffer = framebuffer;
            commandBuffer = mainCommandBuffer();
            
            commandBuffer.barrier();
            commandBuffer.beginRenderPass(renderPass, framebuffer);
        }
        
        private void begin(Supplier<String> debugGroup) {
            pushDebugGroup(debugGroup);
            
            uniforms.clear();
            samplers.clear();
            uniformsDirty = true;
            
            boundPipeline = null;
            hgPipeline = null;
            
            commandBuffer.setViewport(0, 0, 0, framebuffer.width(), framebuffer.height());
            commandBuffer.setScissor(0, 0, 0, framebuffer.width(), framebuffer.height());
        }
        
        @Override
        public void close() {
            popDebugGroup();
        }
        
        private void end() {
            commandBuffer.endRenderPass();
            commandBuffer.barrier();
        }
        
        @Override
        public void pushDebugGroup(Supplier<String> name) {
            commandBuffer.pushDebugGroup(name.get());
        }
        
        @Override
        public void popDebugGroup() {
            commandBuffer.popDebugGroup();
        }
        
        @Override
        public void setPipeline(RenderPipeline pipeline) {
            boundPipeline = device.getPipeline(pipeline);
            hgPipeline = boundPipeline.getPipeline(renderPass);
            commandBuffer.bindPipeline(hgPipeline);
            uniformsDirty = true;
        }
        
        @Override
        public void bindSampler(String uniformName, @Nullable GpuTextureView texture) {
            this.samplers.put(uniformName, texture);
            uniformsDirty = true;
        }
        
        @Override
        public void setUniform(String uboName, GpuBuffer buffer) {
            setUniform(uboName, buffer.slice());
        }
        
        @Override
        public void setUniform(String uboName, GpuBufferSlice bufferSlice) {
            uniforms.put(uboName, bufferSlice);
            uniformsDirty = true;
        }
        
        public void setViewport(int x, int y, int width, int height) {
            commandBuffer.setViewport(0, x, y, width, height);
        }
        
        @Override
        public void enableScissor(int x, int y, int width, int height) {
            {
                // in case of invalid inputs, which happens
                int x1 = Math.clamp(Math.min(x, x + width), 0, framebuffer.width());
                int x2 = Math.clamp(Math.max(x, x + width), 0, framebuffer.width());
                int y1 = Math.clamp(Math.min(y, y + height), 0, framebuffer.height());
                int y2 = Math.clamp(Math.max(y, y + height), 0, framebuffer.height());
                x = x1;
                y = y1;
                width = x2 - x;
                height = y2 - y;
            }
            commandBuffer.setScissor(0, x, y, width, height);
        }
        
        @Override
        public void disableScissor() {
            commandBuffer.setScissor(0, 0, 0, framebuffer.width(), framebuffer.height());
        }
        
        @Override
        public void setVertexBuffer(int index, GpuBuffer buffer) {
            assert buffer instanceof Hg3DGpuBuffer;
            commandBuffer.bindVertexBuffer(index, ((Hg3DGpuBuffer) buffer).hgSlice());
        }
        
        @Override
        public void setIndexBuffer(GpuBuffer indexBuffer, VertexFormat.IndexType indexType) {
            assert indexBuffer instanceof Hg3DGpuBuffer;
            commandBuffer.bindIndexBuffer(((Hg3DGpuBuffer) indexBuffer).hgSlice(), indexType == VertexFormat.IndexType.INT ? VK_INDEX_TYPE_UINT32 : VK_INDEX_TYPE_UINT16);
            
        }
        
        @Override
        public void drawIndexed(int vertexOffset, int firstIndex, int indexCount, int instanceCount) {
            updateUniforms();
            commandBuffer.drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, 0);
        }
        
        @Override
        public <T> void drawMultipleIndexed(Collection<Draw<T>> draws, @Nullable GpuBuffer indexBuffer, @Nullable VertexFormat.IndexType indexType, Collection<String> dynamicUniforms, T userData) {
            
            
            if (dynamicUniforms.size() == 1) {
                final var dynamicUniformName = dynamicUniforms.stream().findFirst().get();
                for (final var binding : hgPipeline.layout().uniformSetLayout(0).bindings()) {
                    if (binding.type() == HgUniformType.STORAGE_BUFFER && binding.name().equals(dynamicUniformName)) {
                        fastDrawMultipleIndexed(draws, indexBuffer, indexType, dynamicUniforms, userData);
                        return;
                    }
                }
            }
            
            @Nullable
            GpuBuffer lastIndexBuffer = null;
            @Nullable
            VertexFormat.IndexType lastIndexType = null;
            for (Draw<T> draw : draws) {
                @Nullable final var indexTypeToUse = draw.indexType() == null ? indexType : draw.indexType();
                @Nullable final var indexBufferToUse = draw.indexBuffer() == null ? indexBuffer : draw.indexBuffer();
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
                commandBuffer.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), 0, 0);
            }
        }
        
        @Override
        public void draw(int firstVertex, int vertexCount) {
            updateUniforms();
            commandBuffer.draw(vertexCount, 1, firstVertex, 0);
        }
        
        private void updateUniforms() {
            if (!uniformsDirty) {
                return;
            }
            assert hgPipeline != null;
            assert boundPipeline != null;
            
            final var pipelineLayout = hgPipeline.layout();
            @Nullable final var uniformSetLayout = pipelineLayout.uniformSetLayout(0);
            assert uniformSetLayout != null;
            
            final var writes = new ReferenceArrayList<HgUniformSet.Write>();
            for (final var binding : uniformSetLayout.bindings()) {
                switch (binding.type()) {
                    case COMBINED_IMAGE_SAMPLER -> {
                        @Nullable final var imageView = (Hg3DGpuTextureView) samplers.get(binding.name());
                        assert imageView != null;
                        writes.add(new HgUniformSet.Write.Image(binding, 0, List.of(new Pair<>(imageView.imageView(), imageView.texture().sampler()))));
                    }
                    case UNIFORM_TEXEL_BUFFER -> {
                        @Nullable final var slice = uniforms.get(binding.name());
                        assert slice != null;
                        final var hgBuffer = ((Hg3DGpuBuffer) slice.buffer()).hgSlice();
                        final var view = hgBuffer.view(boundPipeline.texelBufferFormat(binding.name()), slice.offset(), slice.length());
                        writes.add(new HgUniformSet.Write.BufferView(binding, 0, List.of(view)));
                        device.destroyEndOfFrameAsync(view);
                    }
                    case UNIFORM_BUFFER, STORAGE_BUFFER -> {
                        @Nullable final var slice = uniforms.get(binding.name());
                        assert slice != null;
                        writes.add(new HgUniformSet.Write.Buffer(binding, 0, List.of(((Hg3DGpuBuffer) slice.buffer()).hgSlice().slice(slice.offset(), slice.length()))));
                    }
                }
            }
            
            final var uniformSet = boundPipeline.uniformPool().allocate();
            device.destroyEndOfFrame(uniformSet);
            uniformSet.write(writes);
            commandBuffer.bindUniformSet(0, uniformSet);
        }
        
        public void clearAttachments(int clearColor, double clearDepth, int x, int y, int width, int height) {
            commandBuffer.clearAttachments(IntList.of(clearColor), clearDepth, x, y, width, height);
        }
        
        public <T> void fastDrawMultipleIndexed(Collection<Draw<T>> draws, @Nullable GpuBuffer indexBuffer, @Nullable VertexFormat.IndexType indexType, Collection<String> dynamicUniforms, T userData) {
            // the single largest cost is in the descriptor set updates, if those can be avoided (they can) that's a large win (and a step toward multidraw)
            if (dynamicUniforms.size() != 1) {
                // for memory reasons, its assumed there is only a single dynamic uniform in this path, this is currently always the case
                throw new IllegalStateException();
            }
            if (!(draws instanceof RandomAccess) || !(draws instanceof List<?>)) {
                throw new IllegalStateException();
            }
            final var dynamicUniformName = dynamicUniforms.stream().findFirst().get();
            assert !draws.isEmpty();
            assert boundPipeline != null;
            assert hgPipeline != null;
            
            int ssboArrayStride = 0;
            for (final var binding : hgPipeline.layout().uniformSetLayout(0).bindings()) {
                if (binding.type() == HgUniformType.STORAGE_BUFFER && binding.name().equals(dynamicUniformName)) {
                    ssboArrayStride = Math.toIntExact(binding.size());
                    break;
                }
            }
            
            final var drawCount = draws.size();
            final var orderedDraws = (List<Draw<T>>) draws;
            final var orderedDynamicUniformValues = new ReferenceArrayList<GpuBufferSlice>();
            UniformUploader uploader = (name, bufferSlice) -> {
                if (!name.equals(dynamicUniformName)) {
                    throw new IllegalArgumentException();
                }
                orderedDynamicUniformValues.add(bufferSlice);
            };
            for (int i = 0; i < drawCount; i++) {
                final var draw = orderedDraws.get(i);
                if (draw.uniformUploaderConsumer() != null) {
                    draw.uniformUploaderConsumer().accept(userData, uploader);
                    if (orderedDynamicUniformValues.get(i) == null) {
                        throw new IllegalStateException();
                    }
                }
            }
            
            try (final var stack = MemoryStack.stackPush()) {
                var canBatchDynamicUniform = true;
                var canBatchVertexBuffer = true;
                var canBatchIndexBuffer = true;
                var canBatchIndexType = true;
                final var vertexSize = boundPipeline.info().getVertexFormat().getVertexSize();
                final var drawCommands = VkDrawIndexedIndirectCommand.calloc(drawCount, stack);
                
                // if all draws don't share the same GPU buffer, i cant compact the descriptor updates
                // they all also must be at a multiple of the array stride
                final var expectedDynamicUniformGpuBuffer = orderedDynamicUniformValues.getFirst().buffer();
                final var firstDraw = orderedDraws.getFirst();
                final var expectedVertexBuffer = ((Hg3DGpuBuffer) firstDraw.vertexBuffer()).hgSlice().buffer();
                @Nullable final var expectedIndexB3DBuffer = firstDraw.indexBuffer() == null ? indexBuffer : firstDraw.indexBuffer();
                @Nullable final var expectedIndexCinnabarBuffer = expectedIndexB3DBuffer == null ? null : ((Hg3DGpuBuffer) expectedIndexB3DBuffer).hgSlice().buffer();
                @Nullable final var expectedIndexType = firstDraw.indexType() == null ? indexType : firstDraw.indexType();
                for (int i = 0; i < drawCount; i++) {
                    drawCommands.position(i);
                    final var orderedDynamicUniformValue = orderedDynamicUniformValues.get(i);
                    canBatchDynamicUniform = canBatchDynamicUniform && expectedDynamicUniformGpuBuffer == orderedDynamicUniformValue.buffer();
                    final var firstInstance = orderedDynamicUniformValue.offset() / ssboArrayStride;
                    final var firstInstanceRemainder = orderedDynamicUniformValue.offset() % ssboArrayStride;
                    canBatchDynamicUniform = canBatchDynamicUniform && firstInstanceRemainder == 0;
                    canBatchDynamicUniform = canBatchDynamicUniform && orderedDynamicUniformValue.length() == ssboArrayStride;
                    drawCommands.firstInstance(firstInstance);
                    
                    final var draw = orderedDraws.get(i);
                    
                    final var vertexBufferBacking = ((Hg3DGpuBuffer) draw.vertexBuffer()).hgSlice();
                    canBatchVertexBuffer = canBatchVertexBuffer && expectedVertexBuffer == vertexBufferBacking.buffer();
                    final var vertexOffset = vertexBufferBacking.offset() / vertexSize;
                    final var vertexOffsetRemainder = vertexBufferBacking.offset() % vertexSize;
                    canBatchVertexBuffer = canBatchVertexBuffer && vertexOffsetRemainder == 0;
                    @Nullable final var indexB3DBuffer = draw.indexBuffer() == null ? indexBuffer : draw.indexBuffer();
                    @Nullable final var indexBufferSlice = indexB3DBuffer == null ? null : ((Hg3DGpuBuffer) indexB3DBuffer).hgSlice();
                    @Nullable final var indexCinnabarBuffer = indexBufferSlice == null ? null : indexBufferSlice.buffer();
                    canBatchIndexBuffer = canBatchIndexBuffer && expectedIndexCinnabarBuffer == indexCinnabarBuffer;
                    canBatchIndexType = canBatchIndexType && expectedIndexType == (draw.indexType() == null ? indexType : draw.indexType());
                    
                    drawCommands.indexCount(draw.indexCount());
                    drawCommands.instanceCount(1);
                    assert expectedIndexType != null;
                    assert indexBufferSlice != null;
                    drawCommands.firstIndex((int) (indexBufferSlice.offset() / expectedIndexType.bytes) + draw.firstIndex());
                    drawCommands.vertexOffset((int) vertexOffset);
                    drawCommands.firstIndex(draw.firstIndex());
                    drawCommands.vertexOffset(0);
                }
                
                if (canBatchDynamicUniform) {
                    setUniform(dynamicUniformName, expectedDynamicUniformGpuBuffer.slice());
                    updateUniforms();
                    if (canBatchVertexBuffer && canBatchIndexBuffer && canBatchIndexType) {
                        // everything is batchable, MULTIDRAW TIME!
                        
                        commandBuffer.bindVertexBuffer(0, expectedVertexBuffer.slice());
                        assert expectedIndexCinnabarBuffer != null;
                        assert indexType != null;
                        commandBuffer.bindIndexBuffer(expectedIndexCinnabarBuffer.slice(), switch (expectedIndexType) {
                            case SHORT -> VK_INDEX_TYPE_UINT16;
                            case INT -> VK_INDEX_TYPE_UINT32;
                        });
                        
                        final var drawsCPUBuffer = device.hgDevice().createBuffer(HgBuffer.MemoryType.MAPPABLE, (long) drawCount * VkDrawIndexedIndirectCommand.SIZEOF, VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
                        device.destroyEndOfFrameAsync(drawsCPUBuffer);
                        final var ptr = drawsCPUBuffer.map();
                        LibCString.nmemcpy(ptr.pointer(), drawCommands.address(0), (long) drawCount * VkDrawIndexedIndirectCommand.SIZEOF);
                        commandBuffer.drawIndirect(drawsCPUBuffer.slice());
                    } else if (canBatchVertexBuffer) {
                        commandBuffer.bindVertexBuffer(0, expectedVertexBuffer.slice());
                        
                        @Nullable
                        GpuBuffer lastIndexBuffer = null;
                        @Nullable
                        VertexFormat.IndexType lastIndexType = null;
                        for (int i = 0; i < drawCount; i++) {
                            final var draw = orderedDraws.get(i);
                            @Nullable final var indexTypeToUse = draw.indexType() == null ? indexType : draw.indexType();
                            @Nullable final var indexBufferToUse = draw.indexBuffer() == null ? indexBuffer : draw.indexBuffer();
                            assert indexTypeToUse != null;
                            assert indexBufferToUse != null;
                            if (indexBufferToUse != lastIndexBuffer || indexTypeToUse != lastIndexType) {
                                lastIndexBuffer = indexBufferToUse;
                                lastIndexType = indexTypeToUse;
                                setIndexBuffer(indexBufferToUse, indexTypeToUse);
                            }
                            final var arrayIndex = orderedDynamicUniformValues.get(i).offset() / ssboArrayStride;
                            commandBuffer.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), 0, arrayIndex);
                        }
                    } else if (canBatchIndexBuffer && canBatchIndexType) {
                        assert expectedIndexCinnabarBuffer != null;
                        assert indexType != null;
                        commandBuffer.bindIndexBuffer(expectedIndexCinnabarBuffer.slice(), switch (expectedIndexType) {
                            case SHORT -> VK_INDEX_TYPE_UINT16;
                            case INT -> VK_INDEX_TYPE_UINT32;
                        });
                        
                        for (int i = 0; i < drawCount; i++) {
                            final var draw = orderedDraws.get(i);
                            setVertexBuffer(draw.slot(), draw.vertexBuffer());
                            final var arrayIndex = orderedDynamicUniformValues.get(i).offset() / ssboArrayStride;
                            @Nullable final var indexB3DBuffer = draw.indexBuffer() == null ? indexBuffer : draw.indexBuffer();
                            assert indexB3DBuffer != null;
//                            final var firstIndex = (int) (((CinnabarGpuBuffer) indexB3DBuffer).backingSlice().range.offset() / expectedIndexType.bytes);
                            commandBuffer.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), 0, arrayIndex);
                        }
                    } else {
                        @Nullable
                        GpuBuffer lastIndexBuffer = null;
                        @Nullable
                        VertexFormat.IndexType lastIndexType = null;
                        for (int i = 0; i < drawCount; i++) {
                            final var draw = orderedDraws.get(i);
                            @Nullable final var indexTypeToUse = draw.indexType() == null ? indexType : draw.indexType();
                            @Nullable final var indexBufferToUse = draw.indexBuffer() == null ? indexBuffer : draw.indexBuffer();
                            assert indexTypeToUse != null;
                            assert indexBufferToUse != null;
                            if (indexBufferToUse != lastIndexBuffer || indexTypeToUse != lastIndexType) {
                                lastIndexBuffer = indexBufferToUse;
                                lastIndexType = indexTypeToUse;
                                setIndexBuffer(indexBufferToUse, indexTypeToUse);
                            }
                            setVertexBuffer(draw.slot(), draw.vertexBuffer());
                            final var arrayIndex = orderedDynamicUniformValues.get(i).offset() / ssboArrayStride;
                            commandBuffer.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), 0, arrayIndex);
                        }
                    }
                } else {
                    @Nullable
                    GpuBuffer lastIndexBuffer = null;
                    @Nullable
                    VertexFormat.IndexType lastIndexType = null;
                    for (int i = 0; i < drawCount; i++) {
                        final var draw = orderedDraws.get(i);
                        final var uniformValue = orderedDynamicUniformValues.get(i);
                        @Nullable final var indexTypeToUse = draw.indexType() == null ? indexType : draw.indexType();
                        @Nullable final var indexBufferToUse = draw.indexBuffer() == null ? indexBuffer : draw.indexBuffer();
                        assert indexTypeToUse != null;
                        assert indexBufferToUse != null;
                        if (indexBufferToUse != lastIndexBuffer || indexTypeToUse != lastIndexType) {
                            lastIndexBuffer = indexBufferToUse;
                            lastIndexType = indexTypeToUse;
                            setIndexBuffer(indexBufferToUse, indexTypeToUse);
                        }
                        setVertexBuffer(draw.slot(), draw.vertexBuffer());
                        setUniform(dynamicUniformName, uniformValue);
                        updateUniforms();
                        commandBuffer.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), 0, 0);
                    }
                }
            }
        }
    }
}
