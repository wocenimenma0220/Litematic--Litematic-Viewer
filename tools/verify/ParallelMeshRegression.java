package io.github.litematicrender.schematic;

import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.model.*;
import io.github.litematicrender.world.*;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exact serial/parallel files plus bounded publication, worker failures and cancellation. */
public final class ParallelMeshRegression {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static boolean workersAlive(){return Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().startsWith("schematic-section-"));}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),out=Path.of(args[1]).toAbsolutePath();Files.createDirectories(out);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        Path base=LodRegression.fixture(out.resolve("base.litematic"),false);
        var tag=NbtFileUtils.readCompressed(base);var template=tag.getCompound("Regions").orElseThrow().getCompound("lod").orElseThrow();var regions=new NbtCompound();
        for(int i=0;i<24;i++){var region=(NbtCompound)template.copy();region.put("Position",LodRegression.xyz(i*24-192,(i%3)*16-16,(i%2)*16-16));regions.put("part"+i,region);}
        tag.put("Regions",regions);Path input=out.resolve("parallel.litematic");NbtFileUtils.writeCompressed(input,tag);
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)) {
            var registry=BlockRegistry.load(resources);var baker=new ModelBaker(resources,registry);var atlas=SpriteAtlas.load(resources,"blocks");var colors=new Colormaps();colors.load(resources);
            var schematic=LitematicaSchematic.load(input);schematic.materialize(registry);StreamMeshExporter.Index reference=null;
            for(int count:new int[]{1,4}) {
                System.setProperty("litematicrender.mesh.workers",String.valueOf(count));
                var builder=LodRegression.builder(schematic,registry,baker,colors,atlas,true);
                var index=StreamMeshExporter.write(builder,atlas,out.resolve("workers-"+count+".lmesh"));atlas.stitched.writePng(out.resolve("atlas-"+count+".png"));
                check(index.sections().size()>=32,"fixture never entered parallel path");
                if(reference==null)reference=index;else check(reference.quads()==index.quads(),"parallel face count changed");
                check(!workersAlive(),"workers survived completed export");
            }
            check(Files.mismatch(out.resolve("workers-1.lmesh"),out.resolve("workers-4.lmesh"))==-1,"serial/parallel mesh bytes differ");
            check(Files.mismatch(out.resolve("atlas-1.png"),out.resolve("atlas-4.png"))==-1,"serial/parallel atlas differs");
        } finally {System.clearProperty("litematicrender.mesh.workers");}
        var positions=new ArrayList<SectionVisibility.Position>();for(int x=0;x<64;x++)positions.add(new SectionVisibility.Position(x,0,0));
        var active=new AtomicInteger();var peak=new AtomicInteger();var built=new AtomicInteger();var fail=new AtomicBoolean();
        var source=new StreamMeshExporter.Source(){
            public List<SectionVisibility.Position> sections(){return positions;}
            public void prepareStreaming(){}
            public int sectionWorkers(){return 4;}
            public SceneMesh buildSection(SectionVisibility.Position p){
                int concurrent=active.incrementAndGet();peak.accumulateAndGet(concurrent,Math::max);
                try{Thread.sleep(15+(p.x()%4)*3);if(fail.get()&&p.x()==2)throw new IllegalStateException("injected failure");built.incrementAndGet();return new SceneMesh();}
                catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new CancellationException();}
                finally{active.decrementAndGet();}
            }
        };
        int[] count={0};
        StreamMeshExporter.write(source,SpriteAtlas.overview(),out.resolve("ordered.lmesh"),new StreamMeshExporter.Progress(){
            public void prepared(List<SectionVisibility.Position> p,long bytes){}
            public void prioritize(List<SectionVisibility.Position> pending){Collections.reverse(pending);}
            public void section(StreamMeshExporter.Entry entry,long bytes,int done){check(entry.position().x()==63-count[0]++,"completion order changed camera priority");check(built.get()-done<=7,"unbounded mesh backlog");}
        });
        check(peak.get()>1&&peak.get()<=4,"workers did not run concurrently within limit");check(!workersAlive(),"workers survived ordered export");
        Path cancelled=out.resolve("cancelled.lmesh");boolean rejected=false;
        try{StreamMeshExporter.write(source,SpriteAtlas.overview(),cancelled,new StreamMeshExporter.Progress(){public void prepared(List<SectionVisibility.Position> p,long b){}public void section(StreamMeshExporter.Entry e,long b,int c)throws java.io.IOException{throw new java.io.IOException("cancel");}});}catch(java.io.IOException expected){rejected=true;}
        check(rejected&&!Files.exists(cancelled)&&!workersAlive(),"cancel leaked workers or a partial mesh");
        fail.set(true);rejected=false;Path failed=out.resolve("failed.lmesh");
        try{StreamMeshExporter.write(source,SpriteAtlas.overview(),failed);}catch(java.io.IOException expected){rejected=true;}
        check(rejected&&!Files.exists(failed)&&!workersAlive(),"worker failure was not propagated/cleaned");
        System.out.println("PASS: real serial/parallel bytes and atlas identical across negative multi-region boundaries; bounded/concurrent/ordered workers; cancellation and failures cleaned");
    }
}
