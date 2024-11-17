package graphics.cinnabar.internal.vulkan.util;

import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.exceptions.NotImplemented;
import graphics.cinnabar.internal.util.GrowingMemoryStack;
import graphics.cinnabar.internal.vulkan.Destroyable;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.libc.LibCString;
import org.lwjgl.vulkan.*;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static graphics.cinnabar.internal.vulkan.exceptions.VkException.throwFromCode;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.VK13.vkCmdPipelineBarrier2;
import static org.lwjgl.vulkan.VK13.vkQueueSubmit2;

/**
 * Because async work may be on the same queue as main graphics/compute,
 * any signaling operations for async work must be enqueued before the respective wait operation on the main graphics queue
 * <p>
 * TODO: make thread safe
 * TODO: submit locking, to allow recording on a different thread
 */
@NonnullDefault
public class VulkanQueueHelper implements Destroyable {
    
    private final VkDevice device = CinnabarRenderer.device();
    
    private final int submitsInFlight;
    private int currentSubmit = 0;
    
    private final GrowingMemoryStack stack = new GrowingMemoryStack();
    private final MemoryStack lwjglStack = MemoryStack.create();
    
    public enum QueueType {
        MAIN_GRAPHICS,
        ASYNC_COMPUTE,
        ASYNC_TRANSFER,
    }
    
    private enum QueueState {
        WAITING,
        COMMANDS,
        SIGNALING
    }
    
    private class Builder implements Destroyable {
        private final VkQueue queue;
        private final int queueFamilyIndex;
        private QueueState queueState = QueueState.WAITING;
        
        private final List<VulkanTransientCommandBufferPool> commandPools;
        @Nullable
        private VkCommandBuffer currentCommandBuffer;
        
        private final ReferenceArrayList<VkCommandBuffer> commandBuffers = new ReferenceArrayList<>();
        private final LongArrayList submitSemaphores = new LongArrayList();
        private final LongArrayList submitSemaphoreValues = new LongArrayList();
        private final LongArrayList submitSemaphoreStages = new LongArrayList();
        
        // these structs are only 64 bytes in size, so this is 128k of memory
        private final VkSubmitInfo2.Buffer submitInfos = VkSubmitInfo2.calloc(2048);
        private final VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc();
        
        private final long implicitSyncSemaphore;
        private final AtomicLong implicitSyncSemaphoreLastWaitValue = new AtomicLong(0);
        private long implicitSyncSemaphoreValue = 0;
        private long implicitSyncSemaphoreMask = 0;
        private final long[] lastSubmitWaitValues = new long[3];
        
        private Builder(VkQueue queue, int queueFamily) {
            this.queue = queue;
            this.queueFamilyIndex = queueFamily;
            final var pools = new ReferenceArrayList<VulkanTransientCommandBufferPool>(submitsInFlight);
            this.commandPools = Collections.unmodifiableList(pools);
            for (int i = 0; i < submitsInFlight; i++) {
                pools.add(new VulkanTransientCommandBufferPool(queueFamily));
            }
            beginInfo.sType$Default();
            beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            try (final var stack = lwjglStack.push()) {
                final var createInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
                final var typeCreateInfo = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default();
                createInfo.pNext(typeCreateInfo);
                typeCreateInfo.semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE);
                typeCreateInfo.initialValue(0);
                final var handleReturn = stack.mallocLong(1);
                throwFromCode(vkCreateSemaphore(device, createInfo, null, handleReturn));
                implicitSyncSemaphore = handleReturn.get(0);
            }
        }
        
        @Override
        public void destroy() {
            vkDestroySemaphore(device, implicitSyncSemaphore, null);
            commandPools.forEach(VulkanTransientCommandBufferPool::destroy);
            submitInfos.free();
            beginInfo.free();
        }
        
        private void reset() {
            assert queueState == QueueState.WAITING;
            clientWaitImplicit(lastSubmitWaitValues[currentSubmit]);
            commandPools.get(currentSubmit).reset();
        }
        
