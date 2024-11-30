package graphics.cinnabar.api.events;

import graphics.cinnabar.api.annotations.ThreadSafety;
import net.neoforged.bus.api.Event;

/*
 * Intended to allow cleanup of resources created/used on a specific frame
 */
public abstract class EndOfFrame extends Event {
    
    public final long frameNum;
    
    protected EndOfFrame(long frameNum) {
        this.frameNum = frameNum;
    }
    
    /*
     * Fired immediately after vkQueuePresent for a given frame
     */
    @ThreadSafety.MainGraphics
    public static class CPU extends EndOfFrame {
        protected CPU(long frameNum) {
            super(frameNum);
        }
    }
    
    /*
     * Fired after fence for a given frame's graphics queue vkQueueSubmit has signaled
     * will always be fired after the EndOfFrame.CPU event for a given frame number has fired
     * will always be fired in order
     * may take multiple frames
     */
    @ThreadSafety.MainGraphics
    public static class GPU extends EndOfFrame {
        protected GPU(long frameNum) {
            super(frameNum);
        }
    }
}
