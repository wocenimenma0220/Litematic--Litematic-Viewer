package io.github.litematicrender.render;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Simple RGBA image, mirroring the parts of com.mojang.blaze3d.platform.NativeImage we need.
 * Pixels are stored as ARGB ints (alpha in bits 24-31), row-major, top-to-bottom.
 */
public final class Image {
    public final int width;
    public final int height;
    private final int[] pixels;

    public Image(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    private Image(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    public int pixel(int x, int y) {
        return this.pixels[y * this.width + x];
    }

    public void setPixel(int x, int y, int argb) {
        this.pixels[y * this.width + x] = argb;
    }

    public int[] pixels() {
        return this.pixels;
    }

    public static int alpha(int argb) { return (argb >>> 24) & 0xFF; }
    public static int red(int argb)   { return (argb >>> 16) & 0xFF; }
    public static int green(int argb) { return (argb >>> 8) & 0xFF; }
    public static int blue(int argb)  { return argb & 0xFF; }

    public static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Nearest-neighbor sample in [0,1) UV space. */
    public int sampleNearest(float u, float v) {
        int x = Math.min(this.width - 1, Math.max(0, (int) (u * this.width)));
        int y = Math.min(this.height - 1, Math.max(0, (int) (v * this.height)));
        return this.pixel(x, y);
    }

    /** Copies a region of src into this image at dstX/dstY (this = destination). */
    public void blitFrom(Image src, int srcX, int srcY, int dstX, int dstY, int w, int h) {
        for (int y = 0; y < h; y++) {
            int dy = dstY + y;
            if (dy < 0 || dy >= this.height) continue;
            int sy = srcY + y;
            if (sy < 0 || sy >= src.height) continue;
            for (int x = 0; x < w; x++) {
                int dx = dstX + x;
                if (dx < 0 || dx >= this.width) continue;
                int sx = srcX + x;
                if (sx < 0 || sx >= src.width) continue;
                this.setPixel(dx, dy, src.pixel(sx, sy));
            }
        }
    }

    /** Copies a region of this image into dst at dstX/dstY (this = source). */
    public void blitTo(Image dst, int srcX, int srcY, int dstX, int dstY, int w, int h) {
        dst.blitFrom(this, srcX, srcY, dstX, dstY, w, h);
    }

    public static Image read(byte[] data) throws IOException {
        var input=new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(data));
        try{
            BufferedImage img = ImageIO.read(input);
            if (img == null) throw new IOException("Unsupported image format");
            return fromBuffered(img);
        }finally{try{input.close();}catch(IOException alreadyClosed){/* ImageIO closes a successfully decoded stream. */}}
    }

    public static Image read(InputStream in) throws IOException {
        BufferedImage img = ImageIO.read(in);
        if (img == null) throw new IOException("Unsupported image format");
        return fromBuffered(img);
    }

    public static Image read(Path file) throws IOException {
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) throw new IOException("Unsupported image format: " + file);
        return fromBuffered(img);
    }

    public static Image fromBuffered(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = new int[w * h];
        img.getRGB(0, 0, w, h, argb, 0, w);
        return new Image(w, h, argb);
    }

    public BufferedImage toBuffered() {
        BufferedImage img = new BufferedImage(this.width, this.height, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, this.width, this.height, this.pixels, 0, this.width);
        return img;
    }

    public void writePng(Path file) throws IOException {
        Path target = file.toAbsolutePath();
        java.nio.file.Files.createDirectories(target.getParent());
        if (!ImageIO.write(this.toBuffered(), "png", target.toFile())) {
            throw new IOException("PNG writer unavailable: " + target);
        }
    }

    /** The missing texture: pink/black checkerboard (vanilla missingno). */
    public static Image missing() {
        return missing(16, 16);
    }

    public static Image missing(int width, int height) {
        Image img = new Image(width, height);
        int magenta = 0xFFF800F8 & 0xFFFFFFFF;
        int black = 0xFF000000;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setPixel(x, y, (y < height / 2 ^ x < width / 2) ? magenta : black);
            }
        }
        return img;
    }
}
