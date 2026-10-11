package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.model.Quad;
import io.github.litematicrender.render.RenderTest;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.BlockState;

import java.nio.file.Path;

/** Prints the lever stick quads after baking: the rotated stick must rise above the base. */
public final class DiagLeverGeom {
    public static void main(String[] args) throws Exception {
        var res = RenderTest.openResources(Path.of(args[0]), Path.of(args[0]).getParent().getParent());
        var reg = BlockRegistry.load(res);
        var baker = new ModelBaker(res, reg);
        BlockState st = reg.getOrMissing("minecraft:lever").defaultState
            .tryWith("face", "floor").tryWith("facing", "north").tryWith("powered", "false");
        var vs = baker.selectVariants("minecraft:lever", st);
        for (var v : vs) {
            var m = baker.bake(v);
            System.out.println("model: " + v.modelId());
            System.out.println("unculled quads: " + m.unculled.size());
            int i = 0;
            for (Quad q : m.unculled) {
                float top = Math.max(Math.max(q.y0, q.y1), Math.max(q.y2, q.y3));
                if (top > 0.35f) {   // above the base (base top is 2.98/16 = 0.186)
                    System.out.printf("  quad%d top=%.3f  x[%.3f..%.3f] z[%.3f..%.3f]%n",
                        i++, top, Math.min(Math.min(q.x0,q.x1),Math.min(q.x2,q.x3)),
                        Math.max(Math.max(q.x0,q.x1),Math.max(q.x2,q.x3)),
                        Math.min(Math.min(q.z0,q.z1),Math.min(q.z2,q.z3)),
                        Math.max(Math.max(q.z0,q.z1),Math.max(q.z2,q.z3)));
                }
                if (i > 7) break;
            }
        }
        res.close();
    }
}