package io.github.litematicrender.render;

import io.github.litematicrender.render.scene.SceneMesh;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Serialises a built scene for the WebGL viewer.
 *
 * <p>The CPU rasteriser re-transforms and re-shades every vertex on each camera change, which is
 * why dragging was never smooth. quickcraft and WikiRenderer avoid that by keeping geometry
 * resident on the GPU and changing only the model-view matrix while the camera moves. This
 * exporter produces that input: one immutable buffer per blending layer, drawn in the game's own
 * layer order.
 *
 * <p><strong>Quads become six vertices.</strong> The viewer draws with {@code gl.TRIANGLES}, where
 * every three consecutive vertices form one triangle. Emitting four vertices per quad therefore
 * welds unrelated quads together and shreds the model — the failure looks like torn geometry rather
 * than a clean error. Each quad is expanded here into the same two triangles the CPU rasteriser
 * uses, (0,1,2) and (0,2,3), so the GPU needs no index buffer.
 *
 * <p><strong>Layout.</strong> Integers are big-endian, because that is what DataOutputStream
 * writes; a reader that assumes little-endian sees absurd layer and quad counts.
 *
 * <pre>
 *   signature  "LMESH1" padded to 8 bytes
 *   int32      layerCount
 *   int32      totalVertexCount      (quads * 6)
 *   per layer:
 *     int32    layerOrdinal          (SceneMesh.Layer.ordinal())
 *     int32    quadCount
 *     quadCount * 6 vertices, each 9 floats:
 *       x, y, z                      world position
 *       u, v                         ATLAS UV in 0..1 (converted here)
 *       shade                        cardinal light * ambient occlusion, premultiplied
 *       tintR, tintG, tintB          0..1
 * </pre>
 */
public final class MeshExporter {
    private static final byte[] SIGNATURE = "LMESH1".getBytes(StandardCharsets.US_ASCII);
    public static final int SIGNATURE_LENGTH = 8;
    public static final int FLOATS_PER_VERTEX = 9;
    /** Vertices emitted per quad: two triangles sharing the 0-2 diagonal. */
    public static final int VERTICES_PER_QUAD = 6;

    private MeshExporter() {}

    private static SceneMesh.Layer[] drawOrder() {
        return new SceneMesh.Layer[]{
            SceneMesh.Layer.SOLID, SceneMesh.Layer.CUTOUT, SceneMesh.Layer.PORTAL,
            SceneMesh.Layer.FLUID, SceneMesh.Layer.TRANSLUCENT};
    }

    private static List<SceneMesh.MeshQuad> quadsFor(SceneMesh mesh, SceneMesh.Layer layer) {
        return switch (layer) {
            case SOLID -> mesh.solid;
            case CUTOUT -> mesh.cutout;
            case PORTAL -> mesh.portalSorted;
            case FLUID -> mesh.fluidSorted;
            case TRANSLUCENT -> mesh.translucentSorted;
        };
    }

    /**
     * Writes the mesh, converting sprite-relative UVs into atlas UVs.
     *
     * <p>That conversion is mandatory and easy to miss: a baked quad stores UVs in 0..1
     * <em>within its own sprite</em>, and the CPU rasteriser turns them into atlas coordinates at
     * sample time. A shader samples the atlas directly, so without this step every face reads the
     * same few texels at the atlas origin and the model renders as noise.
     */
    public static int write(SceneMesh mesh, SpriteAtlas atlas, Path out) throws IOException {
        SceneMesh.Layer[] order = drawOrder();
        int totalQuads = 0;
        for (SceneMesh.Layer l : order) totalQuads += quadsFor(mesh, l).size();

        try (DataOutputStream o = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(out), 1 << 20))) {
            o.write(SIGNATURE);
            for (int i = SIGNATURE.length; i < SIGNATURE_LENGTH; i++) o.writeByte(0);
            o.writeInt(order.length);
            o.writeInt(totalQuads * VERTICES_PER_QUAD);
            for (SceneMesh.Layer layer : order) {
                List<SceneMesh.MeshQuad> quads = quadsFor(mesh, layer);
                o.writeInt(layer.ordinal());
                o.writeInt(quads.size());
                for (SceneMesh.MeshQuad q : quads) {
                    SpriteAtlas.Entry e = q.spriteId == null ? null
                        : atlas.getOrMissingEntry(q.spriteId);
                    if (e == null) e = atlas.getOrMissingEntry("minecraft:missingno");
                    float[] xs = {q.x0, q.x1, q.x2, q.x3};
                    float[] ys = {q.y0, q.y1, q.y2, q.y3};
                    float[] zs = {q.z0, q.z1, q.z2, q.z3};
                    float[] us = {q.u0, q.u1, q.u2, q.u3};
                    float[] vs = {q.v0, q.v1, q.v2, q.v3};
                    float[] ao = q.ao;
                    // Two triangles: (0,1,2) and (0,2,3), matching the rasteriser's split.
                    for (int tri = 0; tri < 2; tri++) {
                        for (int k = 0; k < 3; k++) {
                            int i = tri == 0 ? k : (k == 0 ? 0 : k + 1);
                            o.writeFloat(xs[i]);
                            o.writeFloat(ys[i]);
                            o.writeFloat(zs[i]);
                            o.writeFloat(e.u0 + (e.u1 - e.u0) * us[i]);
                            o.writeFloat(e.v0 + (e.v1 - e.v0) * vs[i]);
                            float shade = q.shade * (ao == null ? 1f : ao[i]);
                            o.writeFloat(Math.min(1f, Math.max(0f, shade)));
                            o.writeFloat(q.tintR);
                            o.writeFloat(q.tintG);
                            o.writeFloat(q.tintB);
                        }
                    }
                }
            }
        }
        return totalQuads * VERTICES_PER_QUAD;
    }
}