import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.model.*;
import io.github.litematicrender.world.*;
import io.github.litematicrender.schematic.*;
import java.nio.file.*;
import java.util.*;

/** Investigation only: instrumented SceneBuilder lives outside the shipping classes. */
public class WorldRenderProfile {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]),file=Path.of(args[1]),output=Path.of(args[2]);Files.createDirectories(output);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        long start=System.nanoTime();
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)) {
            var registry=BlockRegistry.load(resources);var baker=new ModelBaker(resources,registry);var colors=new Colormaps();colors.load(resources);var atlas=SpriteAtlas.load(resources,"blocks");
            System.out.println("resourcesMillis="+(System.nanoTime()-start)/1e6);start=System.nanoTime();
            var schematic=LitematicaSchematic.load(file);schematic.materialize(registry);
            System.out.println("parseMillis="+(System.nanoTime()-start)/1e6);
            var builder=new SceneBuilder(registry,baker,colors,atlas);
            for(var region:schematic.regions().values()){var origin=region.blockOrigin();builder.addRegion(region,new int[]{origin.x(),origin.y(),origin.z()});}
            start=System.nanoTime();var mesh=builder.build();
            System.out.println("buildMillis="+(System.nanoTime()-start)/1e6);
            System.out.println("stages="+builder.getClass().getField("profile").get(builder));
            System.out.println("quads raw="+mesh.quadCount+" solid="+mesh.solid.size()+" cutout="+mesh.cutout.size()+" blending="+(mesh.portal.size()+mesh.fluid.size()+mesh.translucent.size())+" subdivided="+(mesh.portalSorted.size()+mesh.fluidSorted.size()+mesh.translucentSorted.size())+" bsp="+mesh.translucentBsp.faces.size()+" nodes="+mesh.translucentBsp.nodes.size()+" fallback="+mesh.translucentBsp.fallbackLeaves);
            start=System.nanoTime();MeshExporter.write(mesh,atlas,output.resolve("mesh.bin"));atlas.stitched.writePng(output.resolve("atlas.png"));
            System.out.println("exportMillis="+(System.nanoTime()-start)/1e6+" bytes="+Files.size(output.resolve("mesh.bin")));
            var settings=new RenderSettings(45,30,512,512);start=System.nanoTime();
            SchematicRenderer.render(schematic,mesh,colors,atlas,settings).writePng(output.resolve("preview.png"));
            System.out.println("cpuRenderMillis="+(System.nanoTime()-start)/1e6);
        }
    }
}
