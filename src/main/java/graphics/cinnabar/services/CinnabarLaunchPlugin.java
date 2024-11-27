package graphics.cinnabar.services;

import com.mojang.logging.LogUtils;
import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.NamedPath;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import graphics.cinnabar.services.CinnabarEarlyWindowProvider;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;

import java.util.*;

public class CinnabarLaunchPlugin implements ILaunchPluginService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<Pair<String, String>> constructorCallRewrites;
    
    static {
        LOGGER.trace("CinnabarLaunchPlugin loaded!");
        
        final var list = new ArrayList<Pair<String, String>>();
        constructorCallRewrites = Collections.unmodifiableList(list);
        
        list.add(new ImmutablePair<>("com/mojang/blaze3d/vertex/VertexBuffer", "graphics/cinnabar/internal/extensions/blaze3d/vertex/CinnabarVertexBuffer"));
    }
    
    public static void attemptInject() {
        // because additional launch plugins aren't discovered well, inject ourselves
        try {
            final var launcher = Launcher.INSTANCE;
            final var launcherClass = Launcher.class;
            final var launcherPluginsField = launcherClass.getDeclaredField("launchPlugins");
            launcherPluginsField.setAccessible(true);
            final var launcherPlugins = (LaunchPluginHandler) launcherPluginsField.get(launcher);
            
            final var launchPluginClass = LaunchPluginHandler.class;
            final var pluginsField = launchPluginClass.getDeclaredField("plugins");
            pluginsField.setAccessible(true);
            //noinspection unchecked
            final var pluginsMap = (Map<String, ILaunchPluginService>) pluginsField.get(launcherPlugins);
            
            final var plugin = new CinnabarLaunchPlugin();
            pluginsMap.put(plugin.name(), plugin);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
    
    @Override
    public String name() {
        return "CinnabarLaunchPlugin";
    }
    
    
    @Override
    public void initializeLaunch(ITransformerLoader transformerLoader, NamedPath[] specialPaths) {
        FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_PROVIDER, CinnabarEarlyWindowProvider.EARLY_WINDOW_NAME);
    }
    
    @Override
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty) {
        return EnumSet.of(Phase.AFTER);
    }
    
    @Override
    public int processClassWithFlags(Phase phase, ClassNode classNode, Type classType, String reason) {
        if (phase != Phase.AFTER) {
            return ComputeFlags.NO_REWRITE;
        }
        if (classNode.name.startsWith("graphics/cinnabar")) {
            // dont rewrite my own classes
            return ComputeFlags.NO_REWRITE;
        }
        
        boolean didRewrite = false;
        
        final var methods = classNode.methods;
        for (int i = 0; i < methods.size(); i++) {
            final var method = methods.get(i);
            Pair<String, String> rewritingConstructorCall = null;
            int lineNum = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if(instruction instanceof LineNumberNode lineNumberNode){
                    lineNum = lineNumberNode.line;
                }
                final var opcode = instruction.getOpcode();
                if (rewritingConstructorCall == null && opcode == Opcodes.NEW && instruction instanceof TypeInsnNode typeInsnNode) {
                    for (Pair<String, String> constructorCallRewrite : constructorCallRewrites) {
                        if (typeInsnNode.desc.equals(constructorCallRewrite.getLeft())) {
                            typeInsnNode.desc = constructorCallRewrite.getRight();
                            rewritingConstructorCall = constructorCallRewrite;
                            didRewrite = true;
                            LOGGER.debug("Rewriting {} instance creation in class {} in function {} at line {} with type {}", constructorCallRewrite.getLeft(), classNode.name, method.name, lineNum, constructorCallRewrite.getRight());
                            break;
                        }
                    }
                    continue;
                }
                if (rewritingConstructorCall != null && opcode == Opcodes.INVOKESPECIAL && instruction instanceof MethodInsnNode methodInsnNode) {
                    for (Pair<String, String> constructorCallRewrite : constructorCallRewrites) {
                        if (methodInsnNode.owner.equals(constructorCallRewrite.getLeft()) && methodInsnNode.name.contains("<init>")) {
                            methodInsnNode.owner = rewritingConstructorCall.getRight();
                            break;
                        }
                    }
                }
            }
        }
        
        // because im only changing constructor calls, minimal changes needed
        return didRewrite ? ComputeFlags.SIMPLE_REWRITE : ComputeFlags.NO_REWRITE;
    }
}
