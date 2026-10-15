package io.github.litematicrender.render;

import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.util.MiniJson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Sprite catalog: loads all sprites referenced by atlas json (blocks.json / items.json),
 * parses .mcmeta animation metadata, and provides UV mapping.
 * Mirrors the data parts of TextureAtlas/SpriteLoader/Stitcher, simplified:
 * we keep sprites independent (no GPU atlas needed) — UV lookups go directly to the sprite.
 */
public final class SpriteAtlas {
    /** Per-sprite atlas info: the sprite and its UV rect inside a virtual [0,1] space. */
    public static final class Entry {
        public final SpriteContents contents;
        // UV of the sprite inside the stitched atlas
        public final float u0, v0, u1, v1;
        /** Pixel origin of the sprite in the stitched atlas (needed to re-blit animation frames). */
        public final int pixelX, pixelY;

        Entry(SpriteContents contents, float u0, float v0, float u1, float v1, int pixelX, int pixelY) {
            this.contents = contents;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.pixelX = pixelX;
            this.pixelY = pixelY;
        }

        /** Full-frame UVs of frame 0 within the atlas. */
        public float u(float frac) {
            return this.u0 + (this.u1 - this.u0) * frac;
        }

        public float v(float frac) {
            return this.v0 + (this.v1 - this.v0) * frac;
        }
    }

    public final String id;   // "blocks" or "items"
    private final Map<String, SpriteContents> sprites = new HashMap<>();
    private final Map<String, SpriteContents> baseSprites = new HashMap<>();
    private final Map<String, int[]> generatedPixels = new HashMap<>();
    private final Map<String, Long> generatedSizes = new HashMap<>();
    private final Map<String, Entry> entries = new HashMap<>();
    public Image stitched;
    public final int padding;
    /** Independent atlas for color-only previews; never mutates the complete scene atlas. */
    public static SpriteAtlas overview() {
        Image white=new Image(1,1);white.pixels()[0]=0xffffffff;
        return new SpriteAtlas("overview",Map.of("litematicrender:overview",new SpriteContents("litematicrender:overview",1,1,white,1,1,List.of(0),false)),0);
    }

    /** Restitch scene-generated text and skins so CPU export and WebGL use the same pixels. */
    public void replaceGeneratedSprites(Map<String, Image> images) {
        boolean identical = images.size() == this.generatedPixels.size();
        if (identical) for (var item : images.entrySet()) {
            Image image = item.getValue();
            long size = ((long) image.width << 32) | image.height;
            if (!Long.valueOf(size).equals(this.generatedSizes.get(item.getKey()))
                || !java.util.Arrays.equals(image.pixels(), this.generatedPixels.get(item.getKey()))) {
                identical = false; break;
            }
        }
        if (identical) return;
        Map<String, SpriteContents> combined = new HashMap<>(this.baseSprites);
        images.forEach((id, image) -> combined.put(id,
            new SpriteContents(id, image.width, image.height, image, 1, 1, List.of(0), false)));
        SpriteAtlas rebuilt = new SpriteAtlas(this.id, combined, this.padding);
        this.sprites.clear(); this.sprites.putAll(rebuilt.sprites);
        this.entries.clear(); this.entries.putAll(rebuilt.entries);
        this.stitched = rebuilt.stitched;
        this.generatedPixels.clear(); this.generatedSizes.clear();
        images.forEach((name,image) -> {
            this.generatedPixels.put(name,image.pixels().clone());
            this.generatedSizes.put(name,((long)image.width << 32) | image.height);
        });
        this.regionTransparency.clear();
        this.bakedTick = 0;
        this.sprites.values().forEach(sprite -> sprite.lastBakedFrame = sprite.frameAtTick(0));
    }
    /** Animation tick currently baked into {@link #stitched}. */
    private int bakedTick;

