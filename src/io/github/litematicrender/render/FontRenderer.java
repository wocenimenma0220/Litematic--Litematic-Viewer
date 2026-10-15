package io.github.litematicrender.render;

import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.util.MiniJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Minecraft bitmap and Unihex fonts. Output is oversampled 2x to preserve Chinese glyph detail. */
public final class FontRenderer {
    private record Glyph(Image sheet, int x, int y, int w, int h, float scale, float advance) {}
    private final Map<Integer, Glyph> glyphs = new HashMap<>();
    private final Map<Integer, Float> spaces = new HashMap<>();
    private final Set<String> visited = new HashSet<>();
    private int missingGlyphs, providers;
    private record SignKey(List<String> lines,int color,boolean glow,boolean hanging) {}
    public record SignPanel(String spriteId,Image image) {}
    private final Map<SignKey,SignPanel> signPanels = new LinkedHashMap<>(256,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<SignKey,SignPanel> item) { return size()>256; }
    };

    /** Resource-session cache. Coordinates and lighting do not change the text pixels. */
    public synchronized SignPanel signPanel(List<String> lines,int color,boolean glow,boolean hanging) {
        SignKey key=new SignKey(List.copyOf(lines.subList(0,Math.min(4,lines.size()))),color,glow,hanging);
        SignPanel cached=signPanels.get(key);if(cached!=null)return cached;
        int dark=color==0&&glow?0xF0EBCC:scaleColor(color,0.4f);
        int maxWidth=hanging?60:90,lineHeight=hanging?9:10;
        Image panel=new Image((maxWidth+2)*2,(lineHeight*4+2)*2);
        for(int i=0;i<key.lines.size();i++) {
            Image line=renderLine(key.lines.get(i),0xFF000000|(glow?color:dark),maxWidth,
                glow?0xFF000000|dark:null);
            int dx=(panel.width-line.width)/2,dy=i*lineHeight*2+(glow?0:2);
            for(int py=0;py<line.height;py++)for(int px=0;px<line.width;px++) {
                int tx=dx+px,ty=dy+py,argb=line.pixel(px,py);
                if(tx>=0&&ty>=0&&tx<panel.width&&ty<panel.height&&Image.alpha(argb)!=0)panel.setPixel(tx,ty,argb);
            }
        }
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            var bytes=java.nio.ByteBuffer.allocate(8+panel.pixels().length*4);
            bytes.putInt(panel.width).putInt(panel.height);
            for(int pixel:panel.pixels())bytes.putInt(pixel);
            String id="litematicrender:sign/"+HexFormat.of().formatHex(digest.digest(bytes.array()));
            SignPanel result=new SignPanel(id,panel);signPanels.put(key,result);return result;
        } catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static int scaleColor(int rgb,float scale) {
        return (int)(((rgb>>16)&255)*scale)<<16 | (int)(((rgb>>8)&255)*scale)<<8 | (int)((rgb&255)*scale);
    }

    public static FontRenderer load(ResourceManager resources) {
        FontRenderer font = new FontRenderer();
        font.readFont(resources, "minecraft:default");
        return font;
    }

    private void readFont(ResourceManager resources, String id) {
        if (!visited.add(id)) return;
        byte[] data = resource(resources, id, "font/", ".json");
        if (data == null) return;
        try {
            var root = MiniJson.parseObject(new String(data, StandardCharsets.UTF_8));
            var list = MiniJson.asArray(root.get("providers"));
            if (list == null) return;
            for (Object item : list) {
                var p = MiniJson.asObject(item);
                if (p == null) continue;
                var filter = MiniJson.asObject(p.get("filter"));
                if (filter != null && Boolean.TRUE.equals(filter.get("jp"))) continue;
                switch (MiniJson.asString(p.get("type"), "")) {
                    case "reference", "minecraft:reference" -> readFont(resources, MiniJson.asString(p.get("id"), ""));
                    case "bitmap", "minecraft:bitmap" -> readBitmap(resources, p);
                    case "space", "minecraft:space" -> {
                        var advances = MiniJson.asObject(p.get("advances"));
                        if (advances != null) advances.forEach((s, n) -> {
                            if (!s.isEmpty() && n instanceof Number value) spaces.putIfAbsent(s.codePointAt(0), value.floatValue());
                        });
                    }
                    case "unihex", "minecraft:unihex" -> readUnihex(resources, p);
                    default -> { }
                }
            }
        } catch (Exception e) {
            System.err.println("[FontRenderer] Cannot load " + id + ": " + e.getMessage());
        }
    }

    private static byte[] resource(ResourceManager resources, String id, String prefix, String suffix) {
        String[] parts = id.split(":", 2);
        return resources.getResource(parts.length == 2 ? parts[0] : "minecraft",
            prefix + parts[parts.length - 1] + suffix).orElse(null);
    }

    private void readBitmap(ResourceManager resources, Map<String, Object> p) throws IOException {
        byte[] bytes = resource(resources, MiniJson.asString(p.get("file"), ""), "textures/", "");
        if (bytes == null) return;
        Image sheet = Image.read(bytes);
        var rows = MiniJson.asArray(p.get("chars"));
        if (rows == null || rows.isEmpty()) return;
        int cols = rows.stream().mapToInt(r -> r.toString().codePointCount(0, r.toString().length())).max().orElse(0);
        if (cols == 0) return;
        int cellW = sheet.width / cols, cellH = sheet.height / rows.size();
        float scale = MiniJson.asInt(p.get("height"), 8) / (float)cellH;
        for (int y = 0; y < rows.size(); y++) {
            int[] codepoints = rows.get(y).toString().codePoints().toArray();
            for (int x = 0; x < codepoints.length; x++) {
                int cp = codepoints[x];
                if (!supported(cp)) continue;
                int last = -1;
                for (int gx = 0; gx < cellW; gx++) for (int gy = 0; gy < cellH; gy++) {
                    if (Image.alpha(sheet.pixel(x * cellW + gx, y * cellH + gy)) > 0) last = Math.max(last, gx);
                }
                int inkW = last + 1;
                glyphs.putIfAbsent(cp, new Glyph(sheet, x * cellW, y * cellH, inkW, cellH, scale, inkW * scale + 1));
            }
        }
        providers++;
    }

    private void readUnihex(ResourceManager resources, Map<String, Object> p) throws IOException {
        byte[] zip = resource(resources, MiniJson.asString(p.get("hex_file"), ""), "", "");
        if (zip == null) throw new IOException("Missing bundled Unihex file");
        var overrides = MiniJson.asArray(p.get("size_overrides"));
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (!entry.getName().endsWith(".hex")) continue;
                var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
                String line;
                while ((line = reader.readLine()) != null) {
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    int cp = Integer.parseInt(line.substring(0, colon), 16);
                    if (!supported(cp) || glyphs.containsKey(cp) || spaces.containsKey(cp)) continue;
                    String hex = line.substring(colon + 1).trim();
                    int digits = hex.length() / 16, width = digits * 4;
                    if (digits < 2 || digits > 8 || hex.length() != digits * 16) continue;
                    long[] rows = new long[16];
                    int left = width, right = -1;
                    for (int y = 0; y < 16; y++) {
                        rows[y] = Long.parseLong(hex.substring(y * digits, (y + 1) * digits), 16);
                        for (int x = 0; x < width; x++) if ((rows[y] & (1L << (width - 1 - x))) != 0) {
                            left = Math.min(left, x); right = Math.max(right, x);
                        }
                    }
                    if (right < left) { left = 0; right = width - 1; }
                    if (overrides != null) for (Object o : overrides) {
                        var range = MiniJson.asObject(o);
                        if (range == null) continue;
                        String from = MiniJson.asString(range.get("from"), ""), to = MiniJson.asString(range.get("to"), "");
                        if (!from.isEmpty() && !to.isEmpty() && cp >= from.codePointAt(0) && cp <= to.codePointAt(0)) {
                            left = MiniJson.asInt(range.get("left"), left); right = MiniJson.asInt(range.get("right"), right);
                        }
                    }
                    Image image = new Image(right - left + 1, 16);
                    for (int y = 0; y < 16; y++) for (int x = left; x <= right; x++) {
                        if (x >= 0 && x < width && (rows[y] & (1L << (width - 1 - x))) != 0) image.setPixel(x - left, y, 0xFFFFFFFF);
                    }
                    glyphs.put(cp, new Glyph(image, 0, 0, image.width, 16, 0.5f, image.width / 2f + 1));
                }
            }
        }
        providers++;
    }

    private static boolean supported(int cp) {
        return cp >= 32 && cp <= 126 || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
            || cp >= 0x3000 && cp <= 0x303F || cp >= 0xFF01 && cp <= 0xFF60;
    }
    public boolean hasGlyph(int cp) { return supported(cp) && (glyphs.containsKey(cp) || spaces.containsKey(cp)); }
    public int glyphsLoaded() { return glyphs.size(); }
    public int providersLoaded() { return providers; }
    public int missingGlyphCount() { return missingGlyphs; }
    public boolean canRenderAny(String text) { return text.codePoints().anyMatch(cp -> supported(cp) && glyphs.containsKey(cp)); }

    /** Draws a sign line at 2x resolution, clipping at the game's logical pixel limit. */
    public Image renderLine(String text, int argb, int maxWidth, Integer outline) {
        List<Glyph> laid = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        float width = 0;
        for (int cp : text.codePoints().toArray()) {
            if (cp == '\n' || cp == '\r') break;
            Glyph g = supported(cp) ? glyphs.get(cp) : null;
            float advance = spaces.getOrDefault(cp, g == null ? 5f : g.advance);
            if (width + advance > maxWidth) break;
            if (g == null && supported(cp) && !spaces.containsKey(cp)) missingGlyphs++;
            laid.add(g); positions.add(Math.round(width * 2)); width += advance;
        }
        int pad = outline == null ? 0 : 2;
        Image out = new Image(Math.max(1, Math.round(width * 2)) + pad * 2, 16 + pad * 2);
        if (outline != null) for (int oy = -2; oy <= 2; oy += 2) for (int ox = -2; ox <= 2; ox += 2) {
            if (ox == 0 && oy == 0) continue;
            for (int i = 0; i < laid.size(); i++) if (laid.get(i) != null) blit(out, laid.get(i), positions.get(i) + pad + ox, pad + oy, outline);
        }
        for (int i = 0; i < laid.size(); i++) if (laid.get(i) != null) blit(out, laid.get(i), positions.get(i) + pad, pad, argb);
        return out;
    }

    private static void blit(Image dst, Glyph glyph, int dx, int dy, int argb) {
        int w = Math.round(glyph.w * glyph.scale * 2), h = Math.round(glyph.h * glyph.scale * 2);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int sx = glyph.x + Math.min(glyph.w - 1, (int)(x / (glyph.scale * 2)));
            int sy = glyph.y + Math.min(glyph.h - 1, (int)(y / (glyph.scale * 2)));
            if (Image.alpha(glyph.sheet.pixel(sx, sy)) == 0) continue;
            int px = dx + x, py = dy + y;
            if (px >= 0 && py >= 0 && px < dst.width && py < dst.height) dst.setPixel(px, py, argb);
        }
    }
}
