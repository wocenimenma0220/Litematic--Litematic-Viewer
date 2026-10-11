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

/**
 * Reports which fluid sprite each water quad used.
 *
 * <p>The point is the sprite choice, not the geometry: vanilla draws a fluid with the still sprite
 * only when its flow vector is exactly zero, and with the flowing sprite otherwise. A flow vector
 * that always comes out zero makes every water block — including flowing ones — render as a source,
 * which is the symptom this proves or disproves.
 */
public final class DiagWaterSprite {
    public static void main(String[] args) throws Exception {
        Path vanilla = Path.of(args[1]);
        ResourceManager res = RenderTest.openResources(vanilla, vanilla.getParent().getParent());
        LitematicaSchematic schem = LitematicaSchematic.load(Path.of(args[0]));
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

        java.util.Map<String, Integer> bySprite = new java.util.TreeMap<>();
        for (SceneMesh.MeshQuad q : mesh.translucent) {
            bySprite.merge(String.valueOf(q.spriteId), 1, Integer::sum);
        }
        System.out.println("translucent quads (fluid lives here since the layer merge): " + mesh.translucent.size());
        bySprite.forEach((k, v) -> System.out.printf("  %-38s %6d%n", k, v));
        System.out.println();
        System.out.println(bySprite.containsKey("minecraft:block/water_flow")
            ? "=> flowing sprite IS used (flow vectors are non-zero)"
            : "=> only the still sprite is used (flow vectors are all zero)");
        res.close();
    }
}
