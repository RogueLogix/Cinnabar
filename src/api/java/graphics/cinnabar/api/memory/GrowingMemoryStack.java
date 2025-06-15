package graphics.cinnabar.api.memory;

import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.exceptions.NotImplemented;
import graphics.cinnabar.api.util.Destroyable;
import it.unimi.dsi.fastutil.ints.IntLongMutablePair;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import org.lwjgl.system.MemoryStack;

@API
public class GrowingMemoryStack extends MemoryStack implements Destroyable {
    private static final long STACK_BLOCK_SIZE = 64 * MagicMemorySizes.KiB;
    private final ReferenceArrayList<PointerWrapper> stackBlocks = new ReferenceArrayList<>();
    
    private IntLongMutablePair currentFrame;
    private final ReferenceArrayList<IntLongMutablePair> frames = new ReferenceArrayList<>();
    
    public GrowingMemoryStack() {
        super(null, 1, (int) STACK_BLOCK_SIZE);
        stackBlocks.add(PointerWrapper.alloc(STACK_BLOCK_SIZE));
        currentFrame = new IntLongMutablePair(0, 0);
    }
    
    @Override
    public void destroy() {
        stackBlocks.forEach(PointerWrapper::free);
    }
    
    public void reset() {
        currentFrame = new IntLongMutablePair(0, 0);
        frames.clear();
    }
    
    @Override
    public MemoryStack push() {
        frames.push(currentFrame);
        currentFrame = new IntLongMutablePair(currentFrame.leftInt(), currentFrame.rightLong());
        return this;
    }
    
    @Override
    public MemoryStack pop() {
        currentFrame = frames.pop();
        return this;
    }
    
    public long getAddress() {
        throw new NotImplemented();
    }
    
    public int getPointer() {
        throw new NotImplemented();
    }
    
    @Override
    public void setPointer(int pointer) {
        super.setPointer(pointer);
    }
    
    @Override
    public long nmalloc(int alignment, int size) {
        if (size > STACK_BLOCK_SIZE) {
            throw new IllegalArgumentException("Stack alloc size is too large");
        }
        final var currentStackBlockIndex = currentFrame.leftInt();
        final var currentStackBlockAllocated = currentFrame.rightLong();
        var stackBlock = stackBlocks.get(currentStackBlockIndex);
        long allocAddress = ((stackBlock.pointer() + currentStackBlockAllocated) + (alignment - 1)) & (-alignment);
        long allocOffset = allocAddress - stackBlock.pointer();
        // wont fit in this block, get the next one
        if ((allocOffset + size) > STACK_BLOCK_SIZE) {
            currentFrame.left(currentStackBlockIndex + 1);
            final var stackBlockIndex = currentFrame.leftInt();
            // out of blocks, new one
            if (stackBlockIndex == stackBlocks.size()) {
                stackBlocks.add(PointerWrapper.alloc(STACK_BLOCK_SIZE));
            }
            stackBlock = stackBlocks.get(stackBlockIndex);
            allocAddress = (stackBlock.pointer() + (alignment - 1)) & (-alignment);
            allocOffset = allocAddress - stackBlock.pointer();
            if ((allocOffset + size) > STACK_BLOCK_SIZE) {
                // this is a special case where the alignment would require an overrun of the buffer
                // this should be exceedingly rare
                // if this is ever actually hit, ill handle it then
                throw new IllegalArgumentException("Stack alloc size/alignment is too large");
            }
        }
        currentFrame.right(allocOffset + size);
        return stackBlock.pointer() + allocOffset;
    }
}
