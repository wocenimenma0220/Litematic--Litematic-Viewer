package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.render.Colormaps;
import io.github.litematicrender.render.RenderTest;
import io.github.litematicrender.render.SpriteAtlas;
import io.github.litematicrender.render.scene.SceneBuilder;
import io.github.litematicrender.render.scene.SceneMesh;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;

import java.nio.file.Path;

/** Groups translucent quads by position, to see which water layers emitted faces. */
public final class DiagWaterDup {
    public static void main(String[] args) throws Exception {
        var schem = LitematicaSchematic.load(Path.of(args[0]));
        var res = RenderTest.openResources(Path.of(args[1]), Path.of(args[1]).getParent().getParent());
        var reg = BlockRegistry.load(res);
        schem.materialize(reg);
        var cm = new Colormaps(); cm.load(res);
        var atlas = SpriteAtlas.load(res, "blocks");
        var baker = new ModelBaker(res, reg);
        var builder = new SceneBuilder(reg, baker, cm, atlas);
        for (var e : schem.regions().entrySet()) {
            var r = e.getValue();
            builder.addRegion(r, new int[]{r.position.x(), r.position.y(), r.position.z()});
        }
        SceneMesh mesh = builder.build();
        // Count translucent quads whose centroid sits inside each water cell (2,*,2).
        for (int y = 0; y <= 3; y++) {
            int n = 0;
            for (var q : mesh.translucent) {
                float cx = (q.x0 + q.x1 + q.x2 + q.x3) / 4f;
                float cy = (q.y0 + q.y1 + q.y2 + q.y3) / 4f;
                float cz = (q.z0 + q.z1 + q.z2 + q.z3) / 4f;
                if (Math.abs(cx - 2.5f) < 0.4f && Math.abs(cz - 2.5f) < 0.4f
                    && cy > y - 0.2f && cy < y + 1.2f) n++;
            }
            System.out.printf("  water cell y=%d: %d translucent quads%n", y, n);
        }
        res.close();
    }
}