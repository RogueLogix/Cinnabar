package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgCommandBuffer;
import graphics.cinnabar.api.hg.HgQueue;
import graphics.cinnabar.api.hg.HgSemaphore;
import graphics.cinnabar.api.memory.GrowingMemoryStack;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;

import java.util.List;

import static org.lwjgl.vulkan.KHRSynchronization2.vkQueueSubmit2KHR;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;

public class MercuryQueue extends MercuryObject implements HgQueue {
    
    private final VkQueue vkQueue;
    private final int queueFamily;
    private final GrowingMemoryStack stack = new GrowingMemoryStack();
    
    MercuryQueue(MercuryDevice device, VkQueue vkQueue, int queueFamily) {
        super(device);
        this.vkQueue = vkQueue;
        this.queueFamily = queueFamily;
    }
    
    @Override
    public void destroy() {
        // no-op, queues are destroyed with the logical device
    }
    
    @Override
    public Submission submit() {
        return new Submission() {
            
            final List<SubmitStage> stages = new ReferenceArrayList<>();
            SubmitStage currentStage = new SubmitStage();
            
            @Override
            public void wait(HgSemaphore semaphore, long value, long stages) {
                if (!currentStage.commandBuffers.isEmpty() || !currentStage.signals.isEmpty()) {
                    this.stages.add(currentStage);
                    currentStage = new SubmitStage();
                }
                currentStage.waits.add(new SemaphoreOp((MercurySemaphore) semaphore, value, stages));
            }

            @Override
            public void execute(HgCommandBuffer commandBuffer) {
                if (!currentStage.signals.isEmpty()) {
                    stages.add(currentStage);
                    currentStage = new SubmitStage();
                }
                currentStage.commandBuffers.add((MercuryCommandBuffer) commandBuffer);
            }
            
            @Override
            public void execute(HgCommandBuffer... commandBuffers) {
                for (HgCommandBuffer commandBuffer : commandBuffers) {
                    execute(commandBuffer);
                }
            }
            
            @Override
            public void execute(List<HgCommandBuffer> commandBuffers) {
                commandBuffers.forEach(this::execute);
            }
            
            @Override
            public void signal(HgSemaphore semaphore, long value, long stages) {
                currentStage.signals.add(new SemaphoreOp((MercurySemaphore) semaphore, value, stages));
            }
            
            @Override
            public void close() {
                stages.add(currentStage);
                synchronized (MercuryQueue.this) {
                    try (final var stack = MercuryQueue.this.stack.push()) {
                        final var submits = VkSubmitInfo2.calloc(stages.size(), stack);
                        for (int i = 0; i < stages.size(); i++) {
                            final var stage = stages.get(i);
                            submits.position(i).sType$Default();
                            if (!stage.waits.isEmpty()) {
                                final var waits = VkSemaphoreSubmitInfo.calloc(stage.waits.size(), stack);
                                submits.pWaitSemaphoreInfos(waits);
                                for (int j = 0; j < stage.waits.size(); j++) {
                                    final var wait = stage.waits.get(j);
                                    waits.position(j).sType$Default();
                                    waits.semaphore(wait.semaphore.vkSemaphore());
                                    waits.value(wait.value);
                                    waits.stageMask(wait.stages);
                                }
                            }
                            if (!stage.commandBuffers.isEmpty()) {
                                final var buffers = VkCommandBufferSubmitInfo.calloc(stage.commandBuffers.size(), stack);
                                submits.pCommandBufferInfos(buffers);
                                for (int j = 0; j < stage.commandBuffers.size(); j++) {
                                    final var buffer = stage.commandBuffers.get(j);
                                    buffers.position(j).sType$Default();
                                    buffers.commandBuffer(buffer.vkCommandBuffer());
                                }
                            }
                            if (!stage.signals.isEmpty()) {
                                final var signals = VkSemaphoreSubmitInfo.calloc(stage.signals.size(), stack);
                                submits.pSignalSemaphoreInfos(signals);
                                for (int j = 0; j < stage.signals.size(); j++) {
                                    final var signal = stage.signals.get(j);
                                    signals.position(j).sType$Default();
                                    signals.semaphore(signal.semaphore.vkSemaphore());
                                    signals.value(signal.value);
                                    signals.stageMask(signal.stages);
                                }
                            }
                        }
                        submits.position(0);
                        synchronized (vkQueue) {
                            vkQueueSubmit2KHR(vkQueue, submits, VK_NULL_HANDLE);
                        }
                    }
                }
            }
            
            record SemaphoreOp(MercurySemaphore semaphore, long value, long stages) {
            }
            
            record SubmitStage(List<SemaphoreOp> waits, List<MercuryCommandBuffer> commandBuffers, List<SemaphoreOp> signals) {
                public SubmitStage() {
                    this(new ReferenceArrayList<>(), new ReferenceArrayList<>(), new ReferenceArrayList<>());
                }
            }
        };
    }
    
    @Override
    public HgCommandBuffer.Pool createCommandPool(boolean commandBufferReset, boolean oneTimeSubmit) {
        return new MercuryCommandPool(device, queueFamily, commandBufferReset, oneTimeSubmit);
    }
    
    @Override
    public boolean needsOwnershipTransfer(HgQueue otherQueue) {
        if (!(otherQueue instanceof MercuryQueue mercuryQueue)) {
            throw new ClassCastException("queue not instance of MercuryQueue");
        }
        return queueFamily != mercuryQueue.queueFamily;
    }
    
    public VkQueue vkQueue() {
        return vkQueue;
    }
}
