package graphics.cinnabar.api.b3dext.systems;

public record ExtCapabilities(
        boolean textureView,
        boolean baseInstance,
        boolean drawIndirect,
        boolean drawID
) {
    public ExtCapabilities() {
        this(true, true, true, true);
    }
}
