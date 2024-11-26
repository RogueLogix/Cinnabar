package graphics.cinnabar.internal.mixin.mixins.blaze3d.platform;

import com.mojang.blaze3d.platform.NativeImage;
import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.extensions.minecraft.renderer.texture.CinnabarAbstractTexture;
import graphics.cinnabar.internal.vulkan.memory.HostMemoryVkBuffer;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCString;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NativeImage.class)
public abstract class NativeImageMixin implements AutoCloseable{
    @Shadow
    private boolean useStbFree;
    @Shadow
    private long pixels;
    @Shadow
    private long size;
    
    private HostMemoryVkBuffer hostMemoryVkBuffer;
    
    @Redirect(method = "<init>(Lcom/mojang/blaze3d/platform/NativeImage$Format;IIZ)V", at = @At(value = "INVOKE", target = "org/lwjgl/system/MemoryUtil.nmemCalloc(JJ)J"))
    private long Cinnabar$NativeImageMixin$nmemCalloc(long num, long size) {
        return Cinnabar$NativeImageMixin$nmemAlloc(num * size);
    }
    
    @Redirect(method = "<init>(Lcom/mojang/blaze3d/platform/NativeImage$Format;IIZ)V", at = @At(value = "INVOKE", target = "org/lwjgl/system/MemoryUtil.nmemAlloc(J)J"))
    private long Cinnabar$NativeImageMixin$nmemAlloc(long size) {
        final var roundedUpSize = (size + (CinnabarRenderer.hostPtrAlignment() - 1)) & -CinnabarRenderer.hostPtrAlignment();
        this.size = roundedUpSize;
        return MemoryUtil.nmemAlignedAlloc(CinnabarRenderer.hostPtrAlignment(), roundedUpSize);
    }
    
    @Inject(method = "<init>(Lcom/mojang/blaze3d/platform/NativeImage$Format;IIZJ)V", at = @At(value = "RETURN"))
    private void alignmentVerification(CallbackInfo ignored) {
        if ((pixels & (CinnabarRenderer.hostPtrAlignment() - 1)) != 0 || (size & (CinnabarRenderer.hostPtrAlignment() - 1)) != 0) {
            // move pixels to aligned alloc
            final var roundedUpSize = (size + (CinnabarRenderer.hostPtrAlignment() - 1)) & -CinnabarRenderer.hostPtrAlignment();
            final var newPixels = MemoryUtil.nmemAlignedAlloc(CinnabarRenderer.hostPtrAlignment(), roundedUpSize);
            LibCString.nmemcpy(newPixels, pixels, size);
            if (this.useStbFree) {
                STBImage.nstbi_image_free(pixels);
            } else {
                MemoryUtil.nmemFree(pixels);
            }
            pixels = newPixels;
            useStbFree = false;
            size = roundedUpSize;
        }
    }
    
    @Inject(method = "<init>(Lcom/mojang/blaze3d/platform/NativeImage$Format;IIZ)V", at = @At("TAIL"))
    public void Cinnabar$NativeImageMixin$constructed1(CallbackInfo ignored) {
        hostMemoryVkBuffer = HostMemoryVkBuffer.create(pixels, size);
    }
    
    @Inject(method = "<init>(Lcom/mojang/blaze3d/platform/NativeImage$Format;IIZJ)V", at = @At("TAIL"))
    public void Cinnabar$NativeImageMixin$constructed2(CallbackInfo ignored) {
        hostMemoryVkBuffer = HostMemoryVkBuffer.create(pixels, size);
    }
    
    
    @Inject(method = "close", at = @At(value = "HEAD"))
    private void Cinnabar$NativeImageMixin$close(CallbackInfo ignored) {
        if (hostMemoryVkBuffer != null) {
            // this can be called from another thread, so, just add it to the destroy queue
            CinnabarRenderer.queueDestroyEndOfGPUSubmit(hostMemoryVkBuffer);
        }
        hostMemoryVkBuffer = null;
    }
    
    @Overwrite
    private void _upload(int level, int xOffset, int yOffset, int unpackSkipPixels, int unpackSkipRows, int width, int height, boolean blur, boolean clamp, boolean mipmap, boolean autoClose) {
        //noinspection DataFlowIssue
        CinnabarAbstractTexture.currentActiveBound().recordUpload((NativeImage) (Object) this, hostMemoryVkBuffer, level, xOffset, yOffset, unpackSkipPixels, unpackSkipRows, width, height, blur, clamp, mipmap);
        if(autoClose){
            try {
                this.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
