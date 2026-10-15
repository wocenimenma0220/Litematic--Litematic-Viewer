package io.github.litematicrender.schematic;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.*;
import io.github.litematicrender.model.*;
import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.serve.*;
import io.github.litematicrender.world.*;
import java.nio.file.*;
import java.util.*;

public final class MaterialsAndHeadsRegression {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static NbtCompound item(String id,int count){return new NbtCompound().putString("id","minecraft:"+id).putInt("Count",count);}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Map<String,Object> report,String key){return (List<Map<String,Object>>)report.get(key);}
    static Map<String,Object> row(Map<String,Object> report,String group,String id){return rows(report,group).stream().filter(r->r.get("id").equals("minecraft:"+id)).findFirst().orElseThrow();}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),out=root.resolve("tools/verify/material-output");Files.createDirectories(out);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        int sx=64,sy=4,sz=32;int[] cells=new int[sx*sy*sz];var palette=new ArrayList<NbtCompound>();palette.add(VisualFixRegression.state("air"));palette.add(VisualFixRegression.state("stone"));
        for(int i=0;i<1000;i++)cells[i]=1;
        String[] mobs={"skeleton_skull","wither_skeleton_skull","zombie_head","creeper_head","dragon_head","piglin_head","player_head"};
        for(int i=0;i<mobs.length;i++)for(int direction=0;direction<5;direction++) {
            String name=direction==0?mobs[i]:mobs[i].replace("_head","_wall_head").replace("_skull","_wall_skull");
            palette.add(VisualFixRegression.state(name,"rotation","4","facing",new String[]{"north","north","east","south","west"}[direction]));cells[(sz+20+i)*sx+4+direction*8]=palette.size()-1;
        }
        for(String[] data:new String[][]{{"oak_slab","type","double"},{"oak_door","half","lower"},{"oak_door","half","upper"},{"white_bed","part","foot"},{"white_bed","part","head"},{"chest","type","single"}}){palette.add(VisualFixRegression.state(data[0],data[1],data[2]));cells[(sz+3)*sx+palette.size()]=palette.size()-1;}
        for(int x:new int[]{44,45,47})cells[(sz+3)*sx+x]=palette.size()-1;
        palette.add(VisualFixRegression.state("ender_chest"));cells[(sz+3)*sx+46]=palette.size()-1;
        int extra=4096;
        for(var data:List.of(new Object[]{"water",880},new Object[]{"frosted_ice",900},new Object[]{"ice",10},new Object[]{"lava",2},new Object[]{"powder_snow",2},new Object[]{"bamboo_sapling",1},new Object[]{"big_dripleaf_stem",1},new Object[]{"potted_azalea_bush",1},new Object[]{"potted_flowering_azalea_bush",1},new Object[]{"brain_coral_wall_fan",1},new Object[]{"dead_brain_coral_wall_fan",1},new Object[]{"oak_trapdoor",1})) {
            palette.add(VisualFixRegression.state((String)data[0],"level","0","waterlogged",data[0].equals("oak_trapdoor")?"true":"false"));for(int i=0;i<(Integer)data[1];i++)cells[extra++]=palette.size()-1;
        }
        Path fixture=VisualFixRegression.write(out.resolve("materials-heads.litematic"),sx,sy,sz,palette,cells);
        var nbt=NbtFileUtils.readCompressed(fixture);var region=nbt.getCompound("Regions").orElseThrow().getCompound("test").orElseThrow();var tags=new NbtList();var inventory=new NbtList();
        inventory.add(item("snowball",216));inventory.add(item("diamond_sword",14));inventory.add(item("dirt",863));inventory.add(item("stone",864));
        inventory.add(item("water_bucket",2));inventory.add(item("lava_bucket",1));
        var custom=item("egg",27).put("components",new NbtCompound().putInt("minecraft:max_stack_size",2));inventory.add(custom);
        var nested=new NbtList();nested.add(item("iron_ingot",64));inventory.add(item("shulker_box",1).put("tag",new NbtCompound().put("BlockEntityTag",new NbtCompound().put("Items",nested))));
        var bundle=new NbtList();bundle.add(item("gold_ingot",32));inventory.add(item("bundle",1).put("components",new NbtCompound().put("minecraft:bundle_contents",bundle)));
        tags.add(new NbtCompound().putString("id","minecraft:chest").putInt("x",43).putInt("y",1).putInt("z",3).put("Items",inventory));
        tags.add(new NbtCompound().putString("id","minecraft:chest").putInt("x",44).putInt("y",1).putInt("z",3).putString("LootTable","minecraft:chests/test"));
        tags.add(new NbtCompound().putString("id","minecraft:chest").putInt("x",47).putInt("y",1).putInt("z",3).put("Items",new NbtList()));region.put("TileEntities",tags);NbtFileUtils.writeCompressed(fixture,nbt);
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)) {
            var registry=BlockRegistry.load(resources);var baker=new ModelBaker(resources,registry);var colors=new Colormaps();colors.load(resources);var atlas=SpriteAtlas.load(resources,"blocks");
            var schematic=LitematicaSchematic.load(fixture);schematic.materialize(registry);var report=new MaterialList(resources,root).collect(schematic);
            check(row(report,"building","oak_slab").get("count").equals(2L),"double slab amount");check(row(report,"building","oak_door").get("count").equals(1L),"upper door counted twice");check(row(report,"building","white_bed").get("count").equals(1L),"bed head counted twice");
            check(row(report,"contents","snowball").get("boxes").equals(1L),"16-stack half-box threshold");check(row(report,"contents","diamond_sword").get("boxes").equals(1L),"unstackable half-box threshold");check(!row(report,"contents","dirt").containsKey("boxes"),"below half-box displayed");check(row(report,"contents","stone").get("boxes").equals(1L),"exact half-box hidden");
            check(row(report,"contents","egg").get("maxStack").equals(2),"custom stack limit ignored");check(row(report,"contents","iron_ingot").get("count").equals(64L),"nested shulker contents missing");check(!((List<?>)report.get("warnings")).isEmpty(),"unexpanded loot was not identified");
            check(row(report,"contents","gold_ingot").get("count").equals(32L),"nested bundle contents missing");
            check(((List<?>)report.get("warnings")).stream().anyMatch(w->w.toString().contains("有容器未保存库存")),"missing container tag was not identified");
            check(((List<?>)report.get("warnings")).stream().anyMatch(w->w.toString().contains("末影箱")),"personal ender inventory was not identified");
            var reusable=new MaterialList(resources,root);check(reusable.collect(schematic).equals(reusable.collect(schematic)),"repeated collection double-counted materials");
            check(row(report,"building","frosted_ice").get("maxStack").equals(64),"frosted ice estimate missing");check(row(report,"building","frosted_ice").get("estimatedItem").equals("minecraft:ice"),"frosted ice presented as obtainable item");
            check(((List<?>)report.get("warnings")).stream().noneMatch(w->w.toString().contains("未知堆叠上限")),"fixture contains an unresolved block-to-item mapping");
            check(rows(report,"building").stream().allMatch(r->r.get("icon").toString().startsWith("data:image/png;base64,")),"fixture icon missing");
            var converted=MaterialList.waterAsIce(report);check(row(converted,"building","ice").get("count").equals(891L),"water/source/waterlogged + existing ice total incorrect");check(row(converted,"building","ice").get("boxes").equals(1L),"converted ice box amount not recomputed");
            check(rows(converted,"building").stream().noneMatch(r->r.get("id").equals("minecraft:water_bucket")),"water buckets retained after conversion");check(row(report,"building","water_bucket").get("count").equals(881L),"conversion mutated cached original");check(converted.get("contents").equals(report.get("contents")),"conversion changed saved inventory");check(row(converted,"building","lava_bucket").get("count").equals(2L),"lava converted to ice");
            var allPalette=new ArrayList<NbtCompound>();allPalette.add(VisualFixRegression.state("air"));try(var files=Files.list(root.resolve("assets/vanilla/assets/minecraft/blockstates"))){for(Path blockFile:files.filter(p->p.toString().endsWith(".json")).toList())allPalette.add(VisualFixRegression.state(blockFile.getFileName().toString().replace(".json","")));}
            int allSize=(allPalette.size()+127)/128;int[] allCells=new int[128*allSize];for(int i=1;i<allPalette.size();i++)allCells[i-1]=i;
            Path allFixture=VisualFixRegression.write(out.resolve("all-materials.litematic"),128,1,allSize,allPalette,allCells);
            var allNbt=NbtFileUtils.readCompressed(allFixture);var allRegion=allNbt.getCompound("Regions").orElseThrow().getCompound("test").orElseThrow();var allItems=new NbtList();
            try(var files=Files.list(root.resolve("assets/vanilla/assets/minecraft/items"))){for(Path itemFile:files.filter(p->p.toString().endsWith(".json")).toList())allItems.add(item(itemFile.getFileName().toString().replace(".json",""),1));}
            var allTags=new NbtList();allTags.add(new NbtCompound().putString("id","minecraft:chest").put("Items",allItems));allRegion.put("TileEntities",allTags);NbtFileUtils.writeCompressed(allFixture,allNbt);
            var allSchematic=LitematicaSchematic.load(allFixture);allSchematic.materialize(registry);var allReport=new MaterialList(resources,root).collect(allSchematic);
            var unknown=((List<?>)allReport.get("warnings")).stream().filter(w->w.toString().contains("未知堆叠上限")).toList();check(unknown.isEmpty(),"Unmapped vanilla blocks/items: "+unknown);
            var missingIcons=new ArrayList<String>();for(String group:List.of("building","contents"))for(var r:rows(allReport,group))if(r.get("icon").toString().isBlank())missingIcons.add(r.get("id").toString());
            Files.writeString(out.resolve("icon-coverage.json"),"{\"blocks\":"+(allPalette.size()-1)+",\"items\":"+allItems.size()+",\"missingIcons\":["+String.join(",",missingIcons.stream().map(id->"\""+id+"\"").toList())+"]}");
            check(missingIcons.isEmpty(),"Missing vanilla icons: "+missingIcons);
            var builder=LodRegression.builder(schematic,registry,baker,colors,atlas,true);var mesh=builder.build();
            for(String kind:Arrays.copyOf(mobs,6))check(VisualFixRegression.quads(mesh).stream().anyMatch(q->q.spriteId.equals("litematicrender:mob_head/"+kind)),"mob skull texture absent: "+kind);
            for(var q:VisualFixRegression.quads(mesh))if(q.regionName!=null&&q.spriteId.contains("steve"))check(q.blockZ==26,"non-player skull fell back to Steve");
            MeshExporter.write(mesh,atlas,out.resolve("heads.bin"));atlas.stitched.writePng(out.resolve("heads-atlas.png"));SchematicRenderer.render(null,mesh,colors,atlas,new RenderSettings(30,30,1200,700)).writePng(out.resolve("heads.png"));
            var stream=LodRegression.builder(schematic,registry,baker,colors,atlas,true);MeshExporter.writeStreaming(stream,atlas,out.resolve("heads.lmesh"));
            check(LodRegression.geometry(mesh).equals(stream.sections().stream().map(stream::buildSection).map(LodRegression::geometry).collect(HashSet<String>::new,Set::addAll,Set::addAll)),"section lighting/head geometry mismatch");
        }
        try(var engine=new RenderEngine(root)) {engine.open(fixture);for(String extension:List.of("xlsx","csv"))for(String scope:List.of("combined","separate","building","contents"))engine.exportMaterials(out.resolve(scope+"."+extension),scope);engine.exportMaterials(out.resolve("ice-converted.xlsx"),"combined",true);engine.exportMaterials(out.resolve("ice-converted.csv"),"combined",true);}
        System.out.println("PASS: seven skull types/four wall directions, full/section geometry, block-to-item amounts, saved/nested inventory, 64/16/1/custom stacks, half-box threshold, all XLSX/CSV export scopes");
    }
}
