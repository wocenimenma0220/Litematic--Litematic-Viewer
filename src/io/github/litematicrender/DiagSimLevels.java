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

/** Groups translucent quads by their top-face height, to prove the simulation produced a gradient. */
public final class DiagSimLevels {
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
        // Histogram the maximum Y of translucent quads with the water sprite.
        java.util.Map<String, Integer> byTopY = new java.util.TreeMap<>();
        for (var q : mesh.translucent) {
            if (q.spriteId == null || !q.spriteId.contains("water")) continue;
            float topY = Math.max(Math.max(q.y0, q.y1), Math.max(q.y2, q.y3));
            String key = String.format("%.3f", topY);
            byTopY.merge(key, 1, Integer::sum);
        }
        System.out.println("water quad top-Y histogram:");
        byTopY.forEach((k, v) -> System.out.printf("  topY=%s  %d quads%n", k, v));
    }
}