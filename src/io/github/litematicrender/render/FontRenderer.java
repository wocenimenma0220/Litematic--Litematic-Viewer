package io.github.litematicrender.render;

import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.util.MiniJson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders text with Minecraft's own bitmap font.
 *
 * <p>Reads {@code font/include/default.json}, which lists bitmap providers as
 * (texture, ascent, grid of characters). Each glyph occupies a fixed cell in the sheet, so a
 * character can be turned into an image without any TTF machinery — the same thing the game does
 * before uploading glyphs to its font atlas.
 *
 * <p>Note: the shipped assets have no CJK provider (vanilla downloads Unifont separately), so
 * Chinese text has no glyphs here and is reported by {@link #missingGlyphCount()} rather than
 * being drawn as garbage.
 */
public final class FontRenderer {
    /** One glyph: its pixels and the cell it came from. */
    private record Glyph(Image sheet, int x, int y, int w, int h, int ascent, int advance) {}

    private final Map<Integer, Glyph> glyphs = new HashMap<>();
    private final Map<Integer, Integer> advances = new HashMap<>();
    private int missingGlyphs;
    private int loadedProviders;

    /** Loads the default font from a resource pack. */
    public static FontRenderer load(ResourceManager resources) {
        FontRenderer font = new FontRenderer();
        String[] providers = {"font/include/default.json", "font/default.json"};
        for (String path : providers) {
            byte[] data = resources.getResource(path).orElse(null);
            if (data == null) continue;
            try {
                Map<String, Object> root = MiniJson.parseObject(new String(data, StandardCharsets.UTF_8));
                font.readProviders(resources, root, path);
            } catch (Exception e) {
                System.err.println("[FontRenderer] failed to read " + path + ": " + e);
            }
        }
        return font;
    }

    private void readProviders(ResourceManager resources, Map<String, Object> root, String path) {
        List<Object> list = MiniJson.asArray(root.get("providers"));
        if (list == null) return;
        for (Object o : list) {
            Map<String, Object> p = MiniJson.asObject(o);
            if (p == null) continue;
            String type = MiniJson.asString(p.get("type"), "");
            switch (type) {
                case "bitmap" -> readBitmap(resources, p);
                case "space" -> readSpace(p);
                case "reference" -> {
                    // A reference points at another provider file; follow the ones we ship.
                    String id = MiniJson.asString(p.get("id"), "").replace("minecraft:", "");
                    byte[] data = resources.getResource("font/" + id + ".json").orElse(null);
                    if (data != null) {
                        try {
                            readProviders(resources,
                                MiniJson.parseObject(new String(data, StandardCharsets.UTF_8)), id);
                        } catch (Exception ignored) {
                            // malformed optional provider
                        }
                    }
                }
                default -> { /* ttf/legacy_unicode providers need files we don't ship */ }
            }
        }
    }

    private void readBitmap(ResourceManager resources, Map<String, Object> p) {
        String file = MiniJson.asString(p.get("file"), "");
        String path = file.replace("minecraft:", "").replace("font/", "textures/font/");
        if (!path.endsWith(".png")) path += ".png";
        byte[] png = resources.getResource(path).orElse(null);
        if (png == null) return;
        Image sheet;
        try {
            sheet = Image.read(png);
        } catch (Exception e) {
            return;
        }
        List<Object> rows = MiniJson.asArray(p.get("chars"));
        if (rows == null || rows.isEmpty()) return;
        int ascent = MiniJson.asInt(p.get("ascent"), 7);
        Integer heightOverride = p.get("height") == null ? null : MiniJson.asInt(p.get("height"), 8);

        int cols = 0;
        for (Object r : rows) cols = Math.max(cols, MiniJson.asString(r, "").length());
        if (cols == 0) return;
        int cellW = sheet.width / cols;
        int cellH = sheet.height / rows.size();
        int glyphH = heightOverride != null ? heightOverride : cellH;

        for (int ry = 0; ry < rows.size(); ry++) {
            String chars = MiniJson.asString(rows.get(ry), "");
            for (int cx = 0; cx < chars.length(); cx++) {
                int cp = chars.codePointAt(cx);
                if (chars.charAt(cx) == '\u0000') continue;   // unused cell
                this.glyphs.putIfAbsent(cp, new Glyph(sheet, cx * cellW, ry * cellH,
                    cellW, glyphH, ascent, glyphWidth(sheet, cx * cellW, ry * cellH, cellW, glyphH)));
            }
        }
        this.loadedProviders++;
    }

    /**
     * Vanilla derives a glyph's advance from the rightmost non-transparent column (plus one),
     * which is why proportional text works with a fixed-width sheet.
     */
    private static int glyphWidth(Image sheet, int x0, int y0, int w, int h) {
        int last = -1;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int px = x0 + x, py = y0 + y;
                if (px >= sheet.width || py >= sheet.height) continue;
                if (Image.alpha(sheet.pixel(px, py)) > 0) { last = x; break; }
            }
        }
        return last < 0 ? (w / 2) : last + 1;
    }

    private void readSpace(Map<String, Object> p) {
        List<Object> adv = MiniJson.asArray(p.get("advances"));
        if (adv == null) return;
        for (Object o : adv) {
            Map<String, Object> m = MiniJson.asObject(o);
            if (m == null) continue;
            String ch = MiniJson.asString(m.get("char"), "");
            if (ch.isEmpty()) continue;
            this.advances.put(ch.codePointAt(0), MiniJson.asInt(m.get("advance"), 4));
        }
    }

    public boolean hasGlyph(int codePoint) {
        return this.glyphs.containsKey(codePoint) || this.advances.containsKey(codePoint);
    }

    public int missingGlyphCount() {
        return this.missingGlyphs;
    }

    /** Number of glyphs successfully loaded (diagnostics). */
    public int glyphsLoaded() {
        return this.glyphs.size();
    }

    /** Number of bitmap providers that contributed glyphs (diagnostics). */
    public int providersLoaded() {
        return this.loadedProviders;
    }

    /** True when the loaded providers can draw any of {@code text}. */
    public boolean canRenderAny(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (this.glyphs.containsKey(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * Draws one line of text into a new image (transparent background).
     *
     * @param text   the string to draw
     * @param argb   text colour
     * @param shadow true to add vanilla's drop shadow
     */
    public Image renderLine(String text, int argb, boolean shadow) {
        // Measure first so the canvas is exactly wide enough.
        int width = 0;
        List<Glyph> laid = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Glyph g = this.glyphs.get(cp);
            if (g == null) {
                Integer adv = this.advances.get(cp);
                if (adv != null) {
                    laid.add(null); at.add(width); width += adv;
                } else {
                    this.missingGlyphs++;
                    laid.add(null); at.add(width);
                    width += 5;   // reserve a space so the rest of the line still lines up
                }
                continue;
            }
            laid.add(g); at.add(width);
            width += g.advance() + 1;
        }
        if (width == 0) width = 1;

        int pad = shadow ? 1 : 0;
        Image out = new Image(width + pad, 8 + pad);
        for (int k = 0; k < laid.size(); k++) {
            Glyph g = laid.get(k);
            if (g == null) continue;
            int ox = at.get(k);
            if (shadow) blit(out, g, ox + 1, 2, 0xFF3F3F3F);
            blit(out, g, ox, 1, argb);
        }
        return out;
    }

    private static void blit(Image dst, Glyph g, int dx, int dy, int argb) {
        int tr = (argb >> 16) & 0xFF, tg = (argb >> 8) & 0xFF, tb = argb & 0xFF;
        int ta = (argb >>> 24) & 0xFF;
        for (int y = 0; y < g.h(); y++) {
            for (int x = 0; x < g.w(); x++) {
                int sx = g.x() + x, sy = g.y() + y;
                if (sx >= g.sheet().width || sy >= g.sheet().height) continue;
                int src = g.sheet().pixel(sx, sy);
                int sa = Image.alpha(src);
                if (sa == 0) continue;
                int px = dx + x, py = dy + y;
                if (px < 0 || py < 0 || px >= dst.width || py >= dst.height) continue;
                int a = sa * ta / 255;
                int existing = dst.pixel(px, py);
                // Glyphs are 1-bit; just take the strongest coverage so shadow and text do not
                // multiply into a muddy grey.
                if (Image.alpha(existing) >= a) continue;
                dst.setPixel(px, py, Image.argb(a, tr, tg, tb));
            }
        }
    }
}
