package graphics.cinnabar.core.vk.shaders;

import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.util.Pair;
import graphics.cinnabar.lib.repack.tnjson.TnJson;
import it.unimi.dsi.fastutil.longs.LongObjectImmutablePair;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import org.anarres.cpp.CppReader;
import org.anarres.cpp.Preprocessor;
import org.anarres.cpp.StringLexerSource;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.taumc.glsl.Transformer;
import org.taumc.glsl.grammar.GLSLLexer;
import org.taumc.glsl.grammar.GLSLParser;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static graphics.cinnabar.core.CinnabarCore.CINNABAR_CORE_LOG;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spvc.*;

public class ShaderProcessing {
    @ThreadSafety.Many
    public static Pair<String, String> processShaders(String rawVertexShader, String rawFragmentShader, String vertexShaderName, String fragmentShaderName, List<String> pushConstants, List<List<String>> dedicatedUBOs) {
        
        // TODO: remove this when the shader gets fixed
        if ("minecraft:core/entity".equals(vertexShaderName)) {
            rawVertexShader = rawVertexShader.replace("overlayColor = texelFetch(Sampler1, UV1, 0);", """
                        #ifndef NO_OVERLAY
                        overlayColor = texelFetch(Sampler1, UV1, 0);
                        #endif
                    """);
        }
        
        final var versionRemovedPreprocessedVertexSource = rawVertexShader.replace("#version", "#define CINNABAR_VK\n#define CINNABAR_VERTEX_SHADER //");
        final var versionRemovedPreprocessedFragmentSource = rawFragmentShader.replace("#version", "#define CINNABAR_VK\n#define CINNABAR_FRAGMENT_SHADER //");
        
        final var vertexTranslationUnit = glslTransformerIntakeCode(versionRemovedPreprocessedVertexSource);
        final var fragmentTranslationUnit = glslTransformerIntakeCode(versionRemovedPreprocessedFragmentSource);
        final var reformattedVertexShader = getFormattedShader(vertexTranslationUnit, new StringBuilder()).toString();
        final var reformattedFragmentShader = getFormattedShader(fragmentTranslationUnit, new StringBuilder()).toString();
        
        final var vertexShaderReflection = reflectOpenGLSL(vertexShaderName, reformattedVertexShader, true);
        final var fragmentShaderReflection = reflectOpenGLSL(fragmentShaderName, reformattedFragmentShader, false);
        
        final var vertexTransformer = new Transformer(vertexTranslationUnit);
        final var fragmentTransformer = new Transformer(fragmentTranslationUnit);
        
        // TODO: bug Ferri to add this to GLSLT instead of relying on specific string bullshit
        final var vertexInputLines = reformattedVertexShader.lines().filter(line -> line.contains(" out ")).toList();
        final var vertexOutputLines = reformattedVertexShader.lines().filter(line -> line.contains(" out ")).toList();
        final var fragmentInputLines = reformattedFragmentShader.lines().filter(line -> line.contains(" in ")).toList();
        final var fragmentOutputLines = reformattedFragmentShader.lines().filter(line -> line.contains(" in ")).toList();
        final var uniformLines = Stream.concat(reformattedVertexShader.lines(), reformattedFragmentShader.lines()).filter(line -> line.contains(" uniform ") || line.contains(" buffer ")).filter(uniformLine -> {
            // skip UBOs
            // GLSLT output is consistent enough, i can rely on this being in the declaration line
            if (uniformLine.contains("{")) {
                return true;
            }
            
            // skip any location specifier, this is getting merged into a UBO
            final var decStart = uniformLine.indexOf("uniform");
            final var typeStart = uniformLine.indexOf(' ', decStart) + 1;
            final var nameStart = uniformLine.indexOf(' ', typeStart) + 1;
            final var nameEnd = uniformLine.indexOf(' ', nameStart);
            
            final var type = uniformLine.substring(typeStart, nameStart);
            final var name = uniformLine.substring(nameStart, nameEnd);
            
            vertexTransformer.removeVariable(name);
            fragmentTransformer.removeVariable(name);
            
            // is used, but ferri for a removeUnusedUniforms
            return reformattedVertexShader.indexOf(name) != reformattedVertexShader.lastIndexOf(name) || reformattedFragmentShader.indexOf(name) != reformattedFragmentShader.lastIndexOf(name);
        }).toList();
        
        vertexTransformer.rename("gl_VertexID", "gl_VertexIndex");
        vertexTransformer.rename("gl_InstanceID", "gl_InstanceIndex");
        
        @SuppressWarnings("unchecked") final var stageBindings = generatedStageBindings(vertexOutputLines, (List<Map<String, Object>>) vertexShaderReflection.get("outputs"), fragmentInputLines, (List<Map<String, Object>>) fragmentShaderReflection.get("inputs"));
        final var uniforms = generatedUniformMap(uniformLines, pushConstants, dedicatedUBOs);
        
        final var vertexBindings = stageBindings.first();
        for (String toRemove : vertexBindings.first()) {
            vertexTransformer.removeVariable(toRemove);
        }
        for (String toAdd : vertexBindings.second()) {
            vertexTransformer.injectVariable(toAdd);
        }
        final var fragmentBindings = stageBindings.second();
        for (String toRemove : fragmentBindings.first()) {
            fragmentTransformer.removeVariable(toRemove);
        }
        for (String toAdd : fragmentBindings.second()) {
            fragmentTransformer.injectVariable(toAdd);
        }
        
        int nextBindingPoint = 0;
        
        final var ubos = uniforms.first();
        for (int i = 0; i < ubos.size(); i++) {
            final var UBOMap = ubos.get(i);
            if (!UBOMap.isEmpty()) {
                StringBuilder uboBuilder = new StringBuilder();
                uboBuilder.append("layout(set = 0, binding = ").append(nextBindingPoint++).append(", std140) uniform CinnabarGeneratedUBO").append(i).append(" {\n");
                for (Map.Entry<String, String> uniform : UBOMap.entrySet()) {
                    vertexTransformer.removeVariable(uniform.getKey());
                    fragmentTransformer.removeVariable(uniform.getKey());
                    uboBuilder.append(uniform.getValue());
                    uboBuilder.append(" ");
                    uboBuilder.append(uniform.getKey());
                    uboBuilder.append(";\n");
                }
                uboBuilder.append("};\n");
                final var ubo = uboBuilder.toString();
                vertexTransformer.injectVariable(ubo);
                fragmentTransformer.injectVariable(ubo);
            }
        }
        final var pushConstantsMap = uniforms.second();
        if (!pushConstantsMap.isEmpty()) {
            StringBuilder uboBuilder = new StringBuilder();
            uboBuilder.append("layout(push_constant, std430) uniform CinnabarGeneratedPushConstants {\n");
            for (Map.Entry<String, String> uniform : pushConstantsMap.entrySet()) {
                vertexTransformer.removeVariable(uniform.getKey());
                fragmentTransformer.removeVariable(uniform.getKey());
                uboBuilder.append(uniform.getValue());
                uboBuilder.append(" ");
                uboBuilder.append(uniform.getKey());
                uboBuilder.append(";\n");
            }
            uboBuilder.append("};\n");
            final var ubo = uboBuilder.toString();
            vertexTransformer.injectVariable(ubo);
            fragmentTransformer.injectVariable(ubo);
        }
        
        final var samplerNames = new HashSet<String>();
        final var samplerDeclarations = new ReferenceArrayList<String>();
        final var baseSamplerDeclaration = "layout(set = 0, binding = %s) %s;";
        for (String uniformLine : uniformLines) {
            
            // skip UBOs
            // GLSLT output is consistent enough, i can rely on this being in the declaration line
            if (uniformLine.contains("{")) {
                continue;
            }
            
            if (uniformLine.contains(" binding ") || uniformLine.contains(" location ")) {
                // expliclty bound sampler
                throw new IllegalArgumentException("Cinnabar doesn't allow explicit sampler binding");
            }
            
            final var decStart = uniformLine.indexOf("uniform");
            final var typeStart = uniformLine.indexOf(' ', decStart) + 1;
            final var nameStart = uniformLine.indexOf(' ', typeStart) + 1;
            final var nameEnd = uniformLine.indexOf(' ', nameStart);
            
            final var type = uniformLine.substring(typeStart, nameStart);
            final var name = uniformLine.substring(nameStart, nameEnd);
            
            if (!samplerNames.add(name)) {
                // already added
                continue;
            }
            
            if (!type.contains("sampler")) {
                // only handling textures here
                continue;
            }
            
            final var newDeclaration = baseSamplerDeclaration.formatted(nextBindingPoint++, uniformLine);
            samplerDeclarations.add(newDeclaration);
        }
        for (String samplerName : samplerNames) {
            vertexTransformer.removeVariable(samplerName);
            fragmentTransformer.removeVariable(samplerName);
        }
        for (String samplerDeclaration : samplerDeclarations) {
            vertexTransformer.injectVariable(samplerDeclaration);
            fragmentTransformer.injectVariable(samplerDeclaration);
        }
        
        final var regeneratedVertexShader = getFormattedShader(vertexTranslationUnit, new StringBuilder()).toString();
        final var regeneratedFragmentShader = getFormattedShader(fragmentTranslationUnit, new StringBuilder()).toString();
        
        return bindUBOs(uniformLines, regeneratedVertexShader, regeneratedFragmentShader, nextBindingPoint);
    }
    
