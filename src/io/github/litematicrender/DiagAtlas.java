package io.github.litematicrender;

import io.github.litematicrender.render.SpriteAtlas;
import io.github.litematicrender.render.SpriteContents;
import io.github.litematicrender.resource.ResourceManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reports atlas packing statistics, to catch sprites being sized wrongly. */
public final class DiagAtlas {
    public static void main(String[] args) throws Exception {
        ResourceManager res = new ResourceManager(Path.of(args[0]));
        SpriteAtlas atlas = SpriteAtlas.load(res, "blocks");
        System.out.println("atlas: " + atlas.stitched.width + "x" + atlas.stitched.height
            + "  (" + (atlas.stitched.width * atlas.stitched.height * 4L / 1024 / 1024) + " MB)");
        System.out.println("sprites: " + atlas.spriteCount());

        // Rebuild the (sprite, packedWidth, packedHeight) list the packer used.
        List<SpriteContents> all = new ArrayList<>();
        for (String id : idsOf(atlas)) {
            SpriteAtlas.Entry e = atlas.get(id).orElse(null);
            if (e != null) all.add(e.contents);
        }
        all.sort((a, b) -> {
            int cmp = Integer.compare(b.frameHeight, a.frameHeight);
            return cmp != 0 ? cmp : a.name.compareTo(b.name);
        });
        System.out.println("tallest packed sprites:");
        long total = 0;
        for (int i = 0; i < Math.min(15, all.size()); i++) {
            SpriteContents s = all.get(i);
            System.out.printf("  %-46s frame=%dx%d img=%dx%d frames=%d animated=%s%n",
                s.name, s.frameWidth, s.frameHeight, s.sheet.width, s.sheet.height,
                s.frameCount, s.isAnimated());
        }
        int animated = 0, nonAnimStrip = 0;
        for (SpriteContents s : all) {
            total += (long) s.frameWidth * s.frameHeight;
            if (s.isAnimated()) animated++;
            else if (s.sheet.height != s.frameHeight) nonAnimStrip++;
        }
        System.out.println("animated sprites: " + animated);
        System.out.println("non-animated whose sheet is taller than its frame: " + nonAnimStrip);
        System.out.println("total sprite area: " + total + " px ("
            + (total * 4 / 1024 / 1024) + " MB)");
        res.close();
    }

    private static List<String> idsOf(SpriteAtlas atlas) {
        List<String> ids = new ArrayList<>();
        for (var f : SpriteAtlas.class.getDeclaredFields()) {
            if (f.getName().equals("entries")) {
                f.setAccessible(true);
                try {
                    @SuppressWarnings("unchecked")
                    var m = (java.util.Map<String, ?>) f.get(atlas);
                    ids.addAll(m.keySet());
                } catch (Exception ignored) {
                }
            }
        }
        return ids;
    }
}
