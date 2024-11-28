package graphics.cinnabar.internal.extensions.blaze3d.shaders;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.EffectProgram;
import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import graphics.cinnabar.internal.CinnabarDebug;
import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.exceptions.CompileFailure;
import graphics.cinnabar.internal.vulkan.util.LiveHandles;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.anarres.cpp.CppReader;
import org.anarres.cpp.Preprocessor;
import org.anarres.cpp.StringLexerSource;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.taumc.glsl.Transformer;
import org.taumc.glsl.grammar.GLSLLexer;
import org.taumc.glsl.grammar.GLSLParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import static graphics.cinnabar.Cinnabar.CINNABAR_LOG;
import static graphics.cinnabar.Cinnabar.LOGGER;
import static graphics.cinnabar.internal.vulkan.exceptions.VkException.throwFromCode;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;

@NonnullDefault
public class CinnabarProgram extends EffectProgram {
    
    private static final long SHADERC_COMPILER = shaderc_compiler_initialize();
    private static final long SHADERC_COMPILER_OPTIONS = shaderc_compile_options_initialize();
    
    static {
        shaderc_compile_options_set_optimization_level(SHADERC_COMPILER_OPTIONS, shaderc_optimization_level_zero);
        shaderc_compile_options_set_target_env(SHADERC_COMPILER_OPTIONS, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_3);
    }
    
    public final long handle;
    private boolean closed = false;
    
    public CinnabarProgram(Type type, long handle, String name) {
        super(type, -1, name);
        this.handle = handle;
        LiveHandles.create(this);
    }
    
    @Override
    public void attachToShader(Shader shader) {
    }
    
    @Override
    public void close() {
        references--;
        if (references > 0 || closed) {
            return;
        }
        closed = true;
        LiveHandles.destroy(this);
        final var device = CinnabarRenderer.device();
        vkDestroyShaderModule(device, handle, null);
        super.type.getPrograms().remove(this.getName());
    }
    
