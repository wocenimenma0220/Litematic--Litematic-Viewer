package io.github.litematicrender;

import io.github.litematicrender.render.RenderTest;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;

import java.nio.file.Path;

/** Dumps palette entries that carry properties, to see whether NBT parsing keeps them. */
public final class DiagPalette {
    public static void main(String[] args) throws Exception {
        var schem = LitematicaSchematic.load(Path.of(args[0]));
        var reg = BlockRegistry.load(RenderTest.openResources(Path.of(args[1]), Path.of(args[1]).getParent().getParent()));
        schem.materialize(reg);
        int withProps = 0, without = 0;
        java.util.Map<String, String> samples = new java.util.TreeMap<>();
        for (var re : schem.regions().entrySet()) {
            for (var s : re.getValue().blocks.palette) {
                if (s == null || s.isAir()) continue;
                String ps = s.propertyString();
                if (ps.isBlank()) { without++; continue; }
                withProps++;
                samples.putIfAbsent(s.blockId, ps);
            }
        }
        System.out.println("palette entries WITH properties: " + withProps);
        System.out.println("palette entries WITHOUT properties: " + without);
        System.out.println();
        System.out.println("sample of blocks that DO carry properties:");
        samples.entrySet().stream().limit(20)
            .forEach(e -> System.out.printf("  %-46s %s%n", e.getKey(), e.getValue()));
        System.out.println();
        System.out.println("chest/facing present? " +
            samples.entrySet().stream().anyMatch(e -> e.getValue().contains("facing") || e.getValue().contains("type")));
    }
}