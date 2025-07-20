package graphics.cinnabar.core.vk.shaders;

import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.cvk.systems.CVKGpuDevice;
import graphics.cinnabar.api.util.Destroyable;

import static org.lwjgl.util.shaderc.Shaderc.*;

public class ThreadGlobals implements Destroyable {
    
    private static final ThreadLocal<ThreadGlobals> globals = ThreadLocal.withInitial(() -> CVKGpuDevice.get().destroyOnShutdown(new ThreadGlobals()));
    
    public static ThreadGlobals get() {
        return globals.get();
    }
    
    public final long ShaderCCompiler = shaderc_compiler_initialize();
    public final long ShaderCCompilerVKOptions = shaderc_compile_options_initialize();
    
    {
        // optimize for perf w/ debug output gives the reflection info i need but also removes unused stuff
        shaderc_compile_options_set_optimization_level(ShaderCCompilerVKOptions, shaderc_optimization_level_performance);
        shaderc_compile_options_set_generate_debug_info(ShaderCCompilerVKOptions);
        shaderc_compile_options_set_target_env(ShaderCCompilerVKOptions, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        shaderc_compile_options_set_auto_bind_uniforms(ShaderCCompilerVKOptions, true);
        shaderc_compile_options_set_auto_map_locations(ShaderCCompilerVKOptions, true);
        shaderc_compile_options_set_forced_version_profile(ShaderCCompilerVKOptions, 460, shaderc_profile_core);
    }
    
    private ThreadGlobals() {
    }
    
    @Override
    @ThreadSafety.MainGraphics
    public void destroy() {
        
    }
}
