package io.github.litematicrender.serve;

import io.github.litematicrender.nbt.*;
import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.resource.*;
import io.github.litematicrender.schematic.*;
import io.github.litematicrender.world.*;
import io.github.litematicrender.util.MiniJson;
import java.util.*;
import java.io.*;
import javax.imageio.ImageIO;

/** Read-only state/inventory inspection of the schematic snapshot. */
public final class BlockInspector {
    private final LitematicaSchematic schematic;
    private final ResourceManager resources;
    private final Map<String,String> names=new HashMap<>(),icons=new HashMap<>();
    private record Target(LitematicaSchematic.Region region,BlockState state,int x,int y,int z,int lx,int ly,int lz) {
        NbtCompound tag(){return region.blockEntities.get(LitematicaSchematic.pack(lx,ly,lz));}
    }
    public BlockInspector(LitematicaSchematic schematic,ResourceManager resources) {
        this.schematic=schematic;this.resources=resources;
        for(String language:List.of("en_us","zh_cn")) {
            var stack=resources.getResourceStack("minecraft","lang/"+language+".json");
            for(int i=stack.size()-1;i>=0;i--) try {
                MiniJson.parseObject(new String(stack.get(i),java.nio.charset.StandardCharsets.UTF_8)).forEach((key,value)->{if(value instanceof String text)names.put(key,text);});
            }catch(Exception ignored){}
        }
    }
    private Target lookup(int x,int y,int z,String preferred) {
        if(preferred!=null){var region=schematic.regions().get(preferred);var target=at(region,x,y,z);if(target!=null)return target;}
        for(var region:schematic.regions().values()){var target=at(region,x,y,z);if(target!=null)return target;}return null;
    }
    private static Target at(LitematicaSchematic.Region region,int x,int y,int z) {
        if(region==null||region.blocks==null)return null;
        var origin=region.blockOrigin();int lx=x-origin.x(),ly=y-origin.y(),lz=z-origin.z();
        var block=region.blocks.get(lx,ly,lz);return block.isAir()?null:new Target(region,block,x,y,z,lx,ly,lz);
    }
    public Map<String,Object> inspect(ScenePicker.Hit hit) {
        if(hit==null)return Map.of("hit",false);
        var q=hit.quad();
        if(q.entityIndex>=0)return inspectEntity(q.regionName,q.entityIndex,hit.distance());
        var target=lookup(q.blockX,q.blockY,q.blockZ,q.regionName);
        if(target==null)return Map.of("hit",false);
        var result=new LinkedHashMap<String,Object>();var tag=target.tag();
        result.put("hit",true);result.put("id",target.state.blockId);
        String custom=tag==null?"":BlockEntityData.nbtText(tag.get("CustomName").orElse(null));
        result.put("name",custom.isBlank()?name(target.state.blockId):custom);
        result.put("position",List.of(target.x,target.y,target.z));result.put("region",target.region.name);
        var properties=new LinkedHashMap<String,String>();
        for(var property:target.state.properties)properties.put(property.name(),target.state.getValue(property.name()));
        properties.putAll(target.region.storedProperties(target.lx,target.ly,target.lz));
        result.put("properties",properties);result.put("distance",hit.distance());
        if(tag!=null)result.put("data",plain(tag,0));
        var inventory=inventory(target);if(inventory!=null)result.put("container",inventory);
        return result;
    }
    private Map<String,Object> inspectEntity(String regionName,int index,double distance){
        var region=schematic.regions().get(regionName);if(region==null||index>=region.entities.size())return Map.of("hit",false);
        var entity=region.entities.get(index);var result=new LinkedHashMap<String,Object>();var tag=entity.data();String custom=BlockEntityData.nbtText(tag.get("CustomName").orElse(null));
        result.put("hit",true);result.put("kind","entity");result.put("id",entity.id());result.put("name",custom.isBlank()?names.getOrDefault("entity."+entity.id().replace(':','.'),name(entity.id())):custom);
        result.put("position",List.of(region.position.x()+entity.x(),region.position.y()+entity.y(),region.position.z()+entity.z()));result.put("region",regionName);result.put("distance",distance);result.put("data",plain(tag,0));
        var properties=new LinkedHashMap<String,Object>();properties.put("yaw",entity.yaw());properties.put("pitch",entity.pitch());
        if(entity.riding())properties.put("riding",true);
        if(EntityGeometry.boat(entity))properties.put("wood",EntityGeometry.boatWood(entity));
        if(EntityGeometry.kind(entity).equals("piglin"))properties.put("baby",EntityGeometry.baby(entity));
        if(EntityGeometry.kind(entity).equals("snow_golem"))properties.put("pumpkin",tag.getBooleanOrDefault("Pumpkin",true));
        if(tag.contains("Health"))properties.put("health",io.github.litematicrender.schematic.SchematicEntity.number(tag,"Health",0));
        result.put("properties",properties);
        int slots=EntityGeometry.capacity(entity);
        if(slots>0){var rows=new ArrayList<Map<String,Object>>();readItems(tag,0,rows);rows.sort(Comparator.comparingInt(row->(Integer)row.get("displaySlot")));String status=inventoryStatus(tag);if(!rows.isEmpty()&&status.equals("missing"))status="saved";if(!rows.isEmpty()&&status.equals("loot"))status="partial";result.put("container",Map.of("slots",slots,"status",status,"items",groupItems(rows)));}
        return result;
    }
    private Map<String,Object> inventory(Target target) {
        String id=target.state.blockId;NbtCompound tag=target.tag();int slots=capacity(id);
        boolean supplied=items(tag)!=null || singleItem(tag)!=null;
        if(slots==0&&!supplied)return null;
        var result=new LinkedHashMap<String,Object>();var rows=new ArrayList<Map<String,Object>>();
        String status;
        if(id.equals("minecraft:ender_chest")&&!supplied)status="personal";
        else status=inventoryStatus(tag);
        boolean pair=(id.equals("minecraft:chest")||id.equals("minecraft:trapped_chest"))&&Set.of("left","right").contains(String.valueOf(target.state.getValue("type")));
        int offset=pair&&"right".equals(target.state.getValue("type"))?27:0;
        readItems(tag,offset,rows);
        if(pair) {
            slots=54;
            String facing=target.state.getValue("facing"),type=target.state.getValue("type");
            int[] cw=switch(String.valueOf(facing)){case "east"->new int[]{0,1};case "south"->new int[]{-1,0};case "west"->new int[]{0,-1};default->new int[]{1,0};};
            int direction="left".equals(type)?1:-1;
            Target other=lookup(target.x+cw[0]*direction,target.y,target.z+cw[1]*direction,target.region.name);
            boolean valid=other!=null&&other.state.blockId.equals(id)&&Objects.equals(facing,other.state.getValue("facing"))
                &&Objects.equals("left".equals(type)?"right":"left",other.state.getValue("type"));
            if(valid) {
                readItems(other.tag(),offset==0?27:0,rows);
                String second=inventoryStatus(other.tag());
                if(!Set.of("empty","saved").contains(status)||!Set.of("empty","saved").contains(second))status="partial";
                else status=rows.isEmpty()?"empty":"saved";
                result.put("partnerPosition",List.of(other.x,other.y,other.z));
            } else {status="partial";result.put("partnerMissing",true);}
            result.put("doubleChest",true);
        }
        if(!rows.isEmpty()&&(status.equals("empty")||status.equals("missing")))status="saved";
        if(!rows.isEmpty()&&status.equals("loot"))status="partial";
        rows.sort(Comparator.comparingInt(row->(Integer)row.get("displaySlot")));
        if(rows.isEmpty()&&status.equals("saved"))status="empty";
        result.put("slots",slots);result.put("status",status);result.put("items",groupItems(rows));
        return result;
    }
    private static String inventoryStatus(NbtCompound tag) {
        if(tag==null)return "missing";
        if(!tag.getStringOrDefault("LootTable",tag.getStringOrDefault("loot_table","")).isEmpty())return "loot";
        if(items(tag)!=null)return items(tag).isEmpty()?"empty":"saved";
        if(singleItem(tag)!=null)return "saved";
        return "missing";
    }
    static NbtList items(NbtCompound tag) {
        if(tag==null)return null;
        var result=tag.getList("Items").orElseGet(()->tag.getList("items").orElse(null));
        if(result==null){var components=tag.getCompound("components").orElse(null);if(components!=null)result=components.getList("minecraft:container").orElse(null);}
        return result;
    }
    private void readItems(NbtCompound tag,int offset,List<Map<String,Object>> result) {
        if(tag==null)return;var list=items(tag);
        if(list!=null){int index=0;for(var entry:list.getCompounds()){readItem(entry,index++,offset,result);}}
        else {var item=singleItem(tag);if(item!=null)readItem(new NbtCompound().put("item",item),0,offset,result);}
    }
    static NbtCompound singleItem(NbtCompound tag) {
        if(tag==null)return null;
        for(String key:List.of("Item","item","Book","book","RecordItem","record_item")){var item=tag.getCompound(key).orElse(null);if(item!=null)return item;}
        return null;
    }
    private void readItem(NbtCompound entry,int index,int offset,List<Map<String,Object>> result) {
        var item=entry.getCompound("item").orElse(entry);
        String id=item.getStringOrDefault("id","");if(id.isEmpty()||id.equals("minecraft:air"))return;
        int count=number(item,"count",number(item,"Count",number(entry,"count",1)));
        if(count<=0)return;int slot=number(entry,"Slot",number(entry,"slot",index));if(slot<0&&slot>=-128)slot+=256;
        var row=new LinkedHashMap<String,Object>();row.put("slot",slot);row.put("displaySlot",slot+offset);row.put("id",id);row.put("count",count);
        String custom="";var components=item.getCompound("components").orElse(null);
        if(components!=null)custom=BlockEntityData.nbtText(components.get("minecraft:custom_name").orElse(null));
        if(custom.isBlank()) {var old=item.getCompound("tag").orElse(null);if(old!=null){var display=old.getCompound("display").orElse(null);if(display!=null)custom=BlockEntityData.plainText(display.getStringOrDefault("Name",""));}}
        row.put("name",custom.isBlank()?name(id):custom);row.put("data",plain(item,0));
        row.put("_mergeKey",stackKey(item));
        String icon=icons.computeIfAbsent(id,this::icon);if(!icon.isEmpty())row.put("icon",icon);
        result.add(row);
    }
    /** Quantity and container slot are not item identity; all other typed NBT is compared exactly. */
    static String stackKey(NbtCompound item) {
        try {
            var bytes=new ByteArrayOutputStream();var output=new DataOutputStream(bytes);
            canonical(item,output,true);
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch(IOException error){throw new IllegalStateException("无法比较物品数据",error);}
    }
    private static void canonical(NbtElement value,DataOutputStream output,boolean root) throws IOException {
        output.writeByte(value.type());
        if(value instanceof NbtCompound compound) {
            var keys=new ArrayList<>(compound.getKeys());
            if(root)keys.removeAll(List.of("Count","count","Slot","slot"));
            Collections.sort(keys);output.writeInt(keys.size());
            for(String key:keys){output.writeUTF(key);canonical(compound.get(key).orElseThrow(),output,false);}
        } else if(value instanceof NbtList list) {
            output.writeByte(list.elementType());output.writeInt(list.size());
            for(int i=0;i<list.size();i++)canonical(list.get(i),output,false);
        } else value.write(new NbtOutput(output));
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> groupItems(List<Map<String,Object>> rows) {
        var groups=new LinkedHashMap<String,Map<String,Object>>();
        for(var row:rows) {
            String key=(String)row.remove("_mergeKey");var group=groups.get(key);
            if(group==null) {
                row.put("stacks",1);row.put("displaySlots",new ArrayList<>(List.of((Integer)row.get("displaySlot"))));
                groups.put(key,row);
            } else {
                group.put("count",((Number)group.get("count")).longValue()+((Number)row.get("count")).longValue());
                group.put("stacks",(Integer)group.get("stacks")+1);
                ((List<Integer>)group.get("displaySlots")).add((Integer)row.get("displaySlot"));
            }
        }
        return new ArrayList<>(groups.values());
    }
    private String name(String id) {
        String key=id.replace(':','.');return names.getOrDefault("block."+key,names.getOrDefault("item."+key,id));
    }
    private String icon(String id) {
        if(id.contains("..")||!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))return "";
        String[] parts=id.split(":",2);
        for(String kind:List.of("item","block"))try {
            var bytes=resources.getResource(parts[0],"textures/"+kind+"/"+parts[1]+".png").orElse(null);
            if(bytes==null)continue;var image=Image.read(bytes);var source=image.toBuffered();
            var thumb=new java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_ARGB);var graphics=thumb.createGraphics();
            try {graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);int size=Math.min(source.getWidth(),source.getHeight());graphics.drawImage(source,0,0,32,32,0,0,size,size,null);}finally{graphics.dispose();}
            var out=new ByteArrayOutputStream();ImageIO.write(thumb,"png",out);return "data:image/png;base64,"+Base64.getEncoder().encodeToString(out.toByteArray());
        }catch(Exception ignored){}
        return "";
    }
    static int capacity(String id) {
        if(id.endsWith("shulker_box")||Set.of("minecraft:chest","minecraft:trapped_chest","minecraft:ender_chest","minecraft:barrel").contains(id))return 27;
        if(Set.of("minecraft:dispenser","minecraft:dropper","minecraft:crafter").contains(id))return 9;
        if(Set.of("minecraft:hopper","minecraft:brewing_stand").contains(id))return 5;
        if(Set.of("minecraft:furnace","minecraft:blast_furnace","minecraft:smoker").contains(id))return 3;
        if(Set.of("minecraft:decorated_pot","minecraft:lectern","minecraft:jukebox").contains(id))return 1;
        if(id.equals("minecraft:chiseled_bookshelf"))return 6;return 0;
    }
    private static int number(NbtCompound tag,String key,int fallback) {
        var value=tag.get(key).orElse(null);
        if(value instanceof NbtScalars.NbtByte n)return n.value;
        if(value instanceof NbtScalars.NbtShort n)return n.value;
        if(value instanceof NbtScalars.NbtInt n)return n.value;return fallback;
    }
    public static Object plain(NbtElement value,int depth) {
        if(value==null)return null;if(depth>12)return "…";
        if(value instanceof NbtCompound compound){var map=new LinkedHashMap<String,Object>();for(String key:compound.getKeys())map.put(key,plain(compound.get(key).orElse(null),depth+1));return map;}
        if(value instanceof NbtList list){var result=new ArrayList<Object>();for(int i=0;i<Math.min(list.size(),256);i++)result.add(plain(list.get(i),depth+1));if(list.size()>256)result.add("…");return result;}
        if(value instanceof NbtScalars.NbtString n)return n.value.length()>8000?n.value.substring(0,8000)+"…":n.value;
        if(value instanceof NbtScalars.NbtByte n)return n.value;
        if(value instanceof NbtScalars.NbtShort n)return n.value;
        if(value instanceof NbtScalars.NbtInt n)return n.value;
        if(value instanceof NbtScalars.NbtLong n)return n.value>9007199254740991L||n.value< -9007199254740991L?Long.toString(n.value):n.value;
        if(value instanceof NbtScalars.NbtFloat n)return Float.isFinite(n.value)?n.value:String.valueOf(n.value);
        if(value instanceof NbtScalars.NbtDouble n)return Double.isFinite(n.value)?n.value:String.valueOf(n.value);
        if(value instanceof NbtScalars.NbtIntArray n)return Arrays.stream(n.value).limit(256).boxed().toList();
        if(value instanceof NbtScalars.NbtLongArray n)return Arrays.stream(n.value).limit(256).mapToObj(number->number>9007199254740991L||number< -9007199254740991L?(Object)Long.toString(number):(Object)number).toList();
        if(value instanceof NbtScalars.NbtByteArray n){var result=new ArrayList<Integer>();for(int i=0;i<Math.min(n.value.length,256);i++)result.add((int)n.value[i]);return result;}
        return value.toString();
    }
}
