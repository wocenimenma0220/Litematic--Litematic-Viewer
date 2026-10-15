package io.github.litematicrender.schematic;
import io.github.litematicrender.nbt.*;
import java.util.*;
public final class SavedEntityDataRegression {
    static NbtList vector(double... values){var list=new NbtList();for(double v:values)list.add(new NbtScalars.NbtDouble(v));return list;}
    public static void main(String[] args){
        var child=new NbtCompound().putString("id","minecraft:piglin").put("Pos",vector(2000,2000,2000)).put("UUID",new NbtScalars.NbtIntArray(new int[]{1,2,3,4}));var passengers=new NbtList();passengers.add(child);
        for(String id:List.of("minecraft:boat","minecraft:chest_boat","minecraft:bamboo_raft","minecraft:bamboo_chest_raft")){
            var root=new NbtCompound().putString("id",id).putString("Type","bamboo").put("Pos",vector(2,3,4)).put("Rotation",vector(90,0)).put("Passengers",passengers);var entities=new NbtList();entities.add(root);entities.add(child);
            var parsed=SchematicEntity.read(new NbtCompound().put("Entities",entities),6);
            if(parsed.size()!=2||Math.abs(parsed.get(1).y()-2.8)>1e-6||Math.abs(parsed.get(1).z()-4)>1e-6)throw new AssertionError("legacy/new bamboo passenger location: "+id+" "+parsed);
        }
        var entities=new NbtList();entities.add(new NbtCompound().putString("id","minecraft:piglin").put("Pos",vector(Double.NaN,0,0)));if(!SchematicEntity.read(new NbtCompound().put("Entities",entities),6).isEmpty())throw new AssertionError("invalid position accepted");
        System.out.println("PASS: legacy/new bamboo seats, rider UUID deduplication, finite entity coordinates");
    }
}
