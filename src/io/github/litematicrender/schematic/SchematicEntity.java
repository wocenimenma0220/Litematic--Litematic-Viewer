package io.github.litematicrender.schematic;

import io.github.litematicrender.nbt.*;
import java.util.*;

/** A saved entity in region-relative coordinates; passenger world Pos tags are deliberately ignored. */
public record SchematicEntity(String id,NbtCompound data,double x,double y,double z,float yaw,float pitch,boolean riding) {
    public static double number(NbtElement value,double fallback) {
        if(value instanceof NbtScalars.NbtByte n)return n.value;
        if(value instanceof NbtScalars.NbtShort n)return n.value;
        if(value instanceof NbtScalars.NbtInt n)return n.value;
        if(value instanceof NbtScalars.NbtLong n)return n.value;
        if(value instanceof NbtScalars.NbtFloat n)return Float.isFinite(n.value)?n.value:fallback;
        if(value instanceof NbtScalars.NbtDouble n)return Double.isFinite(n.value)?n.value:fallback;
        return fallback;
    }
    public static double number(NbtCompound tag,String key,double fallback){return number(tag.get(key).orElse(null),fallback);}
    public static double[] vector(NbtElement value,int size) {
        var result=new double[size];Arrays.fill(result,Double.NaN);
        if(value instanceof NbtList list&&list.size()>=size)for(int i=0;i<size;i++)result[i]=number(list.get(i),Double.NaN);
        else if(value instanceof NbtCompound compound&&size==3){int i=0;for(String axis:List.of("x","y","z"))result[i++]=number(compound,axis,Double.NaN);}
        for(double v:result)if(!Double.isFinite(v)||Math.abs(v)>1e7)return null;
        return result;
    }
    public static String identity(NbtCompound tag) {
        var uuid=tag.getIntArray("UUID").orElse(null);
        if(uuid!=null&&uuid.value.length==4)return Arrays.toString(uuid.value);
        String text=tag.getStringOrDefault("UUID",tag.getStringOrDefault("uuid",""));
        if(!text.isBlank())return text;
        if(tag.contains("UUIDMost")&&tag.contains("UUIDLeast"))return tag.get("UUIDMost").orElseThrow()+":"+tag.get("UUIDLeast").orElseThrow();
        return "";
    }
    public static List<SchematicEntity> read(NbtCompound region,int version) {
        var saved=region.getList("Entities").orElse(null);if(saved==null)return List.of();
        var passengerIds=new HashSet<String>();
        for(var entry:saved.getCompounds())collectPassengers(version==1?entry.getCompound("EntityData").orElse(entry):entry,passengerIds,0);
        var result=new ArrayList<SchematicEntity>();var seen=new HashSet<String>();
        for(var entry:saved.getCompounds()) {
            var tag=version==1?entry.getCompound("EntityData").orElse(entry):entry;
            var position=vector(tag.get("Pos").orElse(null),3);
            if(position==null&&version==1)position=new double[]{number(entry,"x",Double.NaN),number(entry,"y",Double.NaN),number(entry,"z",Double.NaN)};
            if(position==null||Arrays.stream(position).anyMatch(v->!Double.isFinite(v)||Math.abs(v)>1e7))continue;
            if(passengerIds.contains(identity(tag)))continue;
            append(result,seen,tag,position,false,0);
        }
        return List.copyOf(result);
    }
    private static void collectPassengers(NbtCompound tag,Set<String> ids,int depth) {
        if(depth>16)return;var children=tag.getList("Passengers").orElse(null);if(children==null)return;
        for(var child:children.getCompounds()){String id=identity(child);if(!id.isEmpty())ids.add(id);collectPassengers(child,ids,depth+1);}
    }
    private static void append(List<SchematicEntity> result,Set<String> seen,NbtCompound tag,double[] position,boolean riding,int depth) {
        if(depth>16)return;String identity=identity(tag);if(!identity.isEmpty()&&!seen.add(identity))return;
        String id=tag.getStringOrDefault("id","");
        if(!id.contains(":"))id="minecraft:"+switch(id){case "MinecartRideable"->"minecart";case "MinecartChest"->"chest_minecart";case "MinecartHopper"->"hopper_minecart";case "MinecartFurnace"->"furnace_minecart";case "MinecartTNT"->"tnt_minecart";case "MinecartSpawner"->"spawner_minecart";case "MinecartCommandBlock"->"command_block_minecart";case "Boat"->"boat";case "SnowMan"->"snow_golem";case "VillagerGolem"->"iron_golem";default->id;};
        if(id.equals("minecraft:ItemFrame"))id="minecraft:item_frame";
        if(id.equals("minecraft:GlowItemFrame"))id="minecraft:glow_item_frame";
        if(id.equals("minecraft:"))return;
        double[] rotation=vector(tag.get("Rotation").orElse(null),2);
        float yaw=rotation==null?0:(float)(rotation[0]%360),pitch=rotation==null?0:(float)Math.max(-90,Math.min(90,rotation[1]));
        result.add(new SchematicEntity(id,tag,position[0],position[1],position[2],yaw,pitch,riding));
        var children=tag.getList("Passengers").orElse(null);if(children==null)return;
        boolean boat=id.endsWith("boat")||id.endsWith("raft");double radians=Math.toRadians(yaw);
        for(int i=0;i<children.size();i++)if(children.get(i) instanceof NbtCompound child){
            double offset=boat?(children.size()>1?(i==0?.2:-.6):(id.contains("chest")?.15:0)):0;
            boolean raft=id.contains("bamboo")||id.endsWith("raft")||tag.getStringOrDefault("Type",tag.getStringOrDefault("type","")).equals("bamboo");
            double height=boat?(raft?.5:.1875):id.endsWith("minecart")?.1875:0;
            if(child.getStringOrDefault("id","").equals("minecraft:piglin"))height-=child.getBooleanOrDefault("IsBaby",child.getBooleanOrDefault("is_baby",false))?.35:.7;
            append(result,seen,child,new double[]{position[0]-Math.sin(radians)*offset,position[1]+height,position[2]+Math.cos(radians)*offset},true,depth+1);
        }
    }
}
