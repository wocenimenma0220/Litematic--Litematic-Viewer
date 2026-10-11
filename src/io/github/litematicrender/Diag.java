package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.BlockState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Diagnostic: which blocks in a schematic actually produce geometry, and which silently drop.
 * Usage: Diag <file.litematic> <assets/vanilla>
 */
public final class Diag {
    public static void main(String[] args) throws Exception {
        Path schemFile = Path.of(args[0]);
        Path vanillaRoot = Path.of(args[1]);

        LitematicaSchematic schem = LitematicaSchematic.load(schemFile);
        ResourceManager resources = new ResourceManager(vanillaRoot);
        BlockRegistry registry = BlockRegistry.load(resources);
        schem.materialize(registry);

        Map<String, Integer> counts = new TreeMap<>();
        Map<String, BlockState> samples = new LinkedHashMap<>();
        int[] total = {0};
        int[] beCount = {0};
        List<String> regionLines = new ArrayList<>();

        for (Map.Entry<String, LitematicaSchematic.Region> e : schem.regions().entrySet()) {
            LitematicaSchematic.Region r = e.getValue();
            regionLines.add(String.format("  %-24s size=%dx%dx%d pos=(%d,%d,%d) palette=%d entities=%d",
                e.getKey(), r.blocks.sizeX, r.blocks.sizeY, r.blocks.sizeZ,
                r.position.x(), r.position.y(), r.position.z(),
                r.blocks.palette.length, r.blockEntities.size()));
            beCount[0] += r.blockEntities.size();
            r.blocks.forEachNonAir((x, y, z, state) -> {
                counts.merge(state.blockId, 1, Integer::sum);
                samples.putIfAbsent(state.blockId, state);
                total[0]++;
            });
        }

        System.out.println("== regions ==");
        regionLines.forEach(System.out::println);
        System.out.println("block entities present in file: " + beCount[0]);
        System.out.println("non-air blocks: " + total[0] + ", distinct block ids: " + counts.size());

        ModelBaker baker = new ModelBaker(resources, registry);

        int mpKinds = 0, mpBlocks = 0, nullKinds = 0, nullBlocks = 0;
        List<String> mpList = new ArrayList<>();
        List<String> nullList = new ArrayList<>();

        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String id = e.getKey();
            int n = e.getValue();
            BlockRegistry.BlockInfo info = registry.get(id);
            boolean multipart = info != null && info.definition != null && info.definition.get("multipart") != null;
            BlockState st = samples.get(id);
            ModelBaker.Variant v = st == null ? null : baker.selectVariant(id, st);
            if (multipart) {
                mpKinds++;
                mpBlocks += n;
                mpList.add(String.format("  %-46s x%-6d", id, n));
            }
            if (v == null) {
                nullKinds++;
                nullBlocks += n;
                nullList.add(String.format("  %-46s x%-6d multipart=%s", id, n, multipart));
            }
        }

        System.out.println();
        System.out.println("== MULTIPART blockstates (no model ever selected) ==");
        System.out.printf("kinds=%d, blocks=%d%n", mpKinds, mpBlocks);
        mpList.forEach(System.out::println);

        System.out.println();
        System.out.println("== BLOCK ENTITIES (TileEntities) by id ==");
        Map<String, Integer> beTypes = new TreeMap<>();
        Map<String, LitematicaSchematic.Region> beSampleRegion = new LinkedHashMap<>();
        for (Map.Entry<String, LitematicaSchematic.Region> e : schem.regions().entrySet()) {
            LitematicaSchematic.Region r = e.getValue();
            for (var te : r.blockEntities.values()) {
                String id = te.getStringOrDefault("id", "(no id)");
                beTypes.merge(id, 1, Integer::sum);
                beSampleRegion.putIfAbsent(id, r);
            }
        }
        beTypes.forEach((k, v) -> System.out.printf("  %-52s x%d%n", k, v));

        System.out.println();
        System.out.println("== sample block entity NBT (first per type, keys only) ==");
        Map<String, String> printed = new LinkedHashMap<>();
        for (Map.Entry<String, LitematicaSchematic.Region> e : schem.regions().entrySet()) {
            for (var te : e.getValue().blockEntities.values()) {
                String id = te.getStringOrDefault("id", "(no id)");
                if (printed.containsKey(id)) continue;
                printed.put(id, id);
                List<String> keys = new ArrayList<>();
                for (String k : te.getKeys()) {
                    String v = te.get(k).map(Object::toString).orElse("?");
                    if (v.length() > 60) v = v.substring(0, 60) + "...";
                    keys.add(k + "=" + v);
                }
                System.out.printf("  %s -> %s%n", id, String.join(", ", keys));
            }
        }

        System.out.println();
        System.out.println("== selectVariant() returned NULL (renders nothing) ==");
        System.out.printf("kinds=%d, blocks=%d  (%.2f%% of all blocks)%n",
            nullKinds, nullBlocks, total[0] == 0 ? 0.0 : (100.0 * nullBlocks / total[0]));
        nullList.forEach(System.out::println);

        resources.close();
    }
}
