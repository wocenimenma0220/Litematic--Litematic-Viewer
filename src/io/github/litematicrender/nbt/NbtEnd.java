package io.github.litematicrender.nbt;

import java.io.IOException;

public final class NbtEnd extends NbtElement {
    public static final NbtEnd INSTANCE = new NbtEnd();

    private NbtEnd() {}

    @Override
    public int type() { return NbtTypes.TAG_END; }

    @Override
    public void write(NbtOutput output) throws IOException { output.writeByte(NbtTypes.TAG_END); }

    @Override
    public NbtElement copy() { return this; }

    @Override
    public String toString() { return ""; }
}