        private void submit() {
            if (submitInfos.position() == 0) {
                return;
            }
            // for fencing the reset
            lastSubmitWaitValues[currentSubmit] = signalImplicit(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
            switchToState(QueueState.WAITING);
            // the last switchToState will have added an extra (empty) submit
            submitInfos.limit(submitInfos.position() - 1);
            submitInfos.position(0);
            throwFromCode(vkQueueSubmit2(queue, submitInfos, 0));
            submitInfos.limit(submitInfos.capacity());
            currentCommandBuffer = null;
        }
        
        public void switchToState(QueueState newState) {
            if (queueState == newState) {
                return;
            }
            if (newState != QueueState.COMMANDS) {
                finishActiveBuffer();
            }
            if (queueState == QueueState.COMMANDS) {
                // currently queueing commands, add them to the active submit
                final var cbSubmitInfos = stack.commandBufferSubmitInfo(commandBuffers.size());
                submitInfos.pCommandBufferInfos(cbSubmitInfos);
                for (int i = 0; i < commandBuffers.size(); i++) {
                    cbSubmitInfos.position(i);
                    cbSubmitInfos.sType$Default();
                    cbSubmitInfos.commandBuffer(commandBuffers.get(i));
                }
                commandBuffers.clear();
            } else {
                // currently queueing semaphore operations
                // structs are the same regardless
                final var semaphoreSubmitInfos = stack.semaphoreSubmitInfo(commandBuffers.size());
                if (queueState == QueueState.WAITING) {
                    submitInfos.pWaitSemaphoreInfos(semaphoreSubmitInfos);
                } else {
                    submitInfos.pSignalSemaphoreInfos(semaphoreSubmitInfos);
                }
                for (int i = 0; i < commandBuffers.size(); i++) {
                    semaphoreSubmitInfos.position(i);
                    semaphoreSubmitInfos.sType$Default();
                    semaphoreSubmitInfos.semaphore(submitSemaphores.getLong(i));
                    semaphoreSubmitInfos.value(submitSemaphoreValues.getLong(i));
                    semaphoreSubmitInfos.stageMask(submitSemaphoreStages.getLong(i));
                    submitInfos.pWaitSemaphoreInfos(semaphoreSubmitInfos);
                }
                submitSemaphores.clear();
                submitSemaphoreValues.clear();
                submitSemaphoreStages.clear();
            }
            if (newState.ordinal() < queueState.ordinal()) {
                // new state will require a new submit info
                submitInfos.position(submitInfos.position() + 1);
                // clear out any old memory from last frame
                LibCString.nmemset(submitInfos.address(), 0, VkSubmitInfo2.SIZEOF);
                submitInfos.sType$Default();
            }
            queueState = newState;
        }
        
        public boolean clientImplicitSignaled(long value) {
            // TODO: maybe remove this, technically this is valid for other threads to do
            if (value > implicitSyncSemaphoreValue) {
                throw new IllegalStateException("Attempting to wait for future value");
            }
            try (final var stack = MemoryStack.stackPush()) {
                final var valuePtr = stack.longs(0);
                vkGetSemaphoreCounterValue(device, implicitSyncSemaphore, valuePtr);
                return valuePtr.get(0) >= value;
            }
        }
        
        public void clientWaitImplicit(long value) {
            // TODO: maybe remove this, technically this is valid for other threads to do
            if (value > implicitSyncSemaphoreValue) {
                throw new IllegalStateException("Attempting to wait for future value");
            }
            try (final var stack = MemoryStack.stackPush()) {
                final var waitInfo = VkSemaphoreWaitInfo.calloc(stack).sType$Default();
                final var semaphorePtr = stack.longs(implicitSyncSemaphore);
                final var valuePtr = stack.longs(value);
                waitInfo.semaphoreCount(1);
                waitInfo.pSemaphores(semaphorePtr);
                waitInfo.pValues(valuePtr);
                vkWaitSemaphores(device, waitInfo, -1);
                // client wait is explicitly allowed from multiple threads
                var lastWaitValue = implicitSyncSemaphoreLastWaitValue.get();
                while (lastWaitValue < value) {
                    lastWaitValue = implicitSyncSemaphoreLastWaitValue.compareAndExchange(lastWaitValue, value);
                }
            }
        }
        
        public void waitImplicit(long value, long stageMask) {
            waitSemaphore(implicitSyncSemaphore, value, stageMask);
        }
        
        private void waitSemaphore(long semaphore, long value, long stageMask) {
            switchToState(QueueState.WAITING);
            submitSemaphores.add(semaphore);
            submitSemaphoreValues.add(value);
            submitSemaphoreStages.add(stageMask);
        }
        
        private VkCommandBuffer getTransientCommandBuffer() {
            switchToState(QueueState.COMMANDS);
            finishActiveBuffer();
            final var buffer = commandPools.get(currentSubmit).alloc();
            commandBuffers.add(buffer);
            return buffer;
        }
        
        private VkCommandBuffer getImplicitCommandBuffer() {
            if (currentCommandBuffer == null) {
                currentCommandBuffer = getTransientCommandBuffer();
                throwFromCode(vkBeginCommandBuffer(currentCommandBuffer, beginInfo));
            }
            assert queueState == QueueState.COMMANDS;
            return currentCommandBuffer;
        }
        
        public void finishActiveBuffer() {
            if (currentCommandBuffer != null) {
                assert queueState == QueueState.COMMANDS;
                throwFromCode(vkEndCommandBuffer(currentCommandBuffer));
            }
            currentCommandBuffer = null;
        }
        
        public long nextSignalImplicit(long stageMask) {
            // appends the stage mask to the next implicit signal
            implicitSyncSemaphoreMask |= stageMask;
            return implicitSyncSemaphore + 1;
        }
        
        private long signalImplicit(long stageMask) {
            implicitSyncSemaphoreValue++;
            signalSemaphore(implicitSyncSemaphore, implicitSyncSemaphoreValue, stageMask | implicitSyncSemaphoreMask);
            implicitSyncSemaphoreMask = 0;
            return implicitSyncSemaphore;
        }
        
        private void signalSemaphore(long semaphore, long value, long stageMask) {
            switchToState(QueueState.SIGNALING);
            submitSemaphores.add(semaphore);
            submitSemaphoreValues.add(value);
            submitSemaphoreStages.add(stageMask);
        }
    }
    
