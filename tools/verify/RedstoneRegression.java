package io.github.litematicrender.render.scene;

import io.github.litematicrender.model.*;
import io.github.litematicrender.render.*;
import io.github.litematicrender.serve.*;
import io.github.litematicrender.world.*;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import java.nio.file.*;
import java.util.*;

public class RedstoneRegression {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    static Quad face(String sprite,float y){return new Quad(new float[]{0,0,1,1},new float[]{y,y,y,y},new float[]{0,1,1,0},new float[]{0,0,1,1},new float[]{0,1,1,0},Quad.UP,sprite,-1,Quad.UP,0,true);}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),out=Path.of(args[1]).toAbsolutePath();Files.createDirectories(out);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)) {
            var registry=BlockRegistry.load(resources);var atlas=SpriteAtlas.load(resources,"blocks");
            var red=new Image(1,1);red.setPixel(0,0,0x80ff0000);var white=new Image(1,1);white.setPixel(0,0,0x80ffffff);
            atlas.replaceGeneratedSprites(Map.of("probe:red",red,"probe:white",white));var generated=new HashMap<String,Image>();
            var wire=registry.getOrMissing("minecraft:redstone_wire").defaultState;
            var flat=RedstoneSurfaces.compose(List.of(face("probe:red",.015625f),face("probe:white",.015625f),face("probe:white",.01875f)),wire,atlas,generated);
            check(flat.size()==2,"coplanar layers were not combined or raised number was lost");
            var composed=generated.values().iterator().next();check(composed.pixel(0,0)==0xc0ffaaaa,"source-over color incorrect: "+Integer.toHexString(composed.pixel(0,0)));
        }
        int sx=32,sy=3,sz=15;int[] values=new int[sx*sy*sz];var palette=new NbtList();palette.add(new NbtCompound().putString("Name","minecraft:air"));palette.add(new NbtCompound().putString("Name","minecraft:stone"));
        String[][] shapes={{"none","none","none","none"},{"side","none","side","none"},{"side","side","side","side"},{"side","side","none","none"},{"up","none","none","none"}};
        String[] directions={"north","east","south","west"};
        for(int shape=0;shape<shapes.length;shape++)for(int power=0;power<16;power++) {
            int x=power*2,z=shape*3;var props=new NbtCompound().putString("power",Integer.toString(power));for(int i=0;i<4;i++)props.putString(directions[i],shapes[shape][i]);
            int id=2+shape*16+power;palette.add(new NbtCompound().putString("Name","minecraft:redstone_wire").put("Properties",props));values[(sz+z)*sx+x]=id;values[z*sx+x]=1;
            if(shape==4)values[(sz+z-1)*sx+x]=1;
        }
        int bits=7;long[] data=new long[(values.length*bits+63)/64];for(int i=0;i<values.length;i++){long bit=(long)i*bits;int at=(int)(bit>>6),shift=(int)(bit&63);data[at]|=(long)values[i]<<shift;if(shift+bits>64)data[at+1]|=(long)values[i]>>>(64-shift);}
        var region=new NbtCompound().put("Size",new NbtCompound().putInt("x",sx).putInt("y",sy).putInt("z",sz)).put("Position",new NbtCompound().putInt("x",0).putInt("y",0).putInt("z",0)).put("BlockStatePalette",palette).put("BlockStates",new NbtScalars.NbtLongArray(data));
        Path fixture=out.resolve("redstone.litematic");NbtFileUtils.writeCompressed(fixture,new NbtCompound().putInt("Version",6).putInt("MinecraftDataVersion",0).put("Regions",new NbtCompound().put("wires",region)));
        try(var engine=new RenderEngine(root)) {
            engine.loadResources(root.resolve("assets/vanilla"));engine.open(fixture);
            engine.exportScene(out.resolve("atlas.png"),out.resolve("mesh.bin"));
            var sceneField=RenderEngine.class.getDeclaredField("scene");sceneField.setAccessible(true);var mesh=(SceneMesh)sceneField.get(engine);
            int surfaces=0,numbers=0;for(var list:List.of(mesh.solid,mesh.cutout,mesh.translucent))for(var q:list){if(q.spriteId.startsWith("litematicrender:redstone/"))surfaces++;if(q.spriteId.contains("redstone_dust_p"))numbers++;}
            check(surfaces>=160,"flat/back/climbing composite surfaces missing");check(numbers==80,"power number overlays missing: "+numbers);
            for(int angle:new int[]{45,135,225,315})engine.render(out.resolve("redstone-"+angle+".png"),new RenderSettings(angle,50,1280,720),true);
            Object before=sceneField.get(engine);var cached=engine.exportScene(out.resolve("cached-atlas.png"),out.resolve("cached.bin"));check(Boolean.TRUE.equals(cached.get("sceneCached")),"normal camera export did not reuse scene");
            var forced=engine.exportScene(out.resolve("forced-atlas.png"),out.resolve("forced.bin"),true);check(Boolean.FALSE.equals(forced.get("sceneCached"))&&sceneField.get(engine)!=before,"force rebuild reused the scene");
            check(Files.mismatch(out.resolve("mesh.bin"),out.resolve("forced.bin"))==-1,"rebuild changed stable wire geometry");
            String report="{\"pass\":true,\"powerLevels\":16,\"shapes\":5,\"wireSurfaces\":"+surfaces+",\"numberOverlays\":"+numbers+",\"sourceOver\":true,\"forceRebuild\":true,\"stableMesh\":true}";
            Files.writeString(out.resolve("result.json"),report);System.out.println(report);
        }
    }
}
