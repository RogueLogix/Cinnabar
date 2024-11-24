package graphics.cinnabar.internal.mixin;

import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

@NonnullDefault
public class MixinPlugin implements IMixinConfigPlugin {
    
    private static final Object2ReferenceMap<String, @Nullable String> superclassReplacements = new Object2ReferenceOpenHashMap<>();
    
    static {
        superclassReplacements.put("com/mojang/blaze3d/pipeline/RenderTarget", "graphics/cinnabar/internal/extensions/blaze3d/pipeline/CinnabarRenderTarget");
        superclassReplacements.put("com/mojang/blaze3d/shaders/Program", "graphics/cinnabar/internal/extensions/blaze3d/shaders/CinnabarProgram");
        superclassReplacements.put("net/minecraft/client/renderer/texture/AbstractTexture", "graphics/cinnabar/internal/extensions/minecraft/renderer/texture/CinnabarAbstractTexture");
    }
    
    
    @Override
    public void onLoad(String mixinPackage) {
    
    }
    
    @Override
    @Nullable
    public String getRefMapperConfig() {
        return null;
    }
    
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }
    
    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    
    }
    
    @Override
    public List<String> getMixins() {
        return List.of();
    }
    
    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        @Nullable
        final var superClassReplacement = superclassReplacements.get(targetClass.superName);
        if (superClassReplacement != null && !targetClassName.equals(superClassReplacement)) {
            final var oldSuper = targetClass.superName;
            targetClass.superName = superClassReplacement;
            for (MethodNode method : targetClass.methods) {
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
                    methodInsnNode.owner = targetClass.superName;
                    break;
                }
            }
        }
    }
    
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    
    }
}
