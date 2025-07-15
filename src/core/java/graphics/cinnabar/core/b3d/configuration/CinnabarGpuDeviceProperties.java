package graphics.cinnabar.core.b3d.configuration;

import graphics.cinnabar.api.cvk.configuration.CVKDeviceProperties;

public class CinnabarGpuDeviceProperties implements CVKDeviceProperties {
    @Override
    public String backendName() {
        return "Cinnabar";
    }
    
    @Override
    public String apiName() {
        return "VK";
    }
    
    public record Immutable(
            String backendName,
            String apiName
    ) implements CVKDeviceProperties {
        public Immutable(CVKDeviceProperties properties) {
            this(
                    properties.backendName(),
                    properties.apiName()
            );
        }
    }
}
