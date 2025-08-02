package graphics.cinnabar.api.threading;

public interface ISemaphore {
    
    long value();
    
    void waitValue(long value, long timeout);
    
    void singlaValue(long value);
}
