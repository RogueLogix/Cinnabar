package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgBuffer;
import graphics.cinnabar.api.hg.enums.HgFormat;
import it.unimi.dsi.fastutil.longs.LongIntImmutablePair;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

public class MercuryBufferView extends MercuryObject<HgBuffer.View> implements HgBuffer.View {
    private final long handle;
    
    public MercuryBufferView(MercuryBuffer buffer, HgFormat format, long offset, long size) {
        super(buffer.device);
        try (final var stack = memoryStack().push()) {
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
    
    @Override
    protected LongIntImmutablePair handleAndType() {
        return new LongIntImmutablePair(handle, VK_OBJECT_TYPE_BUFFER_VIEW);
    }
}