    private static Pair<Pair<List<String>, List<String>>, Pair<List<String>, List<String>>> generatedStageBindings(
            List<String> vertexOutputLines, List<Map<String, Object>> vertexShaderOutputs,
            List<String> fragmentInputLines, List<Map<String, Object>> fragmentShaderInputs) {
        
        var unmatchedVertexLines = new ReferenceArrayList<String>();
        fragmentInputLines = new ReferenceArrayList<>(fragmentInputLines);
        
        // TODO: see if Ferri can add the ability to query location elements directly, instead of this bullshit
        
        final var vertexOuputBaseString = "layout(location = %d)%s out %s %s;";
        final var fragmentInputBaseString = "layout(location = %d)%s in %s %s;";
        
        final var vertexVariablesToRemove = new ReferenceArrayList<String>();
        final var vertexVariablesToAdd = new ReferenceArrayList<String>();
        final var fragmentVariablesToRemove = new ReferenceArrayList<String>();
        final var fragmentVariablesToAdd = new ReferenceArrayList<String>();
        
        int nextBindingLocation = 0;
        
        for (int i = 0; i < vertexOutputLines.size(); i++) {
            final var vertexOutputLine = vertexOutputLines.get(i);
            if (vertexOutputLine.contains(" component ")) {
                // TODO: its possible to support this
                throw new IllegalStateException("Cinnabar does not support packing inter-stage values");
            }
            
            final var outputNameEnd = vertexOutputLine.lastIndexOf(' ', vertexOutputLine.lastIndexOf(';') - 1);
            final var outputNameStart = vertexOutputLine.lastIndexOf(' ', outputNameEnd - 1) + 1;
            
            final var outputFlatInterpolated = vertexOutputLine.contains("flat") && vertexOutputLine.indexOf("flat") < outputNameStart;
            
            final var outputDecStart = outputFlatInterpolated && vertexOutputLine.indexOf(" flat ") > vertexOutputLine.indexOf(" out ") ? vertexOutputLine.indexOf(" flat ") + 4 : vertexOutputLine.indexOf(" out ") + 2;
            final var outputTypeStart = vertexOutputLine.indexOf(' ', outputDecStart) + 1;
            final var outputTypeEnd = vertexOutputLine.indexOf(' ', outputTypeStart);
            
            final var outputType = vertexOutputLine.substring(outputTypeStart, outputTypeEnd).trim();
            final var outputName = vertexOutputLine.substring(outputNameStart, outputNameEnd).trim();
            
            final var explicitBoundOutput = vertexOutputLine.contains(" location ");
            
            @Nullable
            String matchedFragmentInputLine = null;
            @Nullable
            Map<String, Object> matchedReflectedInput = null;
            boolean flatInterpolated = false;
            
            boolean doNameMatch = !explicitBoundOutput;
            for (int j = 0; j < fragmentInputLines.size(); j++) {
                final var fragmentInputLine = fragmentInputLines.get(j);
                
                final var inNameEnd = fragmentInputLine.lastIndexOf(' ', fragmentInputLine.lastIndexOf(';') - 1);
                final var inNameStart = fragmentInputLine.lastIndexOf(' ', inNameEnd - 1) + 1;
                
                flatInterpolated = fragmentInputLine.contains("flat") && fragmentInputLine.indexOf("flat") < inNameStart;
                
                final var inDecStart = flatInterpolated && fragmentInputLine.indexOf(" flat ") > fragmentInputLine.indexOf(" in ") ? fragmentInputLine.indexOf(" flat ") + 4 : fragmentInputLine.indexOf(" in ") + 2;
                final var inTypeStart = fragmentInputLine.indexOf(' ', inDecStart) + 1;
                final var inTypeEnd = fragmentInputLine.indexOf(' ', inTypeStart);
                
                final var inputType = fragmentInputLine.substring(inTypeStart, inTypeEnd).trim();
                final var inputName = fragmentInputLine.substring(inNameStart, inNameEnd).trim();
                
                final var explicitBoundInput = fragmentInputLine.contains(" location ");
                
                if (doNameMatch) {
                    if (!outputType.equals(inputType)) {
                        continue;
                    }
                    if (!outputName.equals(inputName)) {
                        continue;
                    }
                    fragmentInputLines.remove(j);
                    // its a match!
                    matchedFragmentInputLine = fragmentInputLine;
                    // both explicit bound, and to the same location, this is a match!
                    matchedReflectedInput = fragmentShaderInputs.stream().filter(output -> output.get("name").equals(inputName)).findFirst().get();
                    break;
                } else if (explicitBoundInput) {
                    // both are explicitly bound, do their locations match?
                    final var outputLocationStart = vertexOutputLine.indexOf("=") + 2;
                    final var outputLocationEnd = vertexOutputLine.indexOf(" ", outputLocationStart);
                    final var outputLocationStr = vertexOutputLine.substring(outputLocationStart, outputLocationEnd);
                    
                    final var inputLocationStart = fragmentInputLine.indexOf("=") + 2;
                    final var inputLocationEnd = fragmentInputLine.indexOf(" ", inputLocationStart);
                    final var inputLocationStr = fragmentInputLine.substring(outputLocationStart, inputLocationEnd);
                    
                    if (outputLocationStr.equals(inputLocationStr)) {
                        fragmentInputLines.remove(j);
                        matchedFragmentInputLine = fragmentInputLine;
                        // both explicit bound, and to the same location, this is a match!
                        matchedReflectedInput = fragmentShaderInputs.stream().filter(output -> output.get("name").equals(inputName)).findFirst().get();
                        break;
                    }
                }
                
                if (!doNameMatch && j == fragmentInputLines.size() - 1) {
                    // attempted explicit binding matching, do name matching now
                    doNameMatch = true;
                    j = -1;
                }
            }
            
            if (matchedReflectedInput != null) {
                final var location = nextBindingLocation++;
                
                final var formattedVertexOutput = vertexOuputBaseString.formatted(location, flatInterpolated ? " flat" : "", outputType, outputName);
                vertexVariablesToRemove.add(outputName);
                vertexVariablesToAdd.add(formattedVertexOutput);
                
                final var inputType = matchedReflectedInput.get("type");
                final var inputName = matchedReflectedInput.get("name");
                final var formattedFragmentInput = fragmentInputBaseString.formatted(location, flatInterpolated ? " flat" : "", inputType, inputName);
                fragmentVariablesToRemove.add((String) inputName);
                fragmentVariablesToAdd.add(formattedFragmentInput);
            } else {
                unmatchedVertexLines.add(vertexOutputLine);
            }
        }
        
        for (int i = 0; i < unmatchedVertexLines.size(); i++) {
            final var location = nextBindingLocation++;
            
            final var vertexOutputLine = unmatchedVertexLines.get(i);
            
            final var outputNameEnd = vertexOutputLine.lastIndexOf(' ', vertexOutputLine.lastIndexOf(';') - 1);
            final var outputNameStart = vertexOutputLine.lastIndexOf(' ', outputNameEnd - 1) + 1;
            
            final var outputFlatInterpolated = vertexOutputLine.contains("flat") && vertexOutputLine.indexOf("flat") < outputNameStart;
            
            final var outputDecStart = outputFlatInterpolated && vertexOutputLine.indexOf(" flat ") > vertexOutputLine.indexOf(" out ") ? vertexOutputLine.indexOf(" flat ") + 4 : vertexOutputLine.indexOf(" out ") + 2;
            final var outputTypeStart = vertexOutputLine.indexOf(' ', outputDecStart) + 1;
            final var outputTypeEnd = vertexOutputLine.indexOf(' ', outputTypeStart);
            
            final var outputType = vertexOutputLine.substring(outputTypeStart, outputTypeEnd).trim();
            final var outputName = vertexOutputLine.substring(outputNameStart, outputNameEnd).trim();
            
            final var formattedVertexOutput = vertexOuputBaseString.formatted(location, outputFlatInterpolated ? " flat" : "", outputType, outputName);
            vertexVariablesToRemove.add(outputName);
            vertexVariablesToAdd.add(formattedVertexOutput);
        }
        
        for (int i = 0; i < fragmentInputLines.size(); i++) {
            final var fragmentInputLine = fragmentInputLines.get(i);
            
            final var inNameEnd = fragmentInputLine.lastIndexOf(' ', fragmentInputLine.lastIndexOf(';') - 1);
            final var inNameStart = fragmentInputLine.lastIndexOf(' ', inNameEnd - 1) + 1;
            
            final var inputName = fragmentInputLine.substring(inNameStart, inNameEnd).trim();
            
            fragmentVariablesToRemove.add(inputName);
            CINNABAR_CORE_LOG.warn("WARNING: Removing unmatched fragment input ({}) from shader, Vulkan will throw an error about this if I dont", inputName);
        }
        
        return new Pair<>(new Pair<>(vertexVariablesToRemove, vertexVariablesToAdd), new Pair<>(fragmentVariablesToRemove, fragmentVariablesToAdd));
    }
    
