import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.serve.*;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import java.nio.file.*;

/** Two separate rooms: closed walls, then a doorway and a glass window. */
public class SectionOcclusionRegression {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),out=Path.of(args[1]).toAbsolutePath();Files.createDirectories(out);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        var blocked=new SectionVisibility();for(int y=0;y<16;y++)for(int z=0;z<16;z++)blocked.setSolid(8,y,z);blocked.compile();
        var wall=blocked.sections.values().iterator().next();
        if((wall.exits[4]&32)!=0)throw new AssertionError("solid wall connected opposite faces");
        var doorway=new SectionVisibility();for(int y=0;y<16;y++)for(int z=0;z<16;z++)if(y!=8||z!=8)doorway.setSolid(8,y,z);doorway.compile();
        if((doorway.sections.values().iterator().next().exits[4]&32)==0)throw new AssertionError("doorway did not connect faces");
        try(var engine=new RenderEngine(root)) {
            engine.loadResources(root.resolve("assets/vanilla"));
            for(int mode=0;mode<4;mode++) {
                int sx=80,sy=32,sz=32;long[] data=new long[(sx*sy*sz*2+63)/64];
                for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++) {
                    int a=x<32?0:48,b=a+31;
                    boolean room=x<=31||x>=48;
                    int state=room&&(x==a||x==b||y==0||y==31||z==0||z==31)?1:0;
                    if((mode==1||mode==2)&&(x==31||x==48)&&y>=8&&y<=11&&z>=8&&z<=11)state=mode==1?0:2;
                    long bit=((y*sz+z)*sx+x)*2L;data[(int)(bit>>6)]|=(long)state<<(bit&63);
                }
                var palette=new NbtList();for(String id:new String[]{"air","stone","glass"})palette.add(new NbtCompound().putString("Name","minecraft:"+id));
                var region=new NbtCompound().put("Size",new NbtCompound().putInt("x",sx).putInt("y",sy).putInt("z",sz))
                    .put("Position",new NbtCompound().putInt("x",mode==3?-48:0).putInt("y",mode==3?-16:0).putInt("z",mode==3?-16:0)).put("BlockStatePalette",palette)
                    .put("BlockStates",new NbtScalars.NbtLongArray(data));
                Path file=out.resolve("rooms-"+mode+".litematic");
                NbtFileUtils.writeCompressed(file,new NbtCompound().putInt("Version",6).putInt("MinecraftDataVersion",0).put("Regions",new NbtCompound().put("rooms",region)));
                engine.open(file);var result=engine.exportScene(out.resolve("rooms-"+mode+".png"),out.resolve("rooms-"+mode+".bin"));System.out.println(result);
            }
        }
        System.out.println("section connectivity fixtures passed");
    }
}