    private SpriteAtlas(String id, Map<String, SpriteContents> loaded, int padding) {
        this.id = id;
        this.padding = padding;
        this.baseSprites.putAll(loaded);
        // stitch: simple shelf packing, sorted by height desc (vanilla-ish greedy)
        List<SpriteContents> sorted = new ArrayList<>(loaded.values());
        sorted.sort((a, b) -> {
            int cmp = Integer.compare(b.frameHeight + 2 * padding, a.frameHeight + 2 * padding);
            return cmp != 0 ? cmp : a.name.compareTo(b.name);
        });
        int pad = padding;
        // choose a near-square atlas: total area -> width, clamp to max texture size 4096
        long totalArea = 0;
        int maxDim = 0;
        for (SpriteContents s : sorted) {
            totalArea += (long) (s.frameWidth + 2 * pad) * (s.frameHeight + 2 * pad);
            maxDim = Math.max(maxDim, s.frameWidth + 2 * pad);
        }
        int estWidth = (int) Math.max(maxDim, Math.ceil(Math.sqrt(totalArea * 1.15)));
        int width = Math.min(4096, roundUpPow2(Math.max(estWidth, 16)));

        // shelf packing passes (identical layout both passes)
        int[] layout = packShelves(sorted, width, pad);
        int height = roundUpPow2(Math.max(layout[0], 16));
        this.stitched = new Image(width, height);
        int cx = 0, cy = 0, crow = 0;
        for (SpriteContents s : sorted) {
            int w = s.frameWidth + 2 * pad, h = s.frameHeight + 2 * pad;
            if (cx + w > width && cx > 0) {
                cy += crow;
                cx = 0;
                crow = 0;
            }
            int px = cx + pad, py = cy + pad;
            // Tick zero follows metadata order: fire starts at source frame 16, not frame 0.
            int firstFrame=s.frameAtTick(0);
            s.sheet.blitTo(this.stitched,s.frameX(firstFrame)*s.frameWidth,s.frameY(firstFrame)*s.frameHeight,
                px,py,s.frameWidth,s.frameHeight);
            extrude(px,py,s.frameWidth,s.frameHeight);
            s.lastBakedFrame=firstFrame;
            float u0 = (float) px / width;
            float v0 = (float) py / height;
            float u1 = (float) (px + s.frameWidth) / width;
            float v1 = (float) (py + s.frameHeight) / height;
            this.sprites.put(s.name, s);
            this.entries.put(s.name, new Entry(s, u0, v0, u1, v1, px, py));
            cx += w;
            crow = Math.max(crow, h);
        }
        this.bakedTick = 0;
    }

    /**
     * Re-blits the animated sprites for a different game tick, in place.
     *
     * <p>Layout and UVs never change, so this is cheap and keeps every previously built quad valid;
     * only the pixels of animated sprites move. Used to produce a GIF: render the same scene at
     * several ticks and the frames differ only where something animates.
     *
     * @return the number of sprites whose pixels changed
     */
    public int rebakeAtTick(int tick) {
        if (tick == this.bakedTick) return 0;
        int changed = 0;
        for (Map.Entry<String, Entry> e : this.entries.entrySet()) {
            SpriteContents s = e.getValue().contents;
            if (!s.isAnimated()) continue;
            int frame = s.frameAtTick(tick);
            if (frame == s.lastBakedFrame) continue;
            s.lastBakedFrame = frame;
            int px = e.getValue().pixelX;
            int py = e.getValue().pixelY;
            for (int y = 0; y < s.frameHeight; y++) {
                for (int x = 0; x < s.frameWidth; x++) {
                    this.stitched.setPixel(px + x, py + y, s.framePixel(frame, x, y));
                }
            }
            extrude(px,py,s.frameWidth,s.frameHeight);
            changed++;
        }
        this.bakedTick = tick;
        return changed;
    }

    /** MSAA extrapolates UVs just past edges; repeat edge texels into the gutter so
     * opaque walls stay closed and adjacent atlas sprites cannot bleed into them. */
    private void extrude(int x,int y,int width,int height) {
        for(int dy=-padding;dy<height+padding;dy++)for(int dx=-padding;dx<width+padding;dx++) {
            if(dx>=0&&dx<width&&dy>=0&&dy<height)continue;
            stitched.setPixel(x+dx,y+dy,stitched.pixel(x+Math.max(0,Math.min(width-1,dx)),y+Math.max(0,Math.min(height-1,dy))));
        }
    }