    public static CinnabarProgram compileShader(JsonObject jsonObject, VertexFormat vertexFormat, Type type, String name, InputStream shaderData, String sourceName, GlslPreprocessor preprocessor) throws IOException {
        
        if (CinnabarDebug.DEBUG) {
            CINNABAR_LOG.debug("Compiling {} shader: {}", type.toString(), name);
        }
        
        final var device = CinnabarRenderer.device();
        
        final var rawShaderSource = IOUtils.toString(shaderData, StandardCharsets.UTF_8);
        final var preProcessedShaderSources = preprocessor.process(rawShaderSource);
        final var preProcessedShaderSource = new StringBuilder();
        for (String processedShaderSource : preProcessedShaderSources) {
            preProcessedShaderSource.append(processedShaderSource);
            preProcessedShaderSource.append("\n");
        }
        final var versionRemovedPreprocessedSource = preProcessedShaderSource.toString().replace("#version", "//#version");
        
        GLSLParser parser;
        try {
            //#define BULLSHIT_TO_MAKE_ANTARES_HAPPY
            GLSLLexer lexer = new GLSLLexer(CharStreams.fromReader(new CppReader(new Preprocessor(new StringLexerSource(versionRemovedPreprocessedSource, true)))));
            parser = new GLSLParser(new CommonTokenStream(lexer));
        } catch (Exception e) {
            throw e;
        }
        
        parser.setBuildParseTree(true);
        var translationUnit = parser.translation_unit();
        
        Transformer transformer = new Transformer(translationUnit);
        
        if (type == Type.VERTEX) {
            final var elements = vertexFormat.getElementAttributeNames();
            final var elementMapping = vertexFormat.getElementMapping();
            for (int i = 0; i < elements.size(); i++) {
                final var elementName = elements.get(i);
                final var element = Objects.requireNonNull(elementMapping.get(elementName));
                transformer.removeVariable(elementName);
                final var elementDeclarationFormat = "layout (location = %d) in %s %s;";
                final var elementDeclaration = String.format(elementDeclarationFormat, i, elementGLSLType(element), elementName);
                transformer.injectVariable(elementDeclaration);
            }
        } else {
            final var inputs = Arrays.stream(versionRemovedPreprocessedSource.split("\n")).filter(a -> a.startsWith("flat in") || a.startsWith("in")).toList();
            for (int i = 0; i < inputs.size(); i++) {
                final var inputLine = inputs.get(i);
                final var nameStartIndex = inputLine.lastIndexOf(' ', inputLine.indexOf(';'));
                final var variableName = inputLine.substring(nameStartIndex + 1, inputLine.indexOf(';'));
                transformer.removeVariable(variableName);
                final var elementDeclarationFormat = "layout (location = %d) %s";
                final var elementDeclaration = String.format(elementDeclarationFormat, i, inputLine);
                transformer.injectVariable(elementDeclaration);
            }
        }
        
        final var outputs = Arrays.stream(versionRemovedPreprocessedSource.split("\n")).filter(a -> a.startsWith("flat out") || a.startsWith("out")).toList();
        for (int i = 0; i < outputs.size(); i++) {
            final var inputLine = outputs.get(i);
            final var nameStartIndex = inputLine.lastIndexOf(' ', inputLine.indexOf(';'));
            final var variableName = inputLine.substring(nameStartIndex + 1, inputLine.indexOf(';'));
            transformer.removeVariable(variableName);
            final var elementDeclarationFormat = "layout (location = %d) %s";
            final var elementDeclaration = String.format(elementDeclarationFormat, i, inputLine);
            transformer.injectVariable(elementDeclaration);
        }
        
        final var uniforms = jsonObject.getAsJsonArray("uniforms");
        if (!uniforms.isEmpty()) {
            final var UBOBuilder = new StringBuilder();
            UBOBuilder.append("layout (set = 0, binding = 0, std140) uniform UBO {\n");
            for (JsonElement uniform : uniforms) {
                if (!(uniform instanceof JsonObject uniformJsonObject)) {
                    continue;
                }
                final var uniformName = uniformJsonObject.getAsJsonPrimitive("name").getAsString();
                final var uniformType = uniformTypeToGLSLType(uniformJsonObject.getAsJsonPrimitive("type").getAsString(), uniformJsonObject.getAsJsonPrimitive("count").getAsInt());
                
                UBOBuilder.append(uniformType);
                UBOBuilder.append(" ");
                UBOBuilder.append(uniformName);
                UBOBuilder.append(";");
                UBOBuilder.append("\n");
                if (!transformer.hasVariable(uniformName)) {
                    continue;
                }
                transformer.removeVariable(uniformName);
            }
            UBOBuilder.append("}; \n");
            transformer.injectVariable(UBOBuilder.toString());
        }
        
        final var samplers = jsonObject.getAsJsonArray("samplers");
        int samplerIndex = 0;
        for (JsonElement sampler : samplers) {
            if (!(sampler instanceof JsonObject samplerJsonObject)) {
                continue;
            }
            final var samplerName = samplerJsonObject.getAsJsonPrimitive("name").getAsString();
            if (!transformer.hasVariable(samplerName)) {
                samplerIndex++;
                continue;
            }
            transformer.removeVariable(samplerName);
            final var elementDeclarationFormat = "layout (set = %d, binding = %d) uniform sampler2D %s;";
            final var elementDeclaration = String.format(elementDeclarationFormat, uniforms.isEmpty() ? 0 : 1, samplerIndex, samplerName);
            transformer.injectVariable(elementDeclaration);
            samplerIndex++;
        }
        
        transformer.replaceExpression("gl_VertexID", "gl_VertexIndex");
        
        
        final var builder = new StringBuilder();
        builder.append("#version 460\n");
        getFormattedShader(translationUnit, builder);
        final var postTransformShaderSource = builder.toString().replace("\nuniform", "\n//uniform");
        if (CinnabarDebug.DEBUG) {
            for (String s : postTransformShaderSource.split("\n")) {
                if (s.startsWith("//uniform")) {
                    CINNABAR_LOG.warn("Removed unused uniform line \"{}\"", s.substring(2));
                }
            }
        }
        
        final var compileResult = shaderc_compile_into_spv(SHADERC_COMPILER, postTransformShaderSource, type == Type.VERTEX ? shaderc_vertex_shader : shaderc_fragment_shader, name, "main", SHADERC_COMPILER_OPTIONS);
        final var compileStatus = shaderc_result_get_compilation_status(compileResult);
        if (compileStatus != shaderc_compilation_status_success) {
            @Nullable final var errorMessage = shaderc_result_get_error_message(compileResult);
            throw new CompileFailure(errorMessage);
        }
        
        final long shaderHandle;
        try (final var stack = MemoryStack.stackPush()) {
            final var createInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default();
            @Nullable final var spvCode = shaderc_result_get_bytes(compileResult);
            assert spvCode != null;
            createInfo.pCode(spvCode);
            final var handlePtr = stack.mallocLong(1);
            throwFromCode(vkCreateShaderModule(device, createInfo, null, handlePtr));
            shaderHandle = handlePtr.get(0);
        }
        
        shaderc_result_release(compileResult);
        
        if (CinnabarDebug.DEBUG) {
            CINNABAR_LOG.debug("Compiled {} shader: {}", type.toString(), name);
        }
        
        final var program = new CinnabarProgram(type, shaderHandle, name);
        type.getPrograms().put(name, program);
        return program;
    }
    
