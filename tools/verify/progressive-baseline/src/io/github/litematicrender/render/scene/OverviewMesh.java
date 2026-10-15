package io.github.litematicrender.render.scene;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.render.*;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockState;
import java.util.*;

/** Bounded color occupancy preview. It deliberately has no final lighting or special geometry. */
public final class OverviewMesh implements StreamMeshExporter.Source {
    private static final int[][] DIRECTIONS={{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
    private static final int[][][] CORNERS={{{0,0,1},{0,0,0},{1,0,0},{1,0,1}},{{0,1,0},{0,1,1},{1,1,1},{1,1,0}},{{1,1,0},{1,0,0},{0,0,0},{0,1,0}},{{0,1,1},{0,0,1},{1,0,1},{1,1,1}},{{0,1,0},{0,0,0},{0,0,1},{0,1,1}},{{1,1,1},{1,0,1},{1,0,0},{1,1,0}}};
    private final Map<SectionVisibility.Position,List<Integer>> sections=new LinkedHashMap<>();
    private final int step,ox,oy,oz,sx,sy,sz;
    private final int[] colors,counts;
    private final float[] bounds={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
    public OverviewMesh(LitematicaSchematic schematic,ModelBaker baker,SpriteAtlas atlas) {
        int[] lo={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE},hi={Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
        for(var r:schematic.regions().values()){var p=r.blockOrigin();int[] a={p.x(),p.y(),p.z()},n={r.blocks.sizeX,r.blocks.sizeY,r.blocks.sizeZ};for(int i=0;i<3;i++){lo[i]=Math.min(lo[i],a[i]);hi[i]=Math.max(hi[i],Math.addExact(a[i],n[i]-1));}}
        if(schematic.regions().isEmpty())Arrays.fill(lo,0);if(schematic.regions().isEmpty())Arrays.fill(hi,0);
        int cell=8;int[] start=new int[3],size=new int[3];
        while(true){long volume=1;for(int i=0;i<3;i++){start[i]=Math.floorDiv(lo[i],cell);size[i]=Math.floorDiv(hi[i],cell)-start[i]+1;volume*=size[i];if(volume>1_000_000)break;}if(volume<=1_000_000)break;if(cell>=1<<29)throw new IllegalArgumentException("投影坐标跨度过大");cell*=2;}
        step=cell;ox=start[0];oy=start[1];oz=start[2];sx=size[0];sy=size[1];sz=size[2];int n=Math.multiplyExact(Math.multiplyExact(sx,sy),sz);
        counts=new int[n];colors=new int[n];long[] red=new long[n],green=new long[n],blue=new long[n];var palette=new HashMap<BlockState,Integer>();
        for(var r:schematic.regions().values()) {var origin=r.blockOrigin();
            r.blocks.forEachNonAir((x,y,z,state)->{
                int wx=origin.x()+x,wy=origin.y()+y,wz=origin.z()+z;
                int index=index(Math.floorDiv(wx,step)-ox,Math.floorDiv(wy,step)-oy,Math.floorDiv(wz,step)-oz);
                int rgb=palette.computeIfAbsent(state,s->color(s,baker,atlas));if(rgb<0)return;counts[index]++;red[index]+=rgb>>16&255;green[index]+=rgb>>8&255;blue[index]+=rgb&255;
                bounds[0]=Math.min(bounds[0],wx);bounds[1]=Math.min(bounds[1],wy);bounds[2]=Math.min(bounds[2],wz);bounds[3]=Math.max(bounds[3],wx+1);bounds[4]=Math.max(bounds[4],wy+1);bounds[5]=Math.max(bounds[5],wz+1);
            });
        }
        for(int i=0;i<n;i++)if(counts[i]>0){colors[i]=(int)(red[i]/counts[i])<<16|(int)(green[i]/counts[i])<<8|(int)(blue[i]/counts[i]);int x=i%sx,y=i/(sx*sz),z=i/sx%sz;var key=new SectionVisibility.Position(Math.floorDiv((x+ox)*step,16),Math.floorDiv((y+oy)*step,16),Math.floorDiv((z+oz)*step,16));sections.computeIfAbsent(key,k->new ArrayList<>()).add(i);}
        if(!Float.isFinite(bounds[0]))Arrays.fill(bounds,0);
    }
    private int index(int x,int y,int z){return (y*sz+z)*sx+x;}
    private static int color(BlockState state,ModelBaker baker,SpriteAtlas atlas) {
        if(Set.of("minecraft:barrier","minecraft:light","minecraft:structure_void").contains(state.blockId))return -1;
        if(state.blockId.equals("minecraft:water"))return 0x3f76e4;if(state.blockId.equals("minecraft:lava"))return 0xef6a13;
        var variants=baker.selectVariants(state.blockId,state);if(variants.isEmpty())return 0x999999;
        var model=baker.bake(variants.getFirst());String particle=model.particleSprite==null?"minecraft:missingno":model.particleSprite;if(!particle.contains(":"))particle="minecraft:"+particle;
        var sprite=atlas.getOrMissing(particle);
        if(sprite.name.equals("minecraft:missingno"))for(var face:model.all){String id=face.spriteId.contains(":")?face.spriteId:"minecraft:"+face.spriteId;var candidate=atlas.getOrMissing(id);if(!candidate.name.equals("minecraft:missingno")){sprite=candidate;break;}}
        if(sprite.name.equals("minecraft:missingno"))return 0x999999;var image=sprite.sheet;
        long r=0,g=0,b=0,n=0;int stride=Math.max(1,image.pixels().length/1024);for(int i=0;i<image.pixels().length;i+=stride){int p=image.pixels()[i];if((p>>>24)<32)continue;r+=p>>16&255;g+=p>>8&255;b+=p&255;n++;}return n==0?0x999999:(int)(r/n)<<16|(int)(g/n)<<8|(int)(b/n);
    }
    public int step(){return step;}
    public float[] bounds(){return bounds.clone();}
    public List<SectionVisibility.Position> sections(){return List.copyOf(sections.keySet());}
    public void prepareStreaming(){}
    public boolean fixedLevels(){return true;}
    public SceneMesh buildSection(SectionVisibility.Position position) {
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();var mesh=new SceneMesh();
        for(int i:sections.getOrDefault(position,List.of())) {int x=i%sx,y=i/(sx*sz),z=i/sx%sz;
            for(int f=0;f<6;f++){int[] d=DIRECTIONS[f];int nx=x+d[0],ny=y+d[1],nz=z+d[2];if(nx>=0&&ny>=0&&nz>=0&&nx<sx&&ny<sy&&nz<sz&&counts[index(nx,ny,nz)]>0)continue;
                float[] xs=new float[4],ys=new float[4],zs=new float[4];for(int c=0;c<4;c++){xs[c]=(x+ox+CORNERS[f][c][0])*step;ys[c]=(y+oy+CORNERS[f][c][1])*step;zs[c]=(z+oz+CORNERS[f][c][2])*step;}
                float shade=f==0?.5f:f==1?1:f<4?.8f:.6f;int rgb=colors[i],v=(int)((rgb>>16&255)*shade)<<16|(int)((rgb>>8&255)*shade)<<8|(int)((rgb&255)*shade);
                mesh.solid.add(new SceneMesh.MeshQuad(xs,ys,zs,0,0,0,1,1,1,1,0,"litematicrender:overview",v,1,1,1,1,null,SceneMesh.Layer.SOLID).colors(new int[]{v,v,v,v}));mesh.quadCount++;
            }
        }return mesh;
    }
}