    @ThreadSafety.Many
    private static Pair<List<Map<String, @Nullable String>>, Map<String, @Nullable String>> generatedUniformMap(List<String> uniformLines, List<String> pushConstantNames, List<List<String>> otherUBOsElementNames) {
        final var allUniforms = new Object2ReferenceOpenHashMap<String, @Nullable String>();
        
        for (String uniformLine : uniformLines) {
            if (uniformLine.lastIndexOf("=") > uniformLine.indexOf("uniform")) {
                throw new IllegalArgumentException("Uniform initializers unsupported by Cinnabar");
            }
            // skip UBOs
            // GLSLT output is consistent enough, i can rely on this being in the declaration line
            if (uniformLine.contains("{")) {
                continue;
            }
            
            // skip any location specifier, this is getting merged into a UBO
            final var decStart = uniformLine.indexOf("uniform");
            final var typeStart = uniformLine.indexOf(' ', decStart) + 1;
            final var nameStart = uniformLine.indexOf(' ', typeStart) + 1;
            final var nameEnd = uniformLine.indexOf(' ', nameStart);
            
            final var type = uniformLine.substring(typeStart, nameStart);
            final var name = uniformLine.substring(nameStart, nameEnd);
            
            if (type.contains("sampler")) {
                // textures are handled separately
                continue;
            }
            
            @Nullable
            final var oldType = allUniforms.put(name, type);
            if (oldType != null && !oldType.equals(type)) {
                throw new IllegalArgumentException("Uniform type mismatch between shader stages");
            }
            
            // TODO: handle default values?
        }
        final var pushConstants = new Object2ReferenceOpenHashMap<String, @Nullable String>();
        for (String pushConstantName : pushConstantNames) {
            @Nullable
            final var declaration = allUniforms.remove(pushConstantName);
            if (declaration != null) {
                pushConstants.put(pushConstantName, declaration);
            }
        }
        final var uboList = new ReferenceArrayList<Map<String, @Nullable String>>();
        for (List<String> otherUBOElements : otherUBOsElementNames) {
            final var uboMap = new Object2ReferenceOpenHashMap<String, @Nullable String>();
            for (String otherUBOElement : otherUBOElements) {
                @Nullable
                final var declaration = allUniforms.remove(otherUBOElement);
                if (declaration != null) {
                    uboMap.put(otherUBOElement, declaration);
                }
            }
            if (!uboMap.isEmpty()) {
                uboList.add(uboMap);
            }
        }
        if (!allUniforms.isEmpty()) {
            uboList.add(allUniforms);
        }
        
        return new Pair<>(uboList, pushConstants);
    }
    
