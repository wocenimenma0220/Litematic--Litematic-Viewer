package io.github.litematicrender.nbt;

import io.github.litematicrender.nbt.io.NbtSizeTracker;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Reads/writes the vanilla NBT binary format using plain DataInput/DataOutput.
 * Ported from malilib CompoundData/DataFileUtils (pure Java).
 */
public final class NbtIo {
    private NbtIo() {}

    /** Reads an unnamed-root compound (the standard file format: type byte + root name + compound body). */
    public static NbtCompound readFile(DataInput input, long maxBytes) throws IOException {
        return readFile(input, new NbtSizeTracker(maxBytes));
    }

    public static NbtCompound readFile(DataInput input, NbtSizeTracker tracker) throws IOException {
        byte type = input.readByte();
        if (type == NbtTypes.TAG_END) {
            return new NbtCompound();
        }
        if (type != NbtTypes.TAG_COMPOUND) {
            throw new IOException("Root tag is not a compound: " + NbtTypes.typeName(type));
        }
        input.readUTF(); // discard root name
        return NbtCompound.read(input, 0, tracker);
    }

    public static void writeFile(NbtCompound root, DataOutput output) throws IOException {
        output.writeByte(NbtTypes.TAG_COMPOUND);
        output.writeUTF("");
        root.write(new NbtOutput(output));
    }

    static void writeEntry(NbtOutput output, String key, NbtElement value) throws IOException {
        output.writeByte(value.type());
        output.writeUTF(key);
        value.write(output);
    }
}
