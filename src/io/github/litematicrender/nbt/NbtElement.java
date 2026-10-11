package io.github.litematicrender.nbt;

import io.github.litematicrender.nbt.io.NbtSizeTracker;
import io.github.litematicrender.nbt.NbtScalars.*;

import java.io.DataInput;
import java.io.IOException;

/**
 * Base of the tag value hierarchy. Ported from fi.dy.masa.malilib.util.data.tag.BaseData,
 * cleaned of Codec dependencies.
 */
public abstract class NbtElement {
    public abstract int type();

    public boolean isNumeric() {
        return switch (this.type()) {
            case NbtTypes.TAG_BYTE, NbtTypes.TAG_SHORT, NbtTypes.TAG_INT, NbtTypes.TAG_LONG,
                 NbtTypes.TAG_FLOAT, NbtTypes.TAG_DOUBLE -> true;
            default -> false;
        };
    }

    public abstract void write(NbtOutput output) throws IOException;

    public abstract NbtElement copy();

    @Override
    public abstract String toString();

    public static NbtElement create(int tagType, DataInput input, int depth, NbtSizeTracker tracker) throws IOException {
        return switch (tagType) {
            case NbtTypes.TAG_BYTE -> NbtByte.read(input, tracker);
            case NbtTypes.TAG_SHORT -> NbtShort.read(input, tracker);
            case NbtTypes.TAG_INT -> NbtInt.read(input, tracker);
            case NbtTypes.TAG_LONG -> NbtLong.read(input, tracker);
            case NbtTypes.TAG_FLOAT -> NbtFloat.read(input, tracker);
            case NbtTypes.TAG_DOUBLE -> NbtDouble.read(input, tracker);
            case NbtTypes.TAG_STRING -> NbtString.read(input, tracker);
            case NbtTypes.TAG_BYTE_ARRAY -> NbtByteArray.read(input, tracker);
            case NbtTypes.TAG_INT_ARRAY -> NbtIntArray.read(input, tracker);
            case NbtTypes.TAG_LONG_ARRAY -> NbtLongArray.read(input, tracker);
            case NbtTypes.TAG_COMPOUND -> NbtCompound.read(input, depth, tracker);
            case NbtTypes.TAG_LIST -> NbtList.read(input, depth, tracker);
            case NbtTypes.TAG_END -> NbtEnd.INSTANCE;
            default -> throw new IOException("Unknown tag type " + tagType);
        };
    }
}
