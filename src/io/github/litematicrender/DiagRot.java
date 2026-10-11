package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;

/** Prints where each world axis lands under the Transform's quarter-turns. */
public final class DiagRot {
    private static String name(double[] d) {
        if (d[1] > 0) return "up";
        if (d[1] < 0) return "down";
        if (d[0] > 0) return "east";
        if (d[0] < 0) return "west";
        if (d[2] > 0) return "south";
        return "north";
    }

    public static void main(String[] args) {
        double[][] dirs = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, -1},
        };
        for (int deg : new int[]{0, 90, 180, 270}) {
            var t = ModelBaker.Transform.of(0, deg, 0);
            StringBuilder sb = new StringBuilder();
            for (double[] d : dirs) {
                double[] r = t.transformPoint(d[0], d[1], d[2]);
                double[] snapped = {Math.round(r[0]), Math.round(r[1]), Math.round(r[2])};
                sb.append(name(d)).append("->").append(name(snapped)).append("  ");
            }
            System.out.printf("y=%-4d %s%n", deg, sb);
        }
    }
}