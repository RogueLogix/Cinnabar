package graphics.cinnabar.lib.config;

#if NEO
import net.neoforged.fml.loading.FMLEnvironment;
#elif FABRIC

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
#endif

public enum ConfigType {
    NULL(false),
    CLIENT(isFMLClient()),
    @Deprecated
    COMMON(true),
    @Deprecated
    SERVER(true);
    
    public final boolean appliesToPhysicalSide;
    
    ConfigType(boolean appliesToPhysicalSide) {
        this.appliesToPhysicalSide = appliesToPhysicalSide;
    }
    
    private static boolean isFMLClient() {
        try {
            #if NEO
            return FMLEnvironment.dist == null || FMLEnvironment.dist.isClient();
            #elif FABRIC
            return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
            #endif
            // in case its loaded without FML present, treat it as client
        } catch (NoClassDefFoundError e) {
            return true;
        }
    }
    
    public ConfigType from(ConfigType type) {
        return switch (this) {
            case NULL -> type;
            case CLIENT -> CLIENT;
            case COMMON -> COMMON;
            case SERVER -> SERVER;
        };
    }
}
