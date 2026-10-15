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
 * exporter produces that input: four corners per quad, consumed by indexed WebGL drawing.
 *
 * <p><strong>Four corners with indices.</strong> The viewer forms triangles (0,1,2) and (0,2,3),
 * matching the CPU rasteriser. LMESH4 appends the BSP tree and conservative section visibility.
 *
 * <p><strong>Layout.</strong> Integers are big-endian, because that is what DataOutputStream
 * writes; a reader that assumes little-endian sees absurd layer and quad counts.
 *
 * <pre>
 *   signature  "LMESH4" padded to 8 bytes
 *   int32      layerCount
 *   int32      totalVertexCount      (quads * 4)
 *   per layer:
 *     int32    layerOrdinal          (SceneMesh.Layer.ordinal())
 *     int32    quadCount
 *     quadCount * 4 vertices, each 9 floats:
 *       x, y, z                      world position
 *       u, v                         ATLAS UV in 0..1 (converted here)
 *       shade                        cardinal light * ambient occlusion, premultiplied
 *       tintR, tintG, tintB          0..1
 *   int32      bspNodeCount
 *   per BSP node:
 *     float64  normalX, normalY, normalZ, planeDistance
 *     int32    firstFace, faceCount, frontChild, backChild  (-1 = no child)
 *   int32      occludingSectionCount
 *   per section: int32 x,y,z; 6 exit masks; 512 bytes of solid voxel bits (x,z,y order)
 * </pre>
 */
public final class MeshExporter {
    private static final byte[] SIGNATURE = "LMESH4".getBytes(StandardCharsets.US_ASCII);
    public static final int SIGNATURE_LENGTH = 8;
    public static final int FLOATS_PER_VERTEX = 9;
    /** Unique corners; the viewer supplies the shared-diagonal triangle indices. */
    public static final int VERTICES_PER_QUAD = 4;

    private MeshExporter() {}

    /** Indexed LMESH7 preview path; the LMESH1-6 readers remain available for existing files. */
    public static StreamMeshExporter.Index writeStreaming(io.github.litematicrender.render.scene.SceneBuilder builder,SpriteAtlas atlas,Path out)throws IOException {
        return StreamMeshExporter.write(builder,atlas,out);
    }

    private static SceneMesh.Layer[] drawOrder() {
        return new SceneMesh.Layer[]{
            SceneMesh.Layer.SOLID, SceneMesh.Layer.CUTOUT, SceneMesh.Layer.PORTAL,
            SceneMesh.Layer.FLUID, SceneMesh.Layer.TRANSLUCENT, SceneMesh.Layer.WATER_MASK};
    }

    private static List<SceneMesh.MeshQuad> quadsFor(SceneMesh mesh, SceneMesh.Layer layer) {
        return switch (layer) {
            case SOLID -> mesh.solid;
            case CUTOUT -> mesh.cutout;
            case PORTAL -> mesh.translucentBsp==null?mesh.portalSorted:List.of();
            case FLUID -> mesh.translucentBsp==null?mesh.fluidSorted:List.of();
            case TRANSLUCENT -> mesh.translucentBsp==null?mesh.translucentSorted:mesh.translucentBsp.faces;
            case WATER_MASK -> mesh.waterMask;
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
                    // Unique corners; both CPU and GPU use the same 0-2 diagonal.
                        for (int i = 0; i < 4; i++) {
                            o.writeFloat(xs[i]);
                            o.writeFloat(ys[i]);
                            o.writeFloat(zs[i]);
                            o.writeFloat(e.u0 + (e.u1 - e.u0) * us[i]);
                            o.writeFloat(e.v0 + (e.v1 - e.v0) * vs[i]);
                            float shade = q.shade * (ao == null ? 1f : ao[i]);
                            o.writeFloat(q.vertexColors==null?Math.min(1f, Math.max(0f, shade)):1f);
                            o.writeFloat(q.vertexColors==null?q.tintR:((q.vertexColors[i]>>16)&255)/255f);
                            o.writeFloat(q.vertexColors==null?q.tintG:((q.vertexColors[i]>>8)&255)/255f);
                            o.writeFloat(q.vertexColors==null?q.tintB:(q.vertexColors[i]&255)/255f);
                        }
                }
            }
            var tree=mesh.translucentBsp;
            o.writeInt(tree==null?0:tree.nodes.size());
            if(tree!=null)for(var node:tree.nodes) {
                for(double value:node.plane)o.writeDouble(value);
                o.writeInt(node.first);o.writeInt(node.count);o.writeInt(node.front);o.writeInt(node.back);
            }
            var sections=mesh.visibility==null?null:mesh.visibility.sections;
            o.writeInt(sections==null?0:sections.size());
            if(sections!=null)for(var entry:sections.entrySet()) {
                var position=entry.getKey();var section=entry.getValue();
                o.writeInt(position.x());o.writeInt(position.y());o.writeInt(position.z());
                o.write(section.exits);o.write(section.solid);
            }
        }
        return totalQuads * VERTICES_PER_QUAD;
    }
}
