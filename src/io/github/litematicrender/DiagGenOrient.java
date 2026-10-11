package io.github.litematicrender;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixture: sideways barrels, top-half stairs and wall levers, the combined-rotation cases. */
public final class DiagGenOrient {
    public static void main(String[] args) throws Exception {
        Map<String, String> b = new LinkedHashMap<>();
        for (int x = -1; x <= 10; x++) for (int z = -1; z <= 6; z++) b.put(DiagGenStairs.Simple.key(x, -1, z), "minecraft:smooth_stone");
        // Sideways barrels: opening must point at the named direction.
        b.put(DiagGenStairs.Simple.key(0, 0, 0), "minecraft:barrel[facing=north,open=false]");
        b.put(DiagGenStairs.Simple.key(2, 0, 0), "minecraft:barrel[facing=east,open=false]");
        b.put(DiagGenStairs.Simple.key(4, 0, 0), "minecraft:barrel[facing=south,open=false]");
        b.put(DiagGenStairs.Simple.key(6, 0, 0), "minecraft:barrel[facing=west,open=false]");
        // Top-half stairs: tall half behind, step front toward facing.
        b.put(DiagGenStairs.Simple.key(0, 0, 2), "minecraft:oak_stairs[facing=north,half=top,shape=straight]");
        b.put(DiagGenStairs.Simple.key(2, 0, 2), "minecraft:oak_stairs[facing=east,half=top,shape=straight]");
        b.put(DiagGenStairs.Simple.key(4, 0, 2), "minecraft:oak_stairs[facing=south,half=top,shape=straight]");
        b.put(DiagGenStairs.Simple.key(6, 0, 2), "minecraft:oak_stairs[facing=west,half=top,shape=straight]");
        // Ceiling levers (x=180 + y).
        for (int x = 0; x <= 6; x += 2) {
            b.put(DiagGenStairs.Simple.key(x, 1, 4), "minecraft:smooth_stone");
        }
        b.put(DiagGenStairs.Simple.key(2, 0, 4), "minecraft:lever[face=ceiling,facing=south,powered=false]");
        b.put(DiagGenStairs.Simple.key(4, 0, 4), "minecraft:lever[face=ceiling,facing=east,powered=false]");
        var out = Path.of(args[0]);
        java.nio.file.Files.createDirectories(out);
        DiagGenStairs.Simple.write(out.resolve("orient.litematic"), b);
        System.out.println("wrote orient fixture");
    }
}