package graphics.cinnabar.api.hg;

import com.mojang.datafixers.util.Either;
import graphics.cinnabar.api.annotations.Constant;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.hg.enums.HgCompareOp;
import graphics.cinnabar.api.hg.enums.HgFormat;
import graphics.cinnabar.api.util.Pair;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4fc;

import java.util.List;

@ApiStatus.NonExtendable
public interface HgGraphicsPipeline extends HgObject {
    
    @Constant
    @ThreadSafety.Many
    Layout layout();
    
    enum PrimitiveTopology {
        POINT_LIST,
        LINE_LIST,
        LINE_STRIP,
        TRIANGLE_LIST,
        TRIANGLE_STRIP,
        TRIANGLE_FAN,
    }
    
    interface ShaderSet extends HgObject {
        
        @Constant
        @Nullable
        @ThreadSafety.Many
        List<VertexAttrib> attribs();
        
        @Constant
        @ThreadSafety.Many
        int attachmentCount();
        
        @Constant
        @ThreadSafety.Many
        int maximumUniformSetIndex();
        
        @Constant
        @Nullable
        @ThreadSafety.Many
        HgUniformSet.Layout.CreateInfo uniformSetLayoutCreateInfo(int setIndex);
        
        @Constant
        @ThreadSafety.Many
        long pushConstantsSize();
        
        record CreateInfo(Either<Mesh, Vertex> vertexStage, Fragment fragmentStage, boolean rebind) {
            public static CreateInfo gl(String vertex, String fragment) {
                return new CreateInfo(Either.right(new Vertex(vertex)), new Fragment(fragment), true);
            }
            
            public static CreateInfo vk(String vertex, String fragment) {
                return new CreateInfo(Either.right(new Vertex(vertex)), new Fragment(fragment), false);
            }
            
            public static CreateInfo vk(String vertex, String vertexEntryPoint, String fragment, String fragmentEntryPoint) {
                return new CreateInfo(Either.right(new Vertex(vertex, vertexEntryPoint)), new Fragment(fragment, fragmentEntryPoint), false);
            }
            
            public static CreateInfo mesh(@Nullable String task, String mesh, String fragment) {
                return new CreateInfo(Either.left(new Mesh(task, mesh)), new Fragment(fragment), false);
            }
            
            record Mesh(@Nullable String task, String mesh) {
            }
            
            public record Vertex(String vertex, String entryPoint) {
                public Vertex(String vertex){
                    this(vertex, "main");
                }
            }
            
            public record Fragment(String fragment, String entryPoint) {
                public Fragment(String fragment){
                    this(fragment, "main");
                }
            }
        }
        
        record VertexAttrib(int location, String name, int spvcBaseType, int count) {
        }
    }
    
    @ApiStatus.NonExtendable
    interface Layout extends HgObject {
        
        @Constant
        @ThreadSafety.Many
        int maximumUniformSetIndex();
        
        @Constant
        @Nullable
        @ThreadSafety.Many
        HgUniformSet.Layout uniformSetLayout(int setIndex);
        
        record CreateInfo(List<HgUniformSet.Layout> uniformLayouts, int pushConstantSize) {
        }
    }
    
    record VertexInput(List<Buffer> buffers, List<Binding> bindings) {
        
        public record Buffer(int index, int stride, InputRate inputRate) {
            public enum InputRate {
                VERTEX,
                INSTANCE,
            }
        }
        
        public record Binding(int bufferIndex, int location, HgFormat format, int offset) {
        }
    }
    
    record Rasterizer(PolygonMode polyMode, boolean cull, float depthBiasConstant, float depthBiasScaleFactor) {
        public enum PolygonMode {
            FILL,
            LINE,
            POINT,
        }
    }
    
    record DepthTest(HgCompareOp compareOp, boolean write) {
    }
    
    record Stencil(OpState front, OpState back) {
        public enum Op {
            KEEP,
            ZERO,
            REPLACE,
            INCREMENT_AND_CLAMP,
            DECREMENT_AND_CLAMP,
            INVERT,
            INCREMENT_AND_WRAP,
            DECREMENT_AND_WRAP,
        }
        
        public record OpState(Op fail, Op depthFail, Op pass, HgCompareOp compareOp, int compareMask, int writeMask, int referenceValue) {
        }
    }
    
    record Blend(List<Attachment> attachments, Vector4fc blendConstants) {
        
        public enum Op {
            ADD,
            SUBTRACT,
            REVERSE_SUBTRACT,
            MIN,
            MAX,
        }
        
        public enum Factor {
            ZERO,
            ONE,
            SRC_COLOR,
            ONE_MINUS_SRC_COLOR,
            DST_COLOR,
            ONE_MINUS_DST_COLOR,
            SRC_ALPHA,
            ONE_MINUS_SRC_ALPHA,
            DST_ALPHA,
            ONE_MINUS_DST_ALPHA,
            CONSTANT_COLOR,
            ONE_MINUS_CONSTANT_COLOR,
            CONSTANT_ALPHA,
            ONE_MINUS_CONSTANT_ALPHA,
            SRC_ALPHA_SATURATE,
        }
        
        public record Attachment(@Nullable Pair<Equation, Equation> equations, int writeMask) {
        }
        
        public record Equation(Factor srcFactor, Factor dstFactor, Op op) {
        }
    }
    
    record CreateInfo(HgRenderPass renderpass, ShaderSet shaderSet, Layout layout, State state) {
        public record State(@Nullable VertexInput vertexInput, PrimitiveTopology topology, Rasterizer rasterizer, @Nullable DepthTest depthTest, @Nullable Stencil stencil, @Nullable Blend blend) {
        }
    }
}
