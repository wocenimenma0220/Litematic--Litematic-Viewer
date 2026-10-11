package io.github.litematicrender;

import io.github.litematicrender.render.RenderTest;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;

import java.nio.file.Path;

/** Dumps water blocks and their neighbours from the user's schematic, top water layer first. */
public final class DiagWaterScene {
    public static void main(String[] args) throws Exception {
        var schem = LitematicaSchematic.load(Path.of(args[0]));
        var reg = BlockRegistry.load(RenderTest.openResources(Path.of(args[1]), Path.of(args[1]).getParent().getParent()));
        schem.materialize(reg);
        int shown = 0;
        for (var re : schem.regions().entrySet()) {
            var region = re.getValue();
            region.blocks.forEachNonAir((x, y, z, s) -> {
                if (!s.blockId.contains("water") || shown >= 40) return;
                // Only report water whose above-neighbour is NOT water (surface blocks).
                var above = (y + 1 < region.blocks.sizeY) ? region.blocks.get(x, y + 1, z) : null;
                String aboveId = above == null ? "air" : above.blockId;
                if (aboveId.contains("water")) return;
                System.out.printf("  (%d,%d,%d) %s%s  above=%s%n",
                    x, y, z, s.blockId, s.propertyString(), aboveId + (above != null ? above.propertyString() : ""));
            });
        }
    }
}