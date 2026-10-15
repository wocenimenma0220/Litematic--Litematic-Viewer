package io.github.litematicrender.render.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry grouped by material. Opaque layers write depth; all blending layers are sorted together.
 */
public final class SceneMesh {
    public SectionVisibility visibility;
    public java.util.Map<String, Object> skinReport = java.util.Map.of();
    public java.util.Map<String, Object> entityReport = java.util.Map.of();
    public enum Layer { SOLID, CUTOUT, PORTAL, FLUID, TRANSLUCENT, WATER_MASK }

    public static final class MeshQuad {
        public final float x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3; // world coords
        /** per-vertex uv fractions within the sprite (0..1) */
        public final float u0, v0, u1, v1, u2, v2, u3, v3;
        public final String spriteId;
        /** Visual effects do not enlarge automatic framing or intercept block selection. */
        public boolean decorativeEffect(){return spriteId!=null&&spriteId.startsWith("litematicrender:beacon/");}
        public final int rgb;              // used when spriteId == null
        /** When set, the quad samples this image directly (UV in [0,1]) instead of the atlas. */
        public final io.github.litematicrender.render.Image image;
        public final float tintR, tintG, tintB;
        /** cardinal lighting for the whole quad (vanilla CardinalLighting) */
        public final float shade;
        /** per-vertex ambient occlusion, or null for flat (unculled / AO-disabled) quads */
        public final float[] ao;
        /** Optional final per-corner RGB, including smooth light, tint, cardinal shade and AO. */
        public int[] vertexColors;
        public MeshQuad colors(int[] colors) { this.vertexColors=colors; return this; }
        public final Layer layer;
        public final float cx, cy, cz;     // center (for depth sorting)
        /**
         * The quad belongs to a model face with no fixed orientation (a cross-shaped plant, a torch
         * side), so it must be drawn from both sides. Vanilla renders those with
         * {@code withCull(false)}, and back-face culling must be skipped for them here too —
         * otherwise half of a cross plane disappears.
         */
        public final boolean doubleSided;
        /** Owning cell/region, independent of geometry extending outside its cell. */
        public int blockX, blockY, blockZ;
        /** Thin boundaries must retain their geometry in distant LODs. */
        public boolean lodProtected;
        public String regionName;
        public int entityIndex=-1;
        public MeshQuad entityOwner(int index,String region){this.entityIndex=index;this.regionName=region;return this;}
        public MeshQuad owner(int x,int y,int z,String region) {
            this.blockX=x; this.blockY=y; this.blockZ=z; this.regionName=region; return this;
        }

        public MeshQuad(float[] xs, float[] ys, float[] zs,
                        float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3,
                        String spriteId, int rgb,
                        float tr, float tg, float tb, float shade, float[] ao, Layer layer) {
            this(xs, ys, zs, u0, v0, u1, v1, u2, v2, u3, v3, spriteId, rgb, null, tr, tg, tb, shade, ao, layer, false);
        }

        public MeshQuad(float[] xs, float[] ys, float[] zs,
                        float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3,
                        String spriteId, int rgb, io.github.litematicrender.render.Image image,
                        float tr, float tg, float tb, float shade, float[] ao, Layer layer) {
            this(xs, ys, zs, u0, v0, u1, v1, u2, v2, u3, v3, spriteId, rgb, image,
                tr, tg, tb, shade, ao, layer, false);
        }

        public MeshQuad(float[] xs, float[] ys, float[] zs,
                        float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3,
                        String spriteId, int rgb, io.github.litematicrender.render.Image image,
                        float tr, float tg, float tb, float shade, float[] ao, Layer layer,
                        boolean doubleSided) {
            this.x0 = xs[0]; this.y0 = ys[0]; this.z0 = zs[0];
            this.x1 = xs[1]; this.y1 = ys[1]; this.z1 = zs[1];
            this.x2 = xs[2]; this.y2 = ys[2]; this.z2 = zs[2];
            this.x3 = xs[3]; this.y3 = ys[3]; this.z3 = zs[3];
            this.u0 = u0; this.v0 = v0;
            this.u1 = u1; this.v1 = v1;
            this.u2 = u2; this.v2 = v2;
            this.u3 = u3; this.v3 = v3;
            this.spriteId = spriteId;
            this.rgb = rgb;
            this.image = image;
            this.tintR = tr; this.tintG = tg; this.tintB = tb;
            this.shade = shade;
            this.ao = ao;
            this.layer = layer;
            this.doubleSided = doubleSided;
            this.cx = (xs[0] + xs[1] + xs[2] + xs[3]) / 4f;
            this.cy = (ys[0] + ys[1] + ys[2] + ys[3]) / 4f;
            this.cz = (zs[0] + zs[1] + zs[2] + zs[3]) / 4f;
        }
    }

    public final List<MeshQuad> solid = new ArrayList<>();
    public final List<MeshQuad> cutout = new ArrayList<>();
    public final List<MeshQuad> portal = new ArrayList<>();
    public final List<MeshQuad> fluid = new ArrayList<>();
    public final List<MeshQuad> translucent = new ArrayList<>();
    /** Boat interior depth, written after visible opaque entities and before blending. */
    public final List<MeshQuad> waterMask = new ArrayList<>();
    public int quadCount;
    public TranslucentBsp translucentBsp;

    /**
     * The blending buckets with each quad already subdivided into sortable pieces.
     *
     * <p>Subdivision depends only on geometry, so it is done once here instead of on every frame —
     * it turns ~22 600 translucent quads into a much larger list, and redoing that per frame cost
     * more than rasterizing the frame did. The camera-dependent back-to-front sort still happens
     * per frame, but on a flat array it is cheap.
     */
    public final List<MeshQuad> portalSorted = new ArrayList<>();
    public final List<MeshQuad> fluidSorted = new ArrayList<>();
    public final List<MeshQuad> translucentSorted = new ArrayList<>();
}
