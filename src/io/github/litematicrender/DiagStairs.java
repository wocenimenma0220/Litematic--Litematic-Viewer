package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.model.Quad;
import io.github.litematicrender.render.RenderTest;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.BlockState;

import java.nio.file.Path;

/**
 * Prints which world face an oak stair's tall side lands on, for each facing.
 *
 * <p>A stair's tall half sits at the back of the block, so with facing=north the tall half must be
 * on the south side (you climb toward north). Reading the quad whose top edge is highest tells us
 * whether the blockstate's y rotation is being applied in the right direction.
 */
public final class DiagStairs {
    public static void main(String[] args) throws Exception {
        var res = RenderTest.openResources(Path.of(args[0]), Path.of(args[0]).getParent().getParent());
        var reg = BlockRegistry.load(res);
        var baker = new ModelBaker(res, reg);
        for (String facing : new String[]{"north", "south", "east", "west"}) {
            BlockState st = reg.getOrMissing("minecraft:oak_stairs").defaultState
                .tryWith("facing", facing).tryWith("half", "bottom")
                .tryWith("shape", "straight").tryWith("waterlogged", "false");
            var variants = baker.selectVariants("minecraft:oak_stairs", st);
            // Find the quad with the highest maximum Y; the tall half of the stair.
            float best = -1; int bestFace = -1;
            for (var v : variants) {
                var m = baker.bake(v);
                for (int f = 0; f < 6; f++) {
                    for (Quad q : m.quadsForFace(f)) {
                        float top = Math.max(Math.max(q.y0, q.y1), Math.max(q.y2, q.y3));
                        if (top > best) { best = top; bestFace = f; }
                    }
                }
                for (Quad q : m.unculled) {
                    float top = Math.max(Math.max(q.y0, q.y1), Math.max(q.y2, q.y3));
                    if (top > best) { best = top; bestFace = -2; }
                }
            }
            // A quad on the NORTH face (z=0) means the tall half is at the north edge.
            String side = switch (bestFace) {
                case Quad.NORTH -> "north edge";
                case Quad.SOUTH -> "south edge";
                case Quad.EAST -> "east edge";
                case Quad.WEST -> "west edge";
                default -> "unculled/none";
            };
            System.out.printf("  facing=%-6s tall half at %-12s (topY=%.2f)%n", facing, side, best);
        }
        res.close();
    }
}