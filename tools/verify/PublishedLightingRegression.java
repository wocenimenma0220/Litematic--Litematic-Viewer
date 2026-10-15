package io.github.litematicrender.render.scene;
import io.github.litematicrender.render.*;
import io.github.litematicrender.model.*;
import io.github.litematicrender.schematic.*;
import io.github.litematicrender.world.*;
import io.github.litematicrender.serve.JsonRpcServer;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Actual 1.0.17.2 light classes versus new packed grids; counters do not modify either engine. */
public final class PublishedLightingRegression {
    static long heap(){return java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();}
    static double median(List<Double> values){values.sort(Double::compare);return values.get(values.size()/2);}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),out=root.resolve("tools/verify/material-output");
        System.setProperty("litematicrender.home",root.toString());
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root);var loader=new URLClassLoader(new URL[]{out.resolve("reference").toUri().toURL()},PublishedLightingRegression.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {if(name.startsWith("io.github.litematicrender.render.scene.LightVolume")){synchronized(getClassLoadingLock(name)){var type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;}}return super.loadClass(name,resolve);}
        }) {
            var registry=BlockRegistry.load(resources);var baker=new ModelBaker(resources,registry);var atlas=SpriteAtlas.load(resources,"blocks");var shapes=new BlockShape(baker,atlas);
            var prior=loader.loadClass("io.github.litematicrender.render.scene.LightVolume").getConstructor(int.class,int.class,int.class,BlockState[].class,BlockShape.class,boolean.class);
            int sx=96,sy=64,sz=96,count=sx*sy*sz;var stone=registry.getOrMissing("minecraft:stone").defaultState;var torch=registry.getOrMissing("minecraft:torch").defaultState;
            var rows=new ArrayList<Map<String,Object>>();
            for(String label:List.of("open-sky","roof-and-lamps","real-crop")) {
                var cells=new BlockState[count];Arrays.fill(cells,BlockState.air());
                if(label.equals("roof-and-lamps")){for(int z=8;z<88;z++)for(int x=8;x<88;x++)cells[(52*sz+z)*sx+x]=stone;for(int z=12;z<88;z+=18)for(int x=12;x<88;x+=18)cells[(20*sz+z)*sx+x]=torch;}
                if(label.equals("real-crop")){var schematic=LitematicaSchematic.load(root.resolve("../../璃月港.litematic").normalize());schematic.materialize(registry);var region=schematic.regions().values().iterator().next();for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++)cells[(y*sz+z)*sx+x]=region.blocks.get(x+541,y,z+427);}
                var previous=(LightSampler)prior.newInstance(sx,sy,sz,cells,shapes,true);var current=new LightVolume(sx,sy,sz,cells,shapes,true);previous.compute();current.compute();
                for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++)if(previous.cellLight(x,y,z)!=current.cellLight(x,y,z))throw new AssertionError(label+" light changed at "+x+","+y+","+z);
                long payload=current.payloadBytes();var times=new List[]{new ArrayList<Double>(),new ArrayList<Double>()};var peaks=new long[2];var samples=new double[2];long checksum=0;
                for(int run=-2;run<3;run++)for(int mode=0;mode<2;mode++){
                    System.gc();long base=heap();var peak=new AtomicLong(base);var active=new AtomicBoolean(true);
                    Thread monitor=Thread.ofPlatform().daemon(true).start(()->{while(active.get()){peak.accumulateAndGet(heap(),Math::max);try{Thread.sleep(2);}catch(InterruptedException stopped){break;}}});
                    LightSampler value=mode==0?(LightSampler)prior.newInstance(sx,sy,sz,cells,shapes,true):new LightVolume(sx,sy,sz,cells,shapes,true);
                    long start=System.nanoTime();value.compute();double ms=(System.nanoTime()-start)/1e6;peak.accumulateAndGet(heap(),Math::max);active.set(false);monitor.interrupt();monitor.join();
                    long sampleStart=System.nanoTime();for(int n=0;n<500000;n++){int p=(int)((n*15485863L)%count);checksum+=value.cellLight(p%sx,p/sx/sz,p/sx%sz);}double sample=(System.nanoTime()-sampleStart)/1e6;
                    if(run>=0){times[mode].add(ms);peaks[mode]=Math.max(peaks[mode],peak.get()-base);samples[mode]+=sample/3;}
                }
                var row=new LinkedHashMap<String,Object>();row.put("case",label);row.put("cells",count);row.put("allCellsEqual",true);row.put("priorMedianMillis",median(times[0]));row.put("packedMedianMillis",median(times[1]));row.put("priorPayloadBytes",2L*count);row.put("packedPayloadBytes",payload);row.put("priorSampledHeapPeakDeltaBytes",peaks[0]);row.put("packedSampledHeapPeakDeltaBytes",peaks[1]);row.put("prior500kSamplesMillis",samples[0]);row.put("packed500kSamplesMillis",samples[1]);row.put("sampleChecksum",checksum);rows.add(row);System.out.println(row);
            }
            var json=JsonRpcServer.class.getDeclaredMethod("toJson",Object.class);json.setAccessible(true);Files.writeString(out.resolve("lighting-measurements.json"),(String)json.invoke(null,Map.of("pass",true,"cases",rows,"method","2 warmups, 3 measurements; same source and shapes; sampled heap at 2 ms is approximate, excludes cells/resources allocated beforehand")));
        }
    }
}
