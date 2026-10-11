package io.github.litematicrender;

import io.github.litematicrender.render.RenderTest;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Dumps every distinct water state in the schematic, to see whether level data exists at all. */
public final class DiagWaterLevels {
    public static void main(String[] args) throws Exception {
        var schem = LitematicaSchematic.load(Path.of(args[0]));
        var reg = BlockRegistry.load(RenderTest.openResources(Path.of(args[1]), Path.of(args[1]).getParent().getParent()));
        schem.materialize(reg);
        Map<String, Integer> byState = new TreeMap<>();
        schem.regions().values().forEach(r ->
            r.blocks.forEachNonAir((x, y, z, s) -> {
                if (s.blockId.contains("water")) byState.merge(s.propertyString(), 1, Integer::sum);
            }));
        System.out.println("water states in schematic:");
        byState.forEach((k, v) -> System.out.printf("  %-56s %6d%n", k, v));
    }
}