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

/** For each water cell along the trench: its stored level next to the surface height drawn there. */
public final class DiagCellVsLevel {
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
        System.out.printf("%-4s %-6s %-10s %-10s%n", "z", "level", "expected", "drawn");
        for (int z = 0; z <= 9; z++) {
            var st = builder.blockAt(1, 0, z);
            if (st == null || !st.blockId.contains("water")) continue;
            String lv = st.getValue("level");
            int level = lv == null ? 0 : Integer.parseInt(lv);
            float expected = ((level == 0 || level >= 8) ? 8 : 8 - level) / 9f;
            float drawn = 0f;
            for (var q : mesh.translucent) {
                if (q.spriteId == null || !q.spriteId.contains("water")) continue;
                if (Math.abs((q.x0+q.x1+q.x2+q.x3)/4f - 1.5f) > 0.3f) continue;
                if (Math.abs((q.z0+q.z1+q.z2+q.z3)/4f - (z + 0.5f)) > 0.3f) continue;
                drawn = Math.max(drawn, Math.max(Math.max(q.y0,q.y1), Math.max(q.y2,q.y3)) - 1f);
            }
            System.out.printf("%-4d %-6d %-10.3f %-10.3f%n", z, level, expected, drawn);
        }
    }
}