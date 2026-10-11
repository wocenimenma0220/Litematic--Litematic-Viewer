package io.github.litematicrender.nbt.io;

import java.io.IOException;

/** Port of malilib SizeTracker: guards NBT parse memory use. */
public final class NbtSizeTracker {
    public static final long DEFAULT_MAX_BYTES = 1024L * 1024L * 1024L;      // 1 GB
    public static final long FILE_MAX_BYTES    =  512L * 1024L * 1024L;      // 512 MB
    public static final long NETWORK_MAX_BYTES =   64L * 1024L * 1024L;      // 64 MB

    private final long maxBytes;
    private long currentBytes;

    public NbtSizeTracker(long maxBytes) {
        long effective = maxBytes <= 0 ? DEFAULT_MAX_BYTES : maxBytes;
        this.maxBytes = Math.min(effective, DEFAULT_MAX_BYTES);
    }

    public void increment(long numBytes) throws IOException {
        this.currentBytes += numBytes;
        if (this.currentBytes > this.maxBytes) {
            throw new IOException("NBT tag size exceeds quota: " + this.currentBytes + " > " + this.maxBytes);
        }
    }

    public long currentBytes() {
        return this.currentBytes;
    }
}
