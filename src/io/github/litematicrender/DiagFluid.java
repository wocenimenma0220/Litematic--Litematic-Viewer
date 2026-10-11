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

/**
 * Reports the quads produced for each fluid block in a fixture, so the height field and face set
 * can be checked numerically instead of by eye.
 */
public final class DiagFluid {
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

        System.out.println("fluid quads: " + mesh.fluid.size() + "   translucent quads: " + mesh.translucent.size());
        // Group water quads by their block column and print the height range.
        java.util.Map<String, float[]> byBlock = new java.util.TreeMap<>();
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (SceneMesh.MeshQuad q : mesh.fluid) {
            if (q.spriteId == null || !q.spriteId.contains("water")) continue;
            int bx = (int) Math.floor(q.cx);
            int by = (int) Math.floor(q.cy);
            int bz = (int) Math.floor(q.cz);
            String key = bx + "," + by + "," + bz;
            float minY = Math.min(Math.min(q.y0, q.y1), Math.min(q.y2, q.y3));
            float maxY = Math.max(Math.max(q.y0, q.y1), Math.max(q.y2, q.y3));
            float[] r = byBlock.computeIfAbsent(key, k -> new float[]{Float.MAX_VALUE, -Float.MAX_VALUE});
            r[0] = Math.min(r[0], minY);
            r[1] = Math.max(r[1], maxY);
            counts.merge(key, 1, Integer::sum);
        }
        System.out.println("water columns: " + byBlock.size());
        System.out.printf("%-12s %5s  %-22s %s%n", "block", "quads", "y range", "height above base");
        byBlock.entrySet().stream().limit(40).forEach(e -> {
            String k = e.getKey();
            float[] r = e.getValue();
            int by = Integer.parseInt(k.split(",")[1]);
            System.out.printf("%-12s %5d  [%6.3f .. %6.3f]   top=%.4f (= %d/9)%n",
                k, counts.get(k), r[0], r[1], r[1] - by, Math.round((r[1] - by) * 9));
        });
        res.close();
    }
}
