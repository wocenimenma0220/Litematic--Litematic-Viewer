package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.render.Colormaps;
import io.github.litematicrender.render.SpriteAtlas;
import io.github.litematicrender.render.scene.SceneBuilder;
import io.github.litematicrender.render.scene.SceneMesh;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;

import java.nio.file.Path;

/** Reports the min/max world position of every quad, to find geometry placed outside its block. */
public final class DiagBounds {
    public static void main(String[] args) throws Exception {
        LitematicaSchematic schem = LitematicaSchematic.load(Path.of(args[0]));
        ResourceManager res = new ResourceManager(Path.of(args[1]));
        BlockRegistry reg = BlockRegistry.load(res);
        schem.materialize(reg);
        ModelBaker baker = new ModelBaker(res, reg);
        Colormaps cm = new Colormaps();
        cm.load(res);
        SpriteAtlas atlas = SpriteAtlas.load(res, "blocks");

        SceneBuilder b = new SceneBuilder(reg, baker, cm, atlas);
        for (var e : schem.regions().entrySet()) {
            var r = e.getValue();
            b.addRegion(r, new int[]{r.position.x(), r.position.y(), r.position.z()});
        }
        SceneMesh mesh = b.build();

        System.out.println("region size from metadata: " + schem.totalSize());
        for (var e : schem.regions().entrySet()) {
            var r = e.getValue();
            System.out.printf("region %-12s pos=(%d,%d,%d) size=%dx%dx%d%n",
                e.getKey(), r.position.x(), r.position.y(), r.position.z(),
                r.blocks.sizeX, r.blocks.sizeY, r.blocks.sizeZ);
        }

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (SceneMesh.MeshQuad q : mesh.solid) {
            minX = Math.min(minX, min(q.x0, q.x1, q.x2, q.x3));
            maxX = Math.max(maxX, max(q.x0, q.x1, q.x2, q.x3));
            minY = Math.min(minY, min(q.y0, q.y1, q.y2, q.y3));
            maxY = Math.max(maxY, max(q.y0, q.y1, q.y2, q.y3));
            minZ = Math.min(minZ, min(q.z0, q.z1, q.z2, q.z3));
            maxZ = Math.max(maxZ, max(q.z0, q.z1, q.z2, q.z3));
        }
        System.out.printf("solid quad bounds: x[%.2f..%.2f] y[%.2f..%.2f] z[%.2f..%.2f]%n",
            minX, maxX, minY, maxY, minZ, maxZ);

        // Which block columns exist per Y level? The redstone fixture puts everything at y=-1 and 0.
        var counts = new java.util.TreeMap<String, Integer>();
        for (SceneMesh.MeshQuad q : mesh.solid) {
            int bx = (int) Math.floor((min(q.x0, q.x1, q.x2, q.x3) + max(q.x0, q.x1, q.x2, q.x3)) / 2);
            int by = (int) Math.floor((min(q.y0, q.y1, q.y2, q.y3) + max(q.y0, q.y1, q.y2, q.y3)) / 2);
            counts.merge("block y=" + by, 1, Integer::sum);
        }
        counts.forEach((k, v) -> System.out.println("  " + k + " quads=" + v));
    }

    private static float min(float a, float b, float c, float d) { return Math.min(Math.min(a, b), Math.min(c, d)); }
    private static float max(float a, float b, float c, float d) { return Math.max(Math.max(a, b), Math.max(c, d)); }
}
