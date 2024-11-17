package graphics.cinnabar.internal.exceptions;

public class CompileFailure extends RuntimeException {
    public CompileFailure() {
        super();
    }
    
    public CompileFailure(String message) {
        super(message);
    }
}
