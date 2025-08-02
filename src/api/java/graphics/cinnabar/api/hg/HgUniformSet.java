package graphics.cinnabar.api.hg;

import graphics.cinnabar.api.annotations.Constant;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.hg.enums.HgUniformType;
import graphics.cinnabar.api.util.Pair;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@ApiStatus.NonExtendable
public interface HgUniformSet extends HgObject {
    
    @ThreadSafety.Any
    @ThreadSafety.VulkanObjectHandle(note = "Must sync with pool")
    void write(List<Write> writes);
    
    @ApiStatus.NonExtendable
    interface Layout extends HgObject {
        
        @Constant
        @ThreadSafety.Many
        List<Binding> bindings();
        
        @ThreadSafety.Many
        Pool createPool(Pool.CreateInfo createInfo);
        
        record Binding(String name, int location, HgUniformType type, int count, boolean updateAfterBind, boolean variableCount, long size) {
        }
        
        record CreateInfo(List<Binding> bindings) {
        }
    }
    
    sealed interface Write {
        
        @Constant
        @ThreadSafety.Many
        Layout.Binding binding();
        
        @Constant
        @ThreadSafety.Many
        int offset();
        
        @Constant
        @ThreadSafety.Many
        int count();
        
        record Image(Layout.Binding binding, int offset, List<Pair<HgImage.@Nullable View, @Nullable HgSampler>> imageInfos) implements Write {
            @Override
            public int count() {
                return imageInfos.size();
            }
        }
        
        record Buffer(Layout.Binding binding, int offset, List<HgBuffer.Slice> slices) implements Write {
            @Override
            public int count() {
                return slices.size();
            }
        }
        
        record BufferView(Layout.Binding binding, int offset, List<HgBuffer.View> bufferViews) implements Write {
            @Override
            public int count() {
                return bufferViews.size();
            }
        }
    }
    
    interface Pool extends HgObject {
        @ThreadSafety.Any
        @ThreadSafety.VulkanObjectHandle
        HgUniformSet allocate();
        
        record CreateInfo() {
        }
    }
}
