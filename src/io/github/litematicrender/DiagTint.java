package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.model.Quad;
import io.github.litematicrender.render.BlockTints;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.BlockState;

import java.nio.file.Path;
import java.util.List;

/**
 * Prints tint colour and force-translucent flag per face, for blocks whose appearance depends on
 * properties rather than textures alone (redstone dust power, redstone torch lit, observer).
 */
public final class DiagTint {
    public static void main(String[] args) throws Exception {
        ResourceManager res = new ResourceManager(Path.of(args[0]));
        BlockRegistry reg = BlockRegistry.load(res);
        ModelBaker baker = new ModelBaker(res, reg);

        System.out.println("== redstone_wire tint by power (vanilla RedstoneWireBlock.COLORS) ==");
        for (int power : new int[]{0, 1, 5, 10, 15}) {
            BlockState st = reg.getOrMissing("minecraft:redstone_wire").defaultState;
            st = st.tryWith("power", String.valueOf(power))
                   .tryWith("north", "side").tryWith("south", "side")
                   .tryWith("east", "none").tryWith("west", "none");
            int c = BlockTints.redstoneColor(st);
            System.out.printf("  power=%2d -> #%06X  rgb(%3d,%3d,%3d)%n",
                power, c, (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
        }

        System.out.println();
        System.out.println("== per-face details ==");
        String[][] cases = {
            {"minecraft:redstone_wire", "power", "15", "north", "side", "south", "none", "east", "none", "west", "none"},
            {"minecraft:redstone_torch", "lit", "true"},
            {"minecraft:redstone_torch", "lit", "false"},
            {"minecraft:observer", "facing", "north", "powered", "false"},
            {"minecraft:grass_block", "snowy", "false"},
            {"minecraft:oak_leaves", "distance", "1", "persistent", "false", "waterlogged", "false"},
            {"minecraft:spruce_leaves", "distance", "1", "persistent", "false", "waterlogged", "false"},
            {"minecraft:water", "level", "0"},
        };
        for (String[] c : cases) {
            String id = c[0];
            BlockState st = reg.getOrMissing(id).defaultState;
            StringBuilder props = new StringBuilder();
            for (int i = 1; i + 1 < c.length; i += 2) {
                st = st.tryWith(c[i], c[i + 1]);
                props.append(c[i]).append('=').append(c[i + 1]).append(' ');
            }
            System.out.printf("%n-- %s [%s]%n", id, props.toString().trim());
            List<ModelBaker.Variant> vs = baker.selectVariants(id, st);
            for (ModelBaker.Variant v : vs) {
                ModelBaker.BakedModel m = baker.bake(v);
                int shown = 0;
                for (int face = 0; face < 6 && shown < 4; face++) {
                    for (Quad q : m.quadsForFace(face)) {
                        if (shown++ >= 4) break;
                        int tint = q.tintIndex >= 0
                            ? BlockTints.colorFor(st, q.spriteId, q.tintIndex) : -1;
                        System.out.printf("   %-34s tintIdx=%2d forceTrans=%s tint=%s%n",
                            q.spriteId, q.tintIndex, q.forceTranslucent,
                            tint < 0 ? "(none)" : String.format("#%06X", tint));
                    }
                }
            }
        }
        res.close();
    }
}
