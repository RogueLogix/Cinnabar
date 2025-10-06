package graphics.cinnabar.loader.services;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.mojang.logging.LogUtils;
import graphics.cinnabar.loader.earlywindow.VulkanStartup;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.lwjgl.system.Configuration;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
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
//        try {
//            final var resource = CinnabarLocator.class.getResource("/modinfo.toml");
//            assert resource != null;
//            final var lines = Files.readString(Path.of(resource.toURI()));
//            config = TomlFormat.instance().createParser().parse(lines).unmodifiable();
//        } catch (IOException | URISyntaxException | FileSystemNotFoundException e) {
//            e.printStackTrace();
//            throw new RuntimeException(e);
//        }
//        for (int i = 0; i < arguments.length - 1; i++) {
//            if ("--fml.neoForgeVersion".equals(arguments[i])) {
//                if (!neoVersionSupported(arguments[i + 1])) {
//                    // don't init cinnabar's early window with an incompatible neo version, the mod itself will fail the loading later
//                    return;
//                }
//                VulkanStartup.Config.mcVersionString = CinnabarGraphicsBootstrapper.config.get("minecraft_version");
//                VulkanStartup.Config.cinnabarVersionString = CinnabarGraphicsBootstrapper.config.get("cinnabar_version");
//                CinnabarEarlyWindowProvider.attemptConfigInit();
//            }
//        }
        CinnabarEarlyWindowProvider.attemptConfigInit();
    }
    
    private static boolean neoVersionSupported(String neoVersion) {
        final var currentVersion = new ComparableVersion(neoVersion);
        final var lowerBound = new ComparableVersion(config.get("neo_version"));
        return lowerBound.compareTo(currentVersion) <= 0;
    }
}