    @ThreadSafety.Many
    private static Map<String, Object> reflectOpenGLSL(String shaderName, String openGLSL, boolean vertexShader) {
        final var globals = ThreadGlobals.get();
        final var compileResult = shaderc_compile_into_spv(globals.ShaderCCompiler, openGLSL, vertexShader ? shaderc_vertex_shader : shaderc_fragment_shader, shaderName, "main", globals.ShaderCCompilerGLOptions);
        final var compileStatus = shaderc_result_get_compilation_status(compileResult);
        if (compileStatus != shaderc_compilation_status_success) {
            @Nullable
            final var errorMessage = shaderc_result_get_error_message(compileResult);
            shaderc_result_release(compileResult);
            throw new RuntimeException(errorMessage);
        }
        final var spvCode = Objects.requireNonNull(shaderc_result_get_bytes(compileResult));
        
        final var reflectResult = reflectSPV(spvCode.asIntBuffer());
        shaderc_result_release(compileResult);
        return reflectResult;
    }
    
    static LongObjectImmutablePair<ByteBuffer> compileVulkanGLSL(String shaderName, String vulkanGLSL, boolean vertexShader) {
        final var globals = ThreadGlobals.get();
        final var compileResult = shaderc_compile_into_spv(globals.ShaderCCompiler, vulkanGLSL, vertexShader ? shaderc_vertex_shader : shaderc_fragment_shader, shaderName, "main", globals.ShaderCCompilerVKOptions);
        final var compileStatus = shaderc_result_get_compilation_status(compileResult);
        if (compileStatus != shaderc_compilation_status_success) {
            @Nullable
            final var errorMessage = shaderc_result_get_error_message(compileResult);
            shaderc_result_release(compileResult);
            throw new RuntimeException(errorMessage);
        }
        final var spvCode = Objects.requireNonNull(shaderc_result_get_bytes(compileResult));
        return new LongObjectImmutablePair<>(compileResult, spvCode);
    }
    
