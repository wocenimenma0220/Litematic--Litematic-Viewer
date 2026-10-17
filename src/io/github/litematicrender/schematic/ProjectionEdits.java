package io.github.litematicrender.schematic;

import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import java.nio.file.*;
import java.util.*;

/** Copy-on-write NBT edits preserve unknown states, entities and untouched region data. */
public final class ProjectionEdits {
    private NbtCompound root;
    private final LinkedHashSet<io.github.litematicrender.world.Vec3i> touched=new LinkedHashSet<>();
    public NbtCompound data(){return root;}
    public List<io.github.litematicrender.world.Vec3i> previewCells(){var cells=new LinkedHashSet<io.github.litematicrender.world.Vec3i>();for(var p:touched)for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)cells.add(new io.github.litematicrender.world.Vec3i(p.x()+x,p.y()+y,p.z()+z));return List.copyOf(cells);}
    public void squash(Checkpoint before,boolean changed){if(!changed)return;undo.clear();undo.addAll(before.undo());undo.push(before.root());if(undo.size()>40)undo.removeLast();redo.clear();}

    private final ArrayDeque<NbtCompound> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    public final Path source;
    public record Checkpoint(NbtCompound root,ArrayDeque<NbtCompound> undo,ArrayDeque<NbtCompound> redo){}
    public Checkpoint checkpoint(){return new Checkpoint(root,new ArrayDeque<>(undo),new ArrayDeque<>(redo));}
    public void restore(Checkpoint saved){root=saved.root();undo.clear();undo.addAll(saved.undo());redo.clear();redo.addAll(saved.redo());}
    public ProjectionEdits(Path source)throws Exception{this.source=source;root=NbtFileUtils.readSchematic(source);}
    private static NbtCompound copy(NbtCompound value){return (NbtCompound)value.copy();}
    private static NbtList listCopy(NbtList list){var out=new NbtList();for(int i=0;i<list.size();i++)out.add(list.get(i));return out;}
    private static NbtCompound pos(int x,int y,int z){return new NbtCompound().putInt("x",x).putInt("y",y).putInt("z",z);}
    private static PackedLongArray storage(NbtCompound region){var tag=region.get("BlockStates").orElseThrow();return tag instanceof NbtPackedLongArray p?p.value:PackedLongArray.wrap(((NbtScalars.NbtLongArray)tag).value);}
    private static int bits(int count){return Math.max(2,32-Integer.numberOfLeadingZeros(Math.max(1,count-1)));}
    private static int[] local(NbtCompound region,int[] xyz){
        var p=SubRegion.readBlockPos(region,"Position");var s=SubRegion.readBlockPos(region,"Size");int[] size={Math.abs(s.x()),Math.abs(s.y()),Math.abs(s.z())};
        int[] at={xyz[0]-p.x()-Math.min(0,s.x()+1),xyz[1]-p.y()-Math.min(0,s.y()+1),xyz[2]-p.z()-Math.min(0,s.z()+1)};
        for(int i=0;i<3;i++)if(at[i]<0||at[i]>=size[i])return null;return at;
    }
    public NbtCompound state(int[] xyz){
        var regions=root.getCompound("Regions").orElseThrow();NbtCompound found=null;
        for(String key:regions.getKeys()){var region=regions.getCompound(key).orElse(null);if(region==null)continue;int[] p=local(region,xyz);if(p==null)continue;var s=SubRegion.readBlockPos(region,"Size");var palette=region.getList("BlockStatePalette").orElseThrow();long index=((long)p[1]*Math.abs(s.z())+p[2])*Math.abs(s.x())+p[0];int id=LitematicaSchematic.getAt(storage(region),bits(palette.size()),index);if(id<palette.size())found=(NbtCompound)palette.get(id);}
        return found==null?new NbtCompound().putString("Name","minecraft:air"):found;
    }
    private static void setWords(Map<Integer,Long> changes,PackedLongArray words,int bits,long index,long value){
        long offset=index*bits;int word=(int)(offset>>>6),shift=(int)(offset&63);long mask=(1L<<bits)-1;
        changes.put(word,(changes.getOrDefault(word,words.get(word))&~(mask<<shift))|(value<<shift));
        if(shift+bits>64){int carry=64-shift;changes.put(word+1,(changes.getOrDefault(word+1,words.get(word+1))&~(mask>>>carry))|(value>>>carry));}
    }
    private static boolean air(NbtCompound state){String id=LitematicaSchematic.readBlockState(state).blockId();return Set.of("minecraft:air","minecraft:cave_air","minecraft:void_air").contains(id);}
    private static NbtCompound updateRegion(NbtCompound input,int[] xyz,NbtCompound value){
        var region=copy(input);var palette=listCopy(input.getList("BlockStatePalette").orElseThrow());var old=storage(input);int oldBits=bits(palette.size()),id=-1;
        var raw=LitematicaSchematic.readBlockState(value);for(int i=0;i<palette.size();i++)if(LitematicaSchematic.readBlockState((NbtCompound)palette.get(i)).equals(raw)){id=i;break;}
        if(id<0){id=palette.size();palette.add(value);}int newBits=bits(palette.size());var size=SubRegion.readBlockPos(input,"Size");int[] at=local(input,xyz);long volume=(long)Math.abs(size.x())*Math.abs(size.y())*Math.abs(size.z()),index=((long)at[1]*Math.abs(size.z())+at[2])*Math.abs(size.x())+at[0];
        int previous=LitematicaSchematic.getAt(old,oldBits,index);var previousState=LitematicaSchematic.readBlockState((NbtCompound)palette.get(previous));
        PackedLongArray words=old;
        if(oldBits!=newBits){long length=(volume*newBits+63)/64;if(length>Integer.MAX_VALUE)throw new IllegalArgumentException("区域过大，无法扩展方块调色板");long[] repacked=new long[(int)length];for(long i=0;i<volume;i++){long v=LitematicaSchematic.getAt(old,oldBits,i),offset=i*newBits;int w=(int)(offset>>>6),b=(int)(offset&63);repacked[w]|=v<<b;if(b+newBits>64)repacked[w+1]|=v>>>(64-b);}words=PackedLongArray.wrap(repacked);}
        var changes=new HashMap<Integer,Long>();setWords(changes,words,newBits,index,id);region.put("BlockStates",new NbtPackedLongArray(words.withWords(changes))).put("BlockStatePalette",palette);
        // State-only debug changes retain container contents. Replacement / deletion removes stale data.
        if(!previousState.blockId().equals(raw.blockId()))for(String key:List.of("TileEntities","PendingBlockTicks","PendingFluidTicks")){
            var list=input.getList(key).orElse(null);if(list==null)continue;var filtered=new NbtList();for(int i=0;i<list.size();i++){var item=list.get(i);if(item instanceof NbtCompound c&&c.getIntOrDefault("x",Integer.MIN_VALUE)==at[0]&&c.getIntOrDefault("y",Integer.MIN_VALUE)==at[1]&&c.getIntOrDefault("z",Integer.MIN_VALUE)==at[2])continue;filtered.add(item);}region.put(key,filtered);
        }
        if(!previousState.blockId().equals(raw.blockId())){
            String blockId=raw.blockId(),kind=blockId.substring(blockId.indexOf(':')+1);boolean inventory=Set.of("chest","trapped_chest","barrel","hopper","dispenser","dropper","furnace","blast_furnace","smoker","brewing_stand","crafter").contains(kind)||kind.endsWith("shulker_box");
            if(inventory){var list=listCopy(region.getList("TileEntities").orElse(new NbtList()));String entityId=blockId.equals("minecraft:trapped_chest")?"minecraft:chest":kind.endsWith("shulker_box")?"minecraft:shulker_box":blockId;list.add(new NbtCompound().putInt("x",at[0]).putInt("y",at[1]).putInt("z",at[2]).putString("id",entityId).put("Items",new NbtList()));region.put("TileEntities",list);}
        }
        return region;
    }
    public boolean set(int[] xyz,NbtCompound value){
        var before=state(xyz);if(LitematicaSchematic.readBlockState(before).equals(LitematicaSchematic.readBlockState(value)))return false;
        var next=copy(root);var regions=copy(root.getCompound("Regions").orElseThrow());var targets=new ArrayList<String>();
        for(String key:regions.getKeys()){var region=regions.getCompound(key).orElse(null);if(region!=null&&local(region,xyz)!=null)targets.add(key);}
        if(targets.isEmpty()){
            int x=xyz[0],y=xyz[1],z=xyz[2];String name="LiteKinda 编辑 "+x+","+y+","+z;while(regions.contains(name))name+="_";
            var palette=new NbtList();palette.add(new NbtCompound().putString("Name","minecraft:air"));regions.put(name,new NbtCompound().put("Position",pos(x,y,z)).put("Size",pos(1,1,1)).put("BlockStatePalette",palette).putLongArray("BlockStates",new long[1]).put("Entities",new NbtList()).put("TileEntities",new NbtList()));targets.add(name);
        }
        long delta=0;
        for(int i=0;i<targets.size();i++){String key=targets.get(i);var region=regions.getCompound(key).orElseThrow();var p=local(region,xyz);var size=SubRegion.readBlockPos(region,"Size");var palette=region.getList("BlockStatePalette").orElseThrow();long index=((long)p[1]*Math.abs(size.z())+p[2])*Math.abs(size.x())+p[0];var prev=(NbtCompound)palette.get(LitematicaSchematic.getAt(storage(region),bits(palette.size()),index));var replacement=i==targets.size()-1?value:new NbtCompound().putString("Name","minecraft:air");delta+=(air(replacement)?0:1)-(air(prev)?0:1);regions.put(key,updateRegion(region,xyz,replacement));}
        next.put("Regions",regions);var metadata=copy(root.getCompoundOrDefault("Metadata",new NbtCompound()));metadata.putInt("TotalBlocks",(int)Math.max(0,metadata.getIntOrDefault("TotalBlocks",0)+delta)).putString("Author","LiteKinda").putLong("TimeModified",System.currentTimeMillis()).putInt("RegionCount",regions.size());
        int[] lo={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE},hi={Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};long volume=0;
        for(String key:regions.getKeys()){var r=regions.getCompound(key).orElse(null);if(r==null)continue;var p=SubRegion.readBlockPos(r,"Position");var s=SubRegion.readBlockPos(r,"Size");int[] a={p.x(),p.y(),p.z()},b={p.x()+s.x()-Integer.signum(s.x()),p.y()+s.y()-Integer.signum(s.y()),p.z()+s.z()-Integer.signum(s.z())};for(int j=0;j<3;j++){lo[j]=Math.min(lo[j],Math.min(a[j],b[j]));hi[j]=Math.max(hi[j],Math.max(a[j],b[j]));}volume+=(long)Math.abs(s.x())*Math.abs(s.y())*Math.abs(s.z());}
        metadata.put("EnclosingSize",pos(hi[0]-lo[0]+1,hi[1]-lo[1]+1,hi[2]-lo[2]+1)).putInt("TotalVolume",(int)Math.min(Integer.MAX_VALUE,volume));next.put("Metadata",metadata);undo.push(root);if(undo.size()>40)undo.removeLast();redo.clear();root=next;touched.add(new io.github.litematicrender.world.Vec3i(xyz[0],xyz[1],xyz[2]));return true;
    }
    public boolean undo(){if(undo.isEmpty())return false;redo.push(root);root=undo.pop();return true;}
    public boolean redo(){if(redo.isEmpty())return false;undo.push(root);root=redo.pop();return true;}
    public Map<String,Object> status(){return Map.of("undo",undo.size(),"redo",redo.size(),"source",source.toAbsolutePath().toString());}
    public void write(Path target)throws Exception{
        var output=copy(root);var metadata=copy(root.getCompoundOrDefault("Metadata",new NbtCompound()));metadata.putString("Author","LiteKinda").putLong("TimeModified",System.currentTimeMillis());output.put("Metadata",metadata);
        target=target.toAbsolutePath();Files.createDirectories(target.getParent());Path temp=Files.createTempFile(target.getParent(),"projection-edit-",".tmp");try{NbtFileUtils.writeCompressed(temp,output);try{Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}
    }
}
