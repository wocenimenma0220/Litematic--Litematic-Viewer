package io.github.litematicrender.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal animated-GIF encoder (GIF89a).
 *
 * <p>Written by hand rather than pulled in as a dependency so the engine stays a single folder of
 * class files with no third-party jars. It covers what an animation export needs: a global palette,
 * per-frame transparency, per-frame delays, and infinite looping.
 *
 * <p>Frames must share the same dimensions. Colours are quantised to a 256-entry palette built from
 * all frames at once, which keeps colours stable across the animation instead of flickering.
 */
public final class GifWriter {
    private final int width;
    private final int height;
    /** 256 RGB entries, 0xRRGGBB. Index 0 is reserved for transparency. */
    private final int[] palette = new int[256];
    private final Sink out = new Sink();
    private static final class Sink {
        java.io.OutputStream stream = new ByteArrayOutputStream();
        void write(int value) { try { stream.write(value); } catch (IOException e) { throw new java.io.UncheckedIOException(e); } }
        void write(byte[] bytes, int start, int length) { try { stream.write(bytes,start,length); } catch (IOException e) { throw new java.io.UncheckedIOException(e); } }
        byte[] toByteArray() { return ((ByteArrayOutputStream)stream).toByteArray(); }
    }
    /** argb -> palette index, so the O(256) search runs once per distinct colour. */
    private final Map<Integer, Integer> nearestCache = new HashMap<>();
    private int framesWritten;

