package io.github.litematicrender.schematic;

import io.github.litematicrender.nbt.NbtCompound;
import io.github.litematicrender.nbt.NbtList;
import io.github.litematicrender.nbt.NbtScalars;
import io.github.litematicrender.nbt.NbtPackedLongArray;
import io.github.litematicrender.nbt.PackedLongArray;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.BlockState;
import io.github.litematicrender.world.Vec3i;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * .litematic file reader.
 * Mirrors fi.dy.masa.litematica.schematic.LitematicaSchematic.readFromData /
 * readSubRegionsFromData + LitematicaBlockStateContainer.
 */
public final class LitematicaSchematic {
    public static final int MAX_SUPPORTED_VERSION = 7;

    // ---------- metadata ----------
    public static final class Metadata {
        public String name = "?";
        public String author = "?";
        public String description = "";
        public int regionCount;
        public long timeCreated;
        public long timeModified;
        public int totalBlocks;
        public int totalVolume;
        public Vec3i enclosingSize = Vec3i.ZERO;
        public int schematicVersion;
        public int minecraftDataVersion;
        public int[] previewImageData;

        static Metadata read(NbtCompound tag) {
            Metadata m = new Metadata();
            m.name = tag.getStringOrDefault("Name", "?");
            m.author = tag.getStringOrDefault("Author", "?");
            m.description = tag.getStringOrDefault("Description", "");
            m.regionCount = tag.getIntOrDefault("RegionCount", 0);
            m.timeCreated = tag.getLongOrDefault("TimeCreated", 0L);
            m.timeModified = tag.getLongOrDefault("TimeModified", 0L);
            m.totalBlocks = tag.getIntOrDefault("TotalBlocks", 0);
            m.totalVolume = tag.getIntOrDefault("TotalVolume", 0);
            m.enclosingSize = SubRegion.readBlockPos(tag, "EnclosingSize");
            m.schematicVersion = tag.getIntOrDefault("SchematicVersion", 0);
            m.minecraftDataVersion = tag.getIntOrDefault("MinecraftDataVersion", 0);
            NbtList prevList = tag.getList("PreviewImageData").orElse(null);
            if (prevList != null && !prevList.isEmpty()) {
                m.previewImageData = new int[prevList.size()];
                for (int i = 0; i < prevList.size(); i++) m.previewImageData[i] = prevList.getIntAt(i);
            }
            NbtScalars.NbtIntArray arr = tag.getIntArray("PreviewImageData").orElse(null);
            if (arr != null) m.previewImageData = arr.value;
            return m;
        }
    }

    // ---------- container ----------
    public static final class BlockContainer {
        public final int sizeX, sizeY, sizeZ;
        public final int bits;
        public final PackedLongArray storage;
        /** resolved palette; index 0 is conventionally air */
        public final BlockState[] palette;

        BlockContainer(int sizeX, int sizeY, int sizeZ, int bits, PackedLongArray storage, BlockState[] palette) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.bits = bits;
            this.storage = storage;
            this.palette = palette;
        }

        public long totalVolume() {
            return (long) sizeX * sizeY * sizeZ;
        }

        /** index = (y * sizeLayer) + z * sizeX + x (litematica LitematicaBlockStateContainer.getIndex) */
        private static long index(int sizeX, int sizeZ, int x, int y, int z) {
            return (long) y * sizeZ * sizeX + (long) z * sizeX + x;
        }

