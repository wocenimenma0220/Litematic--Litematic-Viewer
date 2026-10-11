package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.render.Colormaps;
import io.github.litematicrender.render.RenderSettings;
import io.github.litematicrender.render.SchematicRenderer;
import io.github.litematicrender.render.SpriteAtlas;
import io.github.litematicrender.render.scene.SceneBuilder;
import io.github.litematicrender.render.scene.SceneMesh;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;

import java.nio.file.Path;
import java.util.TreeMap;

/**
 * Splits render time into scene-build and rasterize, and reports the source resolution of the
 * block textures.
 *
 * <p>The scene depends only on the schematic and the resource pack, never on the camera — so if
 * building it dominates, dragging can be made smooth by building once and only re-projecting.
 */
public final class DiagPerf {
    public static void main(String[] args) throws Exception {
        Path schemFile = Path.of(args[0]);
        ResourceManager res = io.github.litematicrender.render.RenderTest.openResources(
            Path.of(args[1]), Path.of(args[1]).getParent().getParent());
        try {
            LitematicaSchematic schem = LitematicaSchematic.load(schemFile);
            BlockRegistry reg = BlockRegistry.load(res);
            schem.materialize(reg);
            long t0 = System.currentTimeMillis();
            Colormaps cm = new Colormaps();
            cm.load(res);
            SpriteAtlas atlas = SpriteAtlas.load(res, "blocks");
            ModelBaker baker = new ModelBaker(res, reg);
            long t1 = System.currentTimeMillis();

            System.out.println("resource load: " + (t1 - t0) + "ms");

            // --- texture resolution census -------------------------------------------------
            TreeMap<String, Integer> sizes = new TreeMap<>();
            for (java.lang.reflect.Field f : SpriteAtlas.class.getDeclaredFields()) {
                if (!f.getName().equals("entries")) continue;
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                var map = (java.util.Map<String, SpriteAtlas.Entry>) f.get(atlas);
                for (SpriteAtlas.Entry e : map.values()) {
                    String key = e.contents.frameWidth + "x" + e.contents.frameHeight;
                    sizes.merge(key, 1, Integer::sum);
                }
            }
            int total = sizes.values().stream().mapToInt(Integer::intValue).sum();
            System.out.println("block texture frame sizes (of " + total + " sprites):");
            sizes.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .forEach(e -> System.out.printf("   %-12s %4d  (%d%%)%n",
                    e.getKey(), e.getValue(), Math.round(100.0 * e.getValue() / total)));

            // --- scene build vs rasterize --------------------------------------------------
            for (int w : new int[]{640, 1024, 2048}) {
                SceneMesh mesh = null;
                long sceneStart = System.currentTimeMillis();
                // Build once, then reuse across camera angles (the point of the measurement).
                SceneBuilder builder = new SceneBuilder(reg, baker, cm, atlas);
                for (var re : schem.regions().entrySet()) {
                    var r = re.getValue();
                    builder.addRegion(r, new int[]{r.position.x(), r.position.y(), r.position.z()});
                }
                mesh = builder.build();
                long sceneEnd = System.currentTimeMillis();

                long rasterSum = 0;
                int runs = 3;
                for (int i = 0; i < runs; i++) {
                    long rs = System.currentTimeMillis();
                    RenderSettings s = new RenderSettings(45 + i * 17, 30, w, w);
                    SchematicRenderer.render(schem, baker, reg, cm, atlas, s);
                    rasterSum += System.currentTimeMillis() - rs;
                }
                System.out.printf("%5dpx: scene build %4dms | rasterize %4dms (avg of %d)%n",
                    w, sceneEnd - sceneStart, rasterSum / runs, runs);
                // Count the quads we are paying to transform every frame.
                System.out.printf("        quads: solid=%d cutout=%d translucent=%d total=%d%n",
                    mesh.solid.size(), mesh.cutout.size(), mesh.translucent.size(), mesh.quadCount);
            }
        } finally {
            res.close();
        }
    }
}
