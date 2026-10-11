package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.model.Quad;
import io.github.litematicrender.render.RenderTest;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.BlockState;

import java.nio.file.Path;

/** Counts the quads a lever produces per face value, to find where the stick goes missing. */
public final class DiagLever {
    public static void main(String[] args) throws Exception {
        var res = RenderTest.openResources(Path.of(args[0]), Path.of(args[0]).getParent().getParent());
        var reg = BlockRegistry.load(res);
        var baker = new ModelBaker(res, reg);
        for (String face : new String[]{"floor", "wall", "ceiling"}) {
            for (String facing : new String[]{"north", "east"}) {
                BlockState st = reg.getOrMissing("minecraft:lever").defaultState
                    .tryWith("face", face).tryWith("facing", facing).tryWith("powered", "false");
                var vs = baker.selectVariants("minecraft:lever", st);
                int quads = 0;
                for (var v : vs) quads += baker.bake(v).all.size();
                System.out.printf("  face=%-8s facing=%-6s models=%d quads=%d%n",
                    face, facing, vs.size(), quads);
            }
        }
        res.close();
    }
}