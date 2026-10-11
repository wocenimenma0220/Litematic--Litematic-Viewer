package io.github.litematicrender;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.render.Colormaps;
import io.github.litematicrender.render.Image;
import io.github.litematicrender.render.RenderSettings;
import io.github.litematicrender.render.SchematicRenderer;
import io.github.litematicrender.render.SpriteAtlas;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;

import java.nio.file.Path;

/** Renders the same scene at two ticks and reports how many pixels differ. */
public final class DiagTick {
    public static void main(String[] args) throws Exception {
        Path schemFile = Path.of(args[0]);
        Path vanilla = Path.of(args[1]);
        int tickA = Integer.parseInt(args[2]);
        int tickB = Integer.parseInt(args[3]);

        LitematicaSchematic schem = LitematicaSchematic.load(schemFile);
        ResourceManager res = new ResourceManager(vanilla);
        BlockRegistry reg = BlockRegistry.load(res);
        schem.materialize(reg);
        Colormaps cm = new Colormaps();
        cm.load(res);
        SpriteAtlas atlas = SpriteAtlas.load(res, "blocks");
        ModelBaker baker = new ModelBaker(res, reg);

        RenderSettings a = new RenderSettings(45f, 30f, 400, 400);
        a.animationTick = tickA;
        Image ia = SchematicRenderer.render(schem, baker, reg, cm, atlas, a);

        RenderSettings b = new RenderSettings(45f, 30f, 400, 400);
        b.animationTick = tickB;
        Image ib = SchematicRenderer.render(schem, baker, reg, cm, atlas, b);

        int diff = 0;
        for (int i = 0; i < ia.pixels().length; i++) {
            int pa = ia.pixels()[i], pb = ib.pixels()[i];
            if (pa != pb) diff++;
        }
        System.out.println("tick " + tickA + " vs " + tickB + ": " + diff + " of "
            + ia.pixels().length + " pixels differ");
        res.close();
    }
}
