package io.github.litematicrender.render.scene;

import io.github.litematicrender.world.*;
import java.util.*;

/** Lighting around occupied sections; distant empty space does not allocate voxel arrays. */
public final class SparseLightVolume implements LightSampler {
    private record Section(int x,int y,int z) {
        @Override public int hashCode(){return coordinateHash(x,y,z);}
    }
    // Record's linear x/z hash creates long collision chains across wide buildings.
    private static int coordinateHash(int x,int y,int z) {
        int hash=x*0x8da6b343^y*0xd8163841^z*0xcb1ab31f;
        hash^=hash>>>16;hash*=0x7feb352d;hash^=hash>>>15;return hash;
    }
    private static final int[][] DIRECTIONS={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private static final int[] DELTA={1,-1,256,-256,16,-16};
    private static final class Chunk {
        final Section section;final int id;
        BlockState[] cells;
        boolean occupied;
        final Chunk[] neighbors=new Chunk[6];
        final LightPage sky=new LightPage(),block=new LightPage();
        Chunk(Section section,int id){this.section=section;this.id=id;}
    }
    private final int sx,sy,sz;
    private final BlockShape shapes;
    private final boolean realLighting;
    private final LightVolume.Cells source;
    private record Column(int x,int z) {
        @Override public int hashCode(){return coordinateHash(x,0,z);}
    }
    private static final class ColumnData {
        int blocker=-1;
        TreeMap<Integer,Integer> absorption=new TreeMap<>();
        int top=-1;
        byte[] levels;
        int calculate(int y) {
            if(y<=blocker)return 0;
            int level=15,previous=-1;
            for(var entry:absorption.tailMap(y,true).descendingMap().entrySet()) {
                if(level<15)level-=previous-entry.getKey()-1;
                level-=entry.getValue();previous=entry.getKey();
                if(level<=0)return 0;
            }
            return Math.max(0,level-(level<15?previous-y:0));
        }
        void freeze() {
            top=absorption.isEmpty()?-1:absorption.lastKey();
            // The first absorber removes at least one level; after 14 more cells it is dark.
            if(top>blocker){levels=new byte[15];for(int i=0;i<levels.length;i++)levels[i]=(byte)calculate(top-i);}
            absorption=null;
        }
        int direct(int y) {
            if(y<=blocker)return 0;
            if(y>top)return 15;
            int depth=top-y;return depth<15?levels[depth]:0;
        }
    }
    private final Map<Column,ColumnData> columns=new HashMap<>();
    private final Map<Section,Chunk> chunks=new LinkedHashMap<>();
    private final List<Chunk> ids=new ArrayList<>();
    private boolean computed,hasBlockLight;
    public SparseLightVolume(int sx,int sy,int sz,BlockShape shapes){this(sx,sy,sz,shapes,false);}
    public SparseLightVolume(int sx,int sy,int sz,BlockShape shapes,boolean realLighting){this(sx,sy,sz,shapes,realLighting,null);}
    public SparseLightVolume(int sx,int sy,int sz,BlockShape shapes,boolean realLighting,LightVolume.Cells source){this.sx=sx;this.sy=sy;this.sz=sz;this.shapes=shapes;this.realLighting=realLighting;this.source=source;}
    private Chunk create(Section section) {
        Chunk existing=chunks.get(section);if(existing!=null)return existing;
        long available=Runtime.getRuntime().maxMemory()-(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
        // Existing chunks are already included in used heap; reserve only incremental headroom.
        if(available<32L*1024*1024){System.gc();available=Runtime.getRuntime().maxMemory()-(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());}
        if(available<16L*1024*1024)throw new IllegalArgumentException("投影过大，分块光照所需内存不足；请拆分投影。");
        Chunk created=new Chunk(section,ids.size());ids.add(created);chunks.put(section,created);return created;
    }
    private static int index(int x,int y,int z){return ((y&15)<<8)|((z&15)<<4)|(x&15);}
    private boolean inside(int x,int y,int z){return x>=0&&y>=0&&z>=0&&x<sx&&y<sy&&z<sz;}
    public void put(int x,int y,int z,BlockState state) {
        if(computed)throw new IllegalStateException("lighting already computed");
        if(!inside(x,y,z))throw new IllegalArgumentException("light cell outside bounds");
        Chunk chunk=create(new Section(x>>4,y>>4,z>>4));chunk.occupied=true;
        if(source==null){if(chunk.cells==null)chunk.cells=new BlockState[4096];chunk.cells[index(x,y,z)]=state;}
    }
    private BlockState state(Chunk chunk,int local) {
        if(!chunk.occupied)return null;
        if(source==null)return chunk.cells[local];
        return source.at(chunk.section.x*16+(local&15),chunk.section.y*16+(local>>8),chunk.section.z*16+((local>>4)&15));
    }
    public int sectionCount(){return chunks.size();}
    @Override public void compute() {
        if(computed)return;computed=true;
        List<Chunk> occupied=new ArrayList<>(ids);
        for(Chunk chunk:occupied) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            var section=chunk.section;
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++) {
                int x=section.x+dx,y=section.y+dy,z=section.z+dz;
                if(x>=0&&y>=0&&z>=0&&x<=(sx-1)/16&&y<=(sy-1)/16&&z<=(sz-1)/16)create(new Section(x,y,z));
            }
            if(realLighting)for(int local=0;local<4096;local++) {
                BlockState state=state(chunk,local);int opacity=LightVolume.skyOpacity(state,shapes);
                if(opacity==0)continue;
                int x=section.x*16+(local&15),y=section.y*16+(local>>8),z=section.z*16+((local>>4)&15);
                ColumnData data=columns.computeIfAbsent(new Column(x,z),key->new ColumnData());
                if(opacity>=15)data.blocker=Math.max(data.blocker,y);else data.absorption.put(y,opacity);
            }
        }
        int columnCount=0;
        for(ColumnData column:columns.values()){
            if((columnCount++&1023)==0&&Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            column.freeze();
        }
        for(Chunk chunk:ids)for(int d=0;d<6;d++)chunk.neighbors[d]=chunks.get(new Section(chunk.section.x+DIRECTIONS[d][0],chunk.section.y+DIRECTIONS[d][1],chunk.section.z+DIRECTIONS[d][2]));
        for(Chunk chunk:ids)chunk.sky.fill(15);
        for(Chunk chunk:ids)for(int local=0;local<4096;local++) {
            if(local==0&&Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            int x=chunk.section.x*16+(local&15),y=chunk.section.y*16+(local>>8),z=chunk.section.z*16+((local>>4)&15);
            // Padding must not become a source of sky along incomplete section edges.
            if(!inside(x,y,z)){chunk.sky.set(local,0);continue;}
            BlockState state=state(chunk,local);
            chunk.sky.set(local,shapes.lightPermeable(state)?(realLighting?direct(x,y,z):15):0);
            int emission=realLighting?LightVolume.realEmission(state):LightVolume.emission(state);chunk.block.set(local,emission);hasBlockLight|=emission>0;
        }
        if(realLighting){seedEmptyBorders();parallelPropagation();compact();return;}
        // 1.0.8 seeds full sky in every permeable cell, so no sky spread is needed.
        propagateBlock();compact();
    }
    private void compact(){for(Chunk chunk:ids){chunk.sky.compact();chunk.block.compact();}}
    private void parallelPropagation() {
        if(!hasBlockLight){propagateReal(true);return;}
        if(!Boolean.parseBoolean(System.getProperty("litematicrender.light.parallel","true"))||(long)sx*sy*sz<262_144||Runtime.getRuntime().availableProcessors()<2){propagateReal(true);propagateReal(false);return;}
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread skyThread=Thread.ofPlatform().name("sparse-sky-light").daemon(true).unstarted(()->{try{propagateReal(true);}catch(Throwable error){failure.compareAndSet(null,error);}});
        Thread blockThread=Thread.ofPlatform().name("sparse-block-light").daemon(true).unstarted(()->{try{propagateReal(false);}catch(Throwable error){failure.compareAndSet(null,error);}});
        skyThread.start();blockThread.start();
        try{skyThread.join();blockThread.join();}catch(InterruptedException stopped){skyThread.interrupt();blockThread.interrupt();Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("光照计算已取消");}
        Throwable error=failure.get();if(error instanceof RuntimeException runtime)throw runtime;if(error instanceof Error fatal)throw fatal;if(error!=null)throw new IllegalStateException(error);
    }
    private int direct(int x,int y,int z){ColumnData data=columns.get(new Column(x,z));return data==null?15:data.direct(y);}
    /** Analytic sky in unallocated air, as in 1.0.10; no arrays for distant gaps. */
    private int emptySky(int x,int y,int z) {
        int value=direct(x,y,z);
        for(int dx=-14;dx<=14&&value<15;dx++)for(int dz=-14+Math.abs(dx);dz<=14-Math.abs(dx);dz++) {
            int distance=Math.abs(dx)+Math.abs(dz);
            if(15-distance>value&&inside(x+dx,y,z+dz))value=Math.max(value,direct(x+dx,y,z+dz)-distance);
        }
        return value;
    }
    private void seedEmptyBorders() {
        for(Chunk chunk:ids)for(int local=0;local<4096;local++) {
            if(local==0&&Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            int lx=local&15,ly=local>>8,lz=(local>>4)&15;
            if(lx!=0&&lx!=15&&ly!=0&&ly!=15&&lz!=0&&lz!=15)continue;
            int x=chunk.section.x*16+lx,y=chunk.section.y*16+ly,z=chunk.section.z*16+lz;
            if(!inside(x,y,z))continue;
            BlockState state=state(chunk,local);if(!shapes.lightPermeable(state))continue;
            int opacity=LightVolume.skyOpacity(state,shapes);
            for(int[] d:DIRECTIONS) {
                int nx=x+d[0],ny=y+d[1],nz=z+d[2];
                if(!inside(nx,ny,nz)||chunks.containsKey(new Section(nx>>4,ny>>4,nz>>4)))continue;
                int source=emptySky(nx,ny,nz),cost=d[1]==1&&source==15&&opacity==0?0:Math.max(1,opacity);
                chunk.sky.set(local,Math.max(chunk.sky.get(local),Math.max(0,source-cost)));
            }
        }
    }
    private void propagateReal(boolean skylight) {
        var queue=new LongQueue();var pending=new BitSet[ids.size()];
        for(Chunk chunk:ids) {
            pending[chunk.id]=new BitSet(4096);LightPage values=skylight?chunk.sky:chunk.block;
            for(int local=0;local<4096;local++)if(values.get(local)>1&&canSpread(chunk,local,skylight)){queue.add(((long)chunk.id<<12)|local);pending[chunk.id].set(local);}
        }
        while(queue.size>0) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            long packed=queue.remove();Chunk chunk=ids.get((int)(packed>>>12));int local=(int)(packed&4095);
            pending[chunk.id].clear(local);int level=(skylight?chunk.sky:chunk.block).get(local);if(level<=1)continue;
            int x=chunk.section.x*16+(local&15),y=chunk.section.y*16+(local>>8),z=chunk.section.z*16+((local>>4)&15);
            for(int d=0;d<6;d++) {
                int[] direction=DIRECTIONS[d];
                int nx=x+direction[0],ny=y+direction[1],nz=z+direction[2];if(!inside(nx,ny,nz))continue;
                Chunk target=neighbor(chunk,local,d);if(target==null)continue;
                int destination=index(nx,ny,nz);
                LightPage channel=skylight?target.sky:target.block;
                if(channel.get(destination)>=(skylight&&d==3&&level==15?15:level-1))continue;
                BlockState state=state(target,destination);
                if(!shapes.lightPermeable(state))continue;
                int opacity=skylight?LightVolume.skyOpacity(state,shapes):LightVolume.blockOpacity(state,shapes);
                int cost=skylight&&d==3&&level==15&&opacity==0?0:Math.max(1,opacity),value=level-cost;
                LightPage values=skylight?target.sky:target.block;
                if(value>values.get(destination)) {
                    values.set(destination,value);
                    if(value>1&&!pending[target.id].get(destination)){queue.add(((long)target.id<<12)|destination);pending[target.id].set(destination);}
                }
            }
        }
    }
    private static Chunk neighbor(Chunk chunk,int local,int direction) {
        int axis=direction<2?local&15:direction<4?local>>8:(local>>4)&15;
        return axis==((direction&1)==0?15:0)?chunk.neighbors[direction]:chunk;
    }
    private boolean canSpread(Chunk chunk,int local,boolean skylight) {
        int level=(skylight?chunk.sky:chunk.block).get(local);
        for(int d=0;d<6;d++) {
            Chunk target=neighbor(chunk,local,d);if(target==null)continue;
            int destination=(local+DELTA[d])&4095;
            // Crossing X/Z edges must wrap only that coordinate, preserving the other axes.
            if(target!=chunk)destination=d<2?(local&~15)|((local+DELTA[d])&15):d<4?(local&255)|((local+DELTA[d])&3840):(local&~240)|((local+DELTA[d])&240);
            int cost=skylight&&d==3&&level==15?0:1;
            if((skylight?target.sky:target.block).get(destination)<level-cost)return true;
        }
        return false;
    }
    private void propagateBlock() {
        var queue=new LongQueue(); long enqueued=0,capacity=(long)sx*sy*sz;
        // Keep the old dense voxel order for emitter seeds, including queue saturation.
        var seeds=new ArrayList<Long>();
        for(Chunk chunk:ids)for(int local=0;local<4096;local++)if(chunk.block.get(local)>1)seeds.add(((long)chunk.id<<12)|local);
        seeds.sort(Comparator.comparingLong(this::voxelIndex));
        for(long seed:seeds){queue.add(seed);enqueued++;}
        while(queue.size>0) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            long packed=queue.remove();Chunk chunk=ids.get((int)(packed>>>12));int local=(int)(packed&4095);
            int level=chunk.block.get(local);if(level<=1)continue;
            int x=chunk.section.x*16+(local&15),y=chunk.section.y*16+(local>>8),z=chunk.section.z*16+((local>>4)&15);
            for(int[] direction:DIRECTIONS) {
                int nx=x+direction[0],ny=y+direction[1],nz=z+direction[2];if(!inside(nx,ny,nz))continue;
                Chunk target=chunks.get(new Section(nx>>4,ny>>4,nz>>4));if(target==null)continue;
                int destination=index(nx,ny,nz);BlockState state=state(target,destination);if(!shapes.lightPermeable(state))continue;
                int value=level-1;LightPage values=target.block;
                if(value>values.get(destination)){values.set(destination,value);if(value>1&&enqueued<capacity){queue.add(((long)target.id<<12)|destination);enqueued++;}}
            }
        }
    }
    private long voxelIndex(long packed) {
        Chunk chunk=ids.get((int)(packed>>>12));int local=(int)(packed&4095);
        int x=chunk.section.x*16+(local&15),y=chunk.section.y*16+(local>>8),z=chunk.section.z*16+((local>>4)&15);
        return ((long)y*sz+z)*sx+x;
    }
    private static final class SampleCache { int x=Integer.MIN_VALUE,y,z;Chunk chunk; }
    private final ThreadLocal<SampleCache> sampleCache=ThreadLocal.withInitial(SampleCache::new);
    private Chunk sampleChunk(int x,int y,int z) {
        SampleCache cache=sampleCache.get();
        if(cache.x==x&&cache.y==y&&cache.z==z)return cache.chunk;
        int dx=x-cache.x,dy=y-cache.y,dz=z-cache.z;Chunk result=null;
        boolean adjacent=cache.chunk!=null&&Math.abs((long)dx)+Math.abs((long)dy)+Math.abs((long)dz)==1;
        if(adjacent)result=cache.chunk.neighbors[dx==1?0:dx==-1?1:dy==1?2:dy==-1?3:dz==1?4:5];
        else result=chunks.get(new Section(x,y,z));
        cache.x=x;cache.y=y;cache.z=z;cache.chunk=result;return result;
    }
    @Override public int cellLight(int x,int y,int z) {
        if(!inside(x,y,z))return 15<<8;
        Chunk chunk=sampleChunk(x>>4,y>>4,z>>4);
        if(chunk==null)return (realLighting?emptySky(x,y,z):15)<<8;
        int local=index(x,y,z);return (chunk.sky.get(local)<<8)|(chunk.block.get(local)&255);
    }
    @Override public LightSampler forSection(int x,int y,int z) {
        if(!computed)throw new IllegalStateException("Light snapshot is not ready");
        // Origins need not align with the world section. Cover [-1,+17] sampling on each axis.
        final int bx=(x-1)>>4,by=(y-1)>>4,bz=(z-1)>>4;
        final Chunk[] neighborhood=new Chunk[27];
        for(int dy=0;dy<3;dy++)for(int dz=0;dz<3;dz++)for(int dx=0;dx<3;dx++)
            neighborhood[(dy*3+dz)*3+dx]=chunks.get(new Section(bx+dx,by+dy,bz+dz));
        return new LightSampler() {
            public void compute(){throw new IllegalStateException("Read-only light snapshot");}
            public int cellLight(int sx,int sy,int sz) {
                if(!inside(sx,sy,sz))return 15<<8;
                int dx=(sx>>4)-bx,dy=(sy>>4)-by,dz=(sz>>4)-bz;
                if(dx<0||dy<0||dz<0||dx>=3||dy>=3||dz>=3)return SparseLightVolume.this.cellLight(sx,sy,sz);
                Chunk chunk=neighborhood[(dy*3+dz)*3+dx];if(chunk==null)return (realLighting?emptySky(sx,sy,sz):15)<<8;
                int local=index(sx,sy,sz);return chunk.sky.get(local)<<8|chunk.block.get(local);
            }
        };
    }
    private static final class LongQueue {
        long[] values=new long[4096];int head,size;
        void add(long value){if(size==values.length){long[] grown=new long[Math.multiplyExact(values.length,2)];for(int i=0;i<size;i++)grown[i]=values[(head+i)%values.length];values=grown;head=0;}values[(head+size)%values.length]=value;size++;}
        long remove(){long result=values[head];head=(head+1)%values.length;size--;return result;}
    }
}
