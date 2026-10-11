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

/** Groups fluid quads by the block position they belong to. */
public final class DiagFluidQuads {
    public static void main(String[] args) throws Exception {
        var schem = LitematicaSchematic.load(Path.of(args[0]));
        var res = RenderTest.openResources(Path.of(args[1]), Path.of(args[1]).getParent().getParent());
        var reg = io.github.litematicrender.world.BlockRegistry.load(res);
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
        java.util.Map<String, Integer> byPos = new java.util.TreeMap<>();
        for (var q : mesh.fluid) {
            int bx = Math.round((q.x0 + q.x1 + q.x2 + q.x3) / 4f - 0.5f);
            int by = Math.round((q.y0 + q.y1 + q.y2 + q.y3) / 4f - 0.5f);
            int bz = Math.round((q.z0 + q.z1 + q.z2 + q.z3) / 4f - 0.5f);
            byPos.merge("(" + bx + "," + by + "," + bz + ")", 1, Integer::sum);
        }
        byPos.forEach((k, v) -> System.out.printf("  %-14s %3d quads%n", k, v));
        res.close();
    }
}