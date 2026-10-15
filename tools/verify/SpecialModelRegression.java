package io.github.litematicrender.schematic;

import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.resource.*;
import io.github.litematicrender.model.*;
import io.github.litematicrender.world.*;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.serve.*;
import java.nio.file.*;
import java.util.*;

public class SpecialModelRegression {
    static BlockRegistry registry; static ModelBaker baker; static Colormaps colors; static SpriteAtlas atlas;
    static Path root,output;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static NbtCompound item(int slot,String id,int count,boolean modern){return new NbtCompound().putInt("Slot",slot).putString("id",id).put(modern?"count":"Count",modern?new NbtScalars.NbtInt(count):new NbtScalars.NbtByte((byte)count));}
    static NbtCompound inventory(NbtCompound...items){var list=new NbtList();for(var item:items)list.add(item);return new NbtCompound().put("Items",list);}
    static SceneMesh scene(BlockState[] blocks,Map<Long,NbtCompound> data){return EntityRegression.fixture(blocks,data);}
    static List<SceneMesh.MeshQuad> all(SceneMesh mesh){var result=new ArrayList<SceneMesh.MeshQuad>();for(var layer:List.of(mesh.solid,mesh.cutout,mesh.translucent))result.addAll(layer);return result;}
    static BlockState state(String id){return registry.getOrMissing("minecraft:"+id).defaultState;}
    static Map<String,Object> inspect(BlockState[] blocks,Map<Long,NbtCompound> data,double x)throws Exception{
        EntityRegression.writeSchematic(blocks,data);
        var schematic=LitematicaSchematic.load(output.resolve("entities.litematic"));schematic.materialize(registry);
        var builder=new SceneBuilder(registry,baker,colors,atlas);
        for(var region:schematic.regions().values())builder.addRegion(region,new int[]{region.position.x(),region.position.y(),region.position.z()});
        var mesh=builder.build();var picker=new ScenePicker(mesh,atlas);
        var hit=picker.pick(new double[]{x,.45,-2},new double[]{0,0,1},20);
        check(hit!=null,"front-face ray missed visible block");
        return new BlockInspector(schematic,baker.resources()).inspect(hit);
    }
    @SuppressWarnings("unchecked") static Map<String,Object> container(Map<String,Object> result){return (Map<String,Object>)result.get("container");}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> items(Map<String,Object> result){return (List<Map<String,Object>>)container(result).get("items");}
    public static void main(String[] args)throws Exception{
        root=Path.of(args[0]).toAbsolutePath();output=Path.of(args[1]).toAbsolutePath();Files.createDirectories(output);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)){
            registry=BlockRegistry.load(resources);baker=new ModelBaker(resources,registry);colors=new Colormaps();colors.load(resources);atlas=SpriteAtlas.load(resources,"blocks");
            EntityRegression.registry=registry;EntityRegression.baker=baker;EntityRegression.colors=colors;EntityRegression.atlas=atlas;EntityRegression.output=output;
            var colorsList=List.of("","white_","orange_","magenta_","light_blue_","yellow_","lime_","pink_","gray_","light_gray_","cyan_","purple_","blue_","brown_","green_","red_","black_");
            for(String color:colorsList)for(String facing:List.of("up","down","north","south","east","west")){
                var model=scene(new BlockState[]{state(color+"shulker_box").with("facing",facing)},Map.of());
                check(all(model).size()==10,"closed shulker geometry missing: "+color+facing);
                check(all(model).stream().allMatch(q->q.spriteId.startsWith("minecraft:entity/shulker/")),"shulker uses wrong texture");
                var variant=baker.selectVariant("minecraft:"+color+"shulker_box",state(color+"shulker_box").with("facing",facing));
                var top=baker.bake(variant).all.stream().filter(q->Math.max(Math.max(q.v0,q.v1),Math.max(q.v2,q.v3))<=.25f&&Math.abs(q.u0-q.u1)+Math.abs(q.v0-q.v1)>.01).findFirst().orElseThrow();
                float x=(top.x0+top.x1+top.x2+top.x3)/4,y=(top.y0+top.y1+top.y2+top.y3)/4,z=(top.z0+top.z1+top.z2+top.z3)/4;
                check(switch(facing){case "up"->y>.99;case "down"->y<.01;case "north"->z<.01;case "south"->z>.99;case "east"->x>.99;default->x<.01;},"shulker lid facing incorrect: "+facing+" "+x+","+y+","+z);
            }
            for(String type:List.of("chest","trapped_chest","ender_chest"))for(String facing:List.of("north","east","south","west")){
                var block=state(type).with("facing",facing);if(!type.equals("ender_chest"))block=block.with("type","single");
                var mesh=scene(new BlockState[]{block},Map.of());check(all(mesh).size()>=15,"chest geometry incomplete");
                check(all(mesh).stream().allMatch(q->q.spriteId.startsWith("minecraft:entity/chest/")),"chest texture fallback");
            }
            for(String attachment:List.of("floor","ceiling","single_wall","double_wall"))for(String facing:List.of("north","east","south","west")){
                var mesh=scene(new BlockState[]{state("bell").with("attachment",attachment).with("facing",facing)},Map.of());
                check(all(mesh).stream().anyMatch(q->q.spriteId.equals("minecraft:entity/bell/bell_body")),"bell body absent: "+attachment);
            }
            var sherds=new NbtList();for(String pattern:List.of("archer","heart","skull","brewer"))sherds.add(new NbtScalars.NbtString("minecraft:"+pattern+"_pottery_sherd"));
            var pot=new NbtCompound().put("sherds",sherds).put("item",item(0,"minecraft:diamond",12,true));
            for(String facing:List.of("north","east","south","west")){
                var mesh=scene(new BlockState[]{state("decorated_pot").with("facing",facing)},Map.of(0L,pot));
                for(String pattern:List.of("archer","heart","skull","brewer"))check(all(mesh).stream().anyMatch(q->q.spriteId.endsWith(pattern+"_pottery_pattern")),"pot pattern absent");
                check(all(mesh).stream().allMatch(q->q.regionName!=null&&q.blockY==0),"overhanging pot lost block ownership");
            }
            var chest=state("chest").with("facing","north").with("type","single");
            var old=inventory(item(7,"minecraft:diamond",64,false));String before=old.toString();var inspected=inspect(new BlockState[]{chest},Map.of(0L,old),.5);
            check(items(inspected).size()==1&&items(inspected).get(0).get("count").equals(64)&&items(inspected).get(0).get("name").equals("钻石"),"legacy slots/count/Chinese names failed");
            check(old.toString().startsWith(before.substring(0,before.length()-1)),"inspection mutated inventory");
            check(items(inspected).get(0).containsKey("icon"),"item icon unavailable");
            check(container(inspect(new BlockState[]{chest},Map.of(),.5)).get("status").equals("missing"),"unsaved inventory labeled empty");
            check(container(inspect(new BlockState[]{chest},Map.of(0L,inventory()),.5)).get("status").equals("empty"),"saved empty inventory not detected");
            check(container(inspect(new BlockState[]{chest},Map.of(0L,new NbtCompound().putString("LootTable","minecraft:chests/simple_dungeon")),.5)).get("status").equals("loot"),"loot table treated as empty");
            check(container(inspect(new BlockState[]{state("ender_chest").with("facing","north")},Map.of(),.5)).get("status").equals("personal"),"ender chest invented empty inventory");
            var left=chest.with("type","left");var right=chest.with("type","right");
            var pair=inspect(new BlockState[]{left,right},Map.of(0L,old,LitematicaSchematic.pack(1,0,0),inventory(item(2,"minecraft:cookie",7,true))),1.5);
            check(container(pair).get("slots").equals(54)&&items(pair).size()==2&&items(pair).get(1).get("displaySlot").equals(29),"double chest not merged left-first");
            var modern=item(0,"minecraft:apple",3,true).put("components",new NbtCompound().putString("minecraft:custom_name","{\"text\":\"测试苹果 <safe>\"}"));
            var box=state("red_shulker_box").with("facing","up");var modernResult=inspect(new BlockState[]{box},Map.of(0L,inventory(modern)),.5);
            check(items(modernResult).get(0).get("name").equals("测试苹果 <safe>")&&items(modernResult).get(0).get("count").equals(3),"modern item components failed");
            var wrapped=new NbtList();wrapped.add(new NbtCompound().putInt("slot",4).put("item",item(0,"minecraft:emerald",9,true)));
            var componentResult=inspect(new BlockState[]{box},Map.of(0L,new NbtCompound().put("components",new NbtCompound().put("minecraft:container",wrapped))),.5);
            check(items(componentResult).get(0).get("displaySlot").equals(4)&&items(componentResult).get(0).get("count").equals(9),"component inventory wrapper failed");
            var repeated=inspect(new BlockState[]{chest},Map.of(0L,inventory(item(0,"minecraft:diamond",2,false),item(4,"minecraft:diamond",64,false),item(5,"minecraft:diamond",3,true))),.5);
            check(items(repeated).size()==1&&((Number)items(repeated).get(0).get("count")).longValue()==69&&items(repeated).get(0).get("stacks").equals(3)&&items(repeated).get(0).get("displaySlots").equals(List.of(0,4,5)),"identical legacy/modern items did not merge quantities/slots");
            var firstMeta=new NbtCompound().putInt("damage",1).putString("name","same");
            var secondMeta=new NbtCompound().putString("name","same").putInt("damage",1);
            var byteMeta=new NbtCompound().putString("name","same").put("damage",new NbtScalars.NbtByte((byte)1));
            var nested=inspect(new BlockState[]{box},Map.of(0L,inventory(item(0,"minecraft:apple",2,true).put("tag",firstMeta),item(1,"minecraft:apple",3,true).put("tag",secondMeta),item(2,"minecraft:apple",4,true).put("tag",byteMeta))),.5);
            check(items(nested).size()==2&&((Number)items(nested).get(0).get("count")).longValue()==5,"compound key order changed identity or typed NBT was lost");
            var innerCount=inspect(new BlockState[]{box},Map.of(0L,inventory(item(0,"minecraft:apple",2,true).put("tag",new NbtCompound().putInt("count",1)),item(1,"minecraft:apple",3,true).put("tag",new NbtCompound().putInt("count",2)))),.5);
            check(items(innerCount).size()==2,"nested count metadata was discarded while merging");
            var duplicatePair=inspect(new BlockState[]{left,right},Map.of(0L,inventory(item(1,"minecraft:emerald",8,false)),LitematicaSchematic.pack(1,0,0),inventory(item(0,"minecraft:emerald",12,true))),1.5);
            check(items(duplicatePair).size()==1&&((Number)items(duplicatePair).get(0).get("count")).longValue()==20&&items(duplicatePair).get(0).get("displaySlots").equals(List.of(1,27)),"identical items across double-chest halves did not merge");
            check(container(inspect(new BlockState[]{left},Map.of(0L,old),.5)).get("status").equals("partial"),"missing double-chest half was labeled complete");
            EntityRegression.writeSchematic(new BlockState[]{chest},Map.of(0L,old));
            var savedRoot=io.github.litematicrender.nbt.io.NbtFileUtils.readCompressed(output.resolve("entities.litematic"));
            var region=savedRoot.getCompound("Regions").orElseThrow().getCompound("entities").orElseThrow();
            region.getCompound("Position").orElseThrow().putInt("x",-7).putInt("y",3).putInt("z",11);
            region.getList("BlockStatePalette").orElseThrow().getCompoundAt(1).orElseThrow().getCompound("Properties").orElseThrow().putString("custom_state","kept");
            io.github.litematicrender.nbt.io.NbtFileUtils.writeCompressed(output.resolve("entities.litematic"),savedRoot);
            var shifted=LitematicaSchematic.load(output.resolve("entities.litematic"));shifted.materialize(registry);
            var shiftedBuilder=new SceneBuilder(registry,baker,colors,atlas);var shiftedRegion=shifted.regions().get("entities");shiftedBuilder.addRegion(shiftedRegion,new int[]{-7,3,11});
            var shiftedHit=new ScenePicker(shiftedBuilder.build(),atlas).pick(new double[]{-6.5,3.45,9},new double[]{0,0,1},20);
            var shiftedInfo=new BlockInspector(shifted,resources).inspect(shiftedHit);
            check(shiftedInfo.get("position").equals(List.of(-7,3,11))&&((Map<?,?>)shiftedInfo.get("properties")).get("custom_state").equals("kept")&&items(shiftedInfo).get(0).get("count").equals(64),"region offset/raw state/NBT ownership failed");
            check(BlockInspector.plain(new NbtScalars.NbtLong(Long.MAX_VALUE),0).equals(Long.toString(Long.MAX_VALUE)),"64-bit metadata lost precision");
            var leaf=new Image(1,1);var opaque=new Image(1,1);opaque.setPixel(0,0,0xFFFFFFFF);var triangles=new SceneMesh();
            for(int i=0;i<2;i++)triangles.cutout.add(new SceneMesh.MeshQuad(new float[]{0,0,1,1},new float[]{0,1,1,0},new float[]{i,i,i,i},0,1,0,0,1,0,1,1,null,-1,i==0?leaf:opaque,1,1,1,1,null,SceneMesh.Layer.CUTOUT).owner(i,0,0,"entities"));
            var hit=new ScenePicker(triangles,atlas).pick(new double[]{.5,.5,-2},new double[]{0,0,1},10);
            check(hit!=null&&hit.quad().blockX==1,"transparent texel blocked visible block picking");
            var potOnly=scene(new BlockState[]{state("decorated_pot")},Map.of(0L,pot));
            var neck=new ScenePicker(potOnly,atlas).pick(new double[]{.5,1.14,-2},new double[]{0,0,1},10);
            check(neck!=null&&neck.quad().blockY==0,"pot neck was attributed to the cell above");
            var displayStates=new BlockState[]{chest,BlockState.air(),left,right,BlockState.air(),box,BlockState.air(),state("bell").with("attachment","floor"),BlockState.air(),state("decorated_pot").with("facing","south")};
            var displayInventory=inventory(item(7,"minecraft:diamond",64,false),item(0,"minecraft:diamond",32,true),item(2,"minecraft:diamond",1,true).put("components",new NbtCompound().putString("minecraft:custom_name","备用钻石")));
            var displayData=Map.of(0L,displayInventory,LitematicaSchematic.pack(2,0,0),inventory(item(1,"minecraft:emerald",8,true)),LitematicaSchematic.pack(3,0,0),inventory(modern,item(1,"minecraft:emerald",12,true)),LitematicaSchematic.pack(5,0,0),inventory(modern),LitematicaSchematic.pack(9,0,0),pot);
            var display=scene(displayStates,displayData);var settings=new RenderSettings(150,25,1500,400);settings.transparentBackground=false;settings.backgroundMode="checker";
            SchematicRenderer.render(null,display,colors,atlas,settings).writePng(output.resolve("special-models.png"));
            EntityRegression.writeSchematic(displayStates,displayData);Files.move(output.resolve("entities.litematic"),output.resolve("inspection.litematic"),StandardCopyOption.REPLACE_EXISTING);
            check(RenderDiagnostics.snapshot().isEmpty(),"special models report unresolved assets: "+RenderDiagnostics.snapshot());
            System.out.println("PASS: 17 shulker colors/six facings, chest families, bell attachments, pottery patterns, geometry picking/alpha, legacy/modern items, double chest, Chinese names/icons, empty/missing/loot/personal inventories");
        }
    }
}