        public BlockState get(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ) {
                return BlockState.air();
            }
            int paletteId = getAt(this.storage, this.bits, index(this.sizeX, this.sizeZ, x, y, z));
            if (paletteId < 0 || paletteId >= this.palette.length) return BlockState.air();
            BlockState s = this.palette[paletteId];
            return s == null ? BlockState.air() : s;
        }

        /** Non-air cell visitor with word-level zero skipping (quickcraft's fast scan). */
        public interface NonAirConsumer {
            void accept(int x, int y, int z, BlockState state);
        }

        public void forEachNonAir(NonAirConsumer consumer) {
            int bits = this.bits;
            PackedLongArray packed = this.storage;
            if (packed.length() == 0) return;
            boolean zeroMeansAir = this.palette.length == 0 || this.palette[0].isAir();
            for (int y = 0; y < this.sizeY; y++) {
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("投影扫描已取消");
                for (int z = 0; z < this.sizeZ; z++) {
                    long rowStart = ((long) y * this.sizeZ + z) * this.sizeX;
                    long rowEnd = rowStart + this.sizeX;
                    int firstWord = (int) ((rowStart * bits) >>> 6);
                    int lastWord = (int) (((rowEnd - 1L) * bits) >>> 6);
                    long lastProcessedIndex = rowStart - 1L;
                    for (int wordIndex = firstWord; wordIndex <= lastWord; wordIndex++) {
                        if (wordIndex >= packed.length()) break;
                        if (zeroMeansAir && packed.zeroPage(wordIndex)) {
                            wordIndex=Math.min(lastWord,((wordIndex/PackedLongArray.PAGE_WORDS)+1)*PackedLongArray.PAGE_WORDS-1);
                            continue;
                        }
                        if (zeroMeansAir && packed.get(wordIndex) == 0L) {
                            continue;
                        }
                        long firstIndex = Math.max(rowStart, ((long) wordIndex * 64L) / bits);
                        long finalIndex = Math.min(rowEnd - 1L, ((((long) wordIndex + 1L) * 64L) - 1L) / bits);
                        for (long idx = firstIndex; idx <= finalIndex; idx++) {
                            if (idx <= lastProcessedIndex) continue;
                            lastProcessedIndex = idx;
                            int stateId = getAt(packed, bits, idx);
                            if (stateId < 0 || stateId >= this.palette.length) continue;
                            BlockState state = this.palette[stateId];
                            if (state == null || state.isAir()) continue;
                            int x = (int) (idx - rowStart);
                            consumer.accept(x, y, z, state);
                        }
                    }
                }
            }
        }
    }

    /** LitematicaBitArray.getAt equivalent. */
    public static int getAt(long[] longArray, int bitsPerEntry, long index) {
        return getAt(PackedLongArray.wrap(longArray),bitsPerEntry,index);
    }

    public static int getAt(PackedLongArray longArray, int bitsPerEntry, long index) {
        long startOffset = index * (long) bitsPerEntry;
        int startArrIndex = (int) (startOffset >> 6);
        int endArrIndex = (int) ((((index + 1L) * (long) bitsPerEntry) - 1L) >> 6);
        int startBitOffset = (int) (startOffset & 0x3F);
        long maxEntryValue = (1L << bitsPerEntry) - 1L;
        if (startArrIndex >= longArray.length()) return 0;
        if (startArrIndex == endArrIndex) {
            return (int) (longArray.get(startArrIndex) >>> startBitOffset & maxEntryValue);
        } else {
            if (endArrIndex >= longArray.length()) endArrIndex = longArray.length() - 1;
            int endOffset = 64 - startBitOffset;
            return (int) ((longArray.get(startArrIndex) >>> startBitOffset | longArray.get(endArrIndex) << endOffset) & maxEntryValue);
        }
    }

    // ---------- region data ----------
    public static final class Region {
        public final String name;
        public final Vec3i position;   // region origin relative to schematic origin
        public final Vec3i size;       // can be negative in raw form
        public BlockContainer blocks;
        public final Map<Long, NbtCompound> blockEntities;  // key = packed local (x,y,z)
        public List<UnresolvedBlockState> storedPalette = List.of();
        public List<SchematicEntity> entities = List.of();
        public Map<String,String> storedProperties(int x,int y,int z) {
            int index=getAt(this.blocks.storage,this.blocks.bits,((long)y*this.blocks.sizeZ+z)*this.blocks.sizeX+x);
            if(index>=0 && index<this.storedPalette.size()) return this.storedPalette.get(index).properties();
            var result=new LinkedHashMap<String,String>(); var state=this.blocks.get(x,y,z);
            for(var property:state.properties) result.put(property.name(),state.getValue(property.name()));
            return result;
        }

        Region(String name, Vec3i position, Vec3i size, BlockContainer blocks, Map<Long, NbtCompound> blockEntities) {
            this.name = name;
            this.position = position;
            this.size = size;
            this.blocks = blocks;
            this.blockEntities = blockEntities;
        }

        public Vec3i absSize() {
            return new Vec3i(Math.abs(this.size.x()), Math.abs(this.size.y()), Math.abs(this.size.z()));
        }
        /** Block storage starts at the minimum corner; saved entity Pos is relative to the first corner. */
        public Vec3i blockOrigin(){return new Vec3i(position.x()+Math.min(0,size.x()+1),position.y()+Math.min(0,size.y()+1),position.z()+Math.min(0,size.z()+1));}
    }

    // ---------- the schematic ----------
    private Metadata metadata = new Metadata();
    private final LinkedHashMap<String, Region> regions = new LinkedHashMap<>();
    public int version;
    public int minecraftDataVersion;

    public static LitematicaSchematic load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("Not a file: " + file);
        NbtCompound root = NbtFileUtils.readSchematic(file);
        LitematicaSchematic schem = new LitematicaSchematic();
        schem.readFromData(root);
        return schem;
    }

    private void readFromData(NbtCompound root) throws IOException {
        if (!root.contains("Version")) {
            throw new IOException("Missing schematic Version");
        }
        this.version = root.getIntOrDefault("Version", -1);
        this.minecraftDataVersion = root.getIntOrDefault("MinecraftDataVersion", 0);
        if (this.version < 1 || this.version > MAX_SUPPORTED_VERSION) {
            throw new IOException("Unsupported schematic version: " + this.version);
        }
        this.metadata = Metadata.read(root.getCompoundOrDefault("Metadata", new NbtCompound()));
        NbtCompound regionsTag = root.getCompound("Regions").orElse(null);
        if (regionsTag == null) {
            throw new IOException("Missing Regions");
        }
        for (String regionName : regionsTag.getKeys()) {
            NbtCompound regionTag = regionsTag.getCompound(regionName).orElse(null);
            if (regionTag == null) continue;
            Vec3i pos = SubRegion.readBlockPos(regionTag, "Position");
            Vec3i size = SubRegion.readBlockPos(regionTag, "Size");
            if (size.x() == 0 && size.y() == 0 && size.z() == 0) continue;

            // raw palette entries
            List<UnresolvedBlockState> rawPalette = readRawPalette(regionTag);
            var statesTag = regionTag.get("BlockStates").orElse(null);
            PackedLongArray storage=statesTag instanceof NbtPackedLongArray packed?packed.value
                :statesTag instanceof NbtScalars.NbtLongArray dense?PackedLongArray.wrap(dense.value):null;
            if (storage == null) continue;
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, rawPalette.size() - 1)));
            Vec3i absSize = new Vec3i(Math.abs(size.x()), Math.abs(size.y()), Math.abs(size.z()));
            Region region = new Region(regionName, pos, size, null, readBlockEntities(regionTag, pos));
            region.storedPalette = List.copyOf(rawPalette);
            region.entities = SchematicEntity.read(regionTag,this.version);
            this.regions.put(regionName, region);
            this.rawContainers.put(regionName, new RawContainer(absSize.x(), absSize.y(), absSize.z(), bits, storage, rawPalette));
        }
    }

    /** Raw (unresolved) container data, resolved once a BlockRegistry is available. */
    private final LinkedHashMap<String, RawContainer> rawContainers = new LinkedHashMap<>();

    private record RawContainer(int sizeX, int sizeY, int sizeZ, int bits, PackedLongArray storage, List<UnresolvedBlockState> rawPalette) {}

    public Metadata metadata() { return this.metadata; }
    public Map<String, Region> regions() { return this.regions; }

    public Vec3i totalSize() { return this.metadata.enclosingSize; }

    /** Resolves raw palettes against the registry; call once before rendering. */
    public void materialize(BlockRegistry registry) {
        for (Map.Entry<String, RawContainer> e : this.rawContainers.entrySet()) {
            Region region = this.regions.get(e.getKey());
            if (region == null) continue;
            RawContainer raw = e.getValue();
            BlockState[] palette = new BlockState[raw.rawPalette.size()];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = resolve(raw.rawPalette.get(i), registry);
            }
            region.blocks = new BlockContainer(raw.sizeX(), raw.sizeY(), raw.sizeZ(), raw.bits(), raw.storage(), palette);
        }
        this.rawContainers.clear();
    }

    private static BlockState resolve(UnresolvedBlockState raw, BlockRegistry registry) {
        BlockRegistry.BlockInfo info = registry.getOrMissing(raw.blockId());
        BlockState state = info.defaultState;
        for (Map.Entry<String, String> e : raw.properties().entrySet()) {
            state = state.tryWith(e.getKey(), e.getValue());
        }
        return state;
    }

    /**
     * Reads a region's block entities.
     *
     * <p>The {@code x/y/z} in a litematic's {@code TileEntities} are already relative to the
     * region origin (verified against real files: the block at the stored coordinate is the one
     * owning the entity), so they are used as-is. Subtracting the region position here would
     * shift every block entity by the region offset.
     */
    private static Map<Long, NbtCompound> readBlockEntities(NbtCompound regionTag, Vec3i regionPos) {
        Map<Long, NbtCompound> tes = new LinkedHashMap<>();
        NbtList teList = regionTag.getList("TileEntities").orElse(null);
        if (teList != null) {
            for (NbtCompound te : teList.getCompounds()) {
                int x = te.getIntOrDefault("x", 0);
                int y = te.getIntOrDefault("y", 0);
                int z = te.getIntOrDefault("z", 0);
                tes.put(pack(x, y, z), te);
            }
        }
        return tes;
    }

    private static List<UnresolvedBlockState> readRawPalette(NbtCompound regionTag) {
        List<UnresolvedBlockState> palette = new ArrayList<>();
        NbtList paletteList = regionTag.getList("BlockStatePalette").orElse(null);
        if (paletteList != null) {
            for (NbtCompound entry : paletteList.getCompounds()) {
                palette.add(readBlockState(entry));
            }
        }
        if (palette.isEmpty()) palette.add(new UnresolvedBlockState("minecraft:air", Map.of()));
        return palette;
    }

    /**
     * BlockState from palette entry NBT:
     * classic: { Name: "minecraft:stone", Properties: { facing: "north" } }
     * 1.20.5+: { blockstate: "minecraft:stone[facing=north]" } (string form)
     */
    public static UnresolvedBlockState readBlockState(NbtCompound tag) {
        String inline = tag.getStringOrDefault("blockstate", null);
        if (inline != null) {
            return parseInline(inline);
        }
        String name = tag.getStringOrDefault("Name", null);
        if (name == null) {
            return new UnresolvedBlockState("minecraft:air", Map.of());
        }
        if (!name.contains(":")) name = "minecraft:" + name;
        NbtCompound propsTag = tag.getCompound("Properties").orElse(null);
        if (propsTag == null || propsTag.isEmpty()) {
            return new UnresolvedBlockState(name, Map.of());
        }
        Map<String, String> props = new LinkedHashMap<>();
        for (String key : propsTag.getKeys()) {
            String v = propsTag.getStringOrDefault(key, null);
            if (v != null) props.put(key, v);
        }
        return new UnresolvedBlockState(name, props);
    }

    private static UnresolvedBlockState parseInline(String fullId) {
        String blockId = fullId;
        Map<String, String> props = new LinkedHashMap<>();
        int bracket = fullId.indexOf('[');
        if (bracket >= 0) {
            blockId = fullId.substring(0, bracket);
            String propStr = fullId.substring(bracket + 1, fullId.length() - 1);
            for (String part : propStr.split(",")) {
                int eq = part.indexOf('=');
                if (eq > 0) props.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return new UnresolvedBlockState(blockId, props);
    }

    public static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    public static int unpackX(long packed) { return (int) (packed >> 38); }
    public static int unpackY(long packed) { return (int) (packed << 52 >> 52); }
    public static int unpackZ(long packed) { return (int) (packed << 26 >> 38); }
}
