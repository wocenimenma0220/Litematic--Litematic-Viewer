package io.github.litematicrender.render;

import java.util.List;

/**
 * A sprite loaded from a resource: single frame or vertical animation sheet.
 * Mirrors net.minecraft.client.renderer.texture.SpriteContents (data part only).
 */
public final class SpriteContents {
    public final String name;            // e.g. "minecraft:block/stone"
    public final int frameWidth;         // per-frame width
    public final int frameHeight;        // per-frame height
    public final Image sheet;            // full sheet (possibly multiple frames stacked vertically)
    public final int frameCount;         // 1 if not animated
    public final int frameTime;          // ticks per frame (from mcmeta "frametime")
    public final List<Integer> frames;   // frame indices in play order (from mcmeta "frames", or 0..n-1)
    public final boolean interpolate;    // mcmeta "interpolate"
    /** Transparency classification (vanilla Transparency semantics) */
    public final boolean hasTransparent;   // any pixel with alpha == 0
    public final boolean hasTranslucent;   // any pixel with alpha != 0/255
    /** Which source frame is currently blitted into the atlas, so rebakes can skip work. */
    int lastBakedFrame = -1;

    public SpriteContents(String name, int frameWidth, int frameHeight, Image sheet,
                          int frameCount, int frameTime, List<Integer> frames, boolean interpolate) {
        this.name = name;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.sheet = sheet;
        this.frameCount = frameCount;
        this.frameTime = frameTime;
        this.frames = frames;
        this.interpolate = interpolate;
        // scan frame 0 for transparency classification (vanilla scans all frames via computeTransparency; frame 0 suffices in practice)
        boolean transparent = false, translucent = false;
        scan:
        for (int y = 0; y < sheet.height; y++) {
            for (int x = 0; x < sheet.width; x++) {
                int a = Image.alpha(sheet.pixel(x, y));
                if (a == 0) transparent = true;
                else if (a != 255) translucent = true;
                if (transparent && translucent) break scan;
            }
        }
        this.hasTransparent = transparent;
        this.hasTranslucent = translucent;
    }

    public boolean isAnimated() {
        return this.frameCount > 1;
    }

    /**
     * Vanilla {@code SpriteContents.computeTransparency(minU, minV, maxU, maxV)}: classifies only
     * the region of the sheet a face's UV rectangle actually samples.
     *
     * <p>The distinction is not academic. {@code force_translucent} is a per-<em>material</em> flag,
     * so {@code redstone_dust_overlay} — whose own pixels are hard-edged — is baked translucent when
     * a face reaches it through a translucent slot. But a face whose UV covers only an opaque part
     * of a mostly transparent sheet comes back {@code NONE} — solid — and that is how a texture that
     * is transparent <em>somewhere</em> still ends up in the SOLID layer. Classifying the whole
     * sprite instead sends those faces to CUTOUT, where they neither write depth the way the game's
     * solid geometry does nor blend, and where the terrain shader's alpha cutoff can erase them.
     *
     * @param minU,minV,maxU,maxV sprite-relative UV bounds in 0..1 (either corner order)
     */
    public Transparency transparencyIn(float minU, float minV, float maxU, float maxV) {
        int x0 = (int) Math.floor(Math.max(0f, Math.min(minU, maxU)) * this.frameWidth);
        int y0 = (int) Math.floor(Math.max(0f, Math.min(minV, maxV)) * this.frameHeight);
        int x1 = (int) Math.ceil(Math.min(1f, Math.max(minU, maxU)) * this.frameWidth);
        int y1 = (int) Math.ceil(Math.min(1f, Math.max(minV, maxV)) * this.frameHeight);
        x0 = Math.max(0, Math.min(this.frameWidth - 1, x0));
        y0 = Math.max(0, Math.min(this.frameHeight - 1, y0));
        x1 = Math.max(x0 + 1, Math.min(this.frameWidth, x1));
        y1 = Math.max(y0 + 1, Math.min(this.frameHeight, y1));

        boolean transparent = false, translucent = false;
        // Vanilla scans the animation frames in play order; the whole sheet is the honest superset
        // here and costs nothing extra, since a still sprite's sheet is exactly its one frame.
        for (int y = 0; y < this.sheet.height; y++) {
            if (y % this.frameHeight < y0 || y % this.frameHeight >= y1) continue;
            for (int x = 0; x < this.sheet.width; x++) {
                if (x % this.frameWidth < x0 || x % this.frameWidth >= x1) continue;
                int a = Image.alpha(this.sheet.pixel(x, y));
                if (a == 0) transparent = true;
                else if (a != 255) translucent = true;
                if (transparent && translucent) return Transparency.TRANSPARENT_AND_TRANSLUCENT;
            }
        }
        if (transparent) return Transparency.TRANSPARENT;
        return translucent ? Transparency.TRANSLUCENT : Transparency.NONE;
    }

    /**
     * Which source frame is shown at a given game tick.
     *
     * <p>Vanilla advances one step in the {@code frames} list every {@code frametime} ticks, wrapping
     * at the end; a {@code frames} list may repeat indices (lava uses a ping-pong sequence), so the
     * list position — not the value — is what the tick selects.
     */
    public int frameAtTick(int tick) {
        if (!isAnimated() || this.frames.isEmpty()) return 0;
        int step = Math.max(0, tick) / Math.max(1, this.frameTime);
        int idx = step % this.frames.size();
        return this.frames.get(idx);
    }

    /** Pixels of frame i as an Image view offset (not a copy). */
    public int framePixel(int frame, int x, int y) {
        int fy = this.frameY(frame) * this.frameHeight;
        int fx = this.frameX(frame) * this.frameWidth;
        return this.sheet.pixel(fx + x, fy + y);
    }

    public int frameX(int index) {
        int rowSize = Math.max(1, this.sheet.width / this.frameWidth);
        return index % rowSize;
    }

    public int frameY(int index) {
        int rowSize = Math.max(1, this.sheet.width / this.frameWidth);
        return index / rowSize;
    }

    /** Frame pixels of the currently-displayed frame as a standalone image (for atlas baking we bake frame 0). */
    public Image frameImage(int frame) {
        Image out = new Image(this.frameWidth, this.frameHeight);
        for (int y = 0; y < this.frameHeight; y++) {
            for (int x = 0; x < this.frameWidth; x++) {
                out.setPixel(x, y, this.framePixel(frame, x, y));
            }
        }
        return out;
    }
}