    /** The tick whose animation frame is currently baked into {@link #stitched}. */
    public int bakedTick() {
        return this.bakedTick;
    }

    public record AnimationCycle(int frames, boolean complete) {}
    /** Choose a common cycle of only the textures actually used by this scene. */
    public AnimationCycle animationCycle(io.github.litematicrender.render.scene.SceneMesh mesh, int limit) {
        int maximum=Math.max(1,limit);
        long period=1;
        java.util.Set<String> used=new java.util.HashSet<>();
        for(var layer:List.of(mesh.solid,mesh.cutout,mesh.portal,mesh.fluid,mesh.translucent))
            for(var quad:layer)if(quad.spriteId!=null)used.add(quad.spriteId);
        for(String id:used) {
            SpriteContents sprite=this.sprites.get(id);
            if(sprite==null || !sprite.isAnimated())continue;
            long duration=(long)sprite.frames.size()*Math.max(1,sprite.frameTime);
            long a=period,b=duration;
            while(b!=0){long rest=a%b;a=b;b=rest;}
            long common=period/a;
            if(common>maximum/duration)return new AnimationCycle(maximum,false);
            period=common*duration;
        }
        return new AnimationCycle((int)period,true);
    }

    /** Returns [totalHeight] for shelf packing into the given width. */
    private static int[] packShelves(List<SpriteContents> sorted, int width, int pad) {
        int x = 0, y = 0, rowH = 0;
        for (SpriteContents s : sorted) {
            int w = s.frameWidth + 2 * pad, h = s.frameHeight + 2 * pad;
            if (x + w > width && x > 0) {
                y += rowH;
                x = 0;
                rowH = 0;
            }
            x += w;
            rowH = Math.max(rowH, h);
        }
        return new int[]{y + rowH};
    }

    private static int roundUpPow2(int v) {
        int p = 16;
        while (p < v) p <<= 1;
        return p;
    }

    public Optional<Entry> get(String spriteId) {
        return Optional.ofNullable(this.entries.get(spriteId));
    }

    /**
     * The transparency of the sprite region one face samples — vanilla
     * {@code FaceBakery.computeMaterialTransparency}, which is what
     * {@code ChunkSectionLayer.byTransparency} is ultimately fed.
     *
     * <p>The region matters, not just the sprite. A sheet can be transparent around the edges and
     * opaque where a particular face's UV lands, and vanilla scores that face as solid. Judging by
     * the whole sprite instead pushes it into the cutout bucket, where it stops writing depth like
     * solid geometry and becomes subject to the terrain shader's alpha cutoff.
     *
     * <p>Results are memoised: a schematic has far fewer distinct (sprite, UV rectangle) pairs than
     * quads, and the region test reads every texel it covers.
     *
     * @param forceTranslucent the material's {@code "force_translucent": true}
     */
    public Transparency transparencyOf(String spriteId, float u0, float v0, float u1, float v1,
                                       float u2, float v2, float u3, float v3,
                                       boolean forceTranslucent) {
        // Vanilla short-circuits on the material flag before it ever looks at the pixels.
        if (forceTranslucent) return Transparency.TRANSLUCENT;
        Entry e = spriteId == null ? null : this.entries.get(spriteId);
        if (e == null) return Transparency.NONE;

        float minU = Math.min(Math.min(u0, u1), Math.min(u2, u3));
        float minV = Math.min(Math.min(v0, v1), Math.min(v2, v3));
        float maxU = Math.max(Math.max(u0, u1), Math.max(u2, u3));
        float maxV = Math.max(Math.max(v0, v1), Math.max(v2, v3));

        // Whole-frame fast path: the overwhelmingly common case, already cached on the contents.
        if (minU <= 0f && minV <= 0f && maxU >= 1f && maxV >= 1f) {
            return Transparency.of(e.contents.hasTransparent, e.contents.hasTranslucent);
        }
        // Quantise so near-identical rectangles share a cache entry; 1/64 of a sprite is far below
        // the precision any UV range in a model JSON expresses.
        String key = spriteId + '|' + Math.round(minU * 64) + ',' + Math.round(minV * 64) + ','
            + Math.round(maxU * 64) + ',' + Math.round(maxV * 64);
        return this.regionTransparency.computeIfAbsent(key,
            k -> e.contents.transparencyIn(minU, minV, maxU, maxV));
    }

