package io.github.litematicrender.render.scene;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.render.*;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockState;
import java.util.*;

/** Sparse color occupancy aligned to ownership sections for atomic detail replacement. */
public final class OverviewMesh implements StreamMeshExporter.Source {
    private static final int[][] DIRECTIONS={{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
    private static final int[][][] CORNERS={{{0,0,1},{0,0,0},{1,0,0},{1,0,1}},{{0,1,0},{0,1,1},{1,1,1},{1,1,0}},{{1,1,0},{1,0,0},{0,0,0},{0,1,0}},{{0,1,1},{0,0,1},{1,0,1},{1,1,1}},{{0,1,0},{0,0,0},{0,0,1},{0,1,1}},{{1,1,1},{1,0,1},{1,0,0},{1,1,0}}};
    private static final class Cell {
        final SectionVisibility.Position position;long red,green,blue,count;int color;
        Cell(SectionVisibility.Position position){this.position=position;}
    }
    private final Map<SectionVisibility.Position,List<Cell>> sections=new LinkedHashMap<>();
    private final Map<SectionVisibility.Position,Cell> cells=new HashMap<>();
    private final int step;
    private final float[] bounds={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
    public OverviewMesh(LitematicaSchematic schematic,ModelBaker baker,SpriteAtlas atlas) {
        long[] lo={Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE},hi={Long.MIN_VALUE,Long.MIN_VALUE,Long.MIN_VALUE};
        for(var r:schematic.regions().values()){var p=r.blockOrigin();int[] a={p.x(),p.y(),p.z()},n={r.blocks.sizeX,r.blocks.sizeY,r.blocks.sizeZ};for(int i=0;i<3;i++){lo[i]=Math.min(lo[i],a[i]);hi[i]=Math.max(hi[i],(long)a[i]+n[i]-1);}}
        double volume=1;for(int i=0;i<3;i++)volume*=Math.max(1,Math.floorDiv(hi[i],8)-Math.floorDiv(lo[i],8)+1);
        step=volume<=1_000_000?8:16;var palette=new HashMap<BlockState,Integer>();
        for(var r:schematic.regions().values()) {var origin=r.blockOrigin();Cell[] previous={null};
            r.blocks.forEachNonAir((x,y,z,state)->{
                int rgb=palette.computeIfAbsent(state,s->color(s,baker,atlas));if(rgb<0)return;
                int wx=origin.x()+x,wy=origin.y()+y,wz=origin.z()+z,cx=Math.floorDiv(wx,step),cy=Math.floorDiv(wy,step),cz=Math.floorDiv(wz,step);Cell cell=previous[0];
                if(cell==null||cell.position.x()!=cx||cell.position.y()!=cy||cell.position.z()!=cz){var key=new SectionVisibility.Position(cx,cy,cz);cell=cells.computeIfAbsent(key,Cell::new);previous[0]=cell;}
                cell.count++;cell.red+=rgb>>16&255;cell.green+=rgb>>8&255;cell.blue+=rgb&255;
                bounds[0]=Math.min(bounds[0],wx);bounds[1]=Math.min(bounds[1],wy);bounds[2]=Math.min(bounds[2],wz);bounds[3]=Math.max(bounds[3],wx+1);bounds[4]=Math.max(bounds[4],wy+1);bounds[5]=Math.max(bounds[5],wz+1);
            });
            for(var entity:r.entities)if(EntityGeometry.supported(entity)&&(!entity.data().getBooleanOrDefault("Invisible",false)||EntityGeometry.frame(entity))){
                double[] at={r.position.x()+entity.x(),r.position.y()+entity.y(),r.position.z()+entity.z()};
                for(int axis=0;axis<3;axis++){bounds[axis]=Math.min(bounds[axis],(float)(at[axis]-(axis==1?0:2)));bounds[axis+3]=Math.max(bounds[axis+3],(float)(at[axis]+(axis==1?3:2)));}
            }
        }
        for(var cell:cells.values()){
            cell.color=(int)(cell.red/cell.count)<<16|(int)(cell.green/cell.count)<<8|(int)(cell.blue/cell.count);var p=cell.position;
            var key=new SectionVisibility.Position(Math.floorDiv(p.x()*step,16),Math.floorDiv(p.y()*step,16),Math.floorDiv(p.z()*step,16));sections.computeIfAbsent(key,k->new ArrayList<>()).add(cell);
        }
        if(!Float.isFinite(bounds[0]))Arrays.fill(bounds,0);
    }
    private static int color(BlockState state,ModelBaker baker,SpriteAtlas atlas) {
        if(Set.of("minecraft:barrier","minecraft:light","minecraft:structure_void").contains(state.blockId))return -1;
        if(state.blockId.equals("minecraft:water"))return 0x3f76e4;if(state.blockId.equals("minecraft:lava"))return 0xef6a13;
        var variants=baker.selectVariants(state.blockId,state);if(variants.isEmpty())return 0x999999;
        var model=baker.bake(variants.getFirst());String particle=model.particleSprite==null?"minecraft:missingno":model.particleSprite;if(!particle.contains(":"))particle="minecraft:"+particle;
        var sprite=atlas.getOrMissing(particle);
        if(sprite.name.equals("minecraft:missingno"))for(var face:model.all){String id=face.spriteId.contains(":")?face.spriteId:"minecraft:"+face.spriteId;var candidate=atlas.getOrMissing(id);if(!candidate.name.equals("minecraft:missingno")){sprite=candidate;break;}}
        if(sprite.name.equals("minecraft:missingno"))return 0x999999;var image=sprite.sheet;
        long red=0,green=0,blue=0,n=0;int stride=Math.max(1,image.pixels().length/1024);for(int i=0;i<image.pixels().length;i+=stride){int p=image.pixels()[i];if((p>>>24)<32)continue;red+=p>>16&255;green+=p>>8&255;blue+=p&255;n++;}return n==0?0x999999:(int)(red/n)<<16|(int)(green/n)<<8|(int)(blue/n);
    }
    public int step(){return step;}
    public float[] bounds(){return bounds.clone();}
    public List<SectionVisibility.Position> sections(){return sections.entrySet().stream().filter(entry->entry.getValue().stream().anyMatch(cell->{var p=cell.position;for(var d:DIRECTIONS)if(!cells.containsKey(new SectionVisibility.Position(p.x()+d[0],p.y()+d[1],p.z()+d[2])))return true;return false;})).map(Map.Entry::getKey).toList();}
    public void prepareStreaming(){}
    public boolean fixedLevels(){return true;}
    public SceneMesh buildSection(SectionVisibility.Position position) {
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();var mesh=new SceneMesh();
        for(var cell:sections.getOrDefault(position,List.of())) {var p=cell.position;int x=p.x(),y=p.y(),z=p.z();
            for(int face=0;face<6;face++){int[] d=DIRECTIONS[face];if(cells.containsKey(new SectionVisibility.Position(x+d[0],y+d[1],z+d[2])))continue;
                float[] xs=new float[4],ys=new float[4],zs=new float[4];for(int c=0;c<4;c++){xs[c]=(x+CORNERS[face][c][0])*step;ys[c]=(y+CORNERS[face][c][1])*step;zs[c]=(z+CORNERS[face][c][2])*step;}
                float shade=face==0?.5f:face==1?1:face<4?.8f:.6f;int rgb=cell.color,v=(int)((rgb>>16&255)*shade)<<16|(int)((rgb>>8&255)*shade)<<8|(int)((rgb&255)*shade);
                mesh.solid.add(new SceneMesh.MeshQuad(xs,ys,zs,0,0,0,1,1,1,1,0,"litematicrender:overview",v,1,1,1,1,null,SceneMesh.Layer.SOLID).colors(new int[]{v,v,v,v}));mesh.quadCount++;
            }
        }return mesh;
    }
}
