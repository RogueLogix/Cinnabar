package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgRenderPass;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAttachmentDescription2;
import org.lwjgl.vulkan.VkAttachmentReference2;
import org.lwjgl.vulkan.VkRenderPassCreateInfo2;
import org.lwjgl.vulkan.VkSubpassDescription2;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.vkCreateRenderPass2;

public class MercuryRenderPass extends MercuryObject implements HgRenderPass {
    
    private final long handle;
    private final int colorAttachmentCount;
    
    public MercuryRenderPass(MercuryDevice device, HgRenderPass.CreateInfo createInfo) {
        super(device);
        colorAttachmentCount = createInfo.colorFormats().size();
        try (final var stack = MemoryStack.stackPush()) {
            final var attachmentDescriptions = VkAttachmentDescription2.calloc(colorAttachmentCount + (createInfo.depthStencilFormat() != null ? 1 : 0), stack);
            for (int i = 0; i < colorAttachmentCount; i++) {
                attachmentDescriptions.position(i).sType$Default();
                attachmentDescriptions.format(MercuryConst.vkFormat(createInfo.colorFormats().get(i)));
                attachmentDescriptions.samples(VK_SAMPLE_COUNT_1_BIT);
                attachmentDescriptions.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
                attachmentDescriptions.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                attachmentDescriptions.initialLayout(VK_IMAGE_LAYOUT_GENERAL);
                attachmentDescriptions.finalLayout(VK_IMAGE_LAYOUT_GENERAL);
            }
            if (createInfo.depthStencilFormat() != null) {
                attachmentDescriptions.position(colorAttachmentCount).sType$Default();
                attachmentDescriptions.format(MercuryConst.vkFormat(createInfo.depthStencilFormat()));
                attachmentDescriptions.samples(VK_SAMPLE_COUNT_1_BIT);
                attachmentDescriptions.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
                attachmentDescriptions.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                attachmentDescriptions.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
                attachmentDescriptions.stencilStoreOp(VK_ATTACHMENT_STORE_OP_STORE);
                attachmentDescriptions.initialLayout(VK_IMAGE_LAYOUT_GENERAL);
                attachmentDescriptions.finalLayout(VK_IMAGE_LAYOUT_GENERAL);
            }
            attachmentDescriptions.position(0);
            
            final var subpassDescription = VkSubpassDescription2.calloc(1, stack).sType$Default();
            subpassDescription.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS);
            
            final var colorReference = VkAttachmentReference2.calloc(colorAttachmentCount, stack);
            for (int i = 0; i < colorAttachmentCount; i++) {
                colorReference.position(i).sType$Default();
                colorReference.attachment(i);
                colorReference.layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                colorReference.aspectMask(MercuryConst.vkFormat(createInfo.colorFormats().get(i)));
            }
            colorReference.position(0);
            subpassDescription.pColorAttachments(colorReference);
            subpassDescription.colorAttachmentCount(colorAttachmentCount);
            
            if (createInfo.depthStencilFormat() != null) {
                final var depthReference = VkAttachmentReference2.calloc(stack).sType$Default();
                depthReference.attachment(colorAttachmentCount);
                depthReference.layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                depthReference.aspectMask(MercuryConst.vkFormat(createInfo.depthStencilFormat()));
                subpassDescription.pDepthStencilAttachment(depthReference);
            }
            
            final var passCreateInfo = VkRenderPassCreateInfo2.calloc(stack).sType$Default();
            passCreateInfo.pAttachments(attachmentDescriptions);
            passCreateInfo.pSubpasses(subpassDescription);
            
            final var renderPassPtr = stack.longs(0);
            checkVkCode(vkCreateRenderPass2(device.vkDevice(), passCreateInfo, null, renderPassPtr));
            handle = renderPassPtr.get(0);
        }
    }
    
    @Override
    public void destroy() {
        vkDestroyRenderPass(device.vkDevice(), handle, null);
    }
    
    public long vkRenderPass() {
        return handle;
    }
    
    @Override
    public int colorAttachmentCount() {
        return colorAttachmentCount;
    }
}