    static Map<String, Object> reflectSPV(IntBuffer spv) {
        try (final var stack = MemoryStack.stackPush()) {
            final var ptrReturn = stack.pointers(0);
            spvc_context_create(ptrReturn);
            final var spvcContext = ptrReturn.get(0);
            
            spvc_context_parse_spirv(spvcContext, spv, spv.remaining(), ptrReturn);
            final var parsedIR = ptrReturn.get(0);
            
            spvc_context_create_compiler(spvcContext, SPVC_BACKEND_JSON, parsedIR, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, ptrReturn);
            final var spvcCompilerJson = ptrReturn.get(0);
            
            spvc_compiler_compile(spvcCompilerJson, ptrReturn);
            final var jsonReflection = MemoryUtil.memASCII(ptrReturn.get(0));
            
            spvc_context_destroy(spvcContext);
            
            return TnJson.parse(jsonReflection);
        }
    }
    
    
    @ThreadSafety.Many
    private static GLSLParser.Translation_unitContext glslTransformerIntakeCode(String rawCode) {
        GLSLParser parser;
        try {
            GLSLLexer lexer = new GLSLLexer(CharStreams.fromReader(new CppReader(new Preprocessor(new StringLexerSource(rawCode, true)))));
            parser = new GLSLParser(new CommonTokenStream(lexer));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        parser.setBuildParseTree(true);
        return parser.translation_unit();
    }
    
    
    @ThreadSafety.Any
    private static StringBuilder getFormattedShader(ParseTree tree, StringBuilder stringBuilder) {
        if (tree instanceof TerminalNode) {
            if (((TerminalNode) tree).getSymbol().getType() == Token.EOF) {
                return stringBuilder;
            }
            String text = tree.getText();
            stringBuilder.append(" ");
            stringBuilder.append(text);
            if (text.equals("{")) {
                stringBuilder.append("\n\t"); //TODO fix indent
            }
            if (text.equals("}")) {
                stringBuilder.deleteCharAt(stringBuilder.length() - 2);
                stringBuilder.append("\n");
            }
            stringBuilder.append(text.equals(";") ? " \n" : "");
        } else {
            for (int i = 0; i < tree.getChildCount(); i++) {
                getFormattedShader(tree.getChild(i), stringBuilder);
            }
        }
        return stringBuilder;
    }
    
    
    @ThreadSafety.Any
    private static Pair<String, String> bindUBOs(List<String> ubos, String vertexShader, String fragmentShader, int bindingIndex) {
        final var ubosSet = new HashSet<>(ubos);
        for (String declarationLine : ubosSet) {
            final var replacementDeclaration = declarationLine.replace("layout ( std140 )", "layout (set = 0, binding = " + bindingIndex++ + ", std140 )");
            vertexShader = vertexShader.replace(declarationLine, replacementDeclaration);
            fragmentShader = fragmentShader.replace(declarationLine, replacementDeclaration);
        }
        return new Pair<>(vertexShader, fragmentShader);
    }
}
