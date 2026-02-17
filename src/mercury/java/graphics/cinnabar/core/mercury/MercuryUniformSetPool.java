package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgUniformSet;
import graphics.cinnabar.api.hg.enums.HgUniformType;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIntImmutablePair;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.List;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.VK10.*;

public class MercuryUniformSetPool extends MercuryObject<HgUniformSet.Pool> implements HgUniformSet.Pool {
    
    private static final int VK_POOL_SIZE = 128;
    
    private final LongArrayList pools = new LongArrayList();
    private final ReferenceArrayList<LongArrayList> availableSets = new ReferenceArrayList<>();
    private final VkDescriptorSetAllocateInfo setAllocInfo = VkDescriptorSetAllocateInfo.calloc().sType$Default();
    private final VkDescriptorPoolCreateInfo poolCreateInfo = VkDescriptorPoolCreateInfo.calloc().sType$Default();
    private final VkDescriptorPoolSize.Buffer poolSize;
    private final LongBuffer layoutPtr = MemoryUtil.memCallocLong(VK_POOL_SIZE);
    private final LongBuffer returnPtr = MemoryUtil.memCallocLong(VK_POOL_SIZE);
    private int freeSetCount = 0;
    
    
    public MercuryUniformSetPool(MercuryUniformSetLayout layout, CreateInfo createInfo) {
        super(layout.device);
        Int2IntArrayMap countsMap = new Int2IntArrayMap();
        for (HgUniformSet.Layout.Binding binding : layout.bindings()) {
            int type = binding.type().ordinal();
            if (binding.variableCount()) {
                throw new IllegalArgumentException("Variable count not yet supported");
            }
            countsMap.put(type, countsMap.get(type) + binding.count());
        }
        poolSize = VkDescriptorPoolSize.calloc(countsMap.size());
        for (Int2IntMap.Entry entry : countsMap.int2IntEntrySet()) {
            final var vkUniformType = switch (HgUniformType.values()[entry.getIntKey()]) {
                case COMBINED_IMAGE_SAMPLER -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                case UNIFORM_TEXEL_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
                case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
                case STORAGE_BUFFER -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            };
            poolSize.type(vkUniformType);
            poolSize.descriptorCount(entry.getIntValue() * VK_POOL_SIZE);
            poolSize.position(poolSize.position() + 1);
        }
        poolSize.position(0);
        
        poolCreateInfo.flags(0);
        poolCreateInfo.maxSets(VK_POOL_SIZE);
        poolCreateInfo.pPoolSizes(poolSize);
        
        for (int i = 0; i < VK_POOL_SIZE; i++) {
            layoutPtr.put(i, layout.vkDescriptorSetLayout());
        }
        setAllocInfo.pSetLayouts(layoutPtr);
    }
    
    @Override
    public void destroy() {
        for (int i = 0; i < pools.size(); i++) {
            vkDestroyDescriptorPool(device.vkDevice(), pools.getLong(i), null);
        }
        poolSize.free();
        poolCreateInfo.free();
        setAllocInfo.free();
        MemoryUtil.memFree(layoutPtr);
        MemoryUtil.memFree(returnPtr);
    }
    
    private void allocateNewPool() {
        checkVkCode(vkCreateDescriptorPool(device.vkDevice(), poolCreateInfo, null, returnPtr));
        pools.add(returnPtr.get(0));
        setAllocInfo.descriptorPool(pools.getLast());
        checkVkCode(vkAllocateDescriptorSets(device.vkDevice(), setAllocInfo, returnPtr));
        freeSetCount += VK_POOL_SIZE;
        
        final var setList = new LongArrayList(VK_POOL_SIZE);
        for (int i = 0; i < VK_POOL_SIZE; i++) {
            setList.add(returnPtr.get(i));
        }
        availableSets.add(setList);
    }
    
    @Override
    public HgUniformSet allocate() {
        setAllocInfo.sType$Default();
        
        if (freeSetCount == 0) {
            allocateNewPool();
        }
        assert freeSetCount != 0;
        
        for (int i = 0; i < pools.size(); i++) {
            final var sets = availableSets.get(i);
            if (!sets.isEmpty()) {
                freeSetCount--;
                return new SetInstance(this, i, sets.popLong());
            }
        }
        
        throw new IllegalStateException();
    }
    
