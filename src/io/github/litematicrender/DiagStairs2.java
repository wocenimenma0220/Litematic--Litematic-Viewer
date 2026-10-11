package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.render.RenderTest;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.BlockState;

import java.nio.file.Path;

/** Prints the model id and y rotation chosen for each stair facing. */
public final class DiagStairs2 {
    public static void main(String[] args) throws Exception {
        var res = RenderTest.openResources(Path.of(args[0]), Path.of(args[0]).getParent().getParent());
        var reg = BlockRegistry.load(res);
        var baker = new ModelBaker(res, reg);
        for (String facing : new String[]{"north", "south", "east", "west"}) {
            BlockState st = reg.getOrMissing("minecraft:oak_stairs").defaultState
                .tryWith("facing", facing).tryWith("half", "bottom")
                .tryWith("shape", "straight").tryWith("waterlogged", "false");
            var vs = baker.selectVariants("minecraft:oak_stairs", st);
            StringBuilder sb = new StringBuilder();
            for (var v : vs) {
                sb.append(v.modelId().replace("minecraft:block/", ""));
                if (v.yRot() != 0) sb.append(" y=").append(v.yRot());
            }
            System.out.printf("  facing=%-6s -> %s%n", facing, sb);
        }
        // Same for the barrel, whose facing is also directional.
        System.out.println("barrel:");
        for (String facing : new String[]{"up", "north", "east", "south", "west", "down"}) {
            BlockState st = reg.getOrMissing("minecraft:barrel").defaultState
                .tryWith("facing", facing).tryWith("open", "false");
            var vs = baker.selectVariants("minecraft:barrel", st);
            StringBuilder sb = new StringBuilder();
            for (var v : vs) {
                sb.append(v.modelId().replace("minecraft:block/", ""));
                if (v.yRot() != 0) sb.append(" y=").append(v.yRot());
                if (v.xRot() != 0) sb.append(" x=").append(v.xRot());
            }
            System.out.printf("  facing=%-6s -> %s%n", facing, sb);
        }
        res.close();
    }
}