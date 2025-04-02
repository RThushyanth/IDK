package codechicken.lib.render;

import java.util.ArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.IBlockAccess;

import codechicken.lib.colour.ColourRGBA;
import codechicken.lib.lighting.LC;
import codechicken.lib.lighting.LightMatrix;
import codechicken.lib.util.Copyable;
import codechicken.lib.vec.Rotation;
import codechicken.lib.vec.Transformation;
import codechicken.lib.vec.Vector3;

/**
 * The core of the CodeChickenLib render system. Where possible assign a local var of CCRenderState to avoid millions of
 * calls to instance(); Uses a ThreadLocal system to assign each thread their own CCRenderState so we can use it in
 * Multithreaded chunk batching.
 * <p>
 * Backported from CCL - 1.16.x
 */

public class CCRenderState {

    public final CCRenderPipeline pipeline;

    private static final ThreadLocal<CCRenderState> instances = ThreadLocal.withInitial(CCRenderState::new);

    private CCRenderState() {
        pipeline = new CCRenderPipeline(this);
    }

    public static CCRenderState instance() {
        return instances.get();
    }

    private static int nextOperationIndex;

    public static int registerOperation() {
        return nextOperationIndex++;
    }

    public static int operationCount() {
        return nextOperationIndex;
    }

    /**
     * Represents an operation to be run for each vertex that operates on and modifies the current state
     */
    public interface IVertexOperation {

        /**
         * Load any required references and add dependencies to the pipeline based on the current model (may be null)
         * Return false if this operation is redundant in the pipeline with the given model
         */
        default boolean load() {
            // Existing code will will override this method so it shouldn't infinite loop
            // Default it only for new state aware code
            return load(CCRenderState.instance());
        }

        default boolean load(CCRenderState state) {
            // New code will override this and not inplement load() so we shouldn't infinite loop
            return load();
        }

        /**
         * Perform the operation on the current render state
         */
        default void operate() {
            operate(CCRenderState.instance());
        }

        default void operate(CCRenderState state) {
            operate();
        }

        /**
         * Get the unique id representing this type of operation. Duplicate operation IDs within the pipeline may have
         * unexpected results. ID shoulld be obtained from CCRenderState.registerOperation() and stored in a static
         * variable
         */
        int operationID();
    }

    private static ArrayList<VertexAttribute<?>> vertexAttributes = new ArrayList<>();

    private static int registerVertexAttribute(VertexAttribute<?> attr) {
        vertexAttributes.add(attr);
        return vertexAttributes.size() - 1;
    }

    public static VertexAttribute<?> getAttribute(int index) {
        return vertexAttributes.get(index);
    }

    /**
     * Management class for a vertex attrute such as colour, normal etc This class should handle the loading of the
     * attrute from an array provided by IVertexSource.getAttributes or the computation of this attrute from others
     *
     * @param <T> The array type for this attrute eg. int[], Vector3[]
     */
    public abstract static class VertexAttribute<T> implements IVertexOperation {

        public final int attributeIndex = registerVertexAttribute(this);
        private final int operationIndex = registerOperation();
        /**
         * Set to true when the attrute is part of the pipeline. Should only be managed by CCRenderState when
         * constructing the pipeline
         */
        public boolean active = false;

        /**
         * Construct a new array for storage of vertex attrutes in a model
         */
        public abstract T newArray(int length);

        @Override
        public int operationID() {
            return operationIndex;
        }
    }

    public static void arrayCopy(Object src, int srcPos, Object dst, int destPos, int length) {
        System.arraycopy(src, srcPos, dst, destPos, length);
        if (dst instanceof Copyable[]) {
            Object[] oa = (Object[]) dst;
            Copyable<Object>[] c = (Copyable[]) dst;
            for (int i = destPos; i < destPos + length; i++) if (c[i] != null) oa[i] = c[i].copy();
        }
    }

