package graphics.cinnabar.core.vk.memory;

import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.vk.VulkanNameable;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkMemoryRequirements2;

@API
public interface VkMemoryPool extends Destroyable, VulkanNameable {
    
    /**
     * synchronize on object monitor if expected to be used by multiple threads
     */
    @ThreadSafety.Any
    VkMemoryAllocation alloc(VkMemoryRequirements memoryRequirements);
    
    long totalAllocatedFromVulkan();
    
    long liveAllocated();
    
    interface CPU extends VkMemoryPool {
        @ThreadSafety.Any
        VkMemoryAllocation.CPU alloc(VkMemoryRequirements memoryRequirements);
    }
    
    interface Transient extends VkMemoryPool {
        @ThreadSafety.Any
        void reset();
        
        interface CPU extends Transient, VkMemoryPool.CPU {
        }
    }
    
    @ApiStatus.Experimental
    interface Dedicated extends VkMemoryPool {
        VkMemoryAllocation alloc(VkMemoryRequirements memoryRequirements, boolean dedicated);
        
        @ApiStatus.Experimental
        interface CPU extends Dedicated, VkMemoryPool.CPU {
            VkMemoryAllocation.CPU alloc(VkMemoryRequirements memoryRequirements, boolean dedicated);
        }
    }
    
    @ApiStatus.Experimental
    interface Req2 extends VkMemoryPool {
        VkMemoryAllocation alloc(VkMemoryRequirements2 memoryRequirements);
        
        @ApiStatus.Experimental
        interface CPU extends Req2, VkMemoryPool.CPU {
            VkMemoryAllocation.CPU alloc(VkMemoryRequirements2 memoryRequirements);
        }
        
        @ApiStatus.Experimental
        interface Dedicated extends Req2 {
            VkMemoryAllocation alloc(VkMemoryRequirements2 memoryRequirements, boolean dedicated);
            
            interface CPU extends Req2.Dedicated, Req2.CPU {
                VkMemoryAllocation.CPU alloc(VkMemoryRequirements2 memoryRequirements, boolean dedicated);
            }
        }
    }
}
