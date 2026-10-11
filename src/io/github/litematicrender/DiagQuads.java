package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.render.Colormaps;
import io.github.litematicrender.render.SpriteAtlas;
import io.github.litematicrender.render.scene.SceneBuilder;
import io.github.litematicrender.render.scene.SceneMesh;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.BlockState;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prints how many quads each block in a fixture contributes, per layer. A block that renders
 * nothing (or only a couple of faces) stands out immediately, which is how missing carpet/snow
 * layers or an over-eager face cull get caught.
 */
public final class DiagQuads {
    public static void main(String[] args) throws Exception {
        LitematicaSchematic schem = LitematicaSchematic.load(Path.of(args[0]));
        ResourceManager res = new ResourceManager(Path.of(args[1]));
        BlockRegistry reg = BlockRegistry.load(res);
        schem.materialize(reg);
        ModelBaker baker = new ModelBaker(res, reg);
        Colormaps cm = new Colormaps();
        cm.load(res);
        SpriteAtlas atlas = SpriteAtlas.load(res, "blocks");

        SceneBuilder b = new SceneBuilder(reg, baker, cm, atlas);
        for (var e : schem.regions().entrySet()) {
            var r = e.getValue();
            b.addRegion(r, new int[]{r.position.x(), r.position.y(), r.position.z()});
        }
        SceneMesh mesh = b.build();

        // Count blocks present and quads per block id.
        Map<String, Integer> blockCount = new TreeMap<>();
        for (var e : schem.regions().entrySet()) {
            e.getValue().blocks.forEachNonAir((x, y, z, st) ->
                blockCount.merge(st.blockId + st.propertyString(), 1, Integer::sum));
        }

        Map<String, Integer> quadsBySprite = new LinkedHashMap<>();
        for (SceneMesh.MeshQuad q : mesh.solid) quadsBySprite.merge("solid:" + q.spriteId, 1, Integer::sum);
        for (SceneMesh.MeshQuad q : mesh.cutout) quadsBySprite.merge("cutout:" + q.spriteId, 1, Integer::sum);
        for (SceneMesh.MeshQuad q : mesh.translucent) quadsBySprite.merge("translucent:" + q.spriteId, 1, Integer::sum);

        System.out.println("=== blocks in the fixture ===");
        blockCount.forEach((k, v) -> System.out.printf("  %-70s x%d%n", k, v));
        System.out.println();
        System.out.println("=== quads by layer:sprite ===");
        quadsBySprite.forEach((k, v) -> System.out.printf("  %-70s %d%n", k, v));
        System.out.printf("%n totals: solid=%d cutout=%d translucent=%d%n",
            mesh.solid.size(), mesh.cutout.size(), mesh.translucent.size());

        // Per-state classification, which decides both culling and AO.
        io.github.litematicrender.world.BlockShape shapes =
            new io.github.litematicrender.world.BlockShape(baker, atlas);
        System.out.println();
        System.out.println("=== per-state classification ===");
        System.out.printf("  %-62s %-9s %-9s %s%n", "state", "occludes", "fullCube", "AO shade");
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (var e : schem.regions().entrySet()) {
            e.getValue().blocks.forEachNonAir((x, y, z, st) -> {
                String key = st.blockId + st.propertyString();
                if (!seen.add(key)) return;
                BlockState s = st;
                System.out.printf("  %-62s %-9s %-9s %.2f%n", key,
                    shapes.occludes(s), shapes.isShapeFullBlock(s), shapes.shadeBrightness(s));
            });
        }
        res.close();
    }
}