    /** Memo for {@link #transparencyOf}; keyed by sprite id plus the quantised UV rectangle. */
    private final Map<String, Transparency> regionTransparency = new java.util.concurrent.ConcurrentHashMap<>();

    public SpriteContents getOrMissing(String spriteId) {
        SpriteContents s = this.sprites.get(spriteId);
        return s != null ? s : missingContents();
    }

    public Entry getOrMissingEntry(String spriteId) {
        Entry e = this.entries.get(spriteId);
        if (e != null) return e;
        RenderDiagnostics.note("sprite not in atlas", spriteId);
        return this.entries.get("minecraft:missingno");
    }

    private static SpriteContents missingContents() {
        return new SpriteContents("minecraft:missingno", 16, 16, Image.missing(), 1, 1, List.of(0), false);
    }

    public int spriteCount() {
        return this.sprites.size();
    }

    // ------------------------------------------------------------------
    // Loading

    /**
     * Loads an atlas by merging every atlas json in the resource stack
     * (like {@code assets/minecraft/atlases/blocks.json}).
     *
     * <p>The json files are <em>combined</em>, not overridden: vanilla's atlas lists the
     * {@code block} directory, and a resource pack that adds, say, a {@code potion} directory
     * contributes that on top. Taking only the highest-priority file would drop vanilla's own
     * sources and collapse the atlas to a handful of sprites. Supported source types: directory
     * (source+prefix), single (resource).
     */
    public static SpriteAtlas load(ResourceManager resources, String atlasId) {
        Map<String, SpriteContents> loaded = new HashMap<>();
        String defPath = "atlases/" + atlasId + ".json";
        List<Object> sources = new ArrayList<>();
        // getResourceStack is highest-priority first; the order of sources within the merged list
        // does not matter because packing is decided by sprite size, not by source order.
        for (byte[] data : resources.getResourceStack("minecraft", defPath)) {
            try {
                Map<String, Object> root = MiniJson.parseObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
                List<Object> arr = MiniJson.asArray(root.get("sources"));
                if (arr != null) sources.addAll(arr);
            } catch (Exception e) {
                System.err.println("[SpriteAtlas] bad atlas json: " + e);
            }
        }

        for (Object srcObj : sources) {
            Map<String, Object> src = MiniJson.asObject(srcObj);
            if (src == null) continue;
            String type = MiniJson.asString(src.get("type"), "");
            switch (type) {
                case "minecraft:directory" -> {
                    String sourceDir = MiniJson.asString(src.get("source"), "");
                    String prefix = MiniJson.asString(src.get("prefix"), sourceDir.isEmpty() ? "" : sourceDir + "/");
                    for (String[] pair : resources.listResources("textures/" + sourceDir, ".png")) {
                        String ns = pair[0];
                        String rel = pair[1];
                        // rel is "textures/<sourceDir>/<file>.png" (relative to namespace)
                        String relNoExt = rel.substring(0, rel.length() - 4);
                        String spriteId = ns + ":" + prefix + relNoExt.substring(("textures/" + sourceDir + "/").length());
                        loadSprite(resources, loaded, ns, relNoExt + ".png", spriteId);
                    }
                }
                case "minecraft:single" -> {
                    String resourceId = MiniJson.asString(src.get("resource"), "");
                    // resource is an Identifier like "minecraft:entity/bell/bell_body"
                    String ns = "minecraft";
                    String path = resourceId;
                    int colon = resourceId.indexOf(':');
                    if (colon >= 0) {
                        ns = resourceId.substring(0, colon);
                        path = resourceId.substring(colon + 1);
                    }
                    String spriteOverride = MiniJson.asString(src.get("sprite"), null);
                    String spriteId = spriteOverride != null ? spriteOverride : resourceId;
                    loadSprite(resources, loaded, ns, "textures/" + path + ".png", spriteId);
                }
                default -> { /* filter/unstitch/paletted_permutations: not needed for vanilla blocks atlas */ }
            }
        }

        // always register missingno
        loaded.putIfAbsent("minecraft:missingno", missingContents());
        return new SpriteAtlas(atlasId, loaded, 1); // padding 1 like vanilla mip=0..1; use 1 to avoid bleed
    }