    public GifWriter(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @FunctionalInterface public interface FrameSource { Image render(int tick) throws Exception; }
    @FunctionalInterface public interface Progress { void update(String stage,int completed,int total); }

    /** Render once, spool raw pixels, then encode with one stable palette and bounded frame memory. */
    public static void writeFrames(Path file, int frameCount, int delayCs, FrameSource source) throws Exception {
        writeFrames(file,frameCount,delayCs,source,(stage,completed,total)->{});
    }

    public static void writeFrames(Path file,int frameCount,int delayCs,FrameSource source,Progress progress)throws Exception {
        if (frameCount <= 0) throw new IOException("no frames to write");
        Path destination = file.toAbsolutePath(), parent = destination.getParent();
        Files.createDirectories(parent);
        Path spool = Files.createTempFile(parent,".projection-frames-",".tmp");
        Path encoded = null;
        try {
            var histogram = new PaletteBuilder();
            GifWriter gif = null;
            try (var pixels = new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(spool),1<<20))) {
                for (int tick=0;tick<frameCount;tick++) {
                    Image frame=source.render(tick);
                    if (gif==null) gif=new GifWriter(frame.width,frame.height);
                    if (frame.width!=gif.width || frame.height!=gif.height) throw new IOException("frame size mismatch");
                    histogram.add(frame);
                    for (int pixel:frame.pixels()) pixels.writeInt(pixel);
                    progress.update("rendering",tick+1,frameCount);
                }
            }
            histogram.finish(gif.palette);
            encoded=Files.createTempFile(parent,".projection-gif-",".tmp");
            try (var output=new java.io.BufferedOutputStream(Files.newOutputStream(encoded),1<<20);
                 var pixels=new java.io.DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(spool),1<<20))) {
                gif.out.stream=output; gif.writeHeader();
                Image frame=new Image(gif.width,gif.height);
                for(int tick=0;tick<frameCount;tick++) {
                    for(int i=0;i<frame.pixels().length;i++) frame.pixels()[i]=pixels.readInt();
                    gif.writeFrame(frame,delayCs);
                    progress.update("encoding",tick+1,frameCount);
                }
                gif.writeTrailer();
            }
            Files.move(encoded,destination,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(spool);
            if(encoded!=null) Files.deleteIfExists(encoded);
        }
    }

    /**
     * Quantises the given frames and writes the whole GIF.
     *
     * @param frames    ARGB images, all the same size
     * @param delayCs   per-frame delay in centiseconds (GIF's unit; 1 cs = 10 ms)
     */
    public static void write(Path file, java.util.List<Image> frames, int delayCs) throws IOException {
        if (frames.isEmpty()) throw new IOException("no frames to write");
        Image first = frames.get(0);
        GifWriter gif = new GifWriter(first.width, first.height);
        gif.buildPalette(frames);
        gif.writeHeader();
        for (Image f : frames) {
            if (f.width != first.width || f.height != first.height) {
                throw new IOException("frame size mismatch: "
                    + f.width + "x" + f.height + " vs " + first.width + "x" + first.height);
            }
            gif.writeFrame(f, delayCs);
        }
        gif.writeTrailer();
        Files.createDirectories(file.getParent());
        Files.write(file, gif.out.toByteArray());
    }

    // ------------------------------------------------------------------

    /**
     * Builds a palette from the colours the frames actually contain.
     *
     * <p>A fixed RGB lattice is tempting but wrong for Minecraft art: block textures use a small
     * number of muted, low-saturation colours, and a uniform lattice spends almost all of its
     * entries on hues that never appear — which shows up as speckled dithering across flat stone
     * and wood. Counting colours first puts the palette where the pixels are.
     *
     * <p>Colours are bucketed to 5 bits per channel, the most frequent buckets are taken, and any
     * remaining slots are filled with the average colour of the frames so gradients stay smooth.
     */
    private void buildPalette(java.util.List<Image> frames) {
        var builder=new PaletteBuilder();
        for(Image frame:frames)builder.add(frame);
        builder.finish(this.palette);
    }

    private static final class PaletteBuilder {
        final Map<Integer, Long> histogram = new HashMap<>();
        long[] sums = new long[3];
        long total = 0;
        void add(Image f) {
            int[] px = f.pixels();
            for (int argb : px) {
                int a = (argb >>> 24) & 0xFF;
                if (a == 0) continue;              // partially transparent detail still needs colour
                int r = (argb >>> 16) & 0xFF, g = (argb >>> 8) & 0xFF, b = argb & 0xFF;
                sums[0] += r*(long)a; sums[1] += g*(long)a; sums[2] += b*(long)a; total+=a;
                // 5 bits per channel, but rounded so neighbouring colours share a bucket.
                int key = ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
                histogram.merge(key, (long)a, Long::sum);
            }
        }

        void finish(int[] palette) {
        // index 0 is reserved for transparency by the frame encoder.
        int idx = 1;
        List<Map.Entry<Integer, Long>> sorted = new ArrayList<>(histogram.entrySet());
        sorted.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        for (Map.Entry<Integer, Long> e : sorted) {
            if (idx >= 256) break;
            int key = e.getKey();
            int r = (key >>> 10) & 0x1F, g = (key >>> 5) & 0x1F, b = key & 0x1F;
            // Reconstruct the centre of the bucket rather than its floor, to avoid a dark bias.
            palette[idx++] = (((r << 3) | 4) << 16) | (((g << 3) | 4) << 8) | ((b << 3) | 4);
        }

        // If the art used fewer than 255 distinct colours, pad with the average so the encoder
        // always has a full table (GIF requires a power-of-two size).
        if (idx < 256) {
            int ar = total == 0 ? 0 : (int) (sums[0] / total);
            int ag = total == 0 ? 0 : (int) (sums[1] / total);
            int ab = total == 0 ? 0 : (int) (sums[2] / total);
            int base = (ar << 16) | (ag << 8) | ab;
            while (idx < 256) {
                palette[idx++] = base;
            }
        }
        }
    }

    /** Nearest colour; coverage is resolved separately at each pixel position. */
    private int nearest(int argb) {
        Integer cached = this.nearestCache.get(argb);
        if (cached != null) return cached;
        int result;
        int a = (argb >>> 24) & 0xFF;
        if (a == 0) {
            result = 0;
        } else {
            int r = (argb >>> 16) & 0xFF, g = (argb >>> 8) & 0xFF, b = argb & 0xFF;
            int best = 1, bestDist = Integer.MAX_VALUE;
            for (int i = 1; i < 256; i++) {
                int p = this.palette[i];
                int dr = r - ((p >>> 16) & 0xFF);
                int dg = g - ((p >>> 8) & 0xFF);
                int db = b - (p & 0xFF);
                int d = dr * dr + dg * dg + db * db;
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                    if (d == 0) break;
                }
            }
            result = best;
        }
        if (this.nearestCache.size() >= 65536) this.nearestCache.clear();
        this.nearestCache.put(argb, result);
        return result;
    }

    private void writeHeader() {
        writeString("GIF89a");
        writeShort(this.width);
        writeShort(this.height);
        this.out.write(0xF7);              // global colour table, 256 entries, 8-bit
        this.out.write(0);                 // background colour index
        this.out.write(0);                 // pixel aspect ratio
        for (int i = 0; i < 256; i++) {
            int c = this.palette[i];
            this.out.write((c >>> 16) & 0xFF);
            this.out.write((c >>> 8) & 0xFF);
            this.out.write(c & 0xFF);
        }
        // NETSCAPE2.0 application extension: loop forever.
        this.out.write(0x21);
        this.out.write(0xFF);
        this.out.write(11);
        writeString("NETSCAPE2.0");
        this.out.write(3);
        this.out.write(1);
        writeShort(0);                     // 0 = infinite
        this.out.write(0);
    }

    private void writeFrame(Image img, int delayCs) {
        // Each full frame replaces the preceding one. Clear it back to transparent before
        // drawing the next frame, including the last-to-first loop transition.
        this.out.write(0x21);
        this.out.write(0xF9);
        this.out.write(4);
        this.out.write(0x09);              // disposal 2 (restore background) + transparent flag
        writeShort(Math.max(1, delayCs));
        this.out.write(0);                 // transparent colour index
        this.out.write(0);

        // Image descriptor.
        this.out.write(0x2C);
        writeShort(0);
        writeShort(0);
        writeShort(this.width);
        writeShort(this.height);
        this.out.write(0);                 // no local colour table, not interlaced

        // LZW-compressed indices.
        byte[] indices = new byte[this.width * this.height];
        for (int y = 0; y < this.height; y++) {
            for (int x = 0; x < this.width; x++) {
                int argb=img.pixel(x,y),alpha=argb>>>24;
                indices[y * this.width + x] = (byte)(visible(alpha,x,y)?nearest(argb):0);
            }
        }
        writeLzw(indices);
        this.framesWritten++;
    }

    private void writeTrailer() {
        this.out.write(0x3B);
    }

    /** GIF has binary alpha. A fixed 16x16 Bayer mask preserves fractional coverage without flicker. */
    private static boolean visible(int alpha,int x,int y) {
        if(alpha==0)return false;
        if(alpha==255)return true;
        int threshold=0;
        for(int bit=0;bit<4;bit++) {
            int xb=(x>>bit)&1,yb=(y>>bit)&1;
            threshold=(threshold<<2)|((xb^yb)<<1)|yb;
        }
        return alpha*256>(threshold*2+1)*255/2;
    }

    // ------------------------------------------------------------------
    // LZW, as GIF specifies it (variable code width, clear/end codes).

    private void writeLzw(byte[] data) {
        final int minCodeSize = 8;
        final int clearCode = 1 << minCodeSize;         // 256
        final int endCode = clearCode + 1;              // 257
        this.out.write(minCodeSize);

        BitWriter bw = new BitWriter();
        int codeSize = minCodeSize + 1;
        int nextCode = endCode + 1;
        java.util.Map<Integer, Integer> table = new java.util.HashMap<>();
        bw.write(clearCode, codeSize);

        int prefix = data.length > 0 ? (data[0] & 0xFF) : -1;
        for (int i = 1; i < data.length; i++) {
            int k = data[i] & 0xFF;
            int key = (prefix << 8) | k;
            Integer existing = table.get(key);
            if (existing != null) {
                prefix = existing;
                continue;
            }
            bw.write(prefix, codeSize);
            table.put(key, nextCode++);
            if (nextCode > (1 << codeSize)) {
                if (codeSize < 12) {
                    codeSize++;
                } else {
                    bw.write(clearCode, codeSize);
                    table.clear();
                    codeSize = minCodeSize + 1;
                    nextCode = endCode + 1;
                }
            }
            prefix = k;
        }
        if (prefix >= 0) bw.write(prefix, codeSize);
        bw.write(endCode, codeSize);
        bw.flush();

        // Emit as sub-blocks of at most 255 bytes.
        byte[] payload = bw.toByteArray();
        int offset = 0;
        while (offset < payload.length) {
            int n = Math.min(255, payload.length - offset);
            this.out.write(n);
            this.out.write(payload, offset, n);
            offset += n;
        }
        this.out.write(0);
    }

    /** LSB-first bit accumulator, which is the order GIF's LZW stream uses. */
    private static final class BitWriter {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private int acc;
        private int bits;

        void write(int code, int size) {
            this.acc |= code << this.bits;
            this.bits += size;
            while (this.bits >= 8) {
                this.buf.write(this.acc & 0xFF);
                this.acc >>>= 8;
                this.bits -= 8;
            }
        }

        void flush() {
            if (this.bits > 0) {
                this.buf.write(this.acc & 0xFF);
                this.acc = 0;
                this.bits = 0;
            }
        }

        byte[] toByteArray() { return this.buf.toByteArray(); }
    }

    private void writeShort(int v) {
        this.out.write(v & 0xFF);
        this.out.write((v >>> 8) & 0xFF);
    }

    private void writeString(String s) {
        for (int i = 0; i < s.length(); i++) this.out.write(s.charAt(i) & 0xFF);
    }
}