    private final Builder[] queueBuilders = new Builder[3];
    
    public VulkanQueueHelper(int submitsInFlight, VkQueue graphicsQueue, int graphicsQueueFamily, @Nullable VkQueue computeQueue, int computeQueueFamily, @Nullable VkQueue transferQueue, int transferQueueFamily) {
        this.submitsInFlight = submitsInFlight;
        queueBuilders[0] = new Builder(graphicsQueue, graphicsQueueFamily);
        if (computeQueue != null && computeQueue != graphicsQueue) {
            queueBuilders[1] = new Builder(computeQueue, computeQueueFamily);
        } else {
            queueBuilders[1] = queueBuilders[0];
        }
        if (transferQueue != null && transferQueue != computeQueue) {
            queueBuilders[2] = new Builder(transferQueue, transferQueueFamily);
        } else {
            queueBuilders[2] = queueBuilders[1];
        }
    }
    
    @Override
    public void destroy() {
        queueBuilders[0].destroy();
        if (queueBuilders[0] != queueBuilders[1]) {
            queueBuilders[1].destroy();
        }
        if (queueBuilders[1] != queueBuilders[2]) {
            queueBuilders[2].destroy();
        }
        stack.destroy();
    }
    
    public void submit() {
        submit(false);
    }
    
    public void submit(boolean wait) {
        if(Thread.currentThread().threadId() != 1){
            throw new IllegalStateException();
        }
        queueBuilders[0].finishActiveBuffer();
        queueBuilders[0].submit();
        if (queueBuilders[0] != queueBuilders[1]) {
            queueBuilders[1].finishActiveBuffer();
            queueBuilders[1].submit();
        }
        if (queueBuilders[1] != queueBuilders[2]) {
            queueBuilders[2].finishActiveBuffer();
            queueBuilders[2].submit();
        }
        if (!wait) {
            currentSubmit++;
            currentSubmit %= submitsInFlight;
        }
        stack.reset();
        // TODO: fence wait for previous submit at this index
        queueBuilders[0].reset();
        if (queueBuilders[0] != queueBuilders[1]) {
            queueBuilders[1].reset();
        }
        if (queueBuilders[1] != queueBuilders[2]) {
            queueBuilders[2].reset();
        }
    }
    
    public boolean clientImplicitSignaled(QueueType queueType, long value) {
        if (value == 0) {
            return true;
        }
        return queueBuilders[queueType.ordinal()].clientImplicitSignaled(value);
    }
    
    public void clientWaitImplicit(QueueType queueType, long value) {
        if (value == 0) {
            return;
        }
        queueBuilders[queueType.ordinal()].clientWaitImplicit(value);
    }
    
    public void clientSubmitAndWaitImplicit(QueueType queueType, long value) {
        if (value == 0) {
            return;
        }
        final var builder = queueBuilders[queueType.ordinal()];
        // if already signaled, early out
        // only guaranteed that everything needed to get to that value will be submitted
        if (builder.clientImplicitSignaled(value)) {
            return;
        }
        submit();
        builder.clientWaitImplicit(value);
    }
    
    public void waitImplicit(QueueType queueType, long value, long stageMask) {
        queueBuilders[queueType.ordinal()].waitImplicit(value, stageMask);
    }
    
    public void waitSemaphore(QueueType queueType, long semaphore, long value, long stageMask) {
        queueBuilders[queueType.ordinal()].waitSemaphore(semaphore, value, stageMask);
    }
    
    public VkCommandBuffer getTransientCommandBuffer(QueueType queueType) {
        return queueBuilders[queueType.ordinal()].getTransientCommandBuffer();
    }
    
    public VkCommandBuffer getImplicitCommandBuffer(QueueType queueType) {
        return queueBuilders[queueType.ordinal()].getImplicitCommandBuffer();
    }
    
    private void finishActiveCommandBuffer(QueueType queueType) {
        queueBuilders[queueType.ordinal()].finishActiveBuffer();
    }
    
    /**
     * Inserts a signal with this value in the command stream sometime between now and when the next submit happens
     * useful for CPU side fencing on buffer usage finished, ie: end of frame fencing
     * IMPL DETAIL: this will be done when the next submit is created for the given queue type, or the next implicit signal happens, this is guaranteed to happen before submit returns
     *
     * @param queueType: queue to signal from
     * @param stageMask: stage mask for this signal
     * @return timeline value to wait for this signal
     */
    public long nextSignalImplicit(QueueType queueType, long stageMask) {
        return queueBuilders[queueType.ordinal()].nextSignalImplicit(stageMask);
    }
    
    public long signalImplicit(QueueType queueType, long stageMask) {
        return queueBuilders[queueType.ordinal()].signalImplicit(stageMask);
    }
    
    public void signalSemaphore(QueueType queueType, long semaphore, long value, long stageMask) {
        queueBuilders[queueType.ordinal()].signalSemaphore(semaphore, value, stageMask);
    }
    
    public void queueBarrier(QueueType queue, long srcStageMask, long srcAccessMask, long dstStageMask, long dstAccessMask) {
        queueToQueueSync(queue, srcStageMask, srcAccessMask, queue, dstStageMask, dstAccessMask);
    }
    
    public void queueToQueueSync(QueueType srcQueue, long srcStageMask, long srcAccessMask, QueueType dstQueue, long dstStageMask, long dstAccessMask) {
        final var srcBuilder = queueBuilders[srcQueue.ordinal()];
        final var dstBuilder = queueBuilders[dstQueue.ordinal()];
        if (srcBuilder != dstBuilder) {
            // this is _not_ an ownership transfer, that's a different function
            // this is useful for re-using a buffer for something that is uploaded on another queue
            // also used by the ownership transfer function when its the same queue family
            final var signalValue = srcBuilder.signalImplicit(srcStageMask);
            dstBuilder.waitSemaphore(srcBuilder.implicitSyncSemaphore, signalValue, dstStageMask);
        } else {
            // they are actually the same queue, so just insert a barrier instead
            try (final var stack = lwjglStack.push()) {
                // TODO: buffer/image memory barriers?
                final var memoryBarrier = VkMemoryBarrier2.calloc(1, stack);
                memoryBarrier.srcStageMask(srcStageMask);
                memoryBarrier.srcAccessMask(srcAccessMask);
                memoryBarrier.dstStageMask(dstStageMask);
                memoryBarrier.dstAccessMask(dstAccessMask);
                final var depInfo = VkDependencyInfoKHR.calloc(stack).sType$Default();
                depInfo.pMemoryBarriers(memoryBarrier);
                vkCmdPipelineBarrier2(srcBuilder.getImplicitCommandBuffer(), depInfo);
            }
        }
    }
    
    
    // TODO: builder/batched for this, each semaphore signal/wait and barrier is pretty expensive to do, so being able to batch ownership transfer is a good thing
    // TODO: also, this needs to take in the buffer/image (region) that is being transferred, currently this relies on no transfer actually needing to happen
    public void ownershipTransfer(QueueType srcQueue, long srcStageMask, long srcAccessMask, QueueType dstQueue, long dstStageMask, long dstAccessMask) {
        // because there is a semaphore wait, no need for access masks
        final var srcBuilder = queueBuilders[srcQueue.ordinal()];
        final var dstBuilder = queueBuilders[dstQueue.ordinal()];
        if (srcBuilder.queueFamilyIndex == dstBuilder.queueFamilyIndex) {
            // queues are of the same family, normal semaphore sync (or barrier of actually the same queue) is good enough
            queueToQueueSync(srcQueue, srcStageMask, srcAccessMask, dstQueue, dstStageMask, dstAccessMask);
            return;
        }
        // different queue families, need to actually do the ownership transfer
        // but thats a later problem
        throw new NotImplemented();
    }
}
