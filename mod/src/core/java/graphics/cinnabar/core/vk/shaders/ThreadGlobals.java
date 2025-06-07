package graphics.cinnabar.core.vk.shaders;

import graphics.cinnabar.api.cvk.systems.CVKGpuDevice;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.util.Destroyable;

import static org.lwjgl.util.shaderc.Shaderc.*;

public class ThreadGlobals implements Destroyable {
    
    private static final ThreadLocal<ThreadGlobals> globals = ThreadLocal.withInitial(() -> CVKGpuDevice.get().destroyOnShutdown(new ThreadGlobals()));
    
    public static ThreadGlobals get() {
        return globals.get();
    }
    
    public final long ShaderCCompiler = shaderc_compiler_initialize();
    public final long ShaderCCompilerGLOptions = shaderc_compile_options_initialize();
    public final long ShaderCCompilerVKOptions = shaderc_compile_options_initialize();
    
    {
        shaderc_compile_options_set_optimization_level(ShaderCCompilerGLOptions, shaderc_optimization_level_zero);
        shaderc_compile_options_set_target_env(ShaderCCompilerGLOptions, shaderc_target_env_opengl, shaderc_env_version_opengl_4_5);
        shaderc_compile_options_set_auto_bind_uniforms(ShaderCCompilerGLOptions, true);
        shaderc_compile_options_set_auto_map_locations(ShaderCCompilerGLOptions, true);
        shaderc_compile_options_set_forced_version_profile(ShaderCCompilerGLOptions, 460, shaderc_profile_core);
        
        shaderc_compile_options_set_optimization_level(ShaderCCompilerVKOptions, shaderc_optimization_level_zero);
        shaderc_compile_options_set_target_env(ShaderCCompilerVKOptions, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_3);
        shaderc_compile_options_set_auto_map_locations(ShaderCCompilerVKOptions, true);
        shaderc_compile_options_set_forced_version_profile(ShaderCCompilerVKOptions, 460, shaderc_profile_core);
    }
    
    private ThreadGlobals(){
    }
    
    @Override
    @ThreadSafety.MainGraphics
    public void destroy() {
    
    }
}
