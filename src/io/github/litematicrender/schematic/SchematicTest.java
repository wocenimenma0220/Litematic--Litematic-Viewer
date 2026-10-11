package io.github.litematicrender.schematic;

import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.BlockState;
import io.github.litematicrender.world.Vec3i;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** CLI smoke test: parse a .litematic and print metadata/regions/palette stats. */
public final class SchematicTest {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        long t0 = System.currentTimeMillis();
        LitematicaSchematic schem = LitematicaSchematic.load(file);
        long t1 = System.currentTimeMillis();
        System.out.println("loaded in " + (t1 - t0) + " ms");
        LitematicaSchematic.Metadata md = schem.metadata();
        System.out.println("name=" + md.name);
        System.out.println("author=" + md.author);
        System.out.println("description=" + md.description);
        System.out.println("version=" + schem.version + " mcDataVersion=" + schem.minecraftDataVersion);
        System.out.println("enclosingSize=" + md.enclosingSize.x() + "x" + md.enclosingSize.y() + "x" + md.enclosingSize.z());
        System.out.println("totalBlocks=" + md.totalBlocks + " totalVolume=" + md.totalVolume);
        System.out.println("regions=" + schem.regions().size());
        for (var e : schem.regions().entrySet()) {
            LitematicaSchematic.Region r = e.getValue();
            System.out.println("  region '" + e.getKey() + "' pos=" + r.position + " size=" + r.absSize()
                + " teCount=" + r.blockEntities.size());
        }

        // resolve against vanilla resources
        Path vanilla = Path.of(args[1]);
        ResourceManager resources = new ResourceManager(vanilla);
        BlockRegistry registry = BlockRegistry.load(resources);
        System.out.println("registry blocks=" + registry.blockCount());
        schem.materialize(registry);
        long t2 = System.currentTimeMillis();
        System.out.println("materialized in " + (t2 - t1) + " ms");

        for (var e : schem.regions().entrySet()) {
            LitematicaSchematic.Region r = e.getValue();
            Map<String, Integer> blockCounts = new TreeMap<>();
            final int[] nonAir = {0};
            long t3 = System.currentTimeMillis();
            r.blocks.forEachNonAir((x, y, z, state) -> {
                nonAir[0]++;
                blockCounts.merge(state.blockId, 1, Integer::sum);
            });
            long t4 = System.currentTimeMillis();
            System.out.println("region '" + e.getKey() + "': nonAir=" + nonAir[0] + " scan=" + (t4 - t3) + "ms distinct=" + blockCounts.size());
            blockCounts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12)
                .forEach(be -> System.out.println("    " + be.getKey() + " x" + be.getValue()));
            // palette sample
            for (int i = 0; i < Math.min(5, r.blocks.palette.length); i++) {
                System.out.println("    palette[" + i + "]=" + r.blocks.palette[i]);
            }
        }
        resources.close();
    }
}
