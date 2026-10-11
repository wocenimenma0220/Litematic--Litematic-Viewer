package io.github.litematicrender;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixture: single and double chests in every facing, to check orientation and the double seam. */
public final class DiagGenChests {
    public static void main(String[] args) throws Exception {
        Map<String, String> b = new LinkedHashMap<>();
        for (int x = -1; x <= 8; x++) for (int z = -1; z <= 5; z++) b.put(DiagGenStairs.Simple.key(x, -1, z), "minecraft:smooth_stone");
        // Singles, one per facing.
        b.put(DiagGenStairs.Simple.key(0, 0, 0), "minecraft:chest[facing=north,type=single,waterlogged=false]");
        b.put(DiagGenStairs.Simple.key(2, 0, 0), "minecraft:chest[facing=east,type=single,waterlogged=false]");
        b.put(DiagGenStairs.Simple.key(4, 0, 0), "minecraft:chest[facing=south,type=single,waterlogged=false]");
        b.put(DiagGenStairs.Simple.key(6, 0, 0), "minecraft:chest[facing=west,type=single,waterlogged=false]");
        // Doubles along x: left then right, facing north.
        b.put(DiagGenStairs.Simple.key(0, 0, 2), "minecraft:chest[facing=north,type=left,waterlogged=false]");
        b.put(DiagGenStairs.Simple.key(1, 0, 2), "minecraft:chest[facing=north,type=right,waterlogged=false]");
        // Doubles along z: right then left (east-facing pair).
        b.put(DiagGenStairs.Simple.key(4, 0, 2), "minecraft:chest[facing=east,type=right,waterlogged=false]");
        b.put(DiagGenStairs.Simple.key(4, 0, 3), "minecraft:chest[facing=east,type=left,waterlogged=false]");
        var out = Path.of(args[0]);
        java.nio.file.Files.createDirectories(out);
        DiagGenStairs.Simple.write(out.resolve("chests.litematic"), b);
        System.out.println("wrote chests fixture");
    }
}