    public static String tab = "";
    
    private static void getFormattedShader(ParseTree tree, StringBuilder stringBuilder) {
        if (tree instanceof TerminalNode) {
            if (((TerminalNode) tree).getSymbol().getType() == Token.EOF) {
                return;
            }
            String text = tree.getText();
            stringBuilder.append(text);
            if (text.equals("{")) {
                stringBuilder.append(" \n\t"); //TODO fix indent
                tab = "\t";
            }
            if (text.equals("}")) {
                stringBuilder.deleteCharAt(stringBuilder.length() - 2);
                stringBuilder.append("\n");
                tab = "";
            }
            stringBuilder.append(text.equals(";") ? " \n" + tab : " ");
        } else {
            for (int i = 0; i < tree.getChildCount(); i++) {
                getFormattedShader(tree.getChild(i), stringBuilder);
            }
        }
    }
    
    private static String uniformTypeToGLSLType(String type, int count) {
        return switch (type) {
            case "matrix4x4" -> "mat4";
            case "matrix3x3" -> "mat3";
            case "float" -> switch (count) {
                case 1 -> "float";
                case 2 -> "vec2";
                case 3 -> "vec3";
                case 4 -> "vec4";
                default -> throw new IllegalStateException("Unexpected value: " + count);
            };
            case "int" -> switch (count) {
                case 1 -> "int";
                case 2 -> "ivec2";
                case 3 -> "ivec3";
                case 4 -> "ivec4";
                default -> throw new IllegalStateException("Unexpected value: " + count);
            };
            default -> throw new IllegalStateException("Unexpected value: " + type);
        };
    }
    
    private static String elementGLSLType(VertexFormatElement element) {
        // only UV uses non float inputs
        return switch (element.usage() == VertexFormatElement.Usage.UV ? element.type() : VertexFormatElement.Type.FLOAT) {
            case FLOAT -> switch (element.count()) {
                case 1 -> "float";
                case 2 -> "vec2";
                case 3 -> "vec3";
                case 4 -> "vec4";
                default -> throw new IllegalStateException("Unexpected value: " + element.count());
            };
            case UINT, USHORT, UBYTE -> switch (element.count()) {
                case 1 -> "uint";
                case 2 -> "uvec2";
                case 3 -> "uvec3";
                case 4 -> "uvec4";
                default -> throw new IllegalStateException("Unexpected value: " + element.count());
            };
            case INT, SHORT, BYTE -> switch (element.count()) {
                case 1 -> "int";
                case 2 -> "ivec2";
                case 3 -> "ivec3";
                case 4 -> "ivec4";
                default -> throw new IllegalStateException("Unexpected value: " + element.count());
            };
        };
    }
}
