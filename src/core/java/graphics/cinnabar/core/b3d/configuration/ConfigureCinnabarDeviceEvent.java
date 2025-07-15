package graphics.cinnabar.core.b3d.configuration;

import graphics.cinnabar.api.cvk.configuration.CVKDeviceFeatures;
import graphics.cinnabar.api.cvk.configuration.CVKDeviceProperties;
import net.neoforged.neoforge.client.event.ConfigureGpuDeviceEvent;

public class ConfigureCinnabarDeviceEvent extends ConfigureGpuDeviceEvent implements CVKDeviceFeatures {
    public final CVKDeviceProperties deviceProperties;
    public final CVKDeviceFeatures availableFeatures;
    
    public ConfigureCinnabarDeviceEvent(CVKDeviceProperties deviceProperties, CVKDeviceFeatures availableFeatures) {
        super(deviceProperties, availableFeatures);
        this.deviceProperties = deviceProperties;
        this.availableFeatures = availableFeatures;
    }
}