    public static <T> T copyOf(VertexAttribute<T> attr, T src, int length) {
        T dst = attr.newArray(length);
        arrayCopy(src, 0, dst, 0, ((Object[]) src).length);
        return dst;
    }

    public interface IVertexSource {

        Vertex5[] getVertices();

        /**
         * Gets an array of vertex attrutes
         *
         * @param attr The vertex attrute to get
         * @param <T>  The attrute array type
         * @return An array, or null if not computed
         */
        <T> T getAttributes(VertexAttribute<T> attr);

        /**
         * @return True if the specified attrute is provided by this model, either by returning an array from
         *         getAttributes or by setting the state in prepareVertex
         */
        boolean hasAttribute(VertexAttribute<?> attr);

        /**
         * Callback to set CCRenderState for a vertex before the pipeline runs
         */
        void prepareVertex(CCRenderState state);

        default void prepareVertex() {
            prepareVertex(CCRenderState.instance());
        }
    }

    public static VertexAttribute<Vector3[]> normalAttrib = new VertexAttribute<>() {

        private Vector3[] normalRef;

        @Override
        public Vector3[] newArray(int length) {
            return new Vector3[length];
        }

        @Override
        public boolean load(CCRenderState state) {
            normalRef = state.model.getAttributes(this);
            if (state.model.hasAttribute(this)) return normalRef != null;

            if (state.model.hasAttribute(sideAttrib)) {
                state.pipeline.addDependency(sideAttrib);
                return true;
            }
            throw new IllegalStateException(
                    "Normals requested but neither normal or side attrutes are provided by the model");
        }

        @Override
        public void operate(CCRenderState state) {
            if (normalRef != null) state.setNormalInstance(normalRef[state.vertexIndex]);
            else state.setNormalInstance(Rotation.axes[state.side]);
        }
    };
    public static VertexAttribute<int[]> colourAttrib = new VertexAttribute<>() {

        private int[] colourRef;

        @Override
        public int[] newArray(int length) {
            return new int[length];
        }

        @Override
        public boolean load(CCRenderState state) {
            colourRef = state.model.getAttributes(this);
            return colourRef != null || !state.model.hasAttribute(this);
        }

        @Override
        public void operate(CCRenderState state) {
            if (colourRef != null)
                state.setColourInstance(ColourRGBA.multiply(state.baseColour, colourRef[state.vertexIndex]));
            else state.setColourInstance(state.baseColour);
        }
    };
    public static VertexAttribute<int[]> lightingAttrib = new VertexAttribute<>() {

        private int[] colourRef;

        @Override
        public int[] newArray(int length) {
            return new int[length];
        }

        @Override
        public boolean load(CCRenderState state) {
            if (!state.computeLighting || !state.useColour || !state.model.hasAttribute(this)) return false;

            colourRef = state.model.getAttributes(this);
            if (colourRef != null) {
                state.pipeline.addDependency(colourAttrib);
                return true;
            }
            return false;
        }

        @Override
        public void operate(CCRenderState state) {
            state.setColourInstance(ColourRGBA.multiply(state.colour, colourRef[state.vertexIndex]));
        }
    };
    public static VertexAttribute<int[]> sideAttrib = new VertexAttribute<>() {

        private int[] sideRef;

        @Override
        public int[] newArray(int length) {
            return new int[length];
        }

        @Override
        public boolean load(CCRenderState state) {
            sideRef = state.model.getAttributes(this);
            if (state.model.hasAttribute(this)) return sideRef != null;

            state.pipeline.addDependency(normalAttrib);
            return true;
        }

        @Override
        public void operate(CCRenderState state) {
            if (sideRef != null) state.side = sideRef[state.vertexIndex];
            else state.side = CCModel.findSide(state.normal);
        }
    };
    /**
     * Uses the position of the lightmatrix to compute LC if not provided
     */
    public static VertexAttribute<LC[]> lightCoordAttrib = new VertexAttribute<>() {

        private LC[] lcRef;
        private final Vector3 vec = new Vector3(); // for computation
        private final Vector3 pos = new Vector3();

        @Override
        public LC[] newArray(int length) {
            return new LC[length];
        }

        @Override
        public boolean load(CCRenderState state) {
            lcRef = state.model.getAttributes(this);
            if (state.model.hasAttribute(this)) return lcRef != null;

            pos.set(state.lightMatrix.pos.x, state.lightMatrix.pos.y, state.lightMatrix.pos.z);
            state.pipeline.addDependency(sideAttrib);
            state.pipeline.addRequirement(Transformation.operationIndex);
            return true;
        }

        @Override
        public void operate(CCRenderState state) {
            if (lcRef != null) state.lc.set(lcRef[state.vertexIndex]);
            else state.lc.compute(vec.set(state.vert.vec).sub(pos), state.side);
        }
    };

