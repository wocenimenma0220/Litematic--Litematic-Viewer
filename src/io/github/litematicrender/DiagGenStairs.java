package io.github.litematicrender;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixture: four bottom slabs of stair, each facing a different way, viewed from above. */
public final class DiagGenStairs {
    public static void main(String[] args) throws Exception {
        Map<String, String> b = new LinkedHashMap<>();
        for (int x = -1; x <= 8; x++) for (int z = -1; z <= 4; z++) b.put(Simple.key(x, -1, z), "minecraft:smooth_stone");
        b.put(Simple.key(0, 0, 0), "minecraft:oak_stairs[facing=north,half=bottom,shape=straight]");
        b.put(Simple.key(2, 0, 0), "minecraft:oak_stairs[facing=south,half=bottom,shape=straight]");
        b.put(Simple.key(4, 0, 0), "minecraft:oak_stairs[facing=east,half=bottom,shape=straight]");
        b.put(Simple.key(6, 0, 0), "minecraft:oak_stairs[facing=west,half=bottom,shape=straight]");
        // Barrels pointing four ways as well.
        b.put(Simple.key(0, 0, 2), "minecraft:barrel[facing=north,open=false]");
        b.put(Simple.key(2, 0, 2), "minecraft:barrel[facing=south,open=false]");
        b.put(Simple.key(4, 0, 2), "minecraft:barrel[facing=east,open=false]");
        b.put(Simple.key(6, 0, 2), "minecraft:barrel[facing=west,open=false]");
        var out = Path.of(args[0]);
        java.nio.file.Files.createDirectories(out);
        Simple.write(out.resolve("stairs.litematic"), b);
        System.out.println("wrote " + out.resolve("stairs.litematic"));
    }

    /** Access to the private helpers of DiagGenSchematic without duplicating the writer. */
    public static final class Simple {
        public static String key(int x, int y, int z) { return x + "," + y + "," + z; }
        public static void write(Path p, Map<String, String> blocks) {
            try {
                var m = DiagGenSchematic.class.getDeclaredMethod("write", Path.class, Map.class);
                m.setAccessible(true);
                m.invoke(null, p, blocks);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}