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
 * Detect GZIP from its header. Parse failures must never trigger an uncompressed retry.
 */
public final class NbtFileUtils {
    private NbtFileUtils() {}

    public static NbtCompound readCompressedOrRaw(Path file) throws IOException {
        try (DataInputStream in = decoded(Files.newInputStream(file))) {
            return NbtIo.readFile(in, NbtSizeTracker.FILE_MAX_BYTES);
        }
    }

    public static NbtCompound readSchematic(Path file) throws IOException {
        try(DataInputStream in=decoded(Files.newInputStream(file))){
            return NbtIo.readFile(in,new NbtSizeTracker(NbtSizeTracker.SCHEMATIC_MAX_BYTES,true));
        }catch(IOException e){throw new IOException("无法读取投影“"+file.getFileName()+"”："+(e.getMessage()==null?"文件不完整或压缩数据损坏":e.getMessage()),e);}
    }

    private static DataInputStream decoded(InputStream raw) throws IOException {
        var buffered=new BufferedInputStream(raw,65536);
        try {
            buffered.mark(2);int first=buffered.read(),second=buffered.read();buffered.reset();
            InputStream content=first==0x1f&&second==0x8b?new GZIPInputStream(buffered,65536):buffered;
            return new DataInputStream(new BufferedInputStream(content,65536));
        }catch(IOException e){try{buffered.close();}catch(IOException close){e.addSuppressed(close);}throw e;}
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
        try (DataInputStream in = decoded(rawStream)) {
            return NbtIo.readFile(in, NbtSizeTracker.FILE_MAX_BYTES);
        }
    }
}
