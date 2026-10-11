package io.github.litematicrender;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixture: player heads at every second rotation step, so the facing can be read from above. */
public final class DiagGenHeads {
    public static void main(String[] args) throws Exception {
        Map<String, String> b = new LinkedHashMap<>();
        for (int x = -1; x <= 8; x++) for (int z = -1; z <= 5; z++) b.put(DiagGenStairs.Simple.key(x, -1, z), "minecraft:smooth_stone");
        int i = 0;
        for (int rot = 0; rot < 16; rot += 2) {
            b.put(DiagGenStairs.Simple.key((i % 4) * 2, 0, (i / 4) * 2 + 1),
                "minecraft:skeleton_skull[rotation=" + rot + "]");
            i++;
        }
        var out = Path.of(args[0]);
        java.nio.file.Files.createDirectories(out);
        DiagGenStairs.Simple.write(out.resolve("heads.litematic"), b);
        System.out.println("wrote heads fixture");
    }
}