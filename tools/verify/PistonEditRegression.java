import io.github.litematicrender.serve.RenderEngine;
import io.github.litematicrender.schematic.BoardSchematic;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import java.nio.file.*;
import java.util.*;

public class PistonEditRegression {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static Map<String,Object> set(int[] p,String id,Map<String,String> states){return Map.of("position",List.of(p[0],p[1],p[2]),"id",id,"states",states);}
    static Map<String,Object> base(String id,String facing,boolean extended){return set(new int[]{0,0,0},id,Map.of("facing",facing,"extended",String.valueOf(extended)));}
    static Map<String,Object> state(RenderEngine engine,int[] p){return ((List<Map<String,Object>>)engine.editStates(List.of(List.of(p[0],p[1],p[2]))).get("blocks")).getFirst();}
    static void isAir(RenderEngine engine,int[] p,String message){check(state(engine,p).get("id").equals("minecraft:air"),message);}
    static void head(RenderEngine engine,int[] p,String type,String facing){var state=state(engine,p);check(state.get("id").equals("minecraft:piston_head"),"missing head "+Arrays.toString(p));check(state.get("states").equals(Map.of("facing",facing,"type",type,"short","false")),"head state "+state);}
    static Map<String,Object> cell(int x,int y,int z,String id,Map<String,String> states){return Map.of("x",x,"y",y,"z",z,"id",id,"states",states);}
    static void fails(RenderEngine engine,Map<String,Object> request,String message)throws Exception{
        try{engine.editBlock(request);throw new AssertionError("edit should fail: "+message);}catch(IllegalArgumentException error){check(error.getMessage().contains(message),"unexpected rejection "+error.getMessage());}
    }
    public static void main(String[] args)throws Exception{
        Path home=Path.of(args[0]).toAbsolutePath(),out=home.resolve("tools/verify/piston-output");Files.createDirectories(out);Path source=out.resolve("piston.litematic");
        BoardSchematic.write(source,List.of(cell(0,0,0,"piston",Map.of("facing","north","extended","false")),cell(5,0,5,"stone",Map.of())));byte[] original=Files.readAllBytes(source);
        String[] directions={"north","east","south","west","up","down"};int[][] vectors={{0,0,-1},{1,0,0},{0,0,1},{-1,0,0},{0,1,0},{0,-1,0}};
        try(var engine=new RenderEngine(home)){
            engine.loadResources(engine.defaultPackRoot());
            for(String id:List.of("piston","sticky_piston"))for(int i=0;i<directions.length;i++){
                String facing=directions[i],type=id.equals("piston")?"normal":"sticky";int[] target=vectors[i];engine.open(source);
                var initial=engine.editBlock(base(id,facing,false));int count=((Number)initial.get("undo")).intValue();
                var extended=engine.editBlock(base(id,facing,true));head(engine,target,type,facing);check(((Number)extended.get("undo")).intValue()==count+1,"extension not one undo");check(((Number)((Map<?,?>)extended.get("info")).get("totalBlocks")).intValue()==3,"head not counted as second block");
                var unchanged=engine.editBlock(base(id,facing,true));check(unchanged.get("changed").equals(false)&&unchanged.get("undo").equals(extended.get("undo")),"unchanged piston adds history");
                var patch=engine.editPatch(out.resolve("patch"),null,null);check(((List<?>)patch.get("cells")).size()==36,"local preview did not cover both cells");check(((Number)patch.get("quads")).intValue()>0,"head preview empty");
                Path exported=out.resolve(id+"-"+facing+".litematic");engine.exportEdited(exported);
                engine.editBlock(base(id,facing,false));isAir(engine,target,"retraction left head");engine.editBlock(Map.of("action","undo"));head(engine,target,type,facing);engine.editBlock(Map.of("action","redo"));isAir(engine,target,"redo retraction left head");engine.editBlock(Map.of("action","undo"));
                int next=(i+1)%directions.length;engine.editBlock(base(id,directions[next],true));isAir(engine,target,"rotation left old head");head(engine,vectors[next],type,directions[next]);check(((Number)engine.searchBlocks("piston_head",0).get("total")).intValue()==1,"multiple heads after rotation");
                engine.editBlock(set(vectors[next],"air",Map.of()));isAir(engine,new int[]{0,0,0},"head deletion left base");isAir(engine,vectors[next],"head deletion failed");engine.editBlock(Map.of("action","undo"));head(engine,vectors[next],type,directions[next]);
                engine.editBlock(set(new int[]{0,0,0},"stone",Map.of()));isAir(engine,vectors[next],"base replacement left head");engine.editBlock(Map.of("action","undo"));head(engine,vectors[next],type,directions[next]);
                engine.editBlock(set(new int[]{0,0,0},"air",Map.of()));isAir(engine,vectors[next],"base deletion left head");engine.editBlock(Map.of("action","undo"));
                engine.open(exported);head(engine,target,type,facing);check(state(engine,new int[]{0,0,0}).get("id").equals("minecraft:"+id),"export lost base type");check(((Map<?,?>)state(engine,new int[]{0,0,0}).get("states")).get("extended").equals("true"),"export lost extension");
            }
            engine.open(source);var limited=new LinkedHashMap<>(base("piston","north",true));limited.put("selection",Map.of("min",List.of(0,0,0),"max",List.of(1,1,1)));fails(engine,limited,"超出当前渲染选区");check(((Map<?,?>)state(engine,new int[]{0,0,0}).get("states")).get("extended").equals("false"),"bounds failure changed base");isAir(engine,vectors[0],"bounds failure added head");
            engine.editBlock(base("piston","north",true));var limitedRetraction=new LinkedHashMap<>(base("piston","north",false));limitedRetraction.put("selection",limited.get("selection"));fails(engine,limitedRetraction,"超出当前渲染选区");head(engine,vectors[0],"normal","north");
            engine.editBlock(base("sticky_piston","north",true));head(engine,vectors[0],"sticky","north");engine.editBlock(Map.of("action","undo"));head(engine,vectors[0],"normal","north");
            engine.open(source);engine.editBlock(base("piston","north",true));engine.commitEdits();engine.editBlock(base("piston","north",false));isAir(engine,vectors[0],"committed piston did not retract");engine.editBlock(Map.of("action","undo"));head(engine,vectors[0],"normal","north");
            Path blocked=out.resolve("blocked.litematic");BoardSchematic.write(blocked,List.of(cell(0,0,0,"piston",Map.of("facing","east","extended","false")),cell(1,0,0,"chest",Map.of("facing","north","type","single","waterlogged","false"))));
            var data=NbtFileUtils.readCompressed(blocked);var region=data.getCompound("Regions").orElseThrow().getCompound("设计").orElseThrow();var items=new NbtList();items.add(new NbtCompound().putInt("Slot",0).putString("id","minecraft:diamond").putInt("count",7));var containers=new NbtList();containers.add(new NbtCompound().putInt("x",1).putInt("y",0).putInt("z",0).putString("id","minecraft:chest").put("Items",items));region.put("TileEntities",containers);NbtFileUtils.writeCompressed(blocked,data);
            engine.open(blocked);fails(engine,base("piston","east",true),"被占用");var container=(Map<?,?>)engine.inspectPosition(new int[]{1,0,0}).get("container");check(((List<?>)container.get("slotItems")).size()==1,"blocked extension removed inventory");check(((Number)engine.editBlock(Map.of("action","undo")).get("undo")).intValue()==0,"failed extension changed history");
            fails(engine,Map.of("changes",List.of(set(new int[]{0,0,0},"piston",Map.of("facing","east","extended","true")),set(new int[]{5,0,5},"diamond_block",Map.of()))),"被占用");isAir(engine,new int[]{5,0,5},"failed batch partially applied");
            engine.editBlock(Map.of("changes",List.of(set(new int[]{1,0,0},"air",Map.of()),base("piston","east",true))));head(engine,vectors[1],"normal","east");engine.editBlock(Map.of("action","undo"));check(((List<?>)((Map<?,?>)engine.inspectPosition(new int[]{1,0,0}).get("container")).get("slotItems")).size()==1,"atomic undo did not restore chest contents");
            Path edge=out.resolve("edge.litematic");BoardSchematic.write(edge,List.of(cell(0,0,0,"piston",Map.of("facing","east","extended","false"))));var edgeData=NbtFileUtils.readCompressed(edge);edgeData.getCompound("Regions").orElseThrow().getCompound("设计").orElseThrow().put("Position",new NbtCompound().putInt("x",30000000).putInt("y",0).putInt("z",0));NbtFileUtils.writeCompressed(edge,edgeData);engine.open(edge);fails(engine,set(new int[]{30000000,0,0},"piston",Map.of("facing","east","extended","true")),"坐标范围");
        }
        check(Arrays.equals(original,Files.readAllBytes(source)),"source changed");
        System.out.println("PASS: normal / sticky six-direction two-cell extension, head states / preview / counts, atomic history, retraction / rotation / deleting either cell, replacement, blocked inventories / rollback, selection bounds, commit / export roundtrip and coordinate limits");
    }
}
