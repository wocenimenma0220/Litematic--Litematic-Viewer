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
import java.util.TreeMap;

/**
 * Correlates each water cell's stored level with the surface height drawn in that column.
 *
 * <p>Walks the region directly rather than through blockAt, so the coordinates always line up.
 */
public final class DiagLevelHeights {
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

        // Map: level -> list of drawn surface heights for cells carrying that level.
        TreeMap<Integer, java.util.List<Float>> byLevel = new TreeMap<>();
        for (var e : schem.regions().entrySet()) {
            var r = e.getValue();
            int[] off = {r.position.x(), r.position.y(), r.position.z()};
            r.blocks.forEachNonAir((lx, ly, lz, s) -> {
                if (!s.blockId.equals("minecraft:water")) return;
                String lv = s.getValue("level");
                if (lv == null) return;
                int level = Integer.parseInt(lv);
                int wx = off[0] + lx, wy = off[1] + ly, wz = off[2] + lz;
                float top = 0f;
                for (var q : mesh.translucent) {
                    if (q.spriteId == null || !q.spriteId.contains("water")) continue;
                    if (Math.abs((q.x0+q.x1+q.x2+q.x3)/4f - (wx + 0.5f)) > 0.3f) continue;
                    if (Math.abs((q.z0+q.z1+q.z2+q.z3)/4f - (wz + 0.5f)) > 0.3f) continue;
                    top = Math.max(top, Math.max(Math.max(q.y0,q.y1), Math.max(q.y2,q.y3)) - wy);
                }
                byLevel.computeIfAbsent(level, k -> new java.util.ArrayList<>()).add(top);
            });
        }
        System.out.printf("%-6s %-9s %-9s %-8s %s%n", "level", "expected", "drawn~", "cells", "drawn range");
        byLevel.forEach((level, list) -> {
            float expected = ((level == 0 || level >= 8) ? 8 : 8 - level) / 9f;
            float sum = 0; float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (float v : list) { sum += v; lo = Math.min(lo, v); hi = Math.max(hi, v); }
            System.out.printf("%-6d %-9.3f %-9.3f %-8d [%.3f .. %.3f]%n",
                level, expected, sum / list.size(), list.size(), lo, hi);
        });
    }
}