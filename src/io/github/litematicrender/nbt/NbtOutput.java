package io.github.litematicrender.nbt;

import java.io.DataOutput;
import java.io.IOException;

/** Thin facade over DataOutput used by element write methods. */
public final class NbtOutput {
    private final DataOutput out;

    public NbtOutput(DataOutput out) {
        this.out = out;
    }

    public DataOutput raw() { return this.out; }

    public void writeByte(int v) throws IOException { this.out.writeByte(v); }
    public void writeShort(int v) throws IOException { this.out.writeShort(v); }
    public void writeInt(int v) throws IOException { this.out.writeInt(v); }
    public void writeLong(long v) throws IOException { this.out.writeLong(v); }
    public void writeFloat(float v) throws IOException { this.out.writeFloat(v); }
    public void writeDouble(double v) throws IOException { this.out.writeDouble(v); }
    public void writeUTF(String s) throws IOException { this.out.writeUTF(s); }
    public void write(byte[] b) throws IOException { this.out.write(b); }
}
