package io.github.litematicrender.nbt;

import io.github.litematicrender.nbt.io.NbtSizeTracker;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Compound & list containers. Ported from malilib CompoundData/ListData (pure Java),
 * with OrDefault-style accessors for robust offline parsing.
 */
public final class NbtCompound extends NbtElement {
    private final LinkedHashMap<String, NbtElement> values = new LinkedHashMap<>();

    public NbtCompound() {}

    public NbtCompound(Map<String, NbtElement> values) { this.values.putAll(values); }

    @Override public int type() { return NbtTypes.TAG_COMPOUND; }
    @Override public NbtElement copy() { return new NbtCompound(this.values); }

    // ---------- structure ----------
    public boolean contains(String key) { return this.values.containsKey(key); }

    public boolean contains(String key, int type) {
        NbtElement el = this.values.get(key);
        return el != null && el.type() == type;
    }

    public Set<String> getKeys() { return this.values.keySet(); }

    public int size() { return this.values.size(); }
    public boolean isEmpty() { return this.values.isEmpty(); }

    public Optional<NbtElement> get(String key) { return Optional.ofNullable(this.values.get(key)); }

    public NbtCompound put(String key, NbtElement value) {
        if (value != null && value.type() != NbtTypes.TAG_END) {
            this.values.put(key, value);
        }
        return this;
    }

    public void remove(String key) { this.values.remove(key); }

    public void combine(NbtCompound other) { this.values.putAll(other.values); }

    // ---------- typed getters ----------
    public Optional<NbtCompound> getCompound(String key) {
        NbtElement el = this.values.get(key);
        return el instanceof NbtCompound c ? Optional.of(c) : Optional.empty();
    }

    public Optional<NbtList> getList(String key) {
        NbtElement el = this.values.get(key);
        return el instanceof NbtList l ? Optional.of(l) : Optional.empty();
    }

    public Optional<NbtScalars.NbtByteArray> getByteArray(String key) {
        NbtElement el = this.values.get(key);
        return el instanceof NbtScalars.NbtByteArray b ? Optional.of(b) : Optional.empty();
    }

    public Optional<NbtScalars.NbtIntArray> getIntArray(String key) {
        NbtElement el = this.values.get(key);
        return el instanceof NbtScalars.NbtIntArray a ? Optional.of(a) : Optional.empty();
    }

    public Optional<NbtScalars.NbtLongArray> getLongArray(String key) {
        NbtElement el = this.values.get(key);
        return el instanceof NbtScalars.NbtLongArray a ? Optional.of(a) : Optional.empty();
    }

    // ---------- OrDefault accessors (numeric promotion like vanilla NBT) ----------
    private Number numberOrNull(String key) {
        NbtElement el = this.values.get(key);
        if (el instanceof NbtScalars.NbtByte b) return b.value;
        if (el instanceof NbtScalars.NbtShort s) return s.value;
        if (el instanceof NbtScalars.NbtInt i) return i.value;
        if (el instanceof NbtScalars.NbtLong l) return l.value;
        if (el instanceof NbtScalars.NbtFloat f) return f.value;
        if (el instanceof NbtScalars.NbtDouble d) return d.value;
        return null;
    }

    public byte getByteOrDefault(String key, byte def) {
        Number n = numberOrNull(key);
        return n != null ? n.byteValue() : def;
    }

    public short getShortOrDefault(String key, short def) {
        Number n = numberOrNull(key);
        return n != null ? n.shortValue() : def;
    }

    public int getIntOrDefault(String key, int def) {
        Number n = numberOrNull(key);
        return n != null ? n.intValue() : def;
    }

    public long getLongOrDefault(String key, long def) {
        Number n = numberOrNull(key);
        return n != null ? n.longValue() : def;
    }

    public float getFloatOrDefault(String key, float def) {
        Number n = numberOrNull(key);
        return n != null ? n.floatValue() : def;
    }

    public double getDoubleOrDefault(String key, double def) {
        Number n = numberOrNull(key);
        return n != null ? n.doubleValue() : def;
    }

    public String getStringOrDefault(String key, String def) {
        NbtElement el = this.values.get(key);
        return el instanceof NbtScalars.NbtString s ? s.value : def;
    }

    public boolean getBooleanOrDefault(String key, boolean def) {
        return this.getByteOrDefault(key, (byte) (def ? 1 : 0)) != 0;
    }

    public NbtCompound getCompoundOrDefault(String key, NbtCompound def) {
        return this.getCompound(key).orElse(def);
    }

    public NbtList getListOrDefault(String key, NbtList def) {
        return this.getList(key).orElse(def);
    }

    // ---------- IO ----------
    @Override public void write(NbtOutput output) throws IOException {
        for (Map.Entry<String, NbtElement> e : this.values.entrySet()) {
            NbtIo.writeEntry(output, e.getKey(), e.getValue());
        }
        output.writeByte(NbtTypes.TAG_END);
    }

    static NbtCompound read(DataInput input, int depth, NbtSizeTracker tracker) throws IOException {
        if (depth > 512) {
            throw new IOException("Tried to read NBT tag with too high complexity, depth > 512");
        }
        NbtCompound result = new NbtCompound();
        while (true) {
            int tagType = input.readUnsignedByte();
            tracker.increment(Byte.BYTES);
            if (tagType == NbtTypes.TAG_END) break;
            String key = input.readUTF();
            tracker.increment(2 + 2L * key.length());
            NbtElement data = tracker.packedBlockStates && depth == 2 && key.equals("BlockStates") && tagType == NbtTypes.TAG_LONG_ARRAY
                ? new NbtPackedLongArray(PackedLongArray.read(input, tracker))
                : NbtElement.create(tagType, input, depth + 1, tracker);
            result.values.put(key, data);
        }
        return result;
    }

    @Override public String toString() {
        if (this.values.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, NbtElement> e : this.values.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.append('}').toString();
    }

    // Convenience write helpers (static-imported by schematic code)
    public NbtCompound putInt(String key, int v) { return this.put(key, new NbtScalars.NbtInt(v)); }
    public NbtCompound putLong(String key, long v) { return this.put(key, new NbtScalars.NbtLong(v)); }
    public NbtCompound putByte(String key, int v) { return this.put(key, new NbtScalars.NbtByte((byte) v)); }
    public NbtCompound putShort(String key, int v) { return this.put(key, new NbtScalars.NbtShort((short) v)); }
    public NbtCompound putFloat(String key, float v) { return this.put(key, new NbtScalars.NbtFloat(v)); }
    public NbtCompound putDouble(String key, double v) { return this.put(key, new NbtScalars.NbtDouble(v)); }
    public NbtCompound putString(String key, String v) { return this.put(key, new NbtScalars.NbtString(v)); }
    public NbtCompound putBoolean(String key, boolean v) { return this.putByte(key, v ? 1 : 0); }
    public NbtCompound putLongArray(String key, long[] v) { return this.put(key, new NbtScalars.NbtLongArray(v)); }
    public NbtCompound putIntArray(String key, int[] v) { return this.put(key, new NbtScalars.NbtIntArray(v)); }
    public NbtCompound putCompound(String key, NbtCompound v) { return this.put(key, v); }
    public NbtCompound putList(String key, NbtList v) { return this.put(key, v); }
}
