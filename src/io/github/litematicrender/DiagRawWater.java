package io.github.litematicrender;

import io.github.litematicrender.nbt.NbtCompound;
import io.github.litematicrender.nbt.NbtIo;
import io.github.litematicrender.nbt.NbtList;
import io.github.litematicrender.nbt.io.NbtSizeTracker;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Dumps the raw NBT of every water palette entry, to see which properties the file really carries. */
public final class DiagRawWater {
    public static void main(String[] args) throws Exception {
        NbtCompound root;
        try (var in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(args[0]))))) {
            root = NbtIo.readFile(in, NbtSizeTracker.FILE_MAX_BYTES);
        }
        NbtCompound regions = root.getCompound("Regions").orElse(null);
        if (regions == null) { System.out.println("no Regions"); return; }
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (String rname : regions.getKeys()) {
            NbtCompound region = regions.getCompound(rname).orElse(null);
            if (region == null) continue;
            NbtList pal = region.getList("BlockStatePalette").orElse(null);
            if (pal == null) continue;
            for (NbtCompound entry : pal.getCompounds()) {
                String name = entry.getStringOrDefault("Name", "");
                if (!name.contains("water")) continue;
                NbtCompound props = entry.getCompound("Properties").orElse(null);
                StringBuilder sb = new StringBuilder(name);
                if (props != null) {
                    sb.append('[');
                    boolean first = true;
                    for (String k : props.getKeys()) {
                        if (!first) sb.append(',');
                        sb.append(k).append('=').append(props.getStringOrDefault(k, "?"));
                        first = false;
                    }
                    sb.append(']');
                } else {
                    sb.append("(no Properties)");
                }
                seen.merge(sb.toString(), 1, Integer::sum);
            }
        }
        System.out.println("raw water palette entries:");
        seen.forEach((k, v) -> System.out.printf("  %-60s x%d%n", k, v));
    }
}