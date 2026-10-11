package io.github.litematicrender;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixture: levers on the floor and walls, powered and unpowered. */
public final class DiagGenLevers {
    public static void main(String[] args) throws Exception {
        Map<String, String> b = new LinkedHashMap<>();
        for (int x = -1; x <= 5; x++) for (int z = -1; z <= 4; z++) b.put(S.key(x, -1, z), "minecraft:smooth_stone");
        b.put(S.key(0, 0, 0), "minecraft:lever[face=floor,facing=north,powered=false]");
        b.put(S.key(2, 0, 0), "minecraft:lever[face=floor,facing=east,powered=true]");
        b.put(S.key(4, 0, 0), "minecraft:lever[face=ceiling,facing=north,powered=false]");
        // A wall to hang wall levers on.
        for (int z = 2; z <= 3; z++) b.put(S.key(2, 0, z), "minecraft:smooth_stone");
        b.put(S.key(2, 1, 2), "minecraft:lever[face=wall,facing=north,powered=false]");
        b.put(S.key(2, 1, 3), "minecraft:lever[face=wall,facing=south,powered=true]");
        var out = Path.of(args[0]);
        java.nio.file.Files.createDirectories(out);
        DiagGenStairs.Simple.write(out.resolve("levers.litematic"), b);
        System.out.println("wrote levers fixture");
    }

    private static final class S {
        static String key(int x, int y, int z) { return x + "," + y + "," + z; }
    }
}