package graphics.cinnabar.core.hg3d;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import graphics.cinnabar.api.hg.HgFramebuffer;
import graphics.cinnabar.api.hg.HgImage;
import graphics.cinnabar.api.hg.HgRenderPass;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class Hg3DGpuTextureView extends GpuTextureView implements Hg3DObject {
    
    private final Hg3DGpuTexture texture;
    private final HgImage.View imageView;
    private final Reference2ReferenceMap<@NotNull HgRenderPass, Reference2ReferenceMap<HgImage.@NotNull View, @Nullable HgFramebuffer>> framebuffers = new Reference2ReferenceArrayMap<>();
    private boolean closed = false;
    
    public Hg3DGpuTextureView(Hg3DGpuTexture texture, int baseMipLevel, int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        this.texture = texture;
        boolean cubemap = (texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
        imageView = texture.image().createView(cubemap ? HgImage.View.Type.TYPE_CUBE : HgImage.View.Type.TYPE_2D, Hg3DConst.format(texture.getFormat()), baseMipLevel, mipLevels, 0, cubemap ? 6 : 1);
    }
    
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        texture.device().destroyEndOfFrame(imageView);
        texture.removeView();
    }
    
    @Override
    public boolean isClosed() {
        return closed;
    }
    
    @Override
    public Hg3DGpuDevice device() {
        return texture.device();
    }
    
    @Override
    public Hg3DGpuTexture texture() {
        return texture;
    }
    
    public HgImage.View imageView() {
        return imageView;
    }
    
    public HgFramebuffer getFramebuffer(HgRenderPass renderPass, @Nullable HgImage.View depthAttachment) {
        final var passFramebuffers = framebuffers.computeIfAbsent(renderPass, i -> new Reference2ReferenceArrayMap<>());
        @Nullable final var framebuffer = passFramebuffers.get(depthAttachment);
        if (framebuffer != null) {
            return framebuffer;
        }
        final var createInfo = new HgFramebuffer.CreateInfo(renderPass, List.of(imageView), depthAttachment);
        final var newFramebuffer = device().hgDevice().createFramebuffer(createInfo);
        passFramebuffers.put(depthAttachment, newFramebuffer);
        return newFramebuffer;
    }
}
