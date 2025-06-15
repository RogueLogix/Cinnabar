package graphics.cinnabar.api.threading;

public interface ISemaphore {
    
    long value();
    
    void wait(long value, long timeout);
    
    void signal(long value);
}