    private static void loadSprite(ResourceManager resources, Map<String, SpriteContents> loaded,
                                   String ns, String texPath, String spriteId) {
        byte[] png = resources.getResource(ns, texPath).orElse(null);
        if (png == null) return;
        try {
            Image img = Image.read(png);
            int frameTime = 1;
            List<Integer> frames = null;
            boolean interpolate = false;
            // Vanilla AnimationMetadataSection.calculateFrameSize: explicit "width"/"height" win,
            // otherwise the frame is the largest square that fits: min(spriteWidth, spriteHeight).
            //
            // This must be applied to *every* animated sprite, not just when the image fails to
            // divide evenly. water_flow.png is 32x1024 with no size metadata, so the frame is
            // 32x32 and there are 32 of them stacked vertically; using the image dimensions
            // (32x1024) instead made the atlas hold the whole strip as a single "frame", so every
            // UV lookup blended all 32 animation frames together.
            int fw = Math.min(img.width, img.height);
            int fh = fw;
            boolean animated = false;

            byte[] mcmeta = resources.getResource(ns, texPath + ".mcmeta").orElse(null);
            if (mcmeta != null) {
                try {
                    Map<String, Object> root = MiniJson.parseObject(new String(mcmeta, java.nio.charset.StandardCharsets.UTF_8));
                    Map<String, Object> anim = MiniJson.asObject(root.get("animation"));
                    if (anim != null) {
                        animated = true;
                        frameTime = Math.max(1, MiniJson.asInt(anim.get("frametime"), 1));
                        interpolate = MiniJson.asBool(anim.get("interpolate"), false);
                        // The metadata keys are "width"/"height".
                        int fwOverride = MiniJson.asInt(anim.get("width"), 0);
                        int fhOverride = MiniJson.asInt(anim.get("height"), 0);
                        if (fwOverride > 0 && fhOverride > 0) {
                            fw = fwOverride;
                            fh = fhOverride;
                        } else if (fwOverride > 0) {
                            fw = fwOverride;
                            fh = img.height;
                        } else if (fhOverride > 0) {
                            fw = img.width;
                            fh = fhOverride;
                        }
                        if (fw <= 0 || fh <= 0 || img.width % fw != 0 || img.height % fh != 0) {
                            int min = Math.min(img.width, img.height);
                            fw = fh = min;
                        }
                        List<Object> frameArr = MiniJson.asArray(anim.get("frames"));
                        if (frameArr != null && !frameArr.isEmpty()) {
                            frames = new ArrayList<>();
                            for (Object f : frameArr) {
                                if (f instanceof Number n) frames.add(n.intValue());
                                // frame objects with time: not needed for static render
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (!animated) {
                // Not animated: the whole image is the frame.
                fw = img.width;
                fh = img.height;
            }
            // Stained glass keeps its authored alpha. An earlier attempt clamped it to the pane's
            // 90% to make blocks and panes read alike, but that made the blocks stop transmitting
            // light — glass has to stay see-through. The pane/block difference is real texture
            // data, and matching the two by making glass opaque is the wrong direction.
            // Frame count is rows x columns, matching SpriteContents.createAnimatedTexture.
            int frameCount = (img.height / fh) * (img.width / fw);
            if (frames == null) {
                frames = new ArrayList<>();
                for (int i = 0; i < frameCount; i++) frames.add(i);
            }
            loaded.putIfAbsent(spriteId, new SpriteContents(spriteId, fw, fh, img, Math.max(1, frameCount), frameTime, frames, interpolate));
        } catch (Exception ignored) {}
    }
}
