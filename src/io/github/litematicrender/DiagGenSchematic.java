package io.github.litematicrender;

import io.github.litematicrender.nbt.NbtCompound;
import io.github.litematicrender.nbt.NbtList;
import io.github.litematicrender.nbt.NbtScalars;
import io.github.litematicrender.nbt.io.NbtFileUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes synthetic {@code .litematic} files used to verify the renderer.
 *
 * <p>Hand-authored schematics are the only way to test geometry, lighting and layer order in
 * isolation: a real build mixes hundreds of blocks, so a single wrong face is impossible to spot.
 * Each fixture below isolates one question a viewer would ask.
 *
 * <pre>
 *   DiagGenSchematic &lt;outDir&gt;
 * </pre>
 */
public final class DiagGenSchematic {
    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "schematics/test");
        Files.createDirectories(outDir);

        write(outDir.resolve("axes.litematic"), axes());
        write(outDir.resolve("faces.litematic"), faces());
        write(outDir.resolve("water.litematic"), water());
        write(outDir.resolve("glass.litematic"), glass());
        write(outDir.resolve("slabs.litematic"), slabs());
        write(outDir.resolve("occlusion.litematic"), occlusion());
        write(outDir.resolve("shading.litematic"), shading());
        write(outDir.resolve("redstone.litematic"), redstone());
        write(outDir.resolve("connections.litematic"), connections());
        write(outDir.resolve("waterlogged.litematic"), waterlogged());
        System.out.println("wrote fixtures to " + outDir.toAbsolutePath());
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Waterlogged blocks and fluids at every level.
     *
     * <p>This fixture exists because a waterlogged block holds its fluid in a property rather than
     * being a fluid block itself, which is a separate code path from the plain water here. Leaves,
     * stairs and a fence each take the waterlogged variant so the water that should appear inside
     * them can be checked against the real game.
     */
    private static Map<String, String> waterlogged() {
        Map<String, String> b = new LinkedHashMap<>();
        for (int x = -1; x <= 10; x++) for (int z = -1; z <= 6; z++) b.put(key(x, -1, z), "minecraft:smooth_stone");
        // A source row so neighbours have something to connect to.
        for (int x = 0; x <= 3; x++) b.put(key(x, 0, 0), "minecraft:water[level=0]");
        // Waterlogged variants: leaves, oak stairs, an oak fence and oak slabs.
        b.put(key(0, 0, 2), "minecraft:oak_leaves[waterlogged=true,distance=1,persistent=true]");
        b.put(key(1, 0, 2), "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=true]");
        b.put(key(2, 0, 2), "minecraft:oak_fence[north=true,south=true,waterlogged=true]");
        b.put(key(3, 0, 2), "minecraft:oak_slab[type=bottom,waterlogged=true]");
        // A flow column for the flowing-sprite path.
        b.put(key(6, 0, 0), "minecraft:water[level=8,falling=true]");
        for (int y = 0; y <= 3; y++) b.put(key(6, y, 0), "minecraft:water[level=8,falling=true]");
        b.put(key(6, 4, 0), "minecraft:water[level=0]");
        return b;
    }

    /**
     * Three 1x1x8 bars along +X (red wool), +Y (lime wool) and +Z (blue wool) from a common corner,
     * plus a white cube at the origin. This makes the camera basis directly readable: which bar
     * points up, which goes right, which comes toward the viewer.
     */
    private static Map<String, String> axes() {
        Map<String, String> b = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {
            b.put(key(0, i, 0), "minecraft:lime_wool");     // +Y  -> must appear UP
            b.put(key(i + 1, 0, 0), "minecraft:red_wool");  // +X  -> must appear to the right/down-right
            b.put(key(0, 0, i + 1), "minecraft:blue_wool"); // +Z  -> must appear down-left
        }
        b.put(key(0, 0, 0), "minecraft:white_concrete");
        return b;
    }

    /**
     * One block of each shape side by side: full cube, slab, stairs, fence, pane, carpet, snow layer.
     * Reveals wrong face culling and wrong AO per shape.
     */
    private static Map<String, String> faces() {
        Map<String, String> b = new LinkedHashMap<>();
        String[] row = {
            "minecraft:stone",
            "minecraft:oak_slab[type=bottom]",
            "minecraft:oak_stairs[facing=east,half=bottom,shape=straight]",
            "minecraft:oak_fence",
            "minecraft:glass_pane",
            "minecraft:white_carpet",
            "minecraft:snow[layers=4]",
            "minecraft:oak_trapdoor[facing=north,half=bottom,open=false]",
        };
        for (int i = 0; i < row.length; i++) b.put(key(i * 2, 0, 0), row[i]);
        // a stone floor so the shapes sit on something and AO has neighbours
        for (int i = -1; i <= row.length * 2; i++) b.put(key(i, -1, 0), "minecraft:stone");
        for (int i = -1; i <= row.length * 2; i++) b.put(key(i, -1, 1), "minecraft:stone");
        return b;
    }

    /**
     * Water in every configuration that stresses {@code FluidRenderer}: a still 3x3 pool, a
     * staircase of levels 8..1 (each level's own height must show), a waterfall dropping into a
     * pool, and a channel whose flow has a clear direction.
     */
    private static Map<String, String> water() {
        Map<String, String> b = new LinkedHashMap<>();
        // still pool (all sources)
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) b.put(key(x, 0, z), "minecraft:water[level=0]");
        // staircase: each step one level lower, so heights 8/9 .. 1/9 must be visible
        for (int i = 0; i < 8; i++) {
            b.put(key(5 + i, 0, 0), "minecraft:water[level=" + (8 - i) + "]");
        }
        // waterfall: source at height 3 falling into a pool
        b.put(key(0, 3, 5), "minecraft:water[level=0]");
        b.put(key(0, 2, 5), "minecraft:water[level=8]");
        b.put(key(0, 1, 5), "minecraft:water[level=8]");
        for (int x = -1; x <= 1; x++) for (int z = 4; z <= 6; z++) b.put(key(x, 0, z), "minecraft:water[level=0]");
        // a walled channel so the flow direction is unambiguous
        for (int z = 0; z < 6; z++) {
            b.put(key(10, 0, z), "minecraft:water[level=" + Math.min(7, z) + "]");
            b.put(key(9, 0, z), "minecraft:stone");
            b.put(key(11, 0, z), "minecraft:stone");
        }
        // floor under everything fluid
        for (int x = -2; x <= 13; x++) for (int z = -1; z <= 8; z++) b.putIfAbsent(key(x, -1, z), "minecraft:stone");
        return b;
    }

    /**
     * Layer-order fixtures: opaque blocks behind translucent ones, a translucent block behind
     * another translucent block, and a translucent block in open air. Anything drawn with the
     * wrong depth rule shows up here as glass covering the whole model or vanishing entirely.
     */
    private static Map<String, String> glass() {
        Map<String, String> b = new LinkedHashMap<>();
        // stone wall with a glass sheet in front of it (front = +Z side)
        for (int x = 0; x < 5; x++) {
            b.put(key(x, 0, 0), "minecraft:stone");
            b.put(key(x, 1, 0), "minecraft:stone");
            b.put(key(x, 2, 0), "minecraft:stone");
        }
        for (int x = 0; x < 5; x++) for (int y = 0; y < 3; y++) b.put(key(x, y, 2), "minecraft:glass");
        // two glass panes one block apart, with a red block visible between them
        b.put(key(7, 0, 0), "minecraft:glass");
        b.put(key(7, 0, 1), "minecraft:red_concrete");
        b.put(key(7, 0, 2), "minecraft:glass");
        // stained glass (has real partial alpha) over stone
        b.put(key(9, 0, 0), "minecraft:stone");
        b.put(key(9, 0, 1), "minecraft:blue_stained_glass");
        // water against glass
        b.put(key(11, 0, 0), "minecraft:water[level=0]");
        b.put(key(11, 0, 1), "minecraft:glass");
        for (int x = -1; x <= 12; x++) for (int z = -1; z <= 3; z++) b.putIfAbsent(key(x, -1, z), "minecraft:stone");
        return b;
    }

    /** Slab halves and doubles: per-state shape classification must tell them apart. */
    private static Map<String, String> slabs() {
        Map<String, String> b = new LinkedHashMap<>();
        b.put(key(0, 0, 0), "minecraft:oak_slab[type=bottom]");
        b.put(key(2, 0, 0), "minecraft:oak_slab[type=top]");
        b.put(key(4, 0, 0), "minecraft:oak_slab[type=double]");
        b.put(key(6, 0, 0), "minecraft:smooth_stone_slab[type=bottom]");
        b.put(key(8, 0, 0), "minecraft:smooth_stone_slab[type=double]");
        // stacked: double slab with a stone block on top exercises occlusion both ways
        b.put(key(10, 0, 0), "minecraft:oak_slab[type=double]");
        b.put(key(10, 1, 0), "minecraft:stone");
        for (int x = -1; x <= 11; x++) for (int z = -1; z <= 1; z++) b.putIfAbsent(key(x, -1, z), "minecraft:dirt");
        return b;
    }

    /**
     * The shapes that used to punch holes: a chest/hopper/comparator row whose faces must be
     * drawn, against a stone slab row whose hidden faces must not be.
     */
    private static Map<String, String> occlusion() {
        Map<String, String> b = new LinkedHashMap<>();
        String[] special = {
            "minecraft:chest[facing=north,type=single,waterlogged=false]",
            "minecraft:hopper[facing=down,enabled=true]",
            "minecraft:comparator[facing=north,mode=compare,powered=false]",
            "minecraft:soul_sand",
            "minecraft:note_block[instrument=harp,note=0,powered=false]",
            "minecraft:observer[facing=north,powered=false]",
        };
        // solid platform under and behind them
        for (int x = -1; x <= 12; x++) for (int z = -1; z <= 2; z++) b.put(key(x, -1, z), "minecraft:stone");
        for (int i = 0; i < special.length; i++) b.put(key(i * 2, 0, 0), special[i]);
        // controls: plain stone and a 2x2x2 stone cube (all internal faces must be culled)
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            b.put(key(x + 0, y, z + 5), "minecraft:stone");
        }
        return b;
    }

    /**
     * A flat stone floor with a single pillar, viewed from above: makes ambient occlusion and the
     * cardinal (per-face) shading directly comparable against the game.
     */
    private static Map<String, String> shading() {
        Map<String, String> b = new LinkedHashMap<>();
        for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) b.put(key(x, 0, z), "minecraft:white_concrete");
        for (int y = 1; y <= 3; y++) b.put(key(3, y, 3), "minecraft:white_concrete");
        // a corner and an edge, where AO must darken
        for (int x = 0; x < 3; x++) for (int y = 1; y <= 3; y++) for (int z = 0; z < 3; z++) {
            if (x == 2 && z == 2) continue;
            b.put(key(x, y, z), "minecraft:white_concrete");
        }
        return b;
    }

    /**
     * Redstone across its powered states, on a quartz slab so the flat parts stay visible.
     *
     * <p>The point of the power ramp is that dust colour is a property-driven tint, not a texture:
     * a wire at power 0 must look almost black-red and one at power 15 bright red. Everything else
     * exercises the lit/unlit model switch (comparator, repeater, torch, lamp, observer).
     */
    private static Map<String, String> redstone() {
        Map<String, String> b = new LinkedHashMap<>();
        for (int x = -1; x <= 20; x++) for (int z = -1; z <= 6; z++) b.put(key(x, -1, z), "minecraft:smooth_stone");
        // power ramp: each wire isolated so its own level is visible
        for (int i = 0; i <= 15; i++) {
            b.put(key(i * 2, 0, 0), "minecraft:redstone_wire[power=" + i
                + ",north=none,south=none,east=none,west=none]");
        }
        // connected runs
        for (int z = 1; z <= 3; z++) {
            b.put(key(0, 0, z + 0), "minecraft:redstone_wire[power=15,north=side,south=side,east=none,west=none]");
        }
        // torch lit vs off, wall and standing
        b.put(key(2, 0, 4), "minecraft:redstone_torch[lit=true]");
        b.put(key(4, 0, 4), "minecraft:redstone_torch[lit=false]");
        b.put(key(6, 1, 4), "minecraft:redstone_wall_torch[lit=true,facing=north]");
        b.put(key(6, 0, 4), "minecraft:stone");
        // repeater / comparator / observer in both powered states
        b.put(key(8, 0, 4), "minecraft:repeater[facing=north,delay=1,locked=false,powered=false]");
        b.put(key(10, 0, 4), "minecraft:repeater[facing=north,delay=1,locked=false,powered=true]");
        b.put(key(12, 0, 4), "minecraft:comparator[facing=north,mode=compare,powered=false]");
        b.put(key(14, 0, 4), "minecraft:comparator[facing=north,mode=compare,powered=true]");
        b.put(key(16, 0, 4), "minecraft:observer[facing=north,powered=false]");
        b.put(key(18, 0, 4), "minecraft:observer[facing=north,powered=true]");
        // lamp, plus a lever and a button for the small-shape path
        b.put(key(0, 0, 5), "minecraft:redstone_lamp[lit=false]");
        b.put(key(2, 0, 5), "minecraft:redstone_lamp[lit=true]");
        b.put(key(4, 0, 5), "minecraft:lever[face=floor,facing=north,enabled=false]");
        b.put(key(6, 0, 5), "minecraft:redstone_block");
        return b;
    }

    /**
     * Connection fixtures: continuous runs of rail, power line and wall-attached blocks.
     *
     * <p>Redstone dust draws each cardinal connection with a <em>different</em> model — north is
     * {@code side0}, south is {@code side_alt0}, east is {@code side_alt1} rotated 270 and west is
     * {@code side1} rotated 270 — so a wrong y rotation shows up as a gap on exactly two sides
     * rather than a uniformly broken line. Rails are variants on the {@code shape} property and
     * must join end to end through corners.
     */
    private static Map<String, String> connections() {
        Map<String, String> b = new LinkedHashMap<>();
        for (int x = -1; x <= 14; x++) for (int z = -1; z <= 14; z++) b.put(key(x, -1, z), "minecraft:smooth_stone");

        // straight runs on both axes
        for (int x = 0; x <= 5; x++) b.put(key(x, 0, 0), "minecraft:rail[shape=east_west]");
        for (int z = 0; z <= 5; z++) b.put(key(8, 0, z), "minecraft:rail[shape=north_south]");
        // corners
        b.put(key(0, 0, 3), "minecraft:rail[shape=north_east]");
        b.put(key(1, 0, 3), "minecraft:rail[shape=south_east]");
        b.put(key(2, 0, 3), "minecraft:rail[shape=south_west]");
        b.put(key(3, 0, 3), "minecraft:rail[shape=north_west]");
        // ascending rails need a block to climb
        b.put(key(5, 0, 3), "minecraft:rail[shape=ascending_east]");
        b.put(key(6, 0, 3), "minecraft:stone");
        b.put(key(6, 1, 3), "minecraft:rail[shape=ascending_west]");

        // a closed redstone loop: every side of every block is connected
        for (int x = 10; x <= 13; x++) {
            b.put(key(x, 0, 10), wire(x, 10, "south", 13, 10));
            b.put(key(x, 0, 13), wire(x, 13, "north", 10, 13));
        }
        for (int z = 10; z <= 13; z++) {
            b.put(key(10, 0, z), wire(10, z, "east", 10, 13));
            b.put(key(13, 0, z), wire(13, z, "west", 13, 10));
        }
        // a straight redstone line along X with power 15
        for (int x = 0; x <= 5; x++) {
            b.put(key(x, 0, 6), "minecraft:redstone_wire[power=15,north=none,south=none,east="
                + (x == 0 ? "none" : "side") + ",west=" + (x == 5 ? "none" : "side") + "]");
        }
        // climbing dust: a wire that runs up a step
        b.put(key(8, 0, 6), "minecraft:redstone_wire[power=15,north=side,south=up,east=none,west=none]");
        b.put(key(8, 1, 7), "minecraft:redstone_wire[power=15,north=up,south=side,east=none,west=none]");
        b.put(key(8, 0, 7), "minecraft:stone");
        return b;
    }

    /** Builds a wire state whose connections point at its neighbours in the loop. */
    private static String wire(int x, int z, String fixed, int edge, int edgeCoord) {
        String north = z == 10 ? "none" : "side";
        String south = z == 13 ? "none" : "side";
        String east = x == 13 ? "none" : "side";
        String west = x == 10 ? "none" : "side";
        return "minecraft:redstone_wire[power=15,north=" + north + ",south=" + south
            + ",east=" + east + ",west=" + west + "]";
    }

    // ------------------------------------------------------------------ writer

    private static String key(int x, int y, int z) { return x + "," + y + "," + z; }

    /**
     * Builds a one-region litematic. Sized to the block bounds, with the palette ordered so index 0
     * is air (the fast-scan path the reader optimises for).
     */
    private static void write(Path file, Map<String, String> blocks) throws Exception {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (String k : blocks.keySet()) {
            String[] p = k.split(",");
            int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        int sizeX = maxX - minX + 1, sizeY = maxY - minY + 1, sizeZ = maxZ - minZ + 1;

        List<String> palette = new ArrayList<>();
        palette.add("minecraft:air");
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        paletteIndex.put("minecraft:air", 0);

        int volume = sizeX * sizeY * sizeZ;
        int[] ids = new int[volume];
        for (Map.Entry<String, String> e : blocks.entrySet()) {
            String[] p = e.getKey().split(",");
            int x = Integer.parseInt(p[0]) - minX;
            int y = Integer.parseInt(p[1]) - minY;
            int z = Integer.parseInt(p[2]) - minZ;
            Integer idx = paletteIndex.get(e.getValue());
            if (idx == null) {
                idx = palette.size();
                palette.add(e.getValue());
                paletteIndex.put(e.getValue(), idx);
            }
            // index = y * sizeZ * sizeX + z * sizeX + x   (LitematicaBlockStateContainer.getIndex)
            ids[y * sizeZ * sizeX + z * sizeX + x] = idx;
        }

        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
        long[] storage = pack(ids, bits);

        NbtCompound root = new NbtCompound();
        root.putInt("Version", 6);
        root.putInt("MinecraftDataVersion", 3465);

        NbtCompound metadata = new NbtCompound();
        metadata.putString("Name", file.getFileName().toString().replace(".litematic", ""));
        metadata.putString("Author", "DiagGenSchematic");
        metadata.putInt("RegionCount", 1);
        metadata.putInt("TotalBlocks", blocks.size());
        metadata.putInt("TotalVolume", volume);
        NbtCompound enclosing = new NbtCompound();
        enclosing.putInt("x", sizeX); enclosing.putInt("y", sizeY); enclosing.putInt("z", sizeZ);
        metadata.putCompound("EnclosingSize", enclosing);
        root.putCompound("Metadata", metadata);

        NbtCompound region = new NbtCompound();
        NbtCompound pos = new NbtCompound();
        pos.putInt("x", 0); pos.putInt("y", 0); pos.putInt("z", 0);
        region.putCompound("Position", pos);
        NbtCompound size = new NbtCompound();
        size.putInt("x", sizeX); size.putInt("y", sizeY); size.putInt("z", sizeZ);
        region.putCompound("Size", size);

        NbtList paletteList = new NbtList();
        for (String entry : palette) {
            NbtCompound e = new NbtCompound();
            int br = entry.indexOf('[');
            String name = br < 0 ? entry : entry.substring(0, br);
            e.putString("Name", name);
            if (br >= 0) {
                NbtCompound props = new NbtCompound();
                String body = entry.substring(br + 1, entry.endsWith("]") ? entry.length() - 1 : entry.length());
                for (String part : body.split(",")) {
                    int eq = part.indexOf('=');
                    if (eq > 0) props.putString(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
                }
                e.putCompound("Properties", props);
            }
            paletteList.add(e);
        }
        region.putList("BlockStatePalette", paletteList);
        region.put("BlockStates", new NbtScalars.NbtLongArray(storage));
        region.putList("TileEntities", new NbtList());

        NbtCompound regions = new NbtCompound();
        regions.putCompound("main", region);
        root.putCompound("Regions", regions);

        NbtFileUtils.writeCompressed(file, root);
        System.out.printf("  %-22s %dx%dx%d  %d blocks  %d palette  bits=%d%n",
            file.getFileName(), sizeX, sizeY, sizeZ, blocks.size(), palette.size(), bits);
    }

    /** LitematicaBitArray packing: values are spread across long boundaries, LSB first. */
    private static long[] pack(int[] ids, int bits) {
        long[] out = new long[(int) (((long) ids.length * bits + 63) / 64)];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < ids.length; i++) {
            long start = (long) i * bits;
            int arr = (int) (start >> 6);
            int off = (int) (start & 0x3F);
            long v = ids[i] & mask;
            out[arr] |= v << off;
            int spill = off + bits - 64;
            if (spill > 0 && arr + 1 < out.length) {
                out[arr + 1] |= v >>> (bits - spill);
            }
        }
        return out;
    }
}
