package graphics.cinnabar.api.hg;

import graphics.cinnabar.api.hg.enums.HgFormat;
import org.jetbrains.annotations.ApiStatus;
import org.joml.Vector3i;
import org.joml.Vector3ic;

@ApiStatus.NonExtendable
public interface HgImage extends HgObject<HgImage> {
    
    Type type();
    
    HgFormat format();
    
    int width();
    
    int height();
    
    int depth();
    
    int layerCount();
    
    int levelCount();
    
    View createView(View.Type viewType, HgFormat format, int baseMipLevel, int mipLevels, int baseArrayLayer, int layerCount);
    
    default ResourceRange resourceRange() {
        return resourceRange(0, levelCount(), 0, layerCount());
    }
    
    default ResourceRange resourceRange(int baseMipLevel, int mipLevels, int baseArrayLayer, int layerCount) {
        return new ResourceRange(this, baseMipLevel, mipLevels, baseArrayLayer, layerCount);
    }
    
    default TransferRange transferRange() {
        return transferRange(0);
    }
    
    default TransferRange transferRange(int mipLevel) {
        return transferRange(new Vector3i(0, 0, 0), new Vector3i(width(), height(), depth()), 0, layerCount(), mipLevel);
    }
    
    default TransferRange transferRange(Vector3ic offset, Vector3ic extent, int baseLayer, int layerCount, int mipLevel) {
        return new TransferRange(this, offset, extent, baseLayer, layerCount, mipLevel);
    }
    
    enum Type {
        TYPE_1D,
        TYPE_2D,
        TYPE_3D,
    }
    
    @ApiStatus.NonExtendable
    interface View extends HgObject<View> {
        HgImage image();
        
        Type type();
        
        HgFormat format();
        
        int baseArrayLayer();
        
        int layerCount();
        
        int baseMipLevel();
        
        int levelCount();
        
        enum Type {
            TYPE_1D,
            TYPE_2D,
            TYPE_3D,
            TYPE_CUBE,
            TYPE_1D_ARRAY,
            TYPE_2D_ARRAY,
            TYPE_CUBE_ARRAY,
        }
    }
    
    record ResourceRange(HgImage image, int baseMipLevel, int mipLevels, int baseArrayLayer, int layerCount) {
    }
    
    record TransferRange(HgImage image, Vector3ic offset, Vector3ic extent, int baseLayer, int layerCount, int mipLevel) {
    }
}
