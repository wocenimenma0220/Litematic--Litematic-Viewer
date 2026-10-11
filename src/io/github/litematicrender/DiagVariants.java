package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.BlockState;

import java.nio.file.Path;
import java.util.List;

/**
 * Shows which model each blockstate resolves to, for states whose variant selection depends on
 * properties (comparator powered, repeater locked, redstone torch lit, observer facing).
 *
 * <p>A wrong answer here is visible as the lit texture on an unpowered block (or vice versa).
 */
public final class DiagVariants {
    public static void main(String[] args) throws Exception {
        ResourceManager res = new ResourceManager(Path.of(args[0]));
        BlockRegistry reg = BlockRegistry.load(res);
        ModelBaker baker = new ModelBaker(res, reg);

        String[][] cases = {
            {"minecraft:comparator", "facing", "north", "mode", "compare", "powered", "false"},
            {"minecraft:comparator", "facing", "north", "mode", "compare", "powered", "true"},
            {"minecraft:comparator", "facing", "north", "mode", "subtract", "powered", "true"},
            {"minecraft:repeater", "facing", "north", "delay", "1", "locked", "false", "powered", "false"},
            {"minecraft:repeater", "facing", "north", "delay", "1", "locked", "false", "powered", "true"},
            {"minecraft:redstone_torch", "lit", "false"},
            {"minecraft:redstone_torch", "lit", "true"},
            {"minecraft:redstone_wall_torch", "facing", "north", "lit", "true"},
            {"minecraft:observer", "facing", "north", "powered", "false"},
            {"minecraft:observer", "facing", "north", "powered", "true"},
            {"minecraft:redstone_wire", "north", "side", "south", "side", "east", "none", "west", "none", "power", "0"},
            {"minecraft:redstone_wire", "north", "none", "south", "none", "east", "none", "west", "none", "power", "0"},
            {"minecraft:redstone_lamp", "lit", "false"},
            {"minecraft:redstone_lamp", "lit", "true"},
        };

        for (String[] c : cases) {
            String id = c[0];
            BlockState st = reg.getOrMissing(id).defaultState;
            StringBuilder props = new StringBuilder();
            for (int i = 1; i + 1 < c.length; i += 2) {
                st = st.tryWith(c[i], c[i + 1]);
                props.append(c[i]).append('=').append(c[i + 1]).append(' ');
            }
            List<ModelBaker.Variant> vs = baker.selectVariants(id, st);
            StringBuilder models = new StringBuilder();
            for (ModelBaker.Variant v : vs) {
                if (models.length() > 0) models.append(", ");
                models.append(v.modelId());
                if (v.yRot() != 0) models.append(" y=").append(v.yRot());
            }
            System.out.printf("%-28s %-46s -> %s%n", id, props.toString().trim(),
                models.length() == 0 ? "(NONE)" : models);
        }
        res.close();
    }
}