    // pipeline state
    public IVertexSource model;

    public int firstVertexIndex;
    public int lastVertexIndex;
    public int vertexIndex;

    // context
    public int baseColour;
    public int alphaOverride;
    public boolean useNormals;
    public boolean computeLighting;
    public boolean useColour;
    public LightMatrix lightMatrix = new LightMatrix();

    // vertex outputs
    public Vertex5 vert = new Vertex5();
    public boolean hasNormal;
    public Vector3 normal = new Vector3();

    public boolean hasColour;
    public int colour;

    public boolean hasBrightness;
    public int brightness;

    // attribute storage
    public int side;
    public LC lc = new LC();

    public static void reset() {
        instance().resetInstance();
    }

    public void resetInstance() {
        model = null;
        pipeline.reset();
        useNormals = hasNormal = hasBrightness = hasColour = false;
        useColour = computeLighting = true;
        baseColour = alphaOverride = -1;
    }

    public void setPipelineInstance(IVertexOperation... ops) {
        pipeline.setPipeline(ops);
    }

    @Deprecated
    public static void setPipeline(IVertexOperation... ops) {
        instance().setPipelineInstance(ops);
    }

    public void setPipelineInstance(IVertexSource model, int start, int end, IVertexOperation... ops) {
        pipeline.reset();
        setModelInstance(model, start, end);
        pipeline.setPipeline(ops);
    }

    @Deprecated
    public static void setPipeline(IVertexSource model, int start, int end, IVertexOperation... ops) {
        instance().setPipelineInstance(model, start, end, ops);
    }

    public void bindModelInstance(IVertexSource model) {
        if (this.model != model) {
            this.model = model;
            pipeline.rebuild();
        }
    }

    @Deprecated
    public static void bindModel(IVertexSource model) {
        instance().bindModelInstance(model);
    }

    public void setModelInstance(IVertexSource source) {
        setModelInstance(source, 0, source.getVertices().length);
    }

    @Deprecated
    public static void setModel(IVertexSource source) {
        instance().setModelInstance(source);
    }

    public void setModelInstance(IVertexSource source, int start, int end) {
        bindModelInstance(source);
        setVertexRangeInstance(start, end);
    }

    @Deprecated
    public static void setModel(IVertexSource source, int start, int end) {
        instance().setModelInstance(source, start, end);
    }

    public void setVertexRangeInstance(int start, int end) {
        firstVertexIndex = start;
        lastVertexIndex = end;
    }

    @Deprecated
    public static void setVertexRange(int start, int end) {
        instance().setVertexRangeInstance(start, end);
    }

    public void renderInstance(IVertexOperation... ops) {
        setPipelineInstance(ops);
        renderInstance();
    }

    @Deprecated
    public static void render(IVertexOperation... ops) {
        instance().renderInstance(ops);
    }

    public void renderInstance() {
        Vertex5[] verts = model.getVertices();
        for (vertexIndex = firstVertexIndex; vertexIndex < lastVertexIndex; vertexIndex++) {
            model.prepareVertex(this);
            vert.set(verts[vertexIndex]);
            runPipelineInstance();
            writeVertInstance();
        }
    }

    @Deprecated
    public static void render() {
        instance().renderInstance();
    }

