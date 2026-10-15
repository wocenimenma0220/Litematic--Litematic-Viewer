import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.model.*;
import io.github.litematicrender.world.*;
import java.nio.file.*;
import java.util.*;

public final class OptimizationRegression {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static SpriteAtlas atlas(Map<String,SpriteContents> sprites)throws Exception {
        var constructor=SpriteAtlas.class.getDeclaredConstructor(String.class,Map.class,int.class);
        constructor.setAccessible(true);return constructor.newInstance("blocks",sprites,0);
    }
    static Image image(int color){var result=new Image(1,1);result.setPixel(0,0,color);return result;}
    static SpriteContents sprite(String id,Image image){return new SpriteContents(id,image.width,image.height,image,1,1,List.of(0),false);}
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[1]);Files.createDirectories(output);
        if(args[0].equals("--stress")){
            int[] calls={0};Image frame=new Image(512,512);
            GifWriter.writeFrames(output.resolve("bounded.gif"),64,5,tick->{calls[0]++;Arrays.fill(frame.pixels(),0xff000000|((tick*3)<<16));return frame;});
            check(calls[0]==64,"frame source rendered more than once per tick");
            try(var input=javax.imageio.ImageIO.createImageInputStream(output.resolve("bounded.gif").toFile())){
                var reader=javax.imageio.ImageIO.getImageReadersByFormatName("gif").next();reader.setInput(input);
                check(reader.getNumImages(true)==64&&reader.read(63).getWidth()==512,"GIF frames did not decode");reader.dispose();
            }
            try(var files=Files.list(output)){check(files.noneMatch(p->p.getFileName().toString().startsWith(".projection-")),"temporary GIF data leaked");}
            System.out.println("bounded GIF passed: 64 MiB raw frames, max heap "+Runtime.getRuntime().maxMemory()/1048576+" MiB");return;
        }
        var original=image(0xff123456);var atlas=atlas(Map.of("minecraft:item/probe",sprite("minecraft:item/probe",original)));
        var changed=image(0xff654321);atlas.replaceGeneratedSprites(Map.of("minecraft:item/probe",changed,"litematicrender:entity/armor/probe",changed));
        check(atlas.spriteCount()==2,"generated entries not added");Image stitched=atlas.stitched;
        atlas.replaceGeneratedSprites(Map.of("minecraft:item/probe",image(0xff654321),"litematicrender:entity/armor/probe",image(0xff654321)));
        check(atlas.stitched==stitched,"identical pixels caused atlas restitch");
        changed.setPixel(0,0,0xffabcdef);atlas.replaceGeneratedSprites(Map.of("minecraft:item/probe",changed,"litematicrender:entity/armor/probe",changed));
        check(atlas.stitched!=stitched,"mutated generated pixels not detected");
        atlas.replaceGeneratedSprites(Map.of());
        check(atlas.spriteCount()==1&&atlas.getOrMissing("minecraft:item/probe").sheet.pixel(0,0)==0xff123456,"base texture not restored or entity entries leaked");
        for(long[] size:List.of(new long[]{2001,2001,2001},new long[]{Long.MAX_VALUE,2,2},new long[]{0,1,1},new long[]{100000000,1,1})) {
            boolean rejected=false;try{LightVolume.checkedCellCount(size[0],size[1],size[2]);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"unsafe light volume not rejected");
        }
        check(LightVolume.checkedCellCount(16,16,16)==4096,"small light volume rejected");
        var sheet=new Image(1,2);sheet.setPixel(0,0,-1);sheet.setPixel(0,1,0xff000000);
        var animated=new SpriteContents("minecraft:block/animated",1,1,sheet,2,2,List.of(1,0),false);
        var cycles=atlas(Map.of(animated.name,animated));var mesh=new SceneMesh();
        var firstEntry=cycles.getOrMissingEntry(animated.name);
        check(cycles.stitched.pixel(firstEntry.pixelX,firstEntry.pixelY)==0xff000000,"animation first frame ignored metadata order");
        cycles.rebakeAtTick(2);cycles.rebakeAtTick(0);
        check(cycles.stitched.pixel(firstEntry.pixelX,firstEntry.pixelY)==0xff000000,"animation restart does not return to first metadata frame");
        var quad=new SceneMesh.MeshQuad(new float[]{0,1,1,0},new float[]{0,0,1,1},new float[4],0,0,1,0,1,1,0,1,animated.name,-1,1,1,1,1,null,SceneMesh.Layer.SOLID);mesh.solid.add(quad);
        check(cycles.animationCycle(mesh,64).frames()==4&&cycles.animationCycle(mesh,64).complete(),"used sprite cycle wrong");
        check(!cycles.animationCycle(mesh,3).complete()&&cycles.animationCycle(new SceneMesh(),64).frames()==1,"truncation or static scene cycle wrong");
        var frames=List.of(image(0xffff0000),image(0xff00ff00),image(0x00000000));
        Path old=output.resolve("list.gif"),streamed=output.resolve("streamed.gif");GifWriter.write(old,frames,5);
        int[] calls={0};GifWriter.writeFrames(streamed,3,5,tick->{calls[0]++;return frames.get(tick);});
        check(calls[0]==3&&Files.mismatch(old,streamed)==-1,"streamed GIF differs from existing encoder");
        byte[] before=Files.readAllBytes(streamed);boolean failed=false;
        try{GifWriter.writeFrames(streamed,3,5,tick->{if(tick==1)throw new Exception("test failure");return frames.get(tick);});}catch(Exception expected){failed=true;}
        check(failed&&Arrays.equals(before,Files.readAllBytes(streamed)),"failed export replaced previous GIF");
        Path root=Path.of(args[0]).toAbsolutePath();
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)) {
            var baker=new ModelBaker(resources,BlockRegistry.load(resources));var font=baker.fontRenderer();
            check(font==baker.fontRenderer(),"font not cached in resource session");
            var first=font.signPanel(List.of("中文 English","同一告示牌"),0xffffff,true,false);
            check(first==font.signPanel(List.of("中文 English","同一告示牌"),0xffffff,true,false),"sign panel not cached");
            check(!first.spriteId().equals(font.signPanel(List.of("中文 English","同一告示牌"),0xff0000,true,false).spriteId()),"different sign colors merged");
            var fresh=FontRenderer.load(resources).signPanel(List.of("中文 English","同一告示牌"),0xffffff,true,false);
            check(Arrays.equals(first.image().pixels(),fresh.image().pixels()),"cached sign pixels changed");
        }
        Files.writeString(output.resolve("optimization.json"),"{\"pass\":true,\"atlasLifecycle\":true,\"lightGuard\":true,\"gifBytesEqual\":true,\"gifFailureCleanup\":true,\"fontCache\":true,\"signCache\":true}");
        System.out.println("optimization regression passed");
    }
}
