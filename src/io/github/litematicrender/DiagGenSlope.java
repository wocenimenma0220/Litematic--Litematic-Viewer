package io.github.litematicrender;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixture: a walled trench whose water levels step 7..1 in a straight line, plus a drop at the end.
 *
 * <p>Isolates the corner-height maths from every other feature: no waterlogged blocks, no
 * diagonals, no sources in the middle. Whatever the surface does here is the descent function.
 */
public final class DiagGenSlope {
    public static void main(String[] args) throws Exception {
        Map<String, String> b = new LinkedHashMap<>();
        for (int x = -1; x <= 3; x++) for (int z = -1; z <= 12; z++) b.put(DiagGenStairs.Simple.key(x, -1, z), "minecraft:smooth_stone");
        // Trench walls at x=-1 and x=3, floor at y=0 across x 0..2.
        for (int z = -1; z <= 12; z++) {
            b.put(DiagGenStairs.Simple.key(-1, 0, z), "minecraft:smooth_stone");
            b.put(DiagGenStairs.Simple.key(3, 0, z), "minecraft:smooth_stone");
        }
        // Levels 7,6,5,4,3,2,1 along z; level 7 is the fullest flowing block.
        int[] levels = {7, 6, 5, 4, 3, 2, 1};
        for (int z = 0; z < levels.length; z++) {
            b.put(DiagGenStairs.Simple.key(1, 0, z), "minecraft:water[level=" + levels[z] + ",falling=false]");
        }
        // A source feeding the top of the run, then a drop into a second trench.
        b.put(DiagGenStairs.Simple.key(1, 0, 8), "minecraft:water[level=0,falling=false]");   // keep the source: it is what a real run has
        for (int z = 9; z <= 11; z++) b.put(DiagGenStairs.Simple.key(1, 0, z), "minecraft:smooth_stone");
        var out = Path.of(args[0]);
        java.nio.file.Files.createDirectories(out);
        DiagGenStairs.Simple.write(out.resolve("slope.litematic"), b);
        System.out.println("wrote slope fixture");
    }
}