package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.model.Quad;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.BlockState;

import java.nio.file.Path;
import java.util.List;

/**
 * Prints the UV corners of a block's faces so texture orientation can be checked numerically.
 *
 * <p>Observer is the canonical test: its model writes the top face as {@code uv:[0,16,16,0]}, a
 * flipped V range, and its north/south faces use different textures, so a wrong corner mapping
 * shows up immediately as swapped or mirrored UVs.
 */
public final class DiagUv {
    public static void main(String[] args) throws Exception {
        ResourceManager res = new ResourceManager(Path.of(args[0]));
        BlockRegistry reg = BlockRegistry.load(res);
        ModelBaker baker = new ModelBaker(res, reg);

        String id = args.length > 1 ? args[1] : "minecraft:observer";
        String[] props = args.length > 2 ? java.util.Arrays.copyOfRange(args, 2, args.length) : new String[0];

        BlockState st = reg.getOrMissing(id).defaultState;
        for (int i = 0; i + 1 < props.length; i += 2) st = st.tryWith(props[i], props[i + 1]);

        System.out.println("block: " + id + " " + st.propertyString());
        List<ModelBaker.Variant> vs = baker.selectVariants(id, st);
        for (ModelBaker.Variant v : vs) {
            ModelBaker.BakedModel m = baker.bake(v);
            System.out.println("model: " + v.modelId() + (v.yRot() != 0 ? "  y=" + v.yRot() : ""));
            for (int face = 0; face < 6; face++) {
                for (Quad q : m.quadsForFace(face)) {
                    System.out.printf("  %-6s sprite=%-34s uv=(%.3f,%.3f) (%.3f,%.3f) (%.3f,%.3f) (%.3f,%.3f)%n",
                        faceName(face), q.spriteId,
                        q.u0, q.v0, q.u1, q.v1, q.u2, q.v2, q.u3, q.v3);
                }
            }
            // Also show unculled quads: torches, rails and redstone dust put everything there.
            for (Quad q : m.unculled) {
                System.out.printf("  %-6s sprite=%-34s uv=(%.3f,%.3f) (%.3f,%.3f) (%.3f,%.3f) (%.3f,%.3f)%n",
                    "flat", q.spriteId, q.u0, q.v0, q.u1, q.v1, q.u2, q.v2, q.u3, q.v3);
                System.out.printf("  %-6s pos=(%.2f,%.2f,%.2f) (%.2f,%.2f,%.2f) (%.2f,%.2f,%.2f) (%.2f,%.2f,%.2f)%n",
                    "flat", q.x0, q.y0, q.z0, q.x1, q.y1, q.z1, q.x2, q.y2, q.z2, q.x3, q.y3, q.z3);
            }
        }
        res.close();
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
