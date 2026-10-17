package io.github.litematicrender.schematic;

import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import io.github.litematicrender.util.MiniJson;
import java.nio.file.*;
import java.util.*;

/** Explicit editor blocks; signed coordinates and full states survive export. */
public final class BoardSchematic {
    private BoardSchematic() {}
    private static NbtCompound pos(int x,int y,int z){return new NbtCompound().putInt("x",x).putInt("y",y).putInt("z",z);}
    public static Map<String,Object> write(Path output,List<Object> cells)throws Exception {
        if(cells==null||cells.size()>150000)throw new IllegalArgumentException("方块数量无效（最多 150000）");
        var blocks=new LinkedHashMap<List<Integer>,NbtCompound>();
        int[] lo={0,0,0},hi={0,0,0};boolean first=true;
        for(Object raw:cells){
            var c=MiniJson.asObject(raw);if(c==null)throw new IllegalArgumentException("方块数据无效");
            int[] xyz=new int[3];String[] axes={"x","y","z"};
            for(int a=0;a<3;a++){Object v=c.get(axes[a]);if(!(v instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.intValue()||Math.abs(n.doubleValue())>192)throw new IllegalArgumentException("坐标须为 -192 至 192 的整数");xyz[a]=n.intValue();}
            String id=MiniJson.asString(c.get("id"),"");if(!id.contains(":"))id="minecraft:"+id;
            if(!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("方块 ID 无效");
            var state=new NbtCompound().putString("Name",id);var props=new NbtCompound();var values=MiniJson.asObject(c.get("states"));
            if(values!=null){if(values.size()>64)throw new IllegalArgumentException("方块状态过多");for(var e:values.entrySet()){String value=String.valueOf(e.getValue());if(!e.getKey().matches("[a-z0-9_]+")||!value.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("方块状态无效");props.putString(e.getKey(),value);}}
            state.put("Properties",props);blocks.put(List.of(xyz[0],xyz[1],xyz[2]),state);
            for(int a=0;a<3;a++){lo[a]=first?xyz[a]:Math.min(lo[a],xyz[a]);hi[a]=first?xyz[a]:Math.max(hi[a],xyz[a]);}first=false;
        }
        int sx=hi[0]-lo[0]+1,sy=hi[1]-lo[1]+1,sz=hi[2]-lo[2]+1,volume=sx*sy*sz;
        if(volume>16000000)throw new IllegalArgumentException("设计包围范围超过 1600 万格，请缩小范围");
        var palette=new NbtList();palette.add(new NbtCompound().putString("Name","minecraft:air"));
        var ids=new LinkedHashMap<String,Integer>();var entries=new ArrayList<Integer>();
        for(var state:blocks.values()){String key=state.toString();Integer index=ids.get(key);if(index==null){index=palette.size();ids.put(key,index);palette.add(state);}entries.add(index);}
        int bits=Math.max(2,32-Integer.numberOfLeadingZeros(palette.size()-1));
        long[] packed=new long[(int)(((long)volume*bits+63)/64)];int i=0;
        for(var xyz:blocks.keySet()){long index=((long)(xyz.get(1)-lo[1])*sz+xyz.get(2)-lo[2])*sx+xyz.get(0)-lo[0],offset=index*bits;int word=(int)(offset>>>6),shift=(int)(offset&63);long value=entries.get(i++);packed[word]|=value<<shift;if(shift+bits>64)packed[word+1]|=value>>>(64-shift);}
        var size=pos(sx,sy,sz);var region=new NbtCompound().put("Position",pos(lo[0],lo[1],lo[2])).put("Size",size).put("BlockStatePalette",palette).putLongArray("BlockStates",packed).put("TileEntities",new NbtList()).put("Entities",new NbtList());
        long now=System.currentTimeMillis();var metadata=new NbtCompound().putString("Name","二维搭建").putString("Author","LiteKinda").putInt("RegionCount",1).putInt("TotalBlocks",blocks.size()).putInt("TotalVolume",volume).putLong("TimeCreated",now).putLong("TimeModified",now).put("EnclosingSize",size);
        var root=new NbtCompound().putInt("Version",6).putInt("MinecraftDataVersion",5023).put("Metadata",metadata).put("Regions",new NbtCompound().put("设计",region));
        Path target=output.toAbsolutePath();Files.createDirectories(target.getParent());Path tmp=Files.createTempFile(target.getParent(),"board-",".tmp");
        try{NbtFileUtils.writeCompressed(tmp,root);try{Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(tmp);}
        return Map.of("path",target.toString(),"blocks",blocks.size());
    }
}
