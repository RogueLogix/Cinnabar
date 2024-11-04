package dev.logix.cinnabar;

import net.roguelogix.phosphophyllite.config.ConfigValue;

public class CinnabarConfig {
    
    @ConfigValue
    public final boolean Debug;
    
    {
        Debug = false;
    }
}
