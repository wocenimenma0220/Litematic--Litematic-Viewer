package io.github.litematicrender;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixture: a deep pool under glass, to reproduce the doubled water surface report. */
public final class DiagGenWaterDepth {
    public static void main(String[] args) throws Exception {
        Map<String, String> b = new LinkedHashMap<>();
        for (int x = -1; x <= 8; x++) for (int z = -1; z <= 6; z++) b.put(DiagGenStairs.Simple.key(x, -1, z), "minecraft:smooth_stone");
        // Deep water: three layers of source, then glass above, like the screenshot.
        for (int x = 0; x <= 5; x++) for (int z = 0; z <= 3; z++) {
            b.put(DiagGenStairs.Simple.key(x, 0, z), "minecraft:water[level=0]");
            b.put(DiagGenStairs.Simple.key(x, 1, z), "minecraft:water[level=0]");
            b.put(DiagGenStairs.Simple.key(x, 2, z), "minecraft:water[level=0]");
            b.put(DiagGenStairs.Simple.key(x, 3, z), "minecraft:white_stained_glass");
        }
        var out = Path.of(args[0]);
        java.nio.file.Files.createDirectories(out);
        DiagGenStairs.Simple.write(out.resolve("waterdepth.litematic"), b);
        System.out.println("wrote waterdepth fixture");
    }
}