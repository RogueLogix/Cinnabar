package net.roguelogix.phosphophyllite.config;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.roguelogix.phosphophyllite.config.spec.ConfigOptionsDefaults;
import net.roguelogix.phosphophyllite.config.spec.SpecObjectNode;
import net.roguelogix.phosphophyllite.parsers.Element;
import net.roguelogix.phosphophyllite.util.Pair;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

public class ConfigRegistration {
    
    public final ConfigType configType;
    public final Object rootConfigObject;
    public final SpecObjectNode rootConfigSpecNode;
    
    public final String modName;
    public final String comment;
    
    public final ConfigFormat fileFormat;
    public final File baseFile;
    public final File writeFile;
    
    private final List<Runnable> preLoadCallbacks;
    private final List<Runnable> postLoadCallbacks;
    
    @Nullable
    private Element savedLocalConfig;
    
    
    ConfigRegistration(
            Object rootConfigObject, String modName, String name, String folder,
            String comment, ConfigFormat format, ConfigType configType, ConfigType rootLevelDefaultType, boolean rootLevelReloadableDefault,
            List<Runnable> preLoadCallbacks, List<Runnable> postLoadCallbacks
    ) {
        this.configType = configType;
        this.rootConfigObject = rootConfigObject;
        final var defaultConfigOptions = new ConfigOptionsDefaults(rootLevelDefaultType, false, false, rootLevelReloadableDefault);
        this.rootConfigSpecNode = new SpecObjectNode(rootConfigObject, comment, configType, defaultConfigOptions);
        this.modName = modName;
        this.comment = comment;
        this.fileFormat = format;
        this.baseFile = new File("config/" + folder + "/" + name + "-" + configType.toString().toLowerCase(Locale.US));
        this.writeFile = new File(baseFile + "." + format.toString().toLowerCase(Locale.US));
        this.preLoadCallbacks = new ObjectArrayList<>(preLoadCallbacks);
        this.postLoadCallbacks = new ObjectArrayList<>(postLoadCallbacks);
    }
    
    public void loadLocalConfigFile(boolean reload) {
        preLoadCallbacks.forEach(Runnable::run);
        savedLocalConfig = null;
        final var foundFile = findFile(baseFile, fileFormat);
        if (foundFile == null) {
            generateFile(reload);
            postLoadCallbacks.forEach(Runnable::run);
            return;
        }
        
        final var fileElement = readFile(foundFile.first(), foundFile.second());
        if (!foundFile.first().equals(writeFile)) {
            writeFile(null, foundFile.first(), foundFile.second());
        }
        if (fileElement == null) {
            generateFile(reload);
            postLoadCallbacks.forEach(Runnable::run);
            return;
        }
        final var orderCorrectedTree = rootConfigSpecNode.correctElementOrder(fileElement);
        final var valueCorrectedTree = rootConfigSpecNode.correctToValidState(orderCorrectedTree);
        if (valueCorrectedTree == null) {
            generateFile(reload);
            postLoadCallbacks.forEach(Runnable::run);
            return;
        }
        final var reloadTrimmed = reload ? rootConfigSpecNode.trimToReloadable(valueCorrectedTree) : valueCorrectedTree;
        if (reloadTrimmed != null) {
            rootConfigSpecNode.writeElement(reloadTrimmed);
        }
        final var regeneratedTree = rootConfigSpecNode.regenerateMissingElements(valueCorrectedTree);
        writeFile(regeneratedTree, writeFile, fileFormat);
        postLoadCallbacks.forEach(Runnable::run);
    }
    
    private void generateFile(boolean reload) {
        if (!reload) {
            rootConfigSpecNode.writeDefault();
        }
        writeFile(rootConfigSpecNode.generateCurrentElement(), writeFile, fileFormat);
    }
    
    public void loadRemoteConfig(Element elementTree, boolean reload) {
        if (configType != ConfigType.COMMON) {
            return;
        }
        preLoadCallbacks.forEach(Runnable::run);
        if (savedLocalConfig == null) {
            savedLocalConfig = rootConfigSpecNode.generateSyncElement();
        }
        elementTree = rootConfigSpecNode.removeUnknownElements(elementTree);
        if (elementTree == null) {
            elementTree = rootConfigSpecNode.generateSyncElement();
        }
        elementTree = rootConfigSpecNode.correctToValidState(elementTree);
        if (elementTree == null) {
            elementTree = rootConfigSpecNode.generateSyncElement();
        }
        if (reload) {
            elementTree = rootConfigSpecNode.trimToReloadable(elementTree);
        }
        if (elementTree == null) {
            postLoadCallbacks.forEach(Runnable::run);
            return;
        }
        rootConfigSpecNode.writeElement(elementTree);
        postLoadCallbacks.forEach(Runnable::run);
    }
    
    public void unloadRemoteConfig() {
        if (configType != ConfigType.COMMON) {
            return;
        }
        if (savedLocalConfig == null) {
            return;
        }
        rootConfigSpecNode.writeElement(savedLocalConfig);
        savedLocalConfig = null;
    }
    
    
    public boolean isLocalLoaded() {
        return savedLocalConfig == null;
    }
    
    private static void writeFile(@Nullable Element tree, File file, ConfigFormat format) {
        if (tree == null) {
            var path = Paths.get(String.valueOf(file));
            if (Files.exists(path)) {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // i really dont care
                }
            }
            return;
        }
        final var writeString = format.parse(tree);
        try {
            //noinspection ResultOfMethodCallIgnored
            file.getParentFile().mkdirs();
            Files.write(Paths.get(String.valueOf(file)), writeString.getBytes());
        } catch (IOException e) {
            ConfigManager.LOGGER.error("Failed to write config file " + file);
            e.printStackTrace();
        }
    }
    
    @Nullable
    private static Element readFile(File file, ConfigFormat format) {
        String fileContents;
        try {
            fileContents = new String(Files.readAllBytes(Paths.get(String.valueOf(file))));
        } catch (IOException e) {
            ConfigManager.LOGGER.error("Failed to read config file " + file);
            return null;
        }
        final var element = format.parse(fileContents);
        if (element == null) {
            ConfigManager.LOGGER.error("Failed to parse config file " + file);
        }
        return element;
    }
    
    @Nullable
    private static Pair<File, ConfigFormat> findFile(File baseFile, ConfigFormat expectedFormat) {
        File file = null;
        ConfigFormat format = null;
        for (ConfigFormat value : ConfigFormat.values()) {
            File fullFile = new File(baseFile + "." + value.toString().toLowerCase(Locale.US));
            if (fullFile.exists()) {
                if (file != null) {
                    // why the fuck are there multiple?
                    // if its the correct format, we will use it, otherwise, whatever we have is good enough
                    if (expectedFormat == value) {
                        file = fullFile;
                        format = value;
                    }
                } else {
                    format = value;
                    file = fullFile;
                }
            }
        }
        
        if (file == null) {
            return null;
        }
        
        return new Pair<>(file, format);
    }
    
    public boolean isEmpty() {
        // if enable advanced is the only option, its empty
        return rootConfigSpecNode.subNodeList.size() == 1;
    }
}
