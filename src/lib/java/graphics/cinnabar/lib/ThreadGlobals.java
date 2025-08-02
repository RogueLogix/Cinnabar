package graphics.cinnabar.lib;

import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.util.Destroyable;

import static org.lwjgl.util.shaderc.Shaderc.*;

public class ThreadGlobals implements Destroyable {
    
    private static final ThreadLocal<ThreadGlobals> globals = ThreadLocal.withInitial(ThreadGlobals::new);
    
    public static ThreadGlobals get() {
        return globals.get();
    }
    
    public final long ShaderCCompiler = shaderc_compiler_initialize();
    public final long ShaderCCompilerVKOptions = shaderc_compile_options_initialize();
    public final long ShaderCCompilerGLOptions = shaderc_compile_options_initialize();
    
    {
        // VK shaders don't get optimized at all, im assuming you aren't being dumb
        shaderc_compile_options_set_optimization_level(ShaderCCompilerVKOptions, shaderc_optimization_level_zero);
        shaderc_compile_options_set_generate_debug_info(ShaderCCompilerVKOptions);
        shaderc_compile_options_set_target_env(ShaderCCompilerVKOptions, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        // im also assuming you have bound everything correctly
        
        // GL shaders, optimize for perf w/ debug output gives the reflection info i need but also removes unused stuff
        shaderc_compile_options_set_optimization_level(ShaderCCompilerGLOptions, shaderc_optimization_level_performance);
        shaderc_compile_options_set_generate_debug_info(ShaderCCompilerGLOptions);
        shaderc_compile_options_set_target_env(ShaderCCompilerGLOptions, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        shaderc_compile_options_set_auto_bind_uniforms(ShaderCCompilerGLOptions, true);
        shaderc_compile_options_set_auto_map_locations(ShaderCCompilerGLOptions, true);
        shaderc_compile_options_set_forced_version_profile(ShaderCCompilerGLOptions, 460, shaderc_profile_core);
    }
    
    private ThreadGlobals() {
    }
    
    @Override
    @ThreadSafety.MainGraphics
    public void destroy() {
        
    }
}
