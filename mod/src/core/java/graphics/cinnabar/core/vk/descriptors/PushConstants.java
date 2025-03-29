package graphics.cinnabar.core.vk.descriptors;

import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.Internal;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.memory.PointerWrapper;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.api.memory.MemoryRange;
import graphics.cinnabar.core.vk.shaders.ShaderSet;
import it.unimi.dsi.fastutil.objects.ReferenceImmutableList;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

@API
public class PushConstants {
    
    private final ShaderSet shaderSet;
    
    private final int fullRangeStageFlags;
    private final MemoryRange fullRange;
    
    @API
    public final List<UBOMember> members;
    
    @Internal
    public PushConstants(ShaderSet shaderSet, List<UBOMember> members, List<MemoryRange> vertexRanges, List<MemoryRange> fragmentRanges) {
        this.shaderSet = shaderSet;
        fullRangeStageFlags = (vertexRanges.isEmpty() ? 0 : VK_SHADER_STAGE_VERTEX_BIT) | (fragmentRanges.isEmpty() ? 0 : VK_SHADER_STAGE_FRAGMENT_BIT);
        long startOfRange = Long.MAX_VALUE;
        long endOfRange = 0;
        for (MemoryRange range : vertexRanges) {
            startOfRange = Math.min(startOfRange, range.offset());
            endOfRange = Math.max(endOfRange, range.offset() + range.size());
        }
        for (MemoryRange range : fragmentRanges) {
            startOfRange = Math.min(startOfRange, range.offset());
            endOfRange = Math.max(endOfRange, range.offset() + range.size());
        }
        fullRange = new MemoryRange(startOfRange, endOfRange);
        this.members = new ReferenceImmutableList<>(members);
    }
    
    public Pusher createPusher() {
        return new Pusher();
    }
    
    @API
    public class Pusher implements Destroyable, UBOWriter {
        
        private final PointerWrapper data;
        private boolean dirty = false;
        @Nullable
        private VkCommandBuffer lastCommandBuffer;
        
        private Pusher() {
            data = PointerWrapper.alloc(fullRange.size());
        }
        
        @Override
        public void destroy() {
            data.free();
        }
        
        @ThreadSafety.Any
        public void push(VkCommandBuffer commandBuffer) {
            push(commandBuffer, false);
        }
        
        @ThreadSafety.Any
        public void push(VkCommandBuffer commandBuffer, boolean force) {
            if (!dirty && commandBuffer == lastCommandBuffer) {
                return;
            }
            dirty = false;
            lastCommandBuffer = commandBuffer;
            nvkCmdPushConstants(commandBuffer, shaderSet.pipelineLayout(), fullRangeStageFlags, (int) fullRange.offset(), (int) fullRange.size(), data.pointer());
        }
        
        @ThreadSafety.Any
        public void writeAll(PointerWrapper newData) {
            dirty = true;
            newData.copyTo(data);
        }
        
        @Override
        @ThreadSafety.Any
        public void write(UBOMember member, PointerWrapper newData) {
            dirty = true;
            newData.copyTo(data, member.offset - fullRange.offset(), member.size);
        }
    }
}
