package graphics.cinnabar.api.threading;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class WorkFuture<T> implements IWorkQueue.Work, Future<T> {
    
    boolean executed = false;
    @Nullable
    private Exception exception;
    @Nullable
    private T object;
    private final Function<ThreadIndex, @Nullable T> creationFunc;
    @Nullable
    private IWorkQueue queue;
    @Nullable
    private BiConsumer<WorkFuture<T>, ThreadIndex> onCompleteSingle;
    @Nullable
    private List<BiConsumer<WorkFuture<T>, ThreadIndex>> onCompleteMultiple;
    
    public WorkFuture(Function<ThreadIndex, @Nullable T> creationFunc) {
        this.creationFunc = creationFunc;
    }
    
    public final WorkFuture<T> enqueue(IWorkQueue queue) {
        if (this.queue != null) {
            return this;
        }
        queue.enqueue(this);
        this.queue = queue;
        return this;
    }
    
    @Override
    public final void accept(ThreadIndex threadIndex) {
        try {
            object = creationFunc.apply(threadIndex);
        } catch (Exception e) {
            exception = e;
        }
        synchronized (this) {
            executed = true;
            notifyAll();
        }
        if (onCompleteMultiple != null) {
            for (final var consumer : onCompleteMultiple) {
                consumer.accept(this, threadIndex);
            }
        } else if (onCompleteSingle != null) {
            onCompleteSingle.accept(this, threadIndex);
        }
        onCompleteMultiple = null;
        onCompleteSingle = null;
    }
    
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
    }
    
    @Override
    public boolean isCancelled() {
        return false;
    }
    
    @Override
    public boolean isDone() {
        return executed;
    }
    
    public T getNotNull() {
        return Objects.requireNonNull(getNoExcept());
    }
    
    @Nullable
    public T getNoExcept() {
        try {
            return get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }
    
    @Override
    @Nullable
    public T get() throws InterruptedException, ExecutionException {
        if (!isDone()) {
            synchronized (this) {
                if (!isDone()) {
                    // TODO: this can deadlock if waiting on the only thread able to execute this future
                    wait();
                }
            }
        }
        if (exception != null) {
            throw new ExecutionException(exception);
        }
        return object;
    }
    
    @Override
    @Nullable
    public T get(long timeout, @NotNull TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (!isDone()) {
            synchronized (this) {
                if (!isDone()) {
                    wait(unit.toMillis(timeout));
                }
                if (!isDone()) {
                    throw new TimeoutException();
                }
            }
        }
        if (exception != null) {
            throw new ExecutionException(exception);
        }
        return object;
    }
    
    public synchronized void onCompleteCallback(BiConsumer<WorkFuture<T>, ThreadIndex> callback) {
        if (onCompleteSingle == null) {
            onCompleteSingle = callback;
            return;
        }
        
        if (onCompleteMultiple == null) {
            onCompleteMultiple = new ReferenceArrayList<>(2);
            onCompleteMultiple.add(onCompleteSingle);
        }
        
        onCompleteMultiple.add(callback);
    }
    
    public <R> WorkFuture<R> enqueueWhenFinished(WorkFuture<R> future) {
        onCompleteCallback((a, b) -> {
            assert queue != null;
            future.enqueue(queue);
        });
        return future;
    }
    
    public static <R, T extends R> WorkFuture<R> cast(WorkFuture<T> other) {
        //noinspection unchecked
        return (WorkFuture<R>) other;
    }
}
