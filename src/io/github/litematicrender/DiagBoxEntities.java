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
 * Counts the geometry the block-entity pass contributes, and lists the sprites it references.
 *
 * <p>A chest's blockstate model has no elements, so any quads for one can only come from the
 * block-entity builder. Reporting the sprite ids proves the entity textures resolved rather than
 * silently falling back to missingno.
 */
public final class DiagBoxEntities {
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

        System.out.println("total quads: " + mesh.quadCount);
        java.util.Map<String, Integer> bySprite = new java.util.TreeMap<>();
        for (SceneMesh.MeshQuad q : mesh.cutout) {
            if (q.spriteId != null && q.spriteId.startsWith("minecraft:entity/")) {
                bySprite.merge(q.spriteId, 1, Integer::sum);
            }
        }
        System.out.println("block-entity quads by sprite:");
        if (bySprite.isEmpty()) {
            System.out.println("  (none - the block entity pass emitted nothing)");
        } else {
            bySprite.forEach((k, v) -> System.out.printf("  %-46s %6d%n", k, v));
        }
        res.close();
    }
}