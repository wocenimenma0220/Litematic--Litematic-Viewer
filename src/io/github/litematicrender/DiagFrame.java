package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.render.Colormaps;
import io.github.litematicrender.render.Image;
import io.github.litematicrender.render.RenderSettings;
import io.github.litematicrender.render.SchematicRenderer;
import io.github.litematicrender.render.SpriteAtlas;
import io.github.litematicrender.render.scene.SceneBuilder;
import io.github.litematicrender.render.scene.SceneMesh;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;

import java.nio.file.Path;

/**
 * Breaks a single frame into its stages, to find what still limits drag latency.
 *
 * <p>Guessing here is expensive: the candidates (vertex transform, fill, translucent sorting,
 * PNG encoding) have completely different fixes.
 */
public final class DiagFrame {
    public static void main(String[] args) throws Exception {
        Path schemFile = Path.of(args[0]);
        Path vanilla = Path.of(args[1]);
        ResourceManager res = io.github.litematicrender.render.RenderTest.openResources(
            vanilla, vanilla.getParent().getParent());
        try {
            LitematicaSchematic schem = LitematicaSchematic.load(schemFile);
            BlockRegistry reg = BlockRegistry.load(res);
            schem.materialize(reg);
            Colormaps cm = new Colormaps();
            cm.load(res);
            SpriteAtlas atlas = SpriteAtlas.load(res, "blocks");
            ModelBaker baker = new ModelBaker(res, reg);

            SceneBuilder builder = new SceneBuilder(reg, baker, cm, atlas);
            for (var e : schem.regions().entrySet()) {
                var r = e.getValue();
                builder.addRegion(r, new int[]{r.position.x(), r.position.y(), r.position.z()});
            }
            SceneMesh mesh = builder.build();
            System.out.printf("quads: solid=%d cutout=%d portal=%d fluid=%d translucent=%d  total=%d%n",
                mesh.solid.size(), mesh.cutout.size(), mesh.portal.size(), mesh.fluid.size(),
                mesh.translucent.size(), mesh.quadCount);

            for (int edge : new int[]{256, 384, 512, 1024}) {
                // warm up the JIT so the numbers are about the algorithm, not compilation
                for (int i = 0; i < 2; i++) {
                    RenderSettings w = new RenderSettings(45, 30, edge, edge);
                    SchematicRenderer.render(schem, mesh, cm, atlas, w);
                }
                long best = Long.MAX_VALUE;
                long worst = 0;
                int runs = 5;
                for (int i = 0; i < runs; i++) {
                    RenderSettings s = new RenderSettings(45 + i * 5, 30, edge, edge);
                    long t = System.nanoTime();
                    Image img = SchematicRenderer.render(schem, mesh, cm, atlas, s);
                    long ms = (System.nanoTime() - t) / 1_000_000;
                    best = Math.min(best, ms);
                    worst = Math.max(worst, ms);
                    if (i == 0) {
                        long e0 = System.nanoTime();
                        img.writePng(Path.of(System.getProperty("java.io.tmpdir"), "frame_probe.png"));
                        System.out.printf("  (%dpx PNG encode: %d ms)%n", edge,
                            (System.nanoTime() - e0) / 1_000_000);
                    }
                }
                System.out.printf("%5dpx: render best=%dms worst=%dms  (%.1f fps at best)%n",
                    edge, best, worst, best == 0 ? 0 : 1000.0 / best);
            }
        } finally {
            res.close();
        }
    }
}
