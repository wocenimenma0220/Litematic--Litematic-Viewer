package io.github.litematicrender.serve;

import io.github.litematicrender.nbt.*;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.schematic.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.world.BlockState;
import io.github.litematicrender.util.MiniJson;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Required placeable items and saved container stacks, counted without building a render mesh. */
public final class MaterialList {
    private final Map<String,String> names=new HashMap<>();
    private final Map<String,Integer> limits=new HashMap<>();
    private final Map<String,String> blockEstimates=new HashMap<>();
    private final MaterialIcons icons;
    private final Map<String,Entry> building=new HashMap<>(),contents=new HashMap<>();
    private final Set<String> warnings=new LinkedHashSet<>();
    private static final Set<String> DOUBLE_PLANTS=Set.of("sunflower","lilac","rose_bush","peony","tall_grass","tall_dry_grass","large_fern","pitcher_plant","pitcher_crop","tall_seagrass");
    private static final Set<String> SKIP=Set.of("air","cave_air","void_air","moving_piston","piston_head","nether_portal","end_portal","end_gateway","fire","soul_fire","bubble_column","structure_void");
    private static final class Entry { String id,name;long count;int stack;Entry(String id,String name,int stack){this.id=id;this.name=name;this.stack=stack;} }
    public MaterialList(ResourceManager resources,java.nio.file.Path home) throws java.io.IOException {
        icons=new MaterialIcons(resources);
        for(String lang:List.of("en_us","zh_cn")) {
            var packs=resources.getResourceStack("minecraft","lang/"+lang+".json");
            for(int i=packs.size()-1;i>=0;i--)try{MiniJson.parseObject(new String(packs.get(i),StandardCharsets.UTF_8)).forEach((k,v)->{if(v instanceof String text)names.put(k,text);});}catch(Exception ignored){}
        }
        var bytes=java.nio.file.Files.readAllBytes(home.resolve("assets/item-stacks.json"));
        var metadata=MiniJson.parseObject(new String(bytes,StandardCharsets.UTF_8));var data=MiniJson.asObject(metadata.get("items"));
        data.forEach((k,v)->limits.put(k,((Number)v).intValue()));
        var estimates=MiniJson.asObject(metadata.get("blockEstimates"));if(estimates!=null)estimates.forEach((k,v)->blockEstimates.put(k,(String)v));
    }
    private String name(String id){String key=id.replace(':','.');return names.getOrDefault("item."+key,names.getOrDefault("block."+key,id));}
    private void add(Map<String,Entry> target,String key,String id,String label,long count,int limit) {
        if(count<=0||id.equals("minecraft:air"))return;
        if(limit==0)warnings.add("未知堆叠上限："+id+"，不推测盒数。");
        var entry=target.computeIfAbsent(key,k->new Entry(id,label,limit));entry.count=Math.addExact(entry.count,count);
    }
    public Map<String,Object> collect(LitematicaSchematic schematic) {
        building.clear();contents.clear();warnings.clear();
        for(var region:schematic.regions().values()) {
            var counts=new HashMap<BlockState,Long>();region.blocks.forEachNonAir((x,y,z,s)->counts.merge(s,1L,Math::addExact));
            counts.forEach(this::block);
            long expected=0,saved=0;
            for(var entry:counts.entrySet())if(BlockInspector.capacity(entry.getKey().blockId)>0) {
                if(entry.getKey().blockId.equals("minecraft:ender_chest"))warnings.add("末影箱的个人库存不保存在投影中，无法统计。");
                else expected=Math.addExact(expected,entry.getValue());
            }
            for(var tag:region.blockEntities.values()) {
                inventory(tag,1,0);
                var state=region.blocks.get(tag.getIntOrDefault("x",0),tag.getIntOrDefault("y",0),tag.getIntOrDefault("z",0));
                if(BlockInspector.capacity(state.blockId)>0&&!state.blockId.equals("minecraft:ender_chest"))saved++;
            }
            if(saved<expected)warnings.add("有容器未保存库存，材料表仅包含已知内容。");
            for(var entity:region.entities){inventory(entity.data(),1,0);entity(entity);}
        }
        var result=new LinkedHashMap<String,Object>();result.put("building",rows(building));result.put("contents",rows(contents));
        result.put("warnings",new ArrayList<>(warnings));result.put("boxSlots",27);result.put("boxThreshold",.5);
        result.put("waterBucketCount",building.containsKey("minecraft:water_bucket")?building.get("minecraft:water_bucket").count:0L);result.put("waterAsIce",false);
        result.put("ice",Map.of("id","minecraft:ice","name",name("minecraft:ice"),"maxStack",limits.get("minecraft:ice"),"icon",icons.icon("minecraft:ice")));return result;
    }
    private void block(BlockState state,long count) {
        String id=state.blockId,path=id.substring(id.indexOf(':')+1);long multiplier=1;
        if(!id.startsWith("minecraft:")){add(building,id,id,name(id),count,limits.getOrDefault(id,0));return;}
        if(SKIP.contains(path))return;
        if("true".equals(state.getValue("waterlogged")))add(building,"minecraft:water_bucket","minecraft:water_bucket",name("minecraft:water_bucket"),count,limits.get("minecraft:water_bucket"));
        if(path.startsWith("potted_")) {
            add(building,"minecraft:flower_pot","minecraft:flower_pot",name("minecraft:flower_pot"),count,64);
            String plant="minecraft:"+switch(path.substring(7)){case "azalea_bush"->"azalea";case "flowering_azalea_bush"->"flowering_azalea";default->path.substring(7);};add(building,plant,plant,name(plant),count,limits.getOrDefault(plant,0));return;
        }
        if(path.endsWith("candle_cake")) {
            add(building,"minecraft:cake","minecraft:cake",name("minecraft:cake"),count,1);
            String candle="minecraft:"+path.substring(0,path.length()-5);add(building,candle,candle,name(candle),count,64);return;
        }
        if((path.endsWith("_door")||DOUBLE_PLANTS.contains(path))&&"upper".equals(state.getValue("half")))return;
        if(path.endsWith("_bed")&&"head".equals(state.getValue("part")))return;
        if(path.endsWith("_slab")&&"double".equals(state.getValue("type")))multiplier=2;
        if(path.equals("snow"))multiplier=integer(state.getValue("layers"),1);
        if(path.equals("turtle_egg"))multiplier=integer(state.getValue("eggs"),1);
        if(path.equals("sea_pickle"))multiplier=integer(state.getValue("pickles"),1);
        if(path.equals("candle")||path.endsWith("_candle"))multiplier=integer(state.getValue("candles"),1);
        if(path.equals("water")||path.equals("lava")) {
            if(!"0".equals(state.getValue("level")))return;
            id="minecraft:"+path+"_bucket";
        } else if(path.endsWith("_wall_sign"))id=id.replace("_wall_sign","_sign");
        else if(path.endsWith("_wall_hanging_sign"))id=id.replace("_wall_hanging_sign","_hanging_sign");
        else if(path.endsWith("_wall_banner"))id=id.replace("_wall_banner","_banner");
        else if(path.endsWith("_wall_head")||path.endsWith("_wall_skull"))id=id.replace("_wall_","_");
        else if(path.endsWith("_wall_torch"))id=id.replace("_wall_torch","_torch");
        else if(path.endsWith("_coral_wall_fan"))id=id.replace("_coral_wall_fan","_coral_fan");
        else id=switch(path) {
            case "wall_torch"->"minecraft:torch";case "redstone_wire"->"minecraft:redstone";case "tripwire"->"minecraft:string";
            case "wheat"->"minecraft:wheat_seeds";case "carrots"->"minecraft:carrot";case "potatoes"->"minecraft:potato";
            case "beetroots"->"minecraft:beetroot_seeds";case "cocoa"->"minecraft:cocoa_beans";case "sweet_berry_bush"->"minecraft:sweet_berries";
            case "pumpkin_stem","attached_pumpkin_stem"->"minecraft:pumpkin_seeds";case "melon_stem","attached_melon_stem"->"minecraft:melon_seeds";
            case "kelp_plant"->"minecraft:kelp";case "tall_seagrass"->"minecraft:seagrass";case "powder_snow"->"minecraft:powder_snow_bucket";
            case "cave_vines","cave_vines_plant"->"minecraft:glow_berries";case "weeping_vines_plant"->"minecraft:weeping_vines";case "twisting_vines_plant"->"minecraft:twisting_vines";
            case "pitcher_crop"->"minecraft:pitcher_pod";case "torchflower_crop"->"minecraft:torchflower_seeds";
            case "bamboo_sapling"->"minecraft:bamboo";case "big_dripleaf_stem"->"minecraft:big_dripleaf";
            case "water_cauldron","lava_cauldron","powder_snow_cauldron"->"minecraft:cauldron";
            default->id;
        };
        String replacement=blockEstimates.get(id);String label=name(id);int limit=limits.getOrDefault(id,0);
        if(replacement!=null){limit=limits.getOrDefault(replacement,0);label+="（按"+name(replacement)+"备料）";warnings.add(name(id)+"没有对应背包物品，数量保留，按"+name(replacement)+"的堆叠上限估算备料。");}
        add(building,id,id,label,Math.multiplyExact(count,multiplier),limit);
        if(path.equals("lava_cauldron")||path.equals("powder_snow_cauldron")||path.equals("water_cauldron")&&"3".equals(state.getValue("level"))) {
            String fluid="minecraft:"+path.replace("_cauldron","_bucket");add(building,fluid,fluid,name(fluid),count,1);
        }
    }
    private void entity(SchematicEntity entity) {
        String id=entity.id();
        if(EntityGeometry.boat(entity))id="minecraft:"+EntityGeometry.boatWood(entity)+(EntityGeometry.kind(entity).contains("chest")?"_chest":"")+(EntityGeometry.boatWood(entity).equals("bamboo")?"_raft":"_boat");
        else if(!EntityGeometry.minecart(entity)&&!EntityGeometry.frame(entity)&&!id.equals("minecraft:painting")&& !id.equals("minecraft:armor_stand"))return;
        if(id.equals("minecraft:furnace_minecart"))id="minecraft:furnace_minecart";
        add(building,id,id,name(id),1,limits.getOrDefault(id,0));
    }
    private void inventory(NbtCompound tag,long multiple,int depth) {
        if(tag==null)return;
        if(depth>16){warnings.add("嵌套容器超过 16 层，深层内容未计入。");return;}
        if(tag.contains("LootTable")||tag.contains("loot_table"))warnings.add("含未展开的战利品表，仅统计已经保存的物品。");
        var items=BlockInspector.items(tag);
        if(items!=null)for(var entry:items.getCompounds())stack(entry.getCompound("item").orElse(entry),multiple,depth);
        else {var item=BlockInspector.singleItem(tag);if(item!=null)stack(item,multiple,depth);}
        if(items==null&&BlockInspector.singleItem(tag)==null&&tag.getStringOrDefault("id","").matches(".*(chest|barrel|shulker_box|hopper|furnace|dispenser|dropper)$"))
            warnings.add("有容器未保存库存，材料表仅包含已知内容。");
    }
    private void stack(NbtCompound item,long multiple,int depth) {
        String id=item.getStringOrDefault("id","");if(id.isEmpty()||id.equals("minecraft:air"))return;
        long count=item.getLongOrDefault("count",item.getLongOrDefault("Count",1));if(count<=0)return;count=Math.multiplyExact(count,multiple);
        var components=item.getCompound("components").orElse(null);int limit=limits.getOrDefault(id,0);String label=name(id);
        if(components!=null) {
            int custom=components.getIntOrDefault("minecraft:max_stack_size",0);if(custom>=1&&custom<=99)limit=custom;
            String title=BlockEntityData.nbtText(components.get("minecraft:custom_name").orElse(null));if(!title.isBlank())label=title+"（"+label+"）";
        }
        var old=item.getCompound("tag").orElse(null);
        if(old!=null){var display=old.getCompound("display").orElse(null);if(display!=null){String title=BlockEntityData.plainText(display.getStringOrDefault("Name",""));if(!title.isBlank())label=title+"（"+label+"）";}}
        String key=BlockInspector.stackKey(item);add(contents,key,id,label,count,limit);
        if(components!=null&&components.getList("minecraft:container").isPresent())inventory(new NbtCompound().put("Items",components.getList("minecraft:container").orElseThrow()),count,depth+1);
        else if(components!=null&&components.getList("minecraft:bundle_contents").isPresent())inventory(new NbtCompound().put("Items",components.getList("minecraft:bundle_contents").orElseThrow()),count,depth+1);
        else if(old!=null)inventory(id.endsWith("bundle")?old:old.getCompound("BlockEntityTag").orElse(null),count,depth+1);
    }
    private static int integer(String value,int fallback){try{return Integer.parseInt(value);}catch(Exception ignored){return fallback;}}
    private List<Map<String,Object>> rows(Map<String,Entry> source) {
        var entries=new ArrayList<>(source.values());entries.sort(Comparator.<Entry>comparingLong(e->e.count).reversed().thenComparing(e->e.name).thenComparing(e->e.id));
        var duplicates=new HashMap<String,Integer>();for(var e:entries)duplicates.merge(e.name,1,Integer::sum);
        var output=new ArrayList<Map<String,Object>>();int index=0;
        for(var e:entries) {
            var row=new LinkedHashMap<String,Object>();String label=e.name;
            if(duplicates.get(e.name)>1)label+="（物品数据 "+(++index)+"）";
            row.put("id",e.id);row.put("name",label);row.put("count",e.count);row.put("maxStack",e.stack);
            row.put("icon",icons.icon(e.id));if(source==building&&blockEstimates.containsKey(e.id))row.put("estimatedItem",blockEstimates.get(e.id));
            quantities(row,e.count);
            output.add(row);
        }
        return output;
    }
    private static void quantities(Map<String,Object> row,long count) {
        row.put("count",count);row.remove("stacks");row.remove("remainder");row.remove("boxes");int stack=((Number)row.get("maxStack")).intValue();
        if(stack>0){row.put("stacks",count/stack);row.put("remainder",count%stack);long capacity=27L*stack;if(count>=(capacity+1)/2&&!row.get("id").toString().endsWith("shulker_box"))row.put("boxes",count/capacity+(count%capacity==0?0:1));}
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> waterAsIce(Map<String,Object> original) {
        if(Boolean.TRUE.equals(original.get("waterAsIce")))return original;
        var result=new LinkedHashMap<String,Object>(original);var building=new ArrayList<Map<String,Object>>();long water=((Number)original.get("waterBucketCount")).longValue();
        for(var row:(List<Map<String,Object>>)original.get("building"))if(!row.get("id").equals("minecraft:water_bucket"))building.add(new LinkedHashMap<>(row));
        if(water>0){var ice=building.stream().filter(row->row.get("id").equals("minecraft:ice")).findFirst().orElse(null);if(ice==null){ice=new LinkedHashMap<>((Map<String,Object>)original.get("ice"));quantities(ice,water);building.add(ice);}else quantities(ice,Math.addExact(((Number)ice.get("count")).longValue(),water));}
        building.sort(Comparator.<Map<String,Object>>comparingLong(row->((Number)row.get("count")).longValue()).reversed().thenComparing(row->row.get("name").toString()).thenComparing(row->row.get("id").toString()));
        result.put("building",building);result.put("waterAsIce",true);return result;
    }
}
