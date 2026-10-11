package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.model.Quad;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.BlockState;

import java.nio.file.Path;
import java.util.List;

/**
 * Asserts that blockstate rotations land a block's "front" face on the right side of the world.
 *
 * <p>Every oriented block inherits this one transform, so a sign error here turns a whole family
 * of blocks around at once (observer, dropper, dispenser, piston, comparator, wall torches). The
 * test reads like the game does: the model's front is baked on a known face, and the blockstate's
 * y value must move it to the face the property names.
 */
public final class DiagOrientation {
    private static int failures;

    public static void main(String[] args) throws Exception {
        ResourceManager res = new ResourceManager(Path.of(args[0]));
        BlockRegistry reg = BlockRegistry.load(res);
        ModelBaker baker = new ModelBaker(res, reg);

        // Which sprite marks the block's front, and which face the model bakes it on unrotated.
        check(reg, baker, "minecraft:observer", "observer_front", Quad.NORTH,
            new String[]{"north", "south", "east", "west"});
        check(reg, baker, "minecraft:dropper", "dropper_front", Quad.NORTH,
            new String[]{"north", "south", "east", "west"});
        check(reg, baker, "minecraft:piston", "piston_top", Quad.NORTH,
            new String[]{"north", "south", "east", "west"});

        // A wall torch has no distinct front texture — all six faces share one sprite, and the
        // direction shows as which wall the torch leans against. Check geometry instead.
        //
        // Vanilla maps facing=east to y=0, and the template leans west unrotated: "facing" is the
        // direction the torch points away from its wall, so the body leans toward the OPPOSITE side.
        // This expectation only passes with a proper rotation — under the reflected matrix that was
        // briefly in place, the north/south cases mirrored.
        for (String facing : new String[]{"north", "south", "east", "west"}) {
            BlockState st = reg.getOrMissing("minecraft:redstone_wall_torch").defaultState
                .tryWith("facing", facing).tryWith("lit", "true");
            String wall = torchWall(baker, st);
            String expected = opposite(facing);
            boolean ok = wall != null && wall.equals(expected);
            if (!ok) failures++;
            System.out.printf("  %-6s %-32s wall expected=%-6s got=%s%n",
                ok ? "ok" : "FAIL", "redstone_wall_torch facing=" + facing,
                "body toward " + expected, wall);
        }

        System.out.println();
        System.out.println(failures == 0 ? "orientation OK" : "orientation FAILURES: " + failures);
        if (failures > 0) System.exit(1);
    }

    private static void check(BlockRegistry reg, ModelBaker baker, String blockId,
                              String frontSprite, int baseFace, String[] facings) {
        for (String facing : facings) {
            BlockState st = reg.getOrMissing(blockId).defaultState;
            // Every block here uses "facing" plus a couple of irrelevant properties; tryWith
            // ignores the ones a block doesn't have.
            st = st.tryWith("facing", facing).tryWith("powered", "false")
                   .tryWith("lit", "true").tryWith("extended", "false")
                   .tryWith("waterlogged", "false");
            String found = frontFaceSprite(baker, blockId, st, frontSprite);
            boolean ok = found != null && found.equals(facing);
            if (!ok) failures++;
            System.out.printf("  %-6s %-32s front expected=%-6s got=%s%n",
                ok ? "ok" : "FAIL", blockId.substring(10) + " facing=" + facing, facing, found);
        }
    }

    /** Finds which world face carries the given sprite, by the quad's own face tag. */
    private static String frontFaceSprite(ModelBaker baker, String blockId, BlockState st, String sprite) {
        List<ModelBaker.Variant> variants = baker.selectVariants(blockId, st);
        for (ModelBaker.Variant v : variants) {
            ModelBaker.BakedModel m = baker.bake(v);
            for (int face = 0; face < 6; face++) {
                for (Quad q : m.quadsForFace(face)) {
                    if (q.spriteId != null && q.spriteId.endsWith(sprite)) {
                        return faceName(face);
                    }
                }
            }
            // Also scan unculled quads: torches put their front on a flat face.
            for (Quad q : m.unculled) {
                if (q.spriteId != null && q.spriteId.endsWith(sprite)) {
                    return "unculled";
                }
            }
        }
        return null;
    }

    /**
     * Reports which wall a wall torch is mounted on, from its geometry: the torch sticks out of
     * the wall, so the side of the block the model does NOT reach is the wall it hangs on.
     */
    private static String torchWall(ModelBaker baker, BlockState st) {
        List<ModelBaker.Variant> variants = baker.selectVariants(st.blockId, st);
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (ModelBaker.Variant v : variants) {
            ModelBaker.BakedModel m = baker.bake(v);
            for (Quad q : m.all) {
                minX = Math.min(minX, min4(q.x0, q.x1, q.x2, q.x3));
                maxX = Math.max(maxX, max4(q.x0, q.x1, q.x2, q.x3));
                minZ = Math.min(minZ, min4(q.z0, q.z1, q.z2, q.z3));
                maxZ = Math.max(maxZ, max4(q.z0, q.z1, q.z2, q.z3));
            }
        }
        // Whichever axis the model spans least towards is the wall plane it sits on.
        boolean spansX = (maxX - minX) > 0.5f;
        boolean spansZ = (maxZ - minZ) > 0.5f;
        if (spansX && !spansZ) return (minX + maxX) / 2f < 0.5f ? "west" : "east";
        if (spansZ && !spansX) return (minZ + maxZ) / 2f < 0.5f ? "north" : "south";
        // A torch leaning out of a wall spans the full block along its own axis and sits against
        // one face on the other; use which extreme it touches.
        return (maxX - minX) >= (maxZ - minZ)
            ? (maxX > 0.9f ? "east" : "west")
            : (maxZ > 0.9f ? "south" : "north");
    }

    private static float min4(float a, float b, float c, float d) { return Math.min(Math.min(a, b), Math.min(c, d)); }
    private static float max4(float a, float b, float c, float d) { return Math.max(Math.max(a, b), Math.max(c, d)); }

    private static String opposite(String facing) {
        return switch (facing) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            default -> "east";
        };
    }

    private static String faceName(int f) {
        return switch (f) {
            case Quad.DOWN -> "down";
            case Quad.UP -> "up";
            case Quad.NORTH -> "north";
            case Quad.SOUTH -> "south";
            case Quad.WEST -> "west";
            default -> "east";
        };
    }
}
