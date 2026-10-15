package io.github.litematicrender.schematic;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.render.*;
import io.github.litematicrender.world.BlockRegistry;
import java.nio.file.*;
import java.util.*;

/** Compares real SceneBuilder output for 16- and 32-cell build units. */
public final class BuildGridRegression {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static long median(List<Long> values){values.sort(Long::compare);return values.get(values.size()/2);}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),out=Path.of(args[1]).toAbsolutePath();Files.createDirectories(out);System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        Path fixture=LodRegression.fixture(out.resolve("grid-dense.litematic"),true);
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)) {
            var registry=BlockRegistry.load(resources);var baker=new ModelBaker(resources,registry);var colors=new Colormaps();colors.load(resources);var atlas=SpriteAtlas.load(resources,"blocks");var schematic=LitematicaSchematic.load(fixture);schematic.materialize(registry);
            List<Long> sixteen=new ArrayList<>(),thirtyTwo=new ArrayList<>();StreamMeshExporter.Index reference=null;
            for(int pass=0;pass<4;pass++)for(int unit:(pass&1)==0?new int[]{16,32}:new int[]{32,16}) {
                System.setProperty("litematicrender.mesh.buildUnit",String.valueOf(unit));long start=System.nanoTime();var index=StreamMeshExporter.write(LodRegression.builder(schematic,registry,baker,colors,atlas,false),atlas,out.resolve("grid-"+unit+"-"+pass+".lmesh"));long millis=(System.nanoTime()-start)/1_000_000;
                if(reference==null)reference=index;else {check(index.sections().size()==reference.sections().size(),"build units produced different ownership sections");check(index.quads()==reference.quads(),"build units produced different face counts");check(Arrays.equals(index.bounds(),reference.bounds()),"build units produced different bounds");}
                (unit==16?sixteen:thirtyTwo).add(millis);
            }
            long before=median(sixteen),after=median(thirtyTwo);System.out.printf(Locale.ROOT,"PASS: 16/32-cell outputs match; median %d ms -> %d ms (%.2fx), sections=%d, faces=%d%n",before,after,(double)before/after,reference.sections().size(),reference.quads());
        } finally {System.clearProperty("litematicrender.mesh.buildUnit");}
    }
}
