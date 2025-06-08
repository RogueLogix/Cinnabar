package graphics.cinnabar.services;

import com.mojang.logging.LogUtils;
import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.*;
import org.lwjgl.system.Platform;
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
        if (!FMLEnvironment.production) {
            LOGGER.warn("Skipping locating Cinnabar and libs in dev environment");
            return;
        }
        if (!FMLEnvironment.dist.isClient()) {
            LOGGER.info("Skipping locating Cinnabar and libs on non-client environment");
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
    
    private static void loadLibs(IDiscoveryPipeline pipeline, IModFile parent) {
        try {
            final var nativesSuffix = "natives-" + switch (Platform.get()) {
                case LINUX -> "linux";
                case MACOSX -> "macos";
                case WINDOWS -> "windows";
            } + (Platform.getArchitecture() == Platform.Architecture.ARM64 ? "-arm64" : "");
            walk(Path.of(CinnabarLocator.class.getResource("/META-INF/lib/").toURI()), 1).forEach(path -> {
                final var filename = path.getFileName().toString();
                if (!filename.endsWith(".jar")) {
                    return;
                }
                if (filename.contains("natives") && !filename.contains(nativesSuffix)) {
                    // dont load natives for different platforms
                    return;
                }
                LOGGER.debug("Loading lib {}", filename);
                pipeline.addModFile(IModFile.create(SecureJar.from(path), JarModsDotTomlModFileReader::manifestParser, IModFile.Type.LIBRARY, ModFileDiscoveryAttributes.DEFAULT.withParent(parent)));
            });
        } catch (Exception e) {
            LOGGER.error("Fatal error encountered locating Cinnabar lib jars", e);
        }
    }
}
