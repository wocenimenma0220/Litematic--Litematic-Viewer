package io.github.litematicrender;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixture matching the user schematic's actual water serialization: no level property. */
public final class DiagGenWaterReal {
    public static void main(String[] args) throws Exception {
        Map<String, String> b = new LinkedHashMap<>();
        for (int x = -1; x <= 8; x++) for (int z = -1; z <= 6; z++) b.put(DiagGenStairs.Simple.key(x, -1, z), "minecraft:smooth_stone");
        for (int x = 0; x <= 5; x++) for (int z = 0; z <= 3; z++) {
            b.put(DiagGenStairs.Simple.key(x, 0, z), "minecraft:water[waterlogged=false]");
            b.put(DiagGenStairs.Simple.key(x, 1, z), "minecraft:white_stained_glass");
        }
        // One edge with a flow level like a real pool rim.
        b.put(DiagGenStairs.Simple.key(7, 0, 2), "minecraft:water[level=3,waterlogged=false]");
        b.put(DiagGenStairs.Simple.key(7, 0, 3), "minecraft:water[level=1,waterlogged=false]");
        var out = Path.of(args[0]);
        java.nio.file.Files.createDirectories(out);
        DiagGenStairs.Simple.write(out.resolve("waterreal.litematic"), b);
        System.out.println("wrote waterreal fixture");
    }
}