    public void runPipelineInstance() {
        pipeline.operate();
    }

    @Deprecated
    public static void runPipeline() {
        instance().runPipelineInstance();
    }

    public void writeVertInstance() {
        if (hasNormal) Tessellator.instance.setNormal((float) normal.x, (float) normal.y, (float) normal.z);
        if (hasColour) Tessellator.instance.setColorRGBA(
                colour >>> 24,
                colour >> 16 & 0xFF,
                colour >> 8 & 0xFF,
                alphaOverride >= 0 ? alphaOverride : colour & 0xFF);
        if (hasBrightness) Tessellator.instance.setBrightness(brightness);
        Tessellator.instance.addVertexWithUV(vert.vec.x, vert.vec.y, vert.vec.z, vert.uv.u, vert.uv.v);
    }

    @Deprecated
    public static void writeVert() {
        instance().writeVertInstance();
    }

    public void setNormalInstance(double x, double y, double z) {
        hasNormal = true;
        normal.set(x, y, z);
    }

    @Deprecated
    public static void setNormal(double x, double y, double z) {
        instance().setNormalInstance(x, y, z);
    }

    public void setNormalInstance(Vector3 n) {
        hasNormal = true;
        normal.set(n);
    }

    @Deprecated
    public static void setNormal(Vector3 n) {
        instance().setNormalInstance(n);
    }

    public void setColourInstance(int c) {
        hasColour = true;
        colour = c;
    }

    @Deprecated
    public static void setColour(int c) {
        instance().setColourInstance(c);
    }

    public void setBrightnessInstance(int b) {
        hasBrightness = true;
        brightness = b;
    }

    @Deprecated
    public static void setBrightness(int b) {
        instance().setBrightnessInstance(b);
    }

    public void setBrightnessInstance(IBlockAccess world, int x, int y, int z) {
        setBrightnessInstance(world.getBlock(x, y, z).getMixedBrightnessForBlock(world, x, y, z));
    }

    @Deprecated
    public static void setBrightness(IBlockAccess world, int x, int y, int z) {
        instance().setBrightnessInstance(world, x, y, z);
    }

    public static void pullLightmap() {
        instance().pullLightmapInstance();
    }

    public void pullLightmapInstance() {
        setBrightnessInstance((int) OpenGlHelper.lastBrightnessY << 16 | (int) OpenGlHelper.lastBrightnessX);
    }

    public static void pushLightmap() {
        instance().pushLightmapInstance();
    }

    public void pushLightmapInstance() {
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, brightness & 0xFFFF, brightness >>> 16);
    }

    /**
     * Compact helper for setting dynamic rendering context. Uses normals and doesn't compute lighting
     */
    public static void setDynamic() {
        instance().setDynamicInstance();
    }

    public void setDynamicInstance() {
        useNormals = true;
        computeLighting = false;
    }

    public static void changeTexture(String texture) {
        changeTexture(new ResourceLocation(texture));
    }

    public static void changeTexture(ResourceLocation texture) {
        Minecraft.getMinecraft().renderEngine.bindTexture(texture);
    }

    public void startDrawingInstance() {
        startDrawingInstance(7);
    }

    @Deprecated
    public static void startDrawing() {
        instance().startDrawingInstance();
    }

    public void startDrawingInstance(int mode) {
        Tessellator.instance.startDrawing(mode);
        if (hasColour) Tessellator.instance.setColorRGBA(
                colour >>> 24,
                colour >> 16 & 0xFF,
                colour >> 8 & 0xFF,
                alphaOverride >= 0 ? alphaOverride : colour & 0xFF);
        if (hasBrightness) Tessellator.instance.setBrightness(brightness);
    }

    @Deprecated
    public static void startDrawing(int mode) {
        instance().startDrawingInstance(mode);
    }

    public static void draw() {
        Tessellator.instance.draw();
    }

    public void drawInstance() {
        Tessellator.instance.draw();
    }
}
