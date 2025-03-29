package graphics.cinnabar.core.vk.descriptors;

import it.unimi.dsi.fastutil.longs.LongLongImmutablePair;
import it.unimi.dsi.fastutil.longs.LongLongPair;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

public class DescriptorSetBuilder {
    
    private final DescriptorSetLayout layout;
    private final IDescriptorPool pool;
    
    private final List<UBOBinding> uboBindings;
    public final List<SamplerBinding> samplerBindings;
    
    public final List<UBOBinding.Staging> uboStagings;
    private boolean samplersDirty = false;
    private final Map<SamplerBinding, @Nullable LongLongPair> activeSamplers = new Reference2ReferenceOpenHashMap<>();
    
    private long lastActiveFrame = -1;
    @Nullable
    private DescriptorSet lastDescriptorSet;
    
    public DescriptorSetBuilder(DescriptorSetLayout layout, IDescriptorPool pool) {
        this.layout = layout;
        this.pool = pool;
        uboBindings = layout.bindings.stream().filter(binding -> binding instanceof UBOBinding).map(a -> (UBOBinding) a).toList();
        samplerBindings = layout.bindings.stream().filter(binding -> binding instanceof SamplerBinding).map(a -> (SamplerBinding) a).toList();
        uboStagings = uboBindings.stream().map(UBOBinding::createStaging).toList();
    }
    
    private boolean dirty() {
        final var currentFrame = layout.device.currentFrameIndex();
        if (lastActiveFrame < currentFrame) {
            // force set this to null, its now invalid
            lastDescriptorSet = null;
            return true;
        } else if (lastDescriptorSet == null || samplersDirty) {
            return true;
        }
        for (UBOBinding.Staging uboStaging : uboStagings) {
            if (uboStaging.isDirty()) {
                return true;
            }
        }
        return false;
    }
    
    public void setSampler(SamplerBinding binding, long samplerHandle, long imageViewHandle) {
        @Nullable
        final var activeSampler = activeSamplers.get(binding);
        if (activeSampler == null || activeSampler.firstLong() != samplerHandle || activeSampler.secondLong() != imageViewHandle) {
            samplersDirty = true;
            activeSamplers.put(binding, new LongLongImmutablePair(samplerHandle, imageViewHandle));
        }
    }
    
    public DescriptorSet getActiveSet() {
        if (!dirty()) {
            assert lastDescriptorSet != null;
            return lastDescriptorSet;
        }
        if (lastDescriptorSet != null) {
            lastDescriptorSet = lastDescriptorSet.createCopy();
        } else {
            lastDescriptorSet = new DescriptorSet(layout, pool, uboBindings, samplerBindings);
        }
        lastActiveFrame = layout.device.currentFrameIndex();
//        for (UBOBinding.Staging uboStaging : uboStagings) {
//            final var buffer = uboStaging.upload();
//            CinnabarAPI.destroyEndOfGPUFrame(buffer);
//            lastDescriptorSet.updateUBO(buffer);
//        }
        for (final var entry : activeSamplers.entrySet()) {
            @Nullable
            final var activeSampler = entry.getValue();
            if (activeSampler == null) {
                throw new IllegalStateException();
            }
            lastDescriptorSet.updateImage(entry.getKey(), activeSampler.firstLong(), activeSampler.secondLong());
        }
        return lastDescriptorSet;
    }
}
