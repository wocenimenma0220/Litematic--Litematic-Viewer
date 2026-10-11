package io.github.litematicrender.nbt.io;

import io.github.litematicrender.nbt.NbtCompound;
import io.github.litematicrender.nbt.NbtIo;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.util.zip.GZIPInputStream;

/**
 * File level helpers. Ported from malilib DataFileUtils:
 * GZIP with automatic fallback to uncompressed reads.
 */
public final class NbtFileUtils {
    private NbtFileUtils() {}

    public static NbtCompound readCompressedOrRaw(Path file) throws IOException {
        IOException lastError = null;
        // 1) try gzip
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            NbtCompound result = NbtIo.readFile(in, NbtSizeTracker.FILE_MAX_BYTES);
            if (result != null) return result;
        } catch (IOException e) {
            lastError = e;
        }
        // 2) fall back to raw
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            return NbtIo.readFile(in, NbtSizeTracker.FILE_MAX_BYTES);
        } catch (IOException e) {
            if (lastError != null) e.addSuppressed(lastError);
            throw e;
        }
    }

    public static NbtCompound readCompressed(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            return NbtIo.readFile(in, NbtSizeTracker.FILE_MAX_BYTES);
        }
    }

    public static NbtCompound readRaw(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            return NbtIo.readFile(in, NbtSizeTracker.FILE_MAX_BYTES);
        }
    }

    public static void writeCompressed(Path file, NbtCompound root) throws IOException {
        Files.createDirectories(file.getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file))))) {
            NbtIo.writeFile(root, out);
        }
    }

    public static void writeRaw(Path file, NbtCompound root) throws IOException {
        Files.createDirectories(file.getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            NbtIo.writeFile(root, out);
        }
    }

    /** Stream variant used by zip-embedded schematics. */
    public static NbtCompound readCompressedOrRawStream(InputStream rawStream) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(rawStream)))) {
            return NbtIo.readFile(in, NbtSizeTracker.FILE_MAX_BYTES);
        }
    }
}
