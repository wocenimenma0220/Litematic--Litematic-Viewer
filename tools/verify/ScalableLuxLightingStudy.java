package io.github.litematicrender.render.scene;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.render.*;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.serve.JsonRpcServer;
import io.github.litematicrender.world.*;
import java.nio.file.*;
import java.util.*;

/** Independent offline experiment, not a port of ScalableLux and not used by the application. */
public final class ScalableLuxLightingStudy {
    private static final int[][] DIR={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private record Case(String name,int sx,int sy,int sz,LightVolume.Cells source,Map<String,Object> details) {
        int count(){return Math.multiplyExact(Math.multiplyExact(sx,sy),sz);}
    }
    private static final class Queue {
        int[] data;int head,size,maximum,peakCapacity;final boolean grow;final int limit;
        Queue(int limit,boolean grow){this.limit=limit;this.grow=grow;data=new int[grow?0:limit];peakCapacity=data.length;}
        void add(int value){
            if(size==data.length){int length=Math.min(limit,Math.max(1024,data.length*2));if(length<=data.length)throw new AssertionError("queue overflow");int[] next=new int[length];for(int i=0;i<size;i++)next[i]=data[(head+i)%data.length];data=next;head=0;peakCapacity=Math.max(peakCapacity,length);}
            data[(head+size)%data.length]=value;size++;maximum=Math.max(maximum,size);
        }
        int remove(){int value=data[head];head=(head+1)%data.length;size--;return value;}
    }
    private static final class Experiment {
        final Case input;final BlockShape shapes;final boolean fast;final byte[] sky,block;
        long stateReads,seeds,pushes;int peakQueue,queueCapacity;
        Experiment(Case input,BlockShape shapes,boolean fast){this.input=input;this.shapes=shapes;this.fast=fast;sky=new byte[input.count()];block=new byte[input.count()];}
        int index(int x,int y,int z){return (y*input.sz+z)*input.sx+x;}
        boolean inside(int x,int y,int z){return x>=0&&y>=0&&z>=0&&x<input.sx&&y<input.sy&&z<input.sz;}
        BlockState state(int x,int y,int z){stateReads++;return input.source.at(x,y,z);}
        void compute(){
            for(int z=0;z<input.sz;z++)for(int x=0;x<input.sx;x++){
                int level=15;
                for(int y=input.sy-1;y>=0;y--){int opacity=LightVolume.skyOpacity(state(x,y,z),shapes);level=Math.max(0,level-(opacity==0&&level==15?0:Math.max(1,opacity)));if(shapes.lightPermeable(state(x,y,z)))sky[index(x,y,z)]=(byte)level;}
            }
            spread(sky,true);
            for(int y=0;y<input.sy;y++)for(int z=0;z<input.sz;z++)for(int x=0;x<input.sx;x++)block[index(x,y,z)]=(byte)LightVolume.realEmission(state(x,y,z));
            spread(block,false);
        }
        boolean couldIncrease(byte[] values,int x,int y,int z,int level,boolean sky){
            for(int d=0;d<6;d++){int nx=x+DIR[d][0],ny=y+DIR[d][1],nz=z+DIR[d][2];if(inside(nx,ny,nz)&&values[index(nx,ny,nz)]<(sky&&d==3&&level==15?15:level-1))return true;}return false;
        }
        void spread(byte[] values,boolean sky){
            Queue queue=new Queue(values.length,fast);BitSet pending=new BitSet(values.length);
            for(int p=0;p<values.length;p++)if(values[p]>1){int x=p%input.sx,y=p/input.sx/input.sz,z=p/input.sx%input.sz;
                if(!fast||couldIncrease(values,x,y,z,values[p],sky)){queue.add(p);pending.set(p);seeds++;pushes++;}
            }
            while(queue.size>0){int p=queue.remove();pending.clear(p);int level=values[p];if(level<=1)continue;int x=p%input.sx,y=p/input.sx/input.sz,z=p/input.sx%input.sz;
                for(int d=0;d<6;d++){int nx=x+DIR[d][0],ny=y+DIR[d][1],nz=z+DIR[d][2];if(!inside(nx,ny,nz))continue;
                    int target=index(nx,ny,nz);if(fast&&values[target]>=(sky&&d==3&&level==15?15:level-1))continue;
                    if(!shapes.lightPermeable(state(nx,ny,nz)))continue;
                    int opacity=sky?LightVolume.skyOpacity(state(nx,ny,nz),shapes):LightVolume.blockOpacity(state(nx,ny,nz),shapes),cost=sky&&d==3&&level==15&&opacity==0?0:Math.max(1,opacity),value=level-cost;
                    if(value>values[target]){values[target]=(byte)value;if(value>1&&!pending.get(target)){queue.add(target);pending.set(target);pushes++;}}
                }
            }
            peakQueue=Math.max(peakQueue,queue.maximum);queueCapacity=Math.max(queueCapacity,queue.peakCapacity);
        }
        Map<String,Object> work(){return Map.of("stateReads",stateReads,"initialQueueEntries",seeds,"totalQueueEntries",pushes,"peakQueueEntries",peakQueue,"peakQueueArrayBytes",(long)queueCapacity*4);}
    }
    private record Page(int uniform,byte[] packed) {
        int get(int local){return packed==null?uniform:(packed[local>>1]>>>((local&1)*4))&15;}
    }
    private static Map<String,Object> storage(Case c,Experiment value){
        long bytes=0;int uniform=0,pages=0;
        for(byte[] channel:List.of(value.sky,value.block))for(int sy=0;sy<(c.sy+15)/16;sy++)for(int sz=0;sz<(c.sz+15)/16;sz++)for(int sx=0;sx<(c.sx+15)/16;sx++){
            byte[] raw=new byte[4096];int first=-1;boolean same=true;
            for(int local=0;local<4096;local++){int x=sx*16+(local&15),y=sy*16+(local>>8),z=sz*16+((local>>4)&15);int n=x<c.sx&&y<c.sy&&z<c.sz?channel[(y*c.sz+z)*c.sx+x]:channel==value.sky?15:0;raw[local]=(byte)n;if(first<0)first=n;else same&=n==first;}
            Page page;if(same){uniform++;page=new Page(first,null);}else{byte[] packed=new byte[2048];for(int local=0;local<4096;local++)packed[local>>1]|=(byte)(raw[local]<<((local&1)*4));page=new Page(-1,packed);bytes+=packed.length;}
            for(int local=0;local<4096;local++)if(page.get(local)!=raw[local])throw new AssertionError("nibble round trip");pages++;
        }
        return Map.of("denseCurrentChannelBytes",2L*c.count(),"paddedCurrentSectionChannelBytes",4096L*pages,"plainNibbleSectionBytes",2048L*pages,"uniformAndNibblePayloadBytes",bytes,"uniformChannels",uniform,"sectionChannels",pages,"allDecodedCellsEqual",true);
    }
    private static Case artificial(String name,int sx,int sy,int sz,BlockRegistry registry){
        BlockState[] cells=new BlockState[sx*sy*sz];Arrays.fill(cells,BlockState.air());BlockState stone=registry.getOrMissing("minecraft:stone").defaultState,torch=registry.getOrMissing("minecraft:torch").defaultState;
        if(name.equals("roof-and-lamps")){
            for(int z=8;z<sz-8;z++)for(int x=8;x<sx-8;x++)cells[(sy-12)*sz*sx+z*sx+x]=stone;
            for(int y=2;y<sy-16;y+=12)for(int z=12;z<sz-8;z+=18)for(int x=12;x<sx-8;x+=18)cells[(y*sz+z)*sx+x]=torch;
        }
        if(name.equals("partial-sections")){
            Random random=new Random(71421);String[] names={"stone","water","oak_leaves","glass","torch","glowstone","oak_slab"};
            for(int i=0;i<cells.length/3;i++)cells[random.nextInt(cells.length)]=registry.getOrMissing("minecraft:"+names[random.nextInt(names.length)]).defaultState;
        }
        return new Case(name,sx,sy,sz,(x,y,z)->cells[(y*sz+z)*sx+x],Map.of("kind","synthetic"));
    }
    private static Case realCrop(Path file,BlockRegistry registry)throws Exception{
        var schematic=LitematicaSchematic.load(file);schematic.materialize(registry);var region=schematic.regions().values().stream().max(Comparator.comparingLong(r->r.blocks.totalVolume())).orElseThrow();var blocks=region.blocks;
        int sx=Math.min(96,blocks.sizeX),sy=Math.min(64,blocks.sizeY),sz=Math.min(96,blocks.sizeZ),bx=0,by=0,bz=0,best=-1;
        for(int xi=0;xi<3;xi++)for(int yi=0;yi<4;yi++)for(int zi=0;zi<3;zi++){
            int ox=(blocks.sizeX-sx)*xi/2,oy=(blocks.sizeY-sy)*yi/3,oz=(blocks.sizeZ-sz)*zi/2,count=0;
            for(int y=0;y<sy;y+=4)for(int z=0;z<sz;z+=4)for(int x=0;x<sx;x+=4)if(!blocks.get(x+ox,y+oy,z+oz).isAir())count++;
            if(count>best){best=count;bx=ox;by=oy;bz=oz;}
        }
        int x0=bx,y0=by,z0=bz;long occupied=0;for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++)if(!blocks.get(x+x0,y+y0,z+z0).isAir())occupied++;
        return new Case("real-schematic-crop",sx,sy,sz,(x,y,z)->blocks.get(x+x0,y+y0,z+z0),Map.of("kind","real projection crop, isolated lighting boundary","file",file.toString(),"region",region.name,"originInRegion",List.of(bx,by,bz),"nonAir",occupied));
    }
    private static double median(List<Double> times){times.sort(Double::compare);return times.get(times.size()/2);}
    private static Map<String,Object> semanticObservations(BlockRegistry registry,BlockShape shapes){
        int sx=21,sy=7,sz=7;BlockState stone=registry.getOrMissing("minecraft:stone").defaultState,tinted=registry.getOrMissing("minecraft:tinted_glass").defaultState;
        BlockState[] cells=new BlockState[sx*sy*sz];Arrays.fill(cells,BlockState.air());
        for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++)if(x==0||x==sx-1||y==0||y==sy-1||z==0||z==sz-1)cells[(y*sz+z)*sx+x]=stone;
        for(int y=1;y<sy-1;y++)for(int z=1;z<sz-1;z++)cells[(y*sz+z)*sx+10]=tinted;
        cells[(3*sz+3)*sx+8]=registry.getOrMissing("minecraft:torch").defaultState;
        var lamp=new LightVolume(sx,sy,sz,cells,shapes,true);lamp.compute();int behind=lamp.cellLight(11,3,3)&255;
        Arrays.fill(cells,BlockState.air());
        for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++)if(x==0||x==sx-1||y==0||z==0||z==sz-1)cells[(y*sz+z)*sx+x]=stone;
        for(int z=1;z<sz-1;z++)for(int x=1;x<sx-1;x++)cells[((sy-1)*sz+z)*sx+x]=tinted;
        var roof=new LightVolume(sx,sy,sz,cells,shapes,true);roof.compute();int under=(roof.cellLight(10,3,3)>>8)&15;
        return Map.of("tintedGlassRenderOccluder",shapes.occludes(tinted),"productionSkyOpacity",LightVolume.skyOpacity(tinted,shapes),"vanillaSourceLightDampening",15,"blockLightBehindSealedTintedWall",behind,"skyLightUnderSealedTintedRoof",under,"expectedVanillaBothSamples",0,"confirmedProductionLightLeak",behind>0&&under>0);
    }
    private static void write(Path file,Object value)throws Exception{var method=JsonRpcServer.class.getDeclaredMethod("toJson",Object.class);method.setAccessible(true);Files.writeString(file,(String)method.invoke(null,value));}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toAbsolutePath(),out=root.resolve("tools/verify/lod-output/scalablelux-study");Files.createDirectories(out);System.setProperty("litematicrender.home",root.toString());
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)){
            var registry=BlockRegistry.load(resources);var baker=new ModelBaker(resources,registry);var atlas=SpriteAtlas.load(resources,"blocks");var shapes=new BlockShape(baker,atlas);
            List<Case> cases=new ArrayList<>(List.of(artificial("open-sky",96,64,96,registry),artificial("roof-and-lamps",96,64,96,registry),artificial("partial-sections",48,33,35,registry)));
            if(args.length>1)cases.add(realCrop(Path.of(args[1]).toAbsolutePath(),registry));
            var rows=new ArrayList<Map<String,Object>>();
            for(Case c:cases){
                Experiment original=new Experiment(c,shapes,false),candidate=new Experiment(c,shapes,true);original.compute();candidate.compute();
                if(!Arrays.equals(original.sky,candidate.sky)||!Arrays.equals(original.block,candidate.block))throw new AssertionError("candidate light differs: "+c.name);
                var production=new LightVolume(c.sx,c.sy,c.sz,c.source,shapes,true);production.compute();
                for(int y=0;y<c.sy;y++)for(int z=0;z<c.sz;z++)for(int x=0;x<c.sx;x++){int index=(y*c.sz+z)*c.sx+x;if(production.cellLight(x,y,z)!=((candidate.sky[index]<<8)|(candidate.block[index]&255)))throw new AssertionError("production mismatch: "+c.name);}
                var baselineTimes=new ArrayList<Double>();var candidateTimes=new ArrayList<Double>();
                for(int iteration=-2;iteration<5;iteration++)for(int pass=0;pass<2;pass++){boolean fast=(iteration&1)==pass;var run=new Experiment(c,shapes,fast);long start=System.nanoTime();run.compute();double ms=(System.nanoTime()-start)/1e6;if(iteration>=0)(fast?candidateTimes:baselineTimes).add(ms);}
                double before=median(baselineTimes),after=median(candidateTimes);var row=new LinkedHashMap<String,Object>();row.put("name",c.name);row.put("dimensions",List.of(c.sx,c.sy,c.sz));row.put("details",c.details);row.put("allCellsMatchProduction",true);row.put("baselineMedianMillis",before);row.put("candidateMedianMillis",after);row.put("speedRatio",before/after);row.put("baselineWork",original.work());row.put("candidateWork",candidate.work());row.put("storage",storage(c,candidate));rows.add(row);write(out.resolve("result.json"),Map.of("pass",false,"completeCases",rows));
                System.out.printf(Locale.ROOT,"%s: %.1f -> %.1f ms (%.2fx), state reads %d -> %d, queue bytes %d -> %d%n",c.name,before,after,before/after,original.stateReads,candidate.stateReads,(long)original.queueCapacity*4,(long)candidate.queueCapacity*4);
            }
            var semantics=semanticObservations(registry,shapes);
            write(out.resolve("result.json"),Map.of("pass",true,"scope","independent queue variants checked against current production; timings include seed and propagation but exclude resources, meshing and GPU", "measurement","2 warmups, 5 timed runs each; paired alternating order; median; counters enabled in both", "java",System.getProperty("java.version"),"cases",rows,"semantics",semantics));System.out.println("PASS: all experimental channels match current production, compressed page round trips exact; semantic observations="+semantics);
        }
    }
}
