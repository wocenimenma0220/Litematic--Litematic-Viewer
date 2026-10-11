package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.model.Quad;
import io.github.litematicrender.render.SpriteAtlas;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.BlockState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Sanity-checks the data-driven full-cube classification against blocks whose answer is known
 * from vanilla. A wrong "true" punches holes in a render (the failure we already hit with
 * chests/hoppers); a wrong "false" only wastes a few quads.
 */
public final class DiagShapeAudit {
    public static void main(String[] args) throws Exception {
        ResourceManager res = new ResourceManager(Path.of(args[0]));
        BlockRegistry reg = BlockRegistry.load(res);
        ModelBaker baker = new ModelBaker(res, reg);
        SpriteAtlas atlas = SpriteAtlas.load(res, "blocks");
        io.github.litematicrender.world.BlockShape shapes =
            new io.github.litematicrender.world.BlockShape(baker, atlas);

        // Blocks that MUST occlude: full-cube model AND fully opaque textures.
        // Vanilla reaches the same answer via getFaceOcclusionShape()==block for opaque cubes.
        String[] mustOcclude = {
            "stone", "oak_planks", "dirt", "oak_log", "bricks", "sandstone",
            "white_wool", "iron_block", "obsidian", "deepslate", "andesite", "granite",
            "bookshelf", "crafting_table", "furnace", "hay_block", "melon", "pumpkin",
            "red_sandstone", "netherrack", "end_stone", "coal_block", "quartz_block",
            "smooth_stone", "terracotta", "netherite_block", "copper_block",
        };
        // Blocks that must NOT occlude. Two groups: blocks vanilla marks noOcclusion()
        // (glass/leaves/ice — full-cube models whose textures are not opaque, and which
        // must never hide a neighbour), and partial models (stairs/fences/panes/slabs).
        String[] mustNotOcclude = {
            // noOcclusion() with a full-cube model — transparency must be enough to detect these
            "glass", "tinted_glass", "white_stained_glass", "ice", "frosted_ice",
            "slime_block", "honey_block", "oak_leaves", "oak_slab",
            // partial models
            "oak_stairs", "oak_fence", "stone_brick_wall", "glass_pane",
            "white_stained_glass_pane", "torch", "redstone_wire", "brewing_stand", "composter",
            "cauldron", "farmland", "dirt_path", "oak_trapdoor", "ladder", "rail",
            "oak_door", "anvil", "chiseled_bookshelf", "sculk_sensor", "lightning_rod",
            "lectern", "stonecutter", "grindstone", "chest", "hopper", "end_rod", "chain",
        };

        int bad = 0;
        System.out.println("== must OCCLUDE ==");
        for (String n : mustOcclude) {
            String id = "minecraft:" + n;
            BlockState st = reg.getOrMissing(id).defaultState;
            boolean got = shapes.occludes(st);
            if (!got) { System.out.println("  FAIL " + id + " -> false (expected true)"); bad++; }
        }
        System.out.println("== must NOT occlude ==");
        for (String n : mustNotOcclude) {
            String id = "minecraft:" + n;
            BlockState st = reg.getOrMissing(id).defaultState;
            boolean got = shapes.occludes(st);
            if (got) { System.out.println("  FAIL " + id + " -> true (expected false)"); bad++; }
        }

        // AO must darken on blocks whose COLLISION shape is a full cube, even when the block
        // does not occlude (glass, soul sand). Vanilla: getShadeBrightness uses the collision shape.
        System.out.println("== AO shade must be 0.2 (full collision cube) ==");
        for (String n : new String[]{"stone", "glass", "ice", "soul_sand", "white_stained_glass"}) {
            String id = "minecraft:" + n;
            float sh = shapes.shadeBrightness(reg.getOrMissing(id).defaultState);
            boolean ok = Math.abs(sh - 0.2f) < 1e-4f;
            if (!ok) { System.out.println("  FAIL " + id + " shade=" + sh + " (expected 0.2)"); bad++; }
        }
        System.out.println("== AO shade must be 1.0 (not a full collision cube) ==");
        for (String n : new String[]{"chest", "oak_slab", "oak_stairs", "hopper"}) {
            String id = "minecraft:" + n;
            float sh = shapes.shadeBrightness(reg.getOrMissing(id).defaultState);
            boolean ok = Math.abs(sh - 1.0f) < 1e-4f;
            if (!ok) { System.out.println("  FAIL " + id + " shade=" + sh + " (expected 1.0)"); bad++; }
        }

        // Regression guard: a retracted piston is Shapes.block() in vanilla
        // (PistonBaseBlock.getShape returns Shapes.block() unless EXTENDED), an extended one is not.
        System.out.println("== piston extended/retracted (PistonBaseBlock.getShape) ==");
        {
            BlockRegistry.BlockInfo info = reg.getOrMissing("minecraft:sticky_piston");
            BlockState retracted = info.defaultState.tryWith("extended", "false").tryWith("facing", "north");
            BlockState extended = info.defaultState.tryWith("extended", "true").tryWith("facing", "north");
            boolean r = shapes.occludes(retracted), x = shapes.occludes(extended);
            if (!r) { System.out.println("  FAIL retracted piston should occlude (Shapes.block())"); bad++; }
            if (x) { System.out.println("  FAIL extended piston should NOT occlude (partial shape)"); bad++; }
        }

        // Report how many of the whole registry classify as occluding, as a smell test.
        int total = 0, occl = 0;
        for (String id : new TreeSet<>(reg.blockIds())) {
            total++;
            if (shapes.occludes(reg.getOrMissing(id).defaultState)) occl++;
        }
        System.out.println();
        System.out.println("registry: " + total + " blocks, " + occl + " occluding ("
            + Math.round(100.0 * occl / Math.max(1, total)) + "%)");
        System.out.println("audit failures: " + bad);
        res.close();
    }
}
