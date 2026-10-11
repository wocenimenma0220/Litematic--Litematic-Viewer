package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.render.raster.Camera;
import io.github.litematicrender.render.Colormaps;
import io.github.litematicrender.render.RenderSettings;
import io.github.litematicrender.render.SchematicRenderer;
import io.github.litematicrender.render.SpriteAtlas;
import io.github.litematicrender.render.scene.SceneBuilder;
import io.github.litematicrender.render.scene.SceneMesh;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;

import java.nio.file.Path;

/**
 * Counts back-facing and off-screen quads, to size the win from culling before implementing it.
 *
 * <p>Both tests are camera-dependent, so they cannot live in the cached mesh; the question is
 * whether a per-frame culling pass is worth its own cost. The camera is orthographic, so both
 * tests are exact and cheap.
 */
public final class DiagCull {
    public static void main(String[] args) throws Exception {
        Path schemFile = Path.of(args[0]);
        Path vanilla = Path.of(args[1]);
        ResourceManager res = io.github.litematicrender.render.RenderTest.openResources(
            vanilla, vanilla.getParent().getParent());
        try {
            LitematicaSchematic schem = LitematicaSchematic.load(schemFile);
            BlockRegistry reg = BlockRegistry.load(res);
            schem.materialize(reg);
            Colormaps cm = new Colormaps();
            cm.load(res);
            SpriteAtlas atlas = SpriteAtlas.load(res, "blocks");
            ModelBaker baker = new ModelBaker(res, reg);
            SceneBuilder builder = new SceneBuilder(reg, baker, cm, atlas);
            for (var e : schem.regions().entrySet()) {
                var r = e.getValue();
                builder.addRegion(r, new int[]{r.position.x(), r.position.y(), r.position.z()});
            }
            SceneMesh mesh = builder.build();

            for (int rot : new int[]{0, 45, 90, 135, 180, 270}) {
                Camera cam = new Camera();
                cam.rotationDeg = rot;
                cam.slantDeg = 30;
                SchematicRenderer.fitCameraToMesh(cam, mesh, 512, 512);
                cam.update();
                double z = cam.zoom;

                int total = 0, back = 0, off = 0;
                for (java.util.List<SceneMesh.MeshQuad> list : java.util.List.of(mesh.solid, mesh.cutout, mesh.fluid, mesh.translucent)) {
                    for (SceneMesh.MeshQuad q : list) {
                        total++;
                        double[] a = view(cam, q.x0, q.y0, q.z0);
                        double[] b = view(cam, q.x1, q.y1, q.z1);
                        double[] c = view(cam, q.x2, q.y2, q.z2);
                        double area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
                        if (area >= 0) { back++; continue; }
                        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
                        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                        for (int i = 0; i < 4; i++) {
                            double[] v = view(cam, cornerX(q, i), cornerY(q, i), cornerZ(q, i));
                            double px = 256 + v[0] * z, py = 256 - v[1] * z;
                            minX = Math.min(minX, px); maxX = Math.max(maxX, px);
                            minY = Math.min(minY, py); maxY = Math.max(maxY, py);
                        }
                        if (maxX < 0 || minX > 512 || maxY < 0 || minY > 512) off++;
                    }
                }
                System.out.printf("rot=%3d: total=%6d  back=%6d (%2d%%)  offscreen=%6d (%2d%%)  drawn=%6d (%2d%%)%n",
                    rot, total, back, pct(back, total), off, pct(off, total),
                    total - back - off, pct(total - back - off, total));
            }
        } finally {
            res.close();
        }
    }

    private static double cornerX(SceneMesh.MeshQuad q, int i) {
        return switch (i) { case 0 -> q.x0; case 1 -> q.x1; case 2 -> q.x2; default -> q.x3; };
    }

    private static double cornerY(SceneMesh.MeshQuad q, int i) {
        return switch (i) { case 0 -> q.y0; case 1 -> q.y1; case 2 -> q.y2; default -> q.y3; };
    }

    private static double cornerZ(SceneMesh.MeshQuad q, int i) {
        return switch (i) { case 0 -> q.z0; case 1 -> q.z1; case 2 -> q.z2; default -> q.z3; };
    }

    private static double[] view(Camera cam, double x, double y, double z) {
        double[] out = new double[3];
        cam.toView(x, y, z, out);
        return out;
    }

    private static int pct(int v, int total) { return total == 0 ? 0 : (int) Math.round(100.0 * v / total); }
}