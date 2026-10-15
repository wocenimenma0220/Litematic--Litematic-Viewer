package io.github.litematicrender.nbt.io;

import java.io.IOException;

/** Port of malilib SizeTracker: guards NBT parse memory use. */
public final class NbtSizeTracker {
    public static final long DEFAULT_MAX_BYTES = 1024L * 1024L * 1024L;      // 1 GB
    public static final long FILE_MAX_BYTES    =  512L * 1024L * 1024L;      // 512 MB
    public static final long NETWORK_MAX_BYTES =   64L * 1024L * 1024L;      // 64 MB
    public static final long SCHEMATIC_MAX_BYTES = 16L * 1024L * 1024L * 1024L;

    private final long maxBytes;
    private long currentBytes;
    private long retainedBytes;
    private final long retainedLimit;
    public final boolean packedBlockStates;

    public NbtSizeTracker(long maxBytes) {
        this(maxBytes, false);
    }

    public NbtSizeTracker(long maxBytes, boolean packedBlockStates) {
        long effective = maxBytes <= 0 ? DEFAULT_MAX_BYTES : maxBytes;
        this.maxBytes = Math.min(effective, packedBlockStates ? SCHEMATIC_MAX_BYTES : DEFAULT_MAX_BYTES);
        this.packedBlockStates = packedBlockStates;
        this.retainedLimit = packedBlockStates ? Math.min(DEFAULT_MAX_BYTES, Runtime.getRuntime().maxMemory()/3) : this.maxBytes;
    }

    public void increment(long numBytes) throws IOException {
        incrementPacked(numBytes);
        retainPacked(numBytes);
    }

    public void incrementPacked(long numBytes) throws IOException {
        if(numBytes<0||numBytes>this.maxBytes-this.currentBytes)throw new IOException("NBT 解压数据超过读取上限（"+(this.maxBytes/1048576)+" MiB）");
        this.currentBytes += numBytes;
    }

    public void retainPacked(long numBytes)throws IOException {
        if(numBytes<0||numBytes>retainedLimit-retainedBytes)throw new IOException("投影数据超过可用读取内存（"+(retainedLimit/1048576)+" MiB），请缩小投影范围");
        retainedBytes+=numBytes;
    }

    public long currentBytes() {
        return this.currentBytes;
    }

    /** Diagnostics and boundary tests; this is retained heap, not logical decompressed bytes. */
    public long retainedBytes() { return this.retainedBytes; }
    public long retainedLimit() { return this.retainedLimit; }
}
