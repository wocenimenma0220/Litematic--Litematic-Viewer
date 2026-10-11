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

/** Prints the water surface height per cell along Z, so the descent direction is unambiguous. */
public final class DiagSlopeProfile {
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
        // For each Z cell in the trench, report the highest water vertex found there.
        java.util.Map<Integer, Float> topByZ = new java.util.TreeMap<>();
        for (var q : mesh.translucent) {
            if (q.spriteId == null || !q.spriteId.contains("water")) continue;
            float cz = (q.z0 + q.z1 + q.z2 + q.z3) / 4f;
            float top = Math.max(Math.max(q.y0, q.y1), Math.max(q.y2, q.y3));
            int zc = (int) Math.floor(cz);
            topByZ.merge(zc, top, Math::max);
        }
        System.out.println("cell Z -> highest water surface Y:");
        topByZ.forEach((z, y) -> System.out.printf("  z=%2d  topY=%.4f  (height %.4f)%n", z, y, y - 1f));
    }
}