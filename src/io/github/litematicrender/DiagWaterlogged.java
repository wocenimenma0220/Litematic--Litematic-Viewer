package io.github.litematicrender;

import io.github.litematicrender.render.RenderTest;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Counts waterlogged blocks by type, to see whether the schematic actually contains any. */
public final class DiagWaterlogged {
    public static void main(String[] args) throws Exception {
        var schem = LitematicaSchematic.load(Path.of(args[0]));
        var reg = BlockRegistry.load(RenderTest.openResources(Path.of(args[1]), Path.of(args[1]).getParent().getParent()));
        schem.materialize(reg);
        Map<String, Integer> byBlock = new TreeMap<>();
        int waterlogged = 0;
        for (var re : schem.regions().entrySet()) {
            re.getValue().blocks.forEachNonAir((x, y, z, s) -> {
                String v = s.getValue("waterlogged");
                if (v != null && Boolean.parseBoolean(v)) {
                    byBlock.merge(s.blockId, 1, Integer::sum);
                }
            });
        }
        System.out.println("waterlogged blocks: " + byBlock.values().stream().mapToInt(Integer::intValue).sum());
        byBlock.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(15)
            .forEach(e -> System.out.printf("  %-44s %5d%n", e.getKey(), e.getValue()));
    }
}