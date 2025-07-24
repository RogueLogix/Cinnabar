package graphics.cinnabar.loader.services;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.mojang.logging.LogUtils;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.lwjgl.system.Configuration;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CinnabarGraphicsBootstrapper implements GraphicsBootstrapper {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static UnmodifiableConfig config;
    
    static {
        LOGGER.trace("CinnabarGraphicsBootstrapper LOADED!");
    }
    
    @Override
    public String name() {
        return "CinnabarGraphicsBootstrapper";
    }
    
    @Override
    public void bootstrap(String[] arguments) {
        Configuration.STACK_SIZE.set(256);
        try {
            final var resource = CinnabarLocator.class.getResource("/modinfo.toml");
            assert resource != null;
            final var lines = Files.readString(Path.of(resource.toURI()));
            config = TomlFormat.instance().createParser().parse(lines).unmodifiable();
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
        config.get("neo_version");
        for (int i = 0; i < arguments.length - 1; i++) {
            if ("--fml.neoForgeVersion".equals(arguments[i])) {
                if (!neoVersionSupported(arguments[i + 1])) {
                    // don't init cinnabar's early window with an incompatible neo version, the mod itself will fail the loading later
                    return;
                }
                CinnabarEarlyWindowProvider.attemptConfigInit();
                CinnabarLaunchPlugin.attemptInject();
            }
        }
    }
    
    private static boolean neoVersionSupported(String neoVersion) {
        final var currentVersion = new ComparableVersion(neoVersion);
        final var lowerBound = new ComparableVersion(config.get("neo_version"));
        return lowerBound.compareTo(currentVersion) <= 0;
    }
}
