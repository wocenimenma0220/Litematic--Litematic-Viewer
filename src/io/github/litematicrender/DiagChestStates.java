package io.github.litematicrender;

import io.github.litematicrender.render.RenderTest;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reports the chest blockstates actually present in a schematic.
 *
 * <p>A double chest is two blocks joined by the {@code type} property (left/right). If the palette
 * carries no such property, every chest is a single block to the renderer and the halves draw
 * full-width lids over the same space — the doubled-texture symptom. This check exists because the
 * renderer can only be as correct as the data it is given.
 */
public final class DiagChestStates {
    public static void main(String[] args) throws Exception {
        LitematicaSchematic schem = LitematicaSchematic.load(Path.of(args[0]));
        // materialize() resolves each region's palette into blockstates; before it runs the block
        // containers are empty, so this must come first.
        BlockRegistry reg = BlockRegistry.load(RenderTest.openResources(Path.of(args[1]), Path.of(args[1]).getParent().getParent()));
        schem.materialize(reg);

        Map<String, Integer> byState = new TreeMap<>();
        for (var regionEntry : schem.regions().entrySet()) {
            var region = regionEntry.getValue();
            region.blocks.forEachNonAir((x, y, z, state) -> {
                if (state.blockId.contains("chest")) {
                    byState.merge(state.blockId + " <<< " + state.propertyString(), 1, Integer::sum);
                }
            });
        }
        System.out.println("chest states found:");
        byState.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(20)
            .forEach(e -> System.out.printf("  %-70s %5d%n", e.getKey(), e.getValue()));
    }
}