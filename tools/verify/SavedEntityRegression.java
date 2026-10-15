package io.github.litematicrender.schematic;

import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.model.*;
import io.github.litematicrender.resource.*;
import io.github.litematicrender.serve.*;
import io.github.litematicrender.world.*;
import java.nio.file.*;
import java.util.*;

public final class SavedEntityRegression {
    static Path root,output;static BlockRegistry registry;static ModelBaker baker;static Colormaps colors;static SpriteAtlas atlas;
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static String json(Object value)throws Exception{var method=JsonRpcServer.class.getDeclaredMethod("toJson",Object.class);method.setAccessible(true);return (String)method.invoke(null,value);}
    static NbtList numbers(double... values){var list=new NbtList();for(double value:values)list.add(new NbtScalars.NbtDouble(value));return list;}
    static NbtCompound entity(String id,double x,double y,double z,float yaw){return new NbtCompound().putString("id","minecraft:"+id).put("Pos",numbers(x,y,z)).put("Rotation",numbers(yaw,0));}
    static NbtCompound item(int slot,String id,int count){return new NbtCompound().putString("id","minecraft:"+id).putInt("count",count).put("Slot",new NbtScalars.NbtByte((byte)slot));}
    static NbtCompound inventory(NbtCompound tag){var items=new NbtList();items.add(item(0,"diamond",64));items.add(item(7,"diamond",32));return tag.put("Items",items);}
    static Path write(String name,List<NbtCompound> entities,Vec3i position)throws Exception{
        var list=new NbtList();entities.forEach(list::add);var palette=new NbtList();palette.add(new NbtCompound().putString("Name","minecraft:air"));
        var pos=new NbtCompound().putInt("x",position.x()).putInt("y",position.y()).putInt("z",position.z());var size=new NbtCompound().putInt("x",1).putInt("y",1).putInt("z",1);
        var region=new NbtCompound().put("Position",pos).put("Size",size).put("BlockStatePalette",palette).put("BlockStates",new NbtScalars.NbtLongArray(new long[1])).put("Entities",list);
        var tag=new NbtCompound().putInt("Version",6).putInt("MinecraftDataVersion",0).put("Metadata",new NbtCompound().putString("Name","指定实体渲染验证").putInt("TotalEntities",entities.size()).put("EnclosingSize",size)).put("Regions",new NbtCompound().put("entities",region));
        var path=output.resolve(name+".litematic");NbtFileUtils.writeCompressed(path,tag);return path;
    }
    static LitematicaSchematic loaded(List<NbtCompound> entities,Vec3i position)throws Exception{var s=LitematicaSchematic.load(write("fixture",entities,position));s.materialize(registry);return s;}
    static SceneMesh scene(LitematicaSchematic schematic){var builder=new SceneBuilder(registry,baker,colors,atlas);for(var r:schematic.regions().values()){var origin=r.blockOrigin();builder.addRegion(r,new int[]{origin.x(),origin.y(),origin.z()});}return builder.build();}
    static SceneMesh single(NbtCompound tag)throws Exception{return scene(loaded(List.of(tag),Vec3i.ZERO));}
    static List<SceneMesh.MeshQuad> all(SceneMesh mesh){var result=new ArrayList<SceneMesh.MeshQuad>();result.addAll(mesh.solid);result.addAll(mesh.cutout);result.addAll(mesh.translucent);return result;}
    static float[] bounds(SceneMesh mesh){var result=new float[]{Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};for(var q:all(mesh)){float[][] points={{q.x0,q.y0,q.z0},{q.x1,q.y1,q.z1},{q.x2,q.y2,q.z2},{q.x3,q.y3,q.z3}};for(var p:points)for(int i=0;i<3;i++){result[i]=Math.min(result[i],p[i]);result[i+3]=Math.max(result[i+3],p[i]);}}return result;}
    static void picture(SceneMesh mesh,String name,int width,int height,float yaw)throws Exception{var settings=new RenderSettings(yaw,25,width,height);settings.transparentBackground=false;settings.backgroundMode="checker";SchematicRenderer.render(null,mesh,colors,atlas,settings).writePng(output.resolve(name));}
    public static void main(String[] args)throws Exception{
        root=Path.of(args[0]).toAbsolutePath();output=Path.of(args[1]).toAbsolutePath();Files.createDirectories(output);System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)){
            registry=BlockRegistry.load(resources);baker=new ModelBaker(resources,registry);colors=new Colormaps();colors.load(resources);atlas=SpriteAtlas.load(resources,"blocks");
            var types=new ArrayList<>(List.of("minecart","chest_minecart","furnace_minecart","hopper_minecart","tnt_minecart","command_block_minecart","spawner_minecart","snow_golem","iron_golem","piglin"));
            for(String wood:EntityGeometry.WOODS){types.add(wood.equals("bamboo")?"bamboo_raft":wood+"_boat");types.add(wood.equals("bamboo")?"bamboo_chest_raft":wood+"_chest_boat");}
            for(String type:types)for(int yaw:List.of(0,90,180,270)){
                var mesh=single(entity(type,0,0,0,yaw));check(mesh.quadCount>0,"missing model: "+type+" / "+yaw);
                for(var q:all(mesh)){check(q.entityIndex==0&&q.regionName.equals("entities"),"entity identity lost");check(Float.isFinite(q.cx)&&Float.isFinite(q.cy)&&Float.isFinite(q.cz),"nonfinite geometry");atlas.getOrMissingEntry(q.spriteId);}
            }
            var legacyBoat=single(entity("boat",0,0,0,90).putString("Type","spruce"));check(all(legacyBoat).stream().allMatch(q->q.spriteId.equals("minecraft:entity/boat/spruce")),"legacy boat Type not applied");
            var v1File=write("version-one",List.of(),Vec3i.ZERO);var v1Root=NbtFileUtils.readCompressed(v1File);v1Root.putInt("Version",1);var v1List=new NbtList();v1List.add(new NbtCompound().put("x",new NbtScalars.NbtDouble(3.25)).putInt("y",0).put("z",new NbtScalars.NbtDouble(-1.5)).put("EntityData",new NbtCompound().putString("id","Boat").putString("Type","birch")));v1Root.getCompound("Regions").orElseThrow().getCompound("entities").orElseThrow().put("Entities",v1List);NbtFileUtils.writeCompressed(v1File,v1Root);var v1=LitematicaSchematic.load(v1File);v1.materialize(registry);check(v1.regions().get("entities").entities.get(0).x()==3.25&&scene(v1).quadCount>0,"v1 wrapped entities not read");
            var noPumpkin=entity("snow_golem",0,0,0,180).put("Pumpkin",new NbtScalars.NbtByte((byte)0));var bareSnow=single(noPumpkin);check(all(bareSnow).stream().noneMatch(q->q.spriteId.contains("pumpkin")),"sheared snow golem still has pumpkin");
            check(single(entity("snow_golem",0,0,0,180)).quadCount>bareSnow.quadCount,"default snow golem missing pumpkin");
            var adult=single(entity("piglin",0,0,0,0));var baby=single(entity("piglin",0,0,0,0).put("IsBaby",new NbtScalars.NbtByte((byte)1)));
            check(bounds(adult)[4]>1.9&&bounds(baby)[4]<1.1,"baby proportions/feet differ from bundled models");
            var north=single(entity("piglin",0,0,0,90));check(bounds(adult)[5]>-bounds(adult)[2]&&-bounds(north)[0]>bounds(north)[3],"entity yaw does not follow Minecraft facing");
            for(int health:List.of(60,40,10)){var damaged=single(entity("iron_golem",0,0,0,0).put("Health",new NbtScalars.NbtFloat(health)));check(all(damaged).stream().allMatch(q->q.spriteId.startsWith("litematicrender:entity/iron_golem/")&&q.image!=null),"crackiness not composited");}
            check(single(entity("piglin",0,0,0,0).put("Invisible",new NbtScalars.NbtByte((byte)1))).quadCount==0,"invisible entity rendered");check(single(entity("cow",0,0,0,0)).quadCount==0,"entity outside requested scope rendered");
            var inspected=new ArrayList<String>();
            for(String type:List.of("chest_minecart","hopper_minecart","oak_chest_boat","bamboo_chest_raft")){
                var schematic=loaded(List.of(inventory(entity(type,.5,0,.5,0))),new Vec3i(-3,2,5));var mesh=scene(schematic);var hit=new ScenePicker(mesh,atlas).pick(new double[]{-2.5,2.4,2},new double[]{0,0,1},20);
                check(hit!=null,"visible entity ray missed "+type);var result=new BlockInspector(schematic,resources).inspect(hit);var container=(Map<?,?>)result.get("container");var rows=(List<?>)container.get("items");var first=(Map<?,?>)rows.get(0);
                check(result.get("kind").equals("entity")&&result.get("position").equals(List.of(-2.5,2.0,5.5)),"entity offset/selection wrong");check(rows.size()==1&&((Number)first.get("count")).intValue()==96&&first.get("stacks").equals(2),"vehicle inventory aggregation wrong");inspected.add(type);
            }
            var passenger=entity("piglin",9999,9999,9999,180).put("UUID",new NbtScalars.NbtIntArray(new int[]{1,2,3,4}));var passengers=new NbtList();passengers.add(passenger);var mounted=entity("minecart",2,0,0,0).put("Passengers",passengers);
            var riding=loaded(List.of(mounted,passenger),Vec3i.ZERO);var snapshots=riding.regions().get("entities").entities;check(snapshots.size()==2&&snapshots.get(1).riding()&&snapshots.get(1).x()==2&&Math.abs(snapshots.get(1).y()+.5125)<1e-6,"passenger duplicated or world Pos used");
            var display=single(entity("minecart",0,0,0,0).put("DisplayState",new NbtCompound().putString("Name","minecraft:gold_block")));check(all(display).stream().anyMatch(q->q.spriteId.contains("gold_block")),"custom minecart display block ignored");
            var negativeFile=write("negative-region",List.of(entity("piglin",-.5,1,.5,0)),new Vec3i(10,0,0));var negativeTag=NbtFileUtils.readCompressed(negativeFile);var negativeRegion=negativeTag.getCompound("Regions").orElseThrow().getCompound("entities").orElseThrow();negativeRegion.getCompound("Size").orElseThrow().putInt("x",-2);negativeRegion.getList("BlockStatePalette").orElseThrow().add(new NbtCompound().putString("Name","minecraft:stone"));negativeRegion.put("BlockStates",new NbtScalars.NbtLongArray(new long[]{5}));NbtFileUtils.writeCompressed(negativeFile,negativeTag);var negative=LitematicaSchematic.load(negativeFile);negative.materialize(registry);var negativeMesh=scene(negative);check(negative.regions().get("entities").blockOrigin().x()==9&&negativeMesh.solid.stream().anyMatch(q->q.blockX==9),"negative region block origin wrong");var negativeHit=new ScenePicker(negativeMesh,atlas).pick(new double[]{9.5,2.4,-3},new double[]{0,0,1},10);var negativePick=new BlockInspector(negative,resources).inspect(negativeHit);check(negativePick.get("position").equals(List.of(9.5,1.0,.5)),"negative region entity origin wrong");
            var equipment=new NbtCompound().put("mainhand",item(0,"golden_sword",1)).put("head",item(0,"golden_helmet",1));var armed=single(entity("piglin",0,0,0,180).put("equipment",equipment));check(all(armed).stream().anyMatch(q->q.spriteId.contains("golden_sword"))&&all(armed).stream().anyMatch(q->q.spriteId.contains("equipment/humanoid/gold")),"piglin basic equipment missing");
            check(!single(entity("oak_boat",0,0,0,0)).waterMask.isEmpty()&&single(entity("bamboo_raft",0,0,0,0)).waterMask.isEmpty(),"boat mask applied to wrong vehicle family");
            var maskScene=new SceneMesh();float[] px={-2,2,2,-2},py={-2,-2,2,2};
            var background=new SceneMesh.MeshQuad(px,py,new float[]{-.5f,-.5f,-.5f,-.5f},0,0,1,0,1,1,0,1,null,0xFF0000FF,1,1,1,1,null,SceneMesh.Layer.SOLID).owner(10,0,0,"entities");maskScene.solid.add(background);
            var waterImage=new Image(1,1);waterImage.setPixel(0,0,0x80FF0000);var waterQuad=new SceneMesh.MeshQuad(px,py,new float[]{0,0,0,0},0,0,1,0,1,1,0,1,null,-1,waterImage,1,1,1,1,null,SceneMesh.Layer.FLUID).owner(20,0,0,"entities");maskScene.fluid.add(waterQuad);maskScene.fluidSorted.add(waterQuad);
            var white=new Image(1,1);white.setPixel(0,0,-1);maskScene.waterMask.add(new SceneMesh.MeshQuad(px,py,new float[]{.25f,.25f,.25f,.25f},0,0,1,0,1,1,0,1,null,-1,white,1,1,1,1,null,SceneMesh.Layer.WATER_MASK));
            var masked=SchematicRenderer.render(null,maskScene,colors,atlas,new RenderSettings(0,0,128,128));check(Image.red(masked.pixel(64,64))==0&&Image.blue(masked.pixel(64,64))>0,"CPU mask painted color or failed to hide water");
            var visible=new ScenePicker(maskScene,atlas).pick(new double[]{0,0,3},new double[]{0,0,-1},10);check(visible!=null&&visible.quad().blockX==10,"masked water intercepted visible boat interior picking");
            var gallery=new ArrayList<NbtCompound>();int i=0;for(String type:types.subList(0,7))gallery.add(entity(type,i++*3,0,0,180));
            i=0;for(String wood:List.of("oak","spruce","birch","cherry","pale_oak","bamboo","poplar")){gallery.add(entity(wood.equals("bamboo")?"bamboo_chest_raft":wood+"_chest_boat",i++*3,0,4,180));}
            gallery.add(entity("snow_golem",0,0,8,180));gallery.add(noPumpkin.put("Pos",numbers(3,0,8)));gallery.add(entity("iron_golem",6,0,8,180));gallery.add(entity("iron_golem",9,0,8,180).put("Health",new NbtScalars.NbtFloat(10)));gallery.add(entity("piglin",12,0,8,180));gallery.add(entity("piglin",15,0,8,180).put("equipment",equipment));gallery.add(entity("piglin",18,0,8,180).put("IsBaby",new NbtScalars.NbtByte((byte)1)));gallery.add(entity("minecart",21,0,8,0).put("Passengers",passengers));
            var galleryFile=write("saved-entities",gallery,Vec3i.ZERO);var gallerySchematic=LitematicaSchematic.load(galleryFile);gallerySchematic.materialize(registry);var galleryMesh=scene(gallerySchematic);picture(galleryMesh,"saved-entities.png",2000,1000,155);
            var vehicle=write("vehicle-inspection",List.of(inventory(entity("chest_minecart",.5,0,.5,180))),Vec3i.ZERO);
            check(RenderDiagnostics.snapshot().isEmpty(),"unresolved entity assets: "+RenderDiagnostics.snapshot());
            if(args.length>2){var reference=LitematicaSchematic.load(Path.of(args[2]));reference.materialize(registry);var referenceMesh=scene(reference);picture(referenceMesh,"reference-with-entities.png",900,900,135);Files.writeString(output.resolve("reference-entities.json"),json(referenceMesh.entityReport));}
            var report=Map.of("pass",true,"types",types.size(),"facings",4,"vehicleInventory",inspected,"gallery",galleryFile.toString(),"vehicleFixture",vehicle.toString());Files.writeString(output.resolve("saved-entity-result.json"),json(report));System.out.println("PASS: "+json(report));
        }
    }
}
