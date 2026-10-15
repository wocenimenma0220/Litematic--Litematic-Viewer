package io.github.litematicrender.schematic;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import java.nio.file.*;
public class ProgressiveFixture {
    public static void main(String[] args)throws Exception{
        int sx=4096,sy=16,sz=16,bits=2;long[] words=new long[sx*sy*sz*bits/64];
        for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++)if(((x+y+z)&1)==0){int index=(y*sz+z)*sx+x;words[index/32]|=1L<<(index%32*2);}
        var palette=new NbtList();palette.add(new NbtCompound().putString("Name","minecraft:air"));palette.add(new NbtCompound().putString("Name","minecraft:stone"));
        var region=new NbtCompound().put("Position",xyz(0,0,0)).put("Size",xyz(sx,sy,sz)).put("BlockStatePalette",palette).putLongArray("BlockStates",words);
        NbtFileUtils.writeCompressed(Path.of(args[0]),new NbtCompound().putInt("Version",6).put("Metadata",new NbtCompound().putString("Name","Progressive 256 sections").putInt("RegionCount",1).putInt("TotalBlocks",sx*sy*sz/2).put("EnclosingSize",xyz(sx,sy,sz))).put("Regions",new NbtCompound().put("test",region)));
    }
    static NbtCompound xyz(int x,int y,int z){return new NbtCompound().putInt("x",x).putInt("y",y).putInt("z",z);}
}
