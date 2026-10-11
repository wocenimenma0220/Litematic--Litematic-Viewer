package io.github.litematicrender;

import io.github.litematicrender.nbt.NbtCompound;
import io.github.litematicrender.nbt.NbtIo;
import io.github.litematicrender.nbt.NbtList;
import io.github.litematicrender.nbt.io.NbtSizeTracker;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Dumps the raw palette entries named "chest" straight from the file, bypassing the loader.
 *
 * <p>Needed because the resolved palette showed empty properties for chests while other blocks kept
 * theirs. Reading the file directly settles whether the data was ever there.
 */
public final class DiagRawPalette {
    public static void main(String[] args) throws IOException {
        NbtCompound root;
        try (var in = new java.io.DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(args[0]))))) {
            root = NbtIo.readFile(in, NbtSizeTracker.FILE_MAX_BYTES);
        }
        NbtCompound regions = root.getCompound("Regions").orElse(null);
        if (regions == null) { System.out.println("no Regions"); return; }
        for (String rname : regions.getKeys()) {
            NbtCompound region = regions.getCompound(rname).orElse(null);
            if (region == null) continue;
            NbtList pal = region.getList("BlockStatePalette").orElse(null);
            if (pal == null) continue;
            System.out.println("region " + rname + ": palette " + pal.size());
            int shown = 0;
            for (NbtCompound entry : pal.getCompounds()) {
                String name = entry.getStringOrDefault("Name", "");
                if (!name.contains("chest")) continue;
                NbtCompound props = entry.getCompound("Properties").orElse(null);
                System.out.println("  " + name + "  props=" + (props == null ? "absent" : props.getKeys()));
                if (++shown > 8) break;
            }
        }
    }
}