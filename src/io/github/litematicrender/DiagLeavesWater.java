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

/** Finds waterlogged leaves in the real schematic and checks each emitted a fluid quad. */
public final class DiagLeavesWater {
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

        // Re-walk the schematic; for each waterlogged leaf, count translucent quads whose centroid
        // falls inside that cell. Two or more means the water rendered.
        java.util.List<int[]> leaves = new java.util.ArrayList<>();
        for (var re : schem.regions().entrySet()) {
            var region = re.getValue();
            int[] off = {region.position.x(), region.position.y(), region.position.z()};
            region.blocks.forEachNonAir((x, y, z, s) -> {
                String v = s.getValue("waterlogged");
                if (s.blockId.equals("minecraft:oak_leaves") && v != null && Boolean.parseBoolean(v)) {
                    leaves.add(new int[]{off[0] + x, off[1] + y, off[2] + z});
                }
            });
        }
        System.out.println("waterlogged oak leaves: " + leaves.size());
        for (int[] p : leaves) {
            int n = 0;
            for (var q : mesh.translucent) {
                float cx = (q.x0 + q.x1 + q.x2 + q.x3) / 4f;
                float cy = (q.y0 + q.y1 + q.y2 + q.y3) / 4f;
                float cz = (q.z0 + q.z1 + q.z2 + q.z3) / 4f;
                if (Math.abs(cx - (p[0] + 0.5f)) < 0.45f && Math.abs(cz - (p[2] + 0.5f)) < 0.45f
                    && cy > p[1] - 0.2f && cy < p[1] + 1.2f) n++;
            }
            System.out.printf("  leaf at (%d,%d,%d): %d fluid quads %s%n",
                p[0], p[1], p[2], n, n >= 2 ? "OK" : "MISSING WATER");
        }
        res.close();
    }
}