package io.github.litematicrender;

import io.github.litematicrender.render.RenderTest;
import io.github.litematicrender.world.BlockRegistry;

import java.nio.file.Path;

/** Prints the property table the registry derived for leaves and stairs. */
public final class DiagLeaves {
    public static void main(String[] args) throws Exception {
        var reg = BlockRegistry.load(RenderTest.openResources(Path.of(args[0]), Path.of(args[0]).getParent().getParent()));
        for (String id : new String[]{"minecraft:oak_leaves", "minecraft:oak_stairs",
                "minecraft:oak_fence", "minecraft:oak_slab", "minecraft:chest"}) {
            var info = reg.getOrMissing(id);
            var names = new StringBuilder();
            info.properties.forEach((k, v) -> names.append(k).append(' '));
            System.out.printf("  %-26s %s%n", id, names.toString().trim());
        }
    }
}