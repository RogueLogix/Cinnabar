package graphics.cinnabar.core.hg3d;

import com.mojang.blaze3d.buffers.GpuBuffer;
import graphics.cinnabar.api.hg.HgBuffer;
import graphics.cinnabar.lib.datastructures.SpliceableLinkedList;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import org.jetbrains.annotations.Nullable;

import static graphics.cinnabar.core.hg3d.Hg3D.*;

public class Hg3DGpuBuffer extends GpuBuffer implements Hg3DObject {
    protected final Hg3DGpuDevice device;
    private final HgBuffer.MemoryType preferredMemoryType;
    private boolean isClosed = false;
    private HgBuffer buffer;
    private long lastUsedFrame = -1;
    private final SpliceableLinkedList.Node<Hg3DGpuBuffer> node = new SpliceableLinkedList.Node<>(this);
    private boolean pendingPromotion = false;
    
    public Hg3DGpuBuffer(Hg3DGpuDevice device, int usage, int size) {
        super(usage, size);
        this.device = device;
        // i consider map for read a client storage hint
        final boolean clientStorageHint = (usage & (USAGE_HINT_CLIENT_STORAGE | USAGE_MAP_READ)) != 0;
        final boolean mappable = (usage & (USAGE_MAP_READ | USAGE_MAP_WRITE)) != 0;
        preferredMemoryType = clientStorageHint ? HgBuffer.MemoryType.MAPPABLE : mappable ? HgBuffer.MemoryType.MAPPABLE_PREF_DEVICE : HgBuffer.MemoryType.AUTO_PREF_DEVICE;
        buffer = device.hgDevice().createBuffer(preferredMemoryType, size, Hg3DConst.bufferUsageBits(usage));
    }
    
    @Override
    public boolean isClosed() {
        return isClosed;
    }
    
    @Override
    public void close() {
        if (isClosed) {
            return;
        }
        isClosed = true;
        device.destroyEndOfFrameAsync(buffer);
        Management.unlink(this);
    }
    
    @Override
    public Hg3DGpuDevice device() {
        return device;
    }
    
    public boolean usedThisFrame() {
        return lastUsedFrame == device.currentFrame();
    }
    
    public HgBuffer buffer() {
        final var currentFrame = device.currentFrame();
        if (currentFrame != lastUsedFrame) {
            Management.used(this);
            lastUsedFrame = currentFrame;
        }
        return buffer;
    }
    
    
    public static class Management {
        private static final SpliceableLinkedList<Hg3DGpuBuffer> pendingDemotionBuffers = new SpliceableLinkedList<>();
        private static final SpliceableLinkedList<Hg3DGpuBuffer> usedThisFrameBuffers = new SpliceableLinkedList<>();
        private static final ReferenceList<Hg3DGpuBuffer> pendingPromotion = new ReferenceArrayList<>();
        private static boolean runningCycle = false;
        
        private static void used(Hg3DGpuBuffer buffer) {
            if (!buffer.buffer.deviceLocal()) {
                // non-device local buffers not pending a promotion are likely specifically asked to be host buffers
                // so, just don't care about them
                if (buffer.pendingPromotion) {
                    pendingPromotion.add(buffer);
                }
                return;
            }
            assert !buffer.isClosed;
            unlink(buffer);
            usedThisFrameBuffers.add(buffer.node);
        }
        
        private static void unlink(Hg3DGpuBuffer buffer) {
            if (buffer.lastUsedFrame == buffer.device.currentFrame()) {
                usedThisFrameBuffers.remove(buffer.node);
            } else {
                pendingDemotionBuffers.remove(buffer.node);
            }
        }
        
        public static void newFrame(Hg3DGpuDevice device) {
            if (runningCycle) {
                return;
            }
            final var memoryStats = device.hgDevice().deviceLocalMemoryStats();
            // demote anything above 93.75% (15/16ths) of VMA's budget
            final var amountToDemote = memoryStats.leftLong() - ((memoryStats.rightLong() >> 4) * 15);
            final var amountPendingPromotion = pendingPromotion.stream().mapToInt(GpuBuffer::size).sum();
            if (amountToDemote < 0 && amountPendingPromotion == 0) {
                pendingDemotionBuffers.sliceEnd(usedThisFrameBuffers);
                return;
            }
            if (pendingDemotionBuffers.empty()) {
                pendingPromotion.clear();
                pendingDemotionBuffers.sliceEnd(usedThisFrameBuffers);
                return;
            }
            if (DEBUG_LOGGING) {
                HG3D_LOG.debug("Running buffer promotion/demotion cycle; currentFrame: {} demoting {}, pendingPromotion {}", device.currentFrame(), amountToDemote, amountPendingPromotion);
            }
            final var encoder = device.createCommandEncoder();
            final var cb = encoder.earlyCommandBuffer();
            cb.barrier();
            long amountDemoted = 0;
            while (amountDemoted < (amountToDemote + amountPendingPromotion)) {
                @Nullable final var toDemoteNode = pendingDemotionBuffers.removeFirst();
                if (toDemoteNode == null) {
                    // end of list, can't demote more
                    break;
                }
                final var toDemote = toDemoteNode.data;
                if (TRACE_LOGGING) {
                    HG3D_LOG.debug("Demoting buffer {}; lastUsed: {}, size: {}, preferredMemoryType: {}", toDemote.hashCode(), toDemote.lastUsedFrame, toDemote.size(), toDemote.preferredMemoryType);
                }
                amountDemoted += toDemote.size();
                final var oldBuffer = toDemote.buffer;
                final var newBuffer = device.hgDevice().createBuffer(HgBuffer.MemoryType.MAPPABLE, toDemote.size(), Hg3DConst.bufferUsageBits(toDemote.usage()));
                cb.copyBufferToBuffer(oldBuffer.slice(), newBuffer.slice());
                toDemote.buffer = newBuffer;
                device.destroyEndOfFrameAsync(oldBuffer);
                toDemote.pendingPromotion = true;
            }
            // aka, excess demoted
            final long amountToPromote = amountDemoted - amountToDemote;
            long amountPromoted = 0;
            for (int i = 0; i < pendingPromotion.size(); i++) {
                final var toPromote = pendingPromotion.get(i);
                if (amountPromoted + toPromote.size() > amountToPromote) {
                    break;
                }
                if (TRACE_LOGGING) {
                    HG3D_LOG.debug("Promoting buffer {}; size: {}, preferredMemoryType: {}", toPromote.hashCode(), toPromote.size(), toPromote.preferredMemoryType);
                }
                amountPromoted += toPromote.size();
                final var oldBuffer = toPromote.buffer;
                final var newBuffer = device.hgDevice().createBuffer(toPromote.preferredMemoryType, toPromote.size(), Hg3DConst.bufferUsageBits(toPromote.usage()));
                cb.copyBufferToBuffer(oldBuffer.slice(), newBuffer.slice());
                toPromote.buffer = newBuffer;
                device.destroyEndOfFrameAsync(oldBuffer);
                toPromote.pendingPromotion = false;
            }
            pendingPromotion.clear();
            pendingDemotionBuffers.sliceEnd(usedThisFrameBuffers);
            cb.barrier();
            runningCycle = true;
            device.endFrame();
            runningCycle = false;
            if (DEBUG_LOGGING) {
                HG3D_LOG.debug("Finished buffer promotion/demotion cycle; demoted {}, promoted {}", amountDemoted, amountPromoted);
            }
        }
    }
}
