package io.github.litematicrender;

import io.github.litematicrender.schematic.LitematicaSchematic;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Lists the block entity types present in a schematic and whether their block still renders.
 *
 * <p>A block entity only needs special handling when its appearance comes from NBT rather than the
 * blockstate — banners, skulls and shulker boxes take their colour or variant from the entity data,
 * while chests, furnaces and hoppers are fully described by their blockstate model and already
 * render through the normal pipeline.
 */
public final class DiagBlockEntities {
    public static void main(String[] args) throws Exception {
        LitematicaSchematic schem = LitematicaSchematic.load(Path.of(args[0]));
        Map<String, Integer> counts = new TreeMap<>();
        int total = 0;
        for (var e : schem.regions().entrySet()) {
            for (var nbt : e.getValue().blockEntities.values()) {
                String id = String.valueOf(nbt.get("id"));
                counts.merge(id, 1, Integer::sum);
                total++;
            }
        }
        System.out.println("block entities: " + total + " across " + schem.regions().size() + " regions");
        counts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .forEach(en -> System.out.printf("  %-44s %5d%n", en.getKey(), en.getValue()));
    }
}
