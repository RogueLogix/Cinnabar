package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgBuffer;
import graphics.cinnabar.api.hg.enums.HgFormat;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;

import static org.lwjgl.vulkan.VK10.vkCreateBufferView;
import static org.lwjgl.vulkan.VK10.vkDestroyBufferView;

public class MercuryBufferView extends MercuryObject implements HgBuffer.View {
    private final long handle;
    
    public MercuryBufferView(MercuryBuffer buffer, HgFormat format, long offset, long size) {
        super(buffer.device);
        try (final var stack = MemoryStack.stackPush()) {
            final var bufferViewCreateInfo = VkBufferViewCreateInfo.calloc(stack).sType$Default();
            bufferViewCreateInfo.buffer(buffer.vkBuffer());
            bufferViewCreateInfo.format(MercuryConst.vkFormat(format));
            bufferViewCreateInfo.offset(offset);
            bufferViewCreateInfo.range(size);
            final var ret = stack.longs(0);
            vkCreateBufferView(device.vkDevice(), bufferViewCreateInfo, null, ret);
            handle = ret.get(0);
        }
    }
    
    @Override
    public void destroy() {
        vkDestroyBufferView(device.vkDevice(), handle, null);
    }
    
    public long vkBufferView() {
        return handle;
    }
}
