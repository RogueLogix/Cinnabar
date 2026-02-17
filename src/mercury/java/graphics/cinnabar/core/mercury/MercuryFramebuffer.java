package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgFramebuffer;
import it.unimi.dsi.fastutil.longs.LongIntImmutablePair;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

public class MercuryFramebuffer extends MercuryObject<HgFramebuffer> implements HgFramebuffer {
    private final long handle;
    private final int width;
    private final int height;
    
    public MercuryFramebuffer(MercuryDevice device, CreateInfo createInfo) {
        super(device);
        try (final var stack = memoryStack().push()) {
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
            final var firstColorAttachment = createInfo.colorAttachments().getFirst();
            final var firstColorAttachmentImage = firstColorAttachment.image();
            assert firstColorAttachment.levelCount() == 1;
            width = firstColorAttachmentImage.width() >> firstColorAttachment.baseMipLevel();
            height = firstColorAttachmentImage.height() >> firstColorAttachment.baseMipLevel();
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
    
    @Override
    protected LongIntImmutablePair handleAndType() {
        return new LongIntImmutablePair(handle, VK_OBJECT_TYPE_FRAMEBUFFER);
    }
}
