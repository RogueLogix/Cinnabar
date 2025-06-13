package graphics.cinnabar.loader.services;

import com.mojang.logging.LogUtils;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CinnabarGraphicsBootstrapper implements GraphicsBootstrapper {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    static {
        LOGGER.trace("CinnabarGraphicsBootstrapper LOADED!");
    }
    
    @Override
    public String name() {
        return "CinnabarGraphicsBootstrapper";
    }
    
    @Override
    public void bootstrap(String[] arguments) {
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
        final String[] versionBounds;
        try {
            final var resource = CinnabarLocator.class.getResource("/modinfo.toml");
            assert resource != null;
            final var lines = Files.readAllLines(Path.of(resource.toURI()));
            final var neoVersionString = lines.getFirst().split("=")[1];
            versionBounds = neoVersionString.substring(2, neoVersionString.length() - 2).split(",");
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
        final var lowerBound = new ComparableVersion(versionBounds[0]);
        final var upperBound = new ComparableVersion(versionBounds[1]);
        final var currentVersion = new ComparableVersion(neoVersion);
        return lowerBound.compareTo(currentVersion) <= 0 && currentVersion.compareTo(upperBound) < 0;
    }
}
