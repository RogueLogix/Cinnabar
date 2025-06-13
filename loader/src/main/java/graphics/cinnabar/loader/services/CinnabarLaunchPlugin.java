package graphics.cinnabar.loader.services;

import com.mojang.logging.LogUtils;
import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.NamedPath;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import net.neoforged.fml.loading.FMLLoader;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

public class CinnabarLaunchPlugin implements ILaunchPluginService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<String, String> functionCallRedirections = new HashMap<>();
    private final Map<String, String> hierarchyRewrites = new HashMap<>();
    
    static {
        LOGGER.trace("CinnabarLaunchPlugin loaded!");
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
        validateAndInitializeRedirectAnnotations();
    }
    
    private static boolean redirectInitDone = false;
    
    public static boolean initCompleted() {
        return redirectInitDone;
    }
    
    public void validateAndInitializeRedirectAnnotations() {
        @Nullable
        final var cinnabarModFile = FMLLoader.getLoadingModList().getModFileById("cinnabar");
        if (cinnabarModFile == null) {
            return;
        }
        @SuppressWarnings("UnstableApiUsage")
        final var cinnabarScanResults = cinnabarModFile.getFile().getScanResult();
        final Map<String, String> functionCallRedirectDestinations = new HashMap<>();
        
        for (final var annotation : cinnabarScanResults.getAnnotations()) {
            final var annotationName = annotation.annotationType().getClassName();
            if (annotationName.equals("graphics.cinnabar.api.annotations.CinnabarRedirectIMPL$Dst")) {
                final var redirect = annotation.annotationData().get("value");
                if (redirect instanceof String redirectSrc) {
                    final var redirectDst = annotation.clazz().getInternalName() + "/" + annotation.memberName();
                    final var functionDescription = annotation.memberName().substring(annotation.memberName().indexOf('('));
                    functionCallRedirectDestinations.put(redirectDst, redirectSrc + functionDescription);
                }
                continue;
            }
            if (annotationName.equals("graphics.cinnabar.api.annotations.RewriteHierarchy")) {
                final var newClazz = annotation.clazz();
                for (final var classData : cinnabarScanResults.getClasses()) {
                    if (classData.clazz() == newClazz) {
                        if ("com/java/object".equals(classData.parent().getInternalName())) {
                            throw new IllegalStateException();
                        }
                        hierarchyRewrites.put(classData.parent().getInternalName(), newClazz.getInternalName());
                        break;
                    }
                }
            }
        }
        
        for (final var annotation : cinnabarScanResults.getAnnotations()) {
            final var annotationName = annotation.annotationType().getClassName();
            if (!annotationName.equals("graphics.cinnabar.api.annotations.CinnabarRedirectIMPL")) {
                continue;
            }
            final var redirect = annotation.annotationData().get("value");
            if (redirect instanceof String redirectDst) {
                final var functionDescription = annotation.memberName().substring(annotation.memberName().indexOf('('));
                final var redirectSrc = annotation.clazz().getInternalName() + "/" + annotation.memberName();
                final var expectedSrc = functionCallRedirectDestinations.remove(redirectDst + functionDescription);
                if (expectedSrc == null) {
                    throw new IllegalStateException("Missing Dst annotation for impl redirect " + redirectSrc);
                }
                if (!redirectSrc.equals(expectedSrc)) {
                    throw new IllegalStateException("Incorrect Dst annotation for impl redirect " + redirectSrc + " expected source of " + expectedSrc);
                }
                functionCallRedirections.put(redirectSrc, redirectDst);
            }
        }
        functionCallRedirectDestinations.forEach((dst, src) -> {
            throw new IllegalStateException("Missing Src annotation for impl redirect " + dst + " expected one at " + src);
        });
        
        redirectInitDone = true;
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
        
        boolean didRewrite = false;
        
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode typeInsnNode) {
                    if (typeInsnNode.desc.equals("graphics/cinnabar/api/exceptions/RedirectImplemented")) {
                        if (!classNode.name.startsWith("graphics/cinnabar")) {
                            throw new IllegalStateException("Attempt to instantiate RedirectImplemented from outside Cinnabar package");
                        }
                        if (method.visibleAnnotations != null && method.visibleAnnotations.stream().anyMatch(annotationNode -> annotationNode.desc.equals("Lgraphics/cinnabar/api/annotations/CinnabarRedirectIMPL;"))) {
                            continue;
                        }
                        throw new IllegalStateException("Method throwing RedirectImplemented must be annotated with @CinnabarRedirectIMPL " + classNode.name + " " + method.name + method.desc);
                    }
                }
                if (instruction instanceof MethodInsnNode methodInsnNode) {
                    final var callName = methodInsnNode.owner + "/" + methodInsnNode.name + methodInsnNode.desc;
                    final var redirect = functionCallRedirections.get(callName);
                    if (redirect == null) {
                        continue;
                    }
                    final var redirectOwnerClass = redirect.substring(0, redirect.lastIndexOf('/'));
                    final var redirectFunctionName = redirect.substring(redirect.lastIndexOf('/') + 1);
                    methodInsnNode.owner = redirectOwnerClass;
                    methodInsnNode.name = redirectFunctionName;
                    methodInsnNode.itf = false;
                    didRewrite = true;
                }
            }
        }
        
        
        if (classNode.name.startsWith("graphics/cinnabar")) {
            // dont rewrite supers or constructors from my own classes
            return didRewrite ? ComputeFlags.SIMPLE_REWRITE : ComputeFlags.NO_REWRITE;
        }
        
        final var oldSuper = classNode.superName;
        final var newSuper = hierarchyRewrites.get(classNode.superName);
        
        if (newSuper != null) {
            didRewrite = true;
            classNode.superName = newSuper;
            for (MethodNode method : classNode.methods) {
                if (!method.name.equals("<init>")) {
                    continue;
                }
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode methodInsnNode)) {
                        continue;
                    }
                    if (!methodInsnNode.owner.equals(oldSuper)) {
                        continue;
                    }
                    methodInsnNode.owner = classNode.superName;
                    break;
                }
            }
        }
        
        final var methods = classNode.methods;
        for (int i = 0; i < methods.size(); i++) {
            final var method = methods.get(i);
            String oldDstClass = null;
            String newDstClass = null;
            int lineNum = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LineNumberNode lineNumberNode) {
                    lineNum = lineNumberNode.line;
                }
                final var opcode = instruction.getOpcode();
                if (newDstClass == null && opcode == Opcodes.NEW && instruction instanceof TypeInsnNode typeInsnNode) {
                    final var newClass = hierarchyRewrites.get(typeInsnNode.desc);
                    if (newClass == null) {
                        continue;
                    }
                    didRewrite = true;
                    oldDstClass = typeInsnNode.desc;
                    newDstClass = newClass;
                    typeInsnNode.desc = newClass;
                    LOGGER.debug("Rewriting {} instance creation in class {} in function {} at line {} with type {}", oldDstClass, classNode.name, method.name, lineNum, newDstClass);
                    continue;
                }
                if (newDstClass != null && opcode == Opcodes.INVOKESPECIAL && instruction instanceof MethodInsnNode methodInsnNode) {
                    if (methodInsnNode.owner.equals(oldDstClass) && methodInsnNode.name.contains("<init>")) {
                        methodInsnNode.owner = newDstClass;
                        oldDstClass = null;
                        newDstClass = null;
                    }
                }
            }
        }
        
        // because im only changing constructor calls, minimal changes needed
        return didRewrite ? ComputeFlags.SIMPLE_REWRITE : ComputeFlags.NO_REWRITE;
    }
}
