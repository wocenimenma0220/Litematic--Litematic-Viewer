package io.github.litematicrender.nbt;

import io.github.litematicrender.nbt.io.NbtSizeTracker;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * NBT list container. Vanilla writes an element-type byte + length prefix;
 * element types are usually homogeneous. Ported from malilib ListData.
 */
public final class NbtList extends NbtElement {
    private final List<NbtElement> elements = new ArrayList<>();
    private int elementType = NbtTypes.TAG_END;

    public NbtList() {}

    @Override public int type() { return NbtTypes.TAG_LIST; }

    public int size() { return this.elements.size(); }
    public boolean isEmpty() { return this.elements.isEmpty(); }
    public int elementType() { return this.elementType; }

    public NbtElement get(int index) { return this.elements.get(index); }

    public void add(NbtElement element) {
        if (element == null || element.type() == NbtTypes.TAG_END) return;
        if (this.elements.isEmpty()) {
            this.elementType = element.type();
        }
        this.elements.add(element);
    }

    public List<NbtElement> getElements() { return List.copyOf(this.elements); }

    public List<NbtCompound> getCompounds() {
        List<NbtCompound> result = new ArrayList<>(this.elements.size());
        for (NbtElement e : this.elements) {
            if (e instanceof NbtCompound c) result.add(c);
        }
        return result;
    }

    public Optional<NbtCompound> getCompoundAt(int index) {
        if (index < 0 || index >= this.elements.size()) return Optional.empty();
        NbtElement e = this.elements.get(index);
        return e instanceof NbtCompound c ? Optional.of(c) : Optional.empty();
    }

    public NbtCompound getCompoundAtOrDefault(int index, NbtCompound def) {
        return this.getCompoundAt(index).orElse(def);
    }

    public Optional<NbtList> getListAt(int index) {
        if (index < 0 || index >= this.elements.size()) return Optional.empty();
        NbtElement e = this.elements.get(index);
        return e instanceof NbtList l ? Optional.of(l) : Optional.empty();
    }

    public String getStringAt(int index) {
        if (index < 0 || index >= this.elements.size()) return "";
        NbtElement e = this.elements.get(index);
        return e instanceof NbtScalars.NbtString s ? s.value : "";
    }

    public int getIntAt(int index) {
        if (index < 0 || index >= this.elements.size()) return 0;
        NbtElement e = this.elements.get(index);
        if (e instanceof NbtScalars.NbtInt i) return i.value;
        if (e instanceof NbtScalars.NbtByte b) return b.value;
        if (e instanceof NbtScalars.NbtShort s) return s.value;
        return 0;
    }

    @Override public NbtElement copy() {
        NbtList copy = new NbtList();
        copy.elementType = this.elementType;
        for (NbtElement e : this.elements) copy.elements.add(e.copy());
        return copy;
    }

    @Override public void write(NbtOutput output) throws IOException {
        int type = this.elements.isEmpty() ? NbtTypes.TAG_END : this.elementType;
        output.writeByte(type);
        output.writeInt(this.elements.size());
        for (NbtElement e : this.elements) e.write(output);
    }

    static NbtList read(DataInput input, int depth, NbtSizeTracker tracker) throws IOException {
        if (depth > 512) {
            throw new IOException("Tried to read NBT tag with too high complexity, depth > 512");
        }
        int elementType = input.readUnsignedByte();
        int length = input.readInt();
        tracker.increment(5L);
        if (length <= 0 || elementType == NbtTypes.TAG_END) {
            return new NbtList();
        }
        tracker.increment((long) length * 4);
        NbtList result = new NbtList();
        result.elementType = elementType;
        for (int i = 0; i < length; i++) {
            result.elements.add(NbtElement.create(elementType, input, depth + 1, tracker));
        }
        return result;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < this.elements.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(this.elements.get(i));
        }
        return sb.append(']').toString();
    }
}
