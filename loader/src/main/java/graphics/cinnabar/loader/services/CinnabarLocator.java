package graphics.cinnabar.loader.services;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.*;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static java.nio.file.Files.walk;

public class CinnabarLocator implements IModFileCandidateLocator {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    static {
        LOGGER.trace("CinnabarLocator loaded!");
    }
    
    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        if (!FMLEnvironment.getDist().isClient()) {
            LOGGER.info("Skipping locating Cinnabar and libs on non-client environment");
            return;
        }
//        final var jarLocation = CinnabarLocator.class.getResource("/graphics/cinnabar/loader/services/CinnabarLocator.class").getPath().replace("%20", " ");
//        final var jarPath = Path.of(jarLocation.substring(jarLocation.indexOf(":") + 1, jarLocation.lastIndexOf('!')));
//        var extractDirectory = FMLPaths.JIJ_CACHEDIR.get() + "/cinnabar";
//        try (
//                final var filesystem = FileSystems.newFileSystem(jarPath);
//                final var jarContents = JarContents.ofPath(jarPath)
//        ) {
//            final var resource = filesystem.getPath("/META-INF/modjar/");
//            walk(resource, 1).forEach(path -> {
//                final var filename = path.getFileName().toString();
//                if (!filename.endsWith(".jar")) {
//                    return;
//                }
//                LOGGER.debug("Loading mod {}", filename);
//                try {
//                    final var bytes = jarContents.readFile(path.toString());
//                    assert bytes != null;
//                    final var extractPath = Path.of(extractDirectory + path.getFileName().toString());
//                    Files.write(extractPath, bytes);
//                    pipeline.addJarContent(JarContents.ofPath(extractPath), ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
//                } catch (IOException e) {
//                    throw new RuntimeException(e);
//                }
//            });
//        } catch (NoSuchFileException e) {
//            if (!FMLLoader.getCurrent().isProduction()) {
//                // this is fine, probably means in my own dev environment
//            } else {
//                throw new ModFileLoadingException("Unable to locate Cinnabar mod files");
//            }
//        } catch (Exception e) {
//            LOGGER.error("Fatal error encountered locating Cinnabar jars", e);
//            if (e instanceof ModFileLoadingException loadingException) {
//                throw loadingException;
//            }
//            throw new ModFileLoadingException(e.getMessage());
//        }
    }
}
