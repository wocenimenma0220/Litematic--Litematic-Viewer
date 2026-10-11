package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.BlockState;

import java.nio.file.Path;
import java.util.List;

/**
 * Reports which blocks resolve to a model that draws no geometry.
 *
 * <p>Blocks whose appearance comes from a block-entity renderer — chests, hoppers, shulker boxes,
 * beds, banners — ship a blockstate model containing only a particle texture and no elements. The
 * model system therefore produces nothing for them, and they have to be built from their entity
 * data instead. This finds them without guessing at names.
 */
public final class DiagModelless {
    public static void main(String[] args) throws Exception {
        ResourceManager res = new ResourceManager(Path.of(args[0]));
        BlockRegistry reg = BlockRegistry.load(res);
        ModelBaker baker = new ModelBaker(res, reg);

        String[] candidates = {
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:ender_chest",
            "minecraft:hopper", "minecraft:shulker_box", "minecraft:white_shulker_box",
            "minecraft:barrel", "minecraft:furnace", "minecraft:dispenser",
            "minecraft:dropper", "minecraft:crafter", "minecraft:brewing_stand",
            "minecraft:jukebox", "minecraft:comparator", "minecraft:chiseled_bookshelf",
            "minecraft:skeleton_skull", "minecraft:player_head", "minecraft:sign",
            "minecraft:beacon", "minecraft:enchanting_table", "minecraft:bell",
        };

        System.out.printf("%-34s %-8s %s%n", "block", "quads", "verdict");
        for (String id : candidates) {
            BlockState st = reg.getOrMissing(id).defaultState;
            List<ModelBaker.Variant> variants = baker.selectVariants(id, st);
            int quads = 0;
            for (ModelBaker.Variant v : variants) {
                ModelBaker.BakedModel m = baker.bake(v);
                quads += m.all.size();
            }
            String verdict = quads == 0 ? "NO GEOMETRY - needs entity renderer"
                : variants.isEmpty() ? "no variant" : "ok";
            System.out.printf("%-34s %-8d %s%n", id, quads, verdict);
        }
        res.close();
    }
}
