import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.resource.*;
import io.github.litematicrender.model.*;
import io.github.litematicrender.schematic.*;
import io.github.litematicrender.world.*;
import java.nio.file.*;
import java.util.*;

/** Measures the proposed GIF scene-cache path without changing the application's renderer. */
public class SceneCacheBenchmark {
    static SceneMesh build(LitematicaSchematic schematic,BlockRegistry registry,ModelBaker baker,Colormaps colors,SpriteAtlas atlas) {
        var builder=new SceneBuilder(registry,baker,colors,atlas);
        for (var region:schematic.regions().values()) {
            var origin=region.blockOrigin();
            builder.addRegion(region,new int[]{origin.x(),origin.y(),origin.z()});
        }
        return builder.build();
    }
    public static void main(String[] args) throws Exception {
        Path home=Path.of(args[0]).toAbsolutePath();
        System.setProperty("litematicrender.home",home.toString());
        System.setProperty("litematicrender.skins.offline","true");
        try (var resources=CliRenderer.openResources(home.resolve("assets/vanilla"),home)) {
            var registry=BlockRegistry.load(resources); var baker=new ModelBaker(resources,registry);
            var colors=new Colormaps(); colors.load(resources); var atlas=SpriteAtlas.load(resources,"blocks");
            var schematic=LitematicaSchematic.load(Path.of(args[1])); schematic.materialize(registry);
            var settings=new RenderSettings(135,30,256,256);
            var warm=build(schematic,registry,baker,colors,atlas);
            SchematicRenderer.render(schematic,warm,colors,atlas,settings);
            int frames=3; int[][] original=new int[frames][];
            long start=System.nanoTime();
            for (int i=0;i<frames;i++) {
                settings.animationTick=i;
                original[i]=SchematicRenderer.render(schematic,baker,registry,colors,atlas,settings).pixels().clone();
            }
            long rebuildMillis=(System.nanoTime()-start)/1_000_000;
            start=System.nanoTime();
            var mesh=build(schematic,registry,baker,colors,atlas);
            for (int i=0;i<frames;i++) {
                settings.animationTick=i;
                var image=SchematicRenderer.render(schematic,mesh,colors,atlas,settings);
                if (!Arrays.equals(original[i],image.pixels())) throw new AssertionError("Cached animation differs at tick "+i);
            }
            long cachedMillis=(System.nanoTime()-start)/1_000_000;
            String report="{\"frames\":"+frames+",\"size\":256,\"rebuildMillis\":"+rebuildMillis+",\"cachedMillis\":"+cachedMillis+",\"pixelsEqual\":true}";
            Path output=Path.of(args[2]); Files.createDirectories(output.toAbsolutePath().getParent()); Files.writeString(output,report);
            System.out.println(report);
        }
    }
}
