package graphics.cinnabar.api.threading;

import net.roguelogix.phosphophyllite.util.API;

@API
public class Queues {
    public static final CounterQueue endOfCPUFrame = new CounterQueue();
    // TODO: end of GPU frame
    public static final CounterQueue backgroundThreads = new CounterQueue(Runtime.getRuntime().availableProcessors() - 2, "Cinnabar background thread %d");
}
