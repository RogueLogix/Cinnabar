package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgFramebuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;

import static org.lwjgl.vulkan.VK10.vkCreateFramebuffer;
import static org.lwjgl.vulkan.VK10.vkDestroyFramebuffer;

public class MercuryFramebuffer extends MercuryObject implements HgFramebuffer {
    private final long handle;
    private final int width;
    private final int height;
    
    public MercuryFramebuffer(MercuryDevice device, CreateInfo createInfo) {
        super(device);
        try (final var stack = MemoryStack.stackPush()) {
            final var vkCreateInfo = VkFramebufferCreateInfo.calloc(stack).sType$Default();
            vkCreateInfo.renderPass(((MercuryRenderPass) createInfo.renderPass()).vkRenderPass());
            vkCreateInfo.attachmentCount(createInfo.colorAttachments().size() + (createInfo.depthAttachment() != null ? 1 : 0));
            final var attachments = stack.callocLong(vkCreateInfo.attachmentCount());
            for (int i = 0; i < createInfo.colorAttachments().size(); i++) {
                attachments.put(i, ((MercuryImageView) createInfo.colorAttachments().get(i)).vkImageView());
            }
            if (createInfo.depthAttachment() != null) {
                attachments.put(createInfo.colorAttachments().size(), ((MercuryImageView) createInfo.depthAttachment()).vkImageView());
            }
            vkCreateInfo.pAttachments(attachments);
            final var firstColorAttachment = createInfo.colorAttachments().getFirst().image();
            width = firstColorAttachment.width();
            height = firstColorAttachment.height();
            vkCreateInfo.width(width);
            vkCreateInfo.height(height);
            vkCreateInfo.layers(1);
            final var handlePtr = stack.longs(0);
            vkCreateFramebuffer(device.vkDevice(), vkCreateInfo, null, handlePtr);
            handle = handlePtr.get(0);
        }
    }
    
    @Override
    public void destroy() {
        vkDestroyFramebuffer(device.vkDevice(), handle, null);
    }
    
    public long vkFramebuffer() {
        return handle;
    }
    
    @Override
    public int width() {
        return width;
    }
    
    @Override
    public int height() {
        return height;
    }
}
