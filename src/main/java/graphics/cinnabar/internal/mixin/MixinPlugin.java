package graphics.cinnabar.internal.mixin;

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
        if (targetClass.superName.equals("com/mojang/blaze3d/pipeline/RenderTarget") && !targetClass.name.equals("graphics/cinnabar/internal/extensions/blaze3d/pipeline/CinnabarRenderTarget")) {
            targetClass.superName = "graphics/cinnabar/internal/extensions/blaze3d/pipeline/CinnabarRenderTarget";
            for (MethodNode method : targetClass.methods) {
                if(!method.name.equals("<init>")){
                    continue;
                }
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode methodInsnNode)) {
                        continue;
                    }
                    if (!methodInsnNode.owner.equals("com/mojang/blaze3d/pipeline/RenderTarget")) {
                        continue;
                    }
                    methodInsnNode.owner = targetClass.superName;
                    break;
                }
                
                break;
            }
        }
    }
    
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    
    }
}
