import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.model.*;
import io.github.litematicrender.world.*;
import io.github.litematicrender.schematic.*;
import java.nio.file.*;
import java.net.*;
import java.util.*;

public class WorldOptimizationRegression {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]),file=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        try(var loader=new URLClassLoader(new URL[]{root.resolve("tools/verify/world-optimization/baseline-classes").toUri().toURL()},WorldOptimizationRegression.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                if(name.equals("io.github.litematicrender.render.scene.SceneBuilder")||name.startsWith("io.github.litematicrender.render.scene.SceneBuilder$")) {
                    synchronized(getClassLoadingLock(name)){var result=findLoadedClass(name);if(result==null)result=findClass(name);if(resolve)resolveClass(result);return result;}
                }return super.loadClass(name,resolve);
            }
        };var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)) {
            var registry=BlockRegistry.load(resources);var baker=new ModelBaker(resources,registry);var colors=new Colormaps();colors.load(resources);var atlas=SpriteAtlas.load(resources,"blocks");
            var schematic=LitematicaSchematic.load(file);schematic.materialize(registry);
            var oldType=loader.loadClass("io.github.litematicrender.render.scene.SceneBuilder");
            Object oldBuilder=oldType.getConstructor(BlockRegistry.class,ModelBaker.class,Colormaps.class,SpriteAtlas.class).newInstance(registry,baker,colors,atlas);
            var builder=new SceneBuilder(registry,baker,colors,atlas);
            for(var region:schematic.regions().values()){var p=region.blockOrigin();int[] offset={p.x(),p.y(),p.z()};builder.addRegion(region,offset);oldType.getMethod("addRegion",LitematicaSchematic.Region.class,int[].class).invoke(oldBuilder,region,offset);}
            long start=System.nanoTime();var before=(SceneMesh)oldType.getMethod("build").invoke(oldBuilder);System.out.println("baselineBuildMillis="+(System.nanoTime()-start)/1e6);
            start=System.nanoTime();var after=builder.build();System.out.println("optimizedBuildMillis="+(System.nanoTime()-start)/1e6);
            MeshExporter.write(before,atlas,out.resolve("baseline.bin"));MeshExporter.write(after,atlas,out.resolve("optimized.bin"));atlas.stitched.writePng(out.resolve("atlas.png"));
            System.out.println("baselineTransparent="+before.translucentBsp.faces.size()+" optimizedTransparent="+after.translucentBsp.faces.size()+" sections="+after.visibility.sections.size());
            int totalDifferent=0,largest=0;
            for(int rotation:new int[]{45,135,225,315}) {
                var settings=new RenderSettings(rotation,30,640,640);
                var a=SchematicRenderer.render(schematic,before,colors,atlas,settings);var b=SchematicRenderer.render(schematic,after,colors,atlas,settings);
                a.writePng(out.resolve("baseline-"+rotation+".png"));b.writePng(out.resolve("optimized-"+rotation+".png"));
                int different=0,max=0,large=0;long error=0;
                for(int i=0;i<a.pixels().length;i++)if(a.pixels()[i]!=b.pixels()[i]) {
                    different++;for(int shift:new int[]{0,8,16,24}){int delta=Math.abs(((a.pixels()[i]>>>shift)&255)-((b.pixels()[i]>>>shift)&255));error+=delta;max=Math.max(max,delta);if(delta>3)large++;}
                }
                totalDifferent+=different;largest=Math.max(largest,max);
                System.out.println("view="+rotation+" changed="+different+" maxChannelError="+max+" channelsOver3="+large+" meanChannelError="+(double)error/(640*640*4));
            }
            Files.writeString(out.resolve("geometry-summary.txt"),"baselineTransparent="+before.translucentBsp.faces.size()+" optimizedTransparent="+after.translucentBsp.faces.size()+" pixelDifferences="+totalDifferent+" maxChannelError="+largest);
            if(totalDifferent!=0)throw new AssertionError("optimized subdivision changed reference pixels");
            if(after.translucentBsp.faces.size()>=before.translucentBsp.faces.size())throw new AssertionError("transparent geometry did not shrink");
        }
    }
}
