package io.github.litematicrender.nbt;

import io.github.litematicrender.nbt.io.NbtSizeTracker;

import java.io.DataInput;
import java.io.IOException;

/** Numeric + string scalar tags. */
public final class NbtScalars {
    private NbtScalars() {}

    public static final class NbtByte extends NbtElement {
        public final byte value;
        public NbtByte(byte value) { this.value = value; }
        @Override public int type() { return NbtTypes.TAG_BYTE; }
        @Override public void write(NbtOutput output) throws IOException { output.writeByte(this.value); }
        @Override public NbtElement copy() { return this; }
        @Override public String toString() { return this.value + "b"; }
        static NbtByte read(DataInput in, NbtSizeTracker t) throws IOException {
            t.increment(Byte.BYTES);
            return new NbtByte(in.readByte());
        }
    }

    public static final class NbtShort extends NbtElement {
        public final short value;
        public NbtShort(short value) { this.value = value; }
        @Override public int type() { return NbtTypes.TAG_SHORT; }
        @Override public void write(NbtOutput output) throws IOException { output.writeShort(this.value); }
        @Override public NbtElement copy() { return this; }
        @Override public String toString() { return this.value + "s"; }
        static NbtShort read(DataInput in, NbtSizeTracker t) throws IOException {
            t.increment(Short.BYTES);
            return new NbtShort(in.readShort());
        }
    }

    public static final class NbtInt extends NbtElement {
        public final int value;
        public NbtInt(int value) { this.value = value; }
        @Override public int type() { return NbtTypes.TAG_INT; }
        @Override public void write(NbtOutput output) throws IOException { output.writeInt(this.value); }
        @Override public NbtElement copy() { return this; }
        @Override public String toString() { return Integer.toString(this.value); }
        static NbtInt read(DataInput in, NbtSizeTracker t) throws IOException {
            t.increment(Integer.BYTES);
            return new NbtInt(in.readInt());
        }
    }

    public static final class NbtLong extends NbtElement {
        public final long value;
        public NbtLong(long value) { this.value = value; }
        @Override public int type() { return NbtTypes.TAG_LONG; }
        @Override public void write(NbtOutput output) throws IOException { output.writeLong(this.value); }
        @Override public NbtElement copy() { return this; }
        @Override public String toString() { return this.value + "L"; }
        static NbtLong read(DataInput in, NbtSizeTracker t) throws IOException {
            t.increment(Long.BYTES);
            return new NbtLong(in.readLong());
        }
    }

    public static final class NbtFloat extends NbtElement {
        public final float value;
        public NbtFloat(float value) { this.value = value; }
        @Override public int type() { return NbtTypes.TAG_FLOAT; }
        @Override public void write(NbtOutput output) throws IOException { output.writeFloat(this.value); }
        @Override public NbtElement copy() { return this; }
        @Override public String toString() { return this.value + "f"; }
        static NbtFloat read(DataInput in, NbtSizeTracker t) throws IOException {
            t.increment(Float.BYTES);
            return new NbtFloat(in.readFloat());
        }
    }

    public static final class NbtDouble extends NbtElement {
        public final double value;
        public NbtDouble(double value) { this.value = value; }
        @Override public int type() { return NbtTypes.TAG_DOUBLE; }
        @Override public void write(NbtOutput output) throws IOException { output.writeDouble(this.value); }
        @Override public NbtElement copy() { return this; }
        @Override public String toString() { return this.value + "d"; }
        static NbtDouble read(DataInput in, NbtSizeTracker t) throws IOException {
            t.increment(Double.BYTES);
            return new NbtDouble(in.readDouble());
        }
    }

    public static final class NbtString extends NbtElement {
        public final String value;
        public NbtString(String value) { this.value = value; }
        @Override public int type() { return NbtTypes.TAG_STRING; }
        @Override public void write(NbtOutput output) throws IOException { output.writeUTF(this.value); }
        @Override public NbtElement copy() { return new NbtString(this.value); }
        @Override public String toString() { return quoteAndEscape(this.value); }

        public static String quoteAndEscape(String str) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                if (c == '\\' || c == '"') sb.append('\\');
                sb.append(c);
            }
            return sb.append('"').toString();
        }

        static NbtString read(DataInput in, NbtSizeTracker t) throws IOException {
            String s = in.readUTF();
            t.increment(2 + 2L * s.length());
            return new NbtString(s);
        }
    }

    public static final class NbtByteArray extends NbtElement {
        public final byte[] value;
        public NbtByteArray(byte[] value) { this.value = value; }
        @Override public int type() { return NbtTypes.TAG_BYTE_ARRAY; }
        @Override public void write(NbtOutput output) throws IOException {
            output.writeInt(this.value.length);
            output.write(this.value);
        }
        @Override public NbtElement copy() { return new NbtByteArray(this.value.clone()); }
        @Override public String toString() { return "[" + this.value.length + " bytes]"; }
        static NbtByteArray read(DataInput in, NbtSizeTracker t) throws IOException {
            int len = in.readInt();
            if(len<0)throw new IOException("NBT 字节数组长度为负数："+len);
            t.increment(4 + (long) len);
            byte[] data = new byte[len];
            in.readFully(data);
            return new NbtByteArray(data);
        }
    }

    public static final class NbtIntArray extends NbtElement {
        public final int[] value;
        public NbtIntArray(int[] value) { this.value = value; }
        @Override public int type() { return NbtTypes.TAG_INT_ARRAY; }
        @Override public void write(NbtOutput output) throws IOException {
            output.writeInt(this.value.length);
            for (int v : this.value) output.writeInt(v);
        }
        @Override public NbtElement copy() { return new NbtIntArray(this.value.clone()); }
        @Override public String toString() { return "[" + this.value.length + " ints]"; }
        static NbtIntArray read(DataInput in, NbtSizeTracker t) throws IOException {
            int len = in.readInt();
            if(len<0)throw new IOException("NBT 整数数组长度为负数："+len);
            t.increment(4 + 4L * len);
            int[] data = new int[len];
            for (int i = 0; i < len; i++) data[i] = in.readInt();
            return new NbtIntArray(data);
        }
    }

    public static final class NbtLongArray extends NbtElement {
        public final long[] value;
        public NbtLongArray(long[] value) { this.value = value; }
        @Override public int type() { return NbtTypes.TAG_LONG_ARRAY; }
        @Override public void write(NbtOutput output) throws IOException {
            output.writeInt(this.value.length);
            for (long v : this.value) output.writeLong(v);
        }
        @Override public NbtElement copy() { return new NbtLongArray(this.value.clone()); }
        @Override public String toString() { return "[" + this.value.length + " longs]"; }
        static NbtLongArray read(DataInput in, NbtSizeTracker t) throws IOException {
            int len = in.readInt();
            if(len<0)throw new IOException("NBT 长整数数组长度为负数："+len);
            t.increment(4 + 8L * len);
            long[] data = new long[len];
            for (int i = 0; i < len; i++) data[i] = in.readLong();
            return new NbtLongArray(data);
        }
    }
}
