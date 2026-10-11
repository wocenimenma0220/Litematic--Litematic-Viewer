package io.github.litematicrender;

import io.github.litematicrender.render.SpriteAtlas;
import io.github.litematicrender.render.SpriteContents;
import io.github.litematicrender.resource.ResourceManager;

import java.nio.file.Path;

/** Reports animated sprites and which frame each tick selects. */
public final class DiagAnim {
    public static void main(String[] args) throws Exception {
        ResourceManager res = new ResourceManager(Path.of(args[0]));
        SpriteAtlas atlas = SpriteAtlas.load(res, "blocks");
        System.out.println("bakedTick=" + atlas.bakedTick() + " period=" + atlas.animationPeriodTicks());

        String[] watch = {"minecraft:block/water_still", "minecraft:block/water_flow",
                          "minecraft:block/lava_still", "minecraft:block/fire_0",
                          "minecraft:block/nether_portal"};
        for (String id : watch) {
            SpriteAtlas.Entry e = atlas.get(id).orElse(null);
            if (e == null) { System.out.println(id + " : NOT IN ATLAS"); continue; }
            SpriteContents s = e.contents;
            System.out.printf("%-34s img=%dx%d frame=%dx%d frames=%d frametime=%d animated=%s%n",
                id, s.sheet.width, s.sheet.height, s.frameWidth, s.frameHeight,
                s.frameCount, s.frameTime, s.isAnimated());
            StringBuilder sb = new StringBuilder("   frameAtTick(0..9): ");
            for (int t = 0; t < 10; t++) sb.append(s.frameAtTick(t)).append(' ');
            System.out.println(sb);
        }

        // Does rebaking actually change atlas pixels?
        int before = atlas.rebakeAtTick(0);
        int after = atlas.rebakeAtTick(5);
        System.out.println("rebake tick0 changed=" + before + ", tick5 changed=" + after);
        int again = atlas.rebakeAtTick(5);
        System.out.println("rebake same tick again changed=" + again + " (should be 0)");
        res.close();
    }
}
