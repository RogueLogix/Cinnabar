package graphics.cinnabar.services;

import com.mojang.logging.LogUtils;
import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.ModuleLayerHandler;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
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
        if (CinnabarGraphicsBootstrapper.inIDE()) {
            LOGGER.warn("Skipping locating Cinnabar and libs in dev environment");
            return;
        }
        try {
            walk(Path.of(CinnabarLocator.class.getResource("/META-INF/mod/").toURI()), 1).forEach(path -> {
                final var filename = path.getFileName().toString();
                if (!filename.endsWith(".jar")) {
                    return;
                }
                LOGGER.debug("Loading mod {}", filename);
                final var parent = pipeline.addJarContent(JarContents.of(path), ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS).get();
                loadLibs(pipeline, parent);
            });
        } catch (Exception e) {
            LOGGER.error("Fatal error encountered locating Cinnabar jars", e);
        }
    }
    
    private static void loadLibs(IDiscoveryPipeline pipeline, IModFile parent){
        try {
            walk(Path.of(CinnabarLocator.class.getResource("/META-INF/lib/").toURI()), 1).forEach(path -> {
                final var filename = path.getFileName().toString();
                if (!filename.endsWith(".jar")) {
                    return;
                }
                LOGGER.debug("Loading lib {}", filename);
                final var layerHandler = (ModuleLayerHandler)Launcher.INSTANCE.findLayerManager().get();
                pipeline.addModFile(IModFile.create(SecureJar.from(path), JarModsDotTomlModFileReader::manifestParser, IModFile.Type.LIBRARY, ModFileDiscoveryAttributes.DEFAULT.withParent(parent)));
            });
        } catch (Exception e) {
            LOGGER.error("Fatal error encountered locating Cinnabar lib jars", e);
        }
    }
}
