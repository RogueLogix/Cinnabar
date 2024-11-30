package graphics.cinnabar.api;

import graphics.cinnabar.api.annotations.API;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;

public class CinnabarEventBus {
    @API
    public static final IEventBus CINNABAR_EVENT_BUS = BusBuilder.builder().allowPerPhasePost().build();
}