    @Override
    protected LongIntImmutablePair handleAndType() {
        throw new IllegalStateException("Cannot name multiple objects at once");
    }
    
    record SetInstance(MercuryUniformSetPool pool, int vkPoolIndex, long set) implements HgUniformSet {
        
        @Override
        public void destroy() {
            pool.freeSetCount++;
            pool.availableSets.get(vkPoolIndex).add(set);
        }
        
        @Override
        public MercuryDevice device() {
            return pool.device;
        }
        
        @Override
        public HgUniformSet setName(String label) {
            if (device().debugUtilsEnabled()) {
                try (final var stack = memoryStack().push()) {
                    final var nameInfo = VkDebugUtilsObjectNameInfoEXT.calloc(stack).sType$Default();
                    nameInfo.pObjectName(stack.UTF8(label));
                    nameInfo.objectHandle(set);
                    nameInfo.objectType(VK_OBJECT_TYPE_DESCRIPTOR_SET);
                    EXTDebugUtils.vkSetDebugUtilsObjectNameEXT(device().vkDevice(), nameInfo);
                }
            }
            return this;
        }
        
        @Override
        public void write(List<Write> writes) {
            try (final var stack = memoryStack().push()) {
                final var vkWrites = VkWriteDescriptorSet.calloc(writes.size(), stack);
                for (int i = 0; i < writes.size(); i++) {
                    final var write = writes.get(i);
                    final var binding = write.binding();
                    vkWrites.position(i);
                    vkWrites.sType$Default();
                    vkWrites.dstSet(set);
                    vkWrites.dstBinding(binding.location());
                    vkWrites.dstArrayElement(write.offset());
                    vkWrites.descriptorCount(write.count());
                    vkWrites.descriptorType(MercuryConst.vkDescriptorType(binding.type()));
                    switch (write) {
                        case Write.Buffer bufferWrite -> {
                            final var vkBufferWrite = VkDescriptorBufferInfo.calloc(write.count(), stack);
                            final var buffers = bufferWrite.slices();
                            for (int j = 0; j < write.count(); j++) {
                                vkBufferWrite.position(j);
                                final var bufferSlice = buffers.get(j);
                                final var buffer = (MercuryBuffer) bufferSlice.buffer();
                                vkBufferWrite.buffer(buffer.vkBuffer());
                                vkBufferWrite.offset(bufferSlice.offset());
                                vkBufferWrite.range(bufferSlice.size());
                            }
                            vkBufferWrite.position(0);
                            vkWrites.pBufferInfo(vkBufferWrite);
                        }
                        case Write.BufferView bufferViewWrite -> {
                            final var views = bufferViewWrite.bufferViews();
                            final var viewHandles = stack.callocLong(views.size());
                            for (int j = 0; j < views.size(); j++) {
                                viewHandles.put(j, ((MercuryBufferView) views.get(j)).vkBufferView());
                            }
                            vkWrites.pTexelBufferView(viewHandles);
                        }
                        case Write.Image imageWrite -> {
                            final var vkImageWrites = VkDescriptorImageInfo.calloc(write.count(), stack);
                            final var images = imageWrite.imageInfos();
                            for (int j = 0; j < write.count(); j++) {
                                final var image = images.get(j);
                                vkImageWrites.position(j);
                                if (image.second() != null) {
                                    vkImageWrites.sampler(((MercurySampler) image.second()).vkSampler());
                                }
                                if (image.first() != null) {
                                    vkImageWrites.imageView(((MercuryImageView) image.first()).vkImageView());
                                    vkImageWrites.imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                                }
                            }
                            vkImageWrites.position(0);
                            vkWrites.pImageInfo(vkImageWrites);
                        }
                    }
                }
                vkWrites.position(0);
                vkUpdateDescriptorSets(device().vkDevice(), vkWrites, null);
            }
        }
    }
}
