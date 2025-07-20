package graphics.cinnabar.loader.services;

import com.mojang.logging.LogUtils;
import cpw.mods.jarhandling.JarContents;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.*;
import org.slf4j.Logger;

import java.nio.file.Path;

import static java.nio.file.Files.walk;

public class CinnabarLocator implements IModFileCandidateLocator {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    static {
        LOGGER.trace("CinnabarLocator loaded!");
    }
    
    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        if (!FMLEnvironment.dist.isClient()) {
            LOGGER.info("Skipping locating Cinnabar and libs on non-client environment");
            return;
        }
        try {
            final var resource = CinnabarLocator.class.getResource("/META-INF/modjar/");
            if (resource == null) {
                if (!FMLEnvironment.production) {
                    // this is fine, probably means in my own dev environment
                    return;
                } else {
                    throw new ModFileLoadingException("Unable to locate Cinnabar mod files");
                }
            }
            walk(Path.of(resource.toURI()), 1).forEach(path -> {
                final var filename = path.getFileName().toString();
                if (!filename.endsWith(".jar")) {
                    return;
                }
                LOGGER.debug("Loading mod {}", filename);
                pipeline.addJarContent(JarContents.of(path), ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
            });
        } catch (Exception e) {
            LOGGER.error("Fatal error encountered locating Cinnabar jars", e);
            if (e instanceof ModFileLoadingException loadingException) {
                throw loadingException;
            }
            throw new ModFileLoadingException(e.getMessage());
        }
    }
}
