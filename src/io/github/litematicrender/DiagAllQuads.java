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

/** Every translucent quad in a fixture, with its sprite and corner heights. */
public final class DiagAllQuads {
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
        System.out.println("translucent quads: " + mesh.translucent.size());
        int n = 0;
        for (var q : mesh.translucent) {
            if (n++ > 20) break;
            System.out.printf("  c=(%.2f,%.2f,%.2f) sprite=%-22s h=[%.4f %.4f %.4f %.4f]%n",
                (q.x0+q.x1+q.x2+q.x3)/4f, (q.y0+q.y1+q.y2+q.y3)/4f, (q.z0+q.z1+q.z2+q.z3)/4f,
                String.valueOf(q.spriteId).replace("minecraft:block/", ""),
                q.y0 - Math.floor(q.y0), q.y1 - Math.floor(q.y1),
                q.y2 - Math.floor(q.y2), q.y3 - Math.floor(q.y3));
        }
    }
}