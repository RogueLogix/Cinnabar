package graphics.cinnabar.core.b3d.configuration;

import graphics.cinnabar.api.cvk.configuration.CVKDeviceFeatures;

public class CinnabarGpuDeviceFeatures implements CVKDeviceFeatures {
    @Override
    public boolean logicOp() {
        // Cinnabar does not support logic op, period
        return false;
    }
    
    public record Immutable(
            boolean logicOp
    ) implements CVKDeviceFeatures {
        public Immutable(CVKDeviceFeatures features) {
            this(features.logicOp());
        }
    }
}
