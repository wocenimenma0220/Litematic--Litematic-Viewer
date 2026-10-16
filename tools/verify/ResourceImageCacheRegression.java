package io.github.litematicrender.serve;

import io.github.litematicrender.render.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.io.*;
import java.util.*;

/** Real base atlas roundtrip, animation sheets, corruption and content/order invalidation. */
public final class ResourceImageCacheRegression {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static SpriteAtlas atlas(RenderEngine engine)throws Exception{var field=RenderEngine.class.getDeclaredField("atlas");field.setAccessible(true);return (SpriteAtlas)field.get(engine);}
    @SuppressWarnings("unchecked") static String snapshot(SpriteAtlas atlas)throws Exception{
        var field=SpriteAtlas.class.getDeclaredField("baseSprites");field.setAccessible(true);var sprites=(Map<String,SpriteContents>)field.get(atlas);
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
            for(String name:new TreeSet<>(sprites.keySet())){var s=sprites.get(name);out.writeUTF(name);out.writeInt(s.frameWidth);out.writeInt(s.frameHeight);out.writeInt(s.frameCount);out.writeInt(s.frameTime);out.writeBoolean(s.interpolate);out.writeUTF(s.frames.toString());for(int pixel:s.sheet.pixels())out.writeInt(pixel);}
            for(int tick:new int[]{0,1,3,20,80,125,0}){atlas.rebakeAtTick(tick);for(int pixel:atlas.stitched.pixels())out.writeInt(pixel);}
        }
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }
    static Path cached(Path directory)throws Exception{try(var paths=Files.list(directory)){return paths.filter(p->p.toString().endsWith(".images")).max(Comparator.comparingLong(p->{try{return Files.getLastModifiedTime(p).toMillis();}catch(IOException e){return 0L;}})).orElseThrow();}}
    static Path pack(Path root,String name,int pixel)throws Exception{
        Path file=root.resolve(name+"/assets/minecraft/textures/block/stone.png");Files.createDirectories(file.getParent());var image=new Image(16,16);Arrays.fill(image.pixels(),pixel);image.writePng(file);try(var padded=new RandomAccessFile(file.toFile(),"rw")){padded.setLength(4096);}return root.resolve(name);
    }
    public static void main(String[] args)throws Exception{
        Path home=Path.of(args[0]).toAbsolutePath(),out=home.resolve("tools/verify/resource-images-output");Files.createDirectories(out);Path run=Files.createTempDirectory(out,"run-");
        System.setProperty("litematicrender.home",home.toString());System.setProperty("litematicrender.resources.cache.directory",run.resolve("images").toString());
        String expected;
        try(var engine=new RenderEngine(home)){var result=engine.loadResources(engine.defaultPackRoot());check(Boolean.FALSE.equals(result.get("imageCacheHit")),"fresh cache reported hit");check(((Number)result.get("sprites")).intValue()>1800,"image decode silently lost sprites");expected=snapshot(atlas(engine));atlas(engine).replaceGeneratedSprites(Map.of("test:scene-only",new Image(16,16)));}
        try(var engine=new RenderEngine(home)){var result=engine.loadResources(engine.defaultPackRoot());check(Boolean.TRUE.equals(result.get("imageCacheHit")),"new engine did not reuse image cache");check(expected.equals(snapshot(atlas(engine))),"cached pixels, animation metadata or UV layout changed");check(atlas(engine).get("test:scene-only").isEmpty(),"scene-specific sprite entered resource cache");}
        Path cache=cached(run.resolve("images"));byte[] corrupt=Files.readAllBytes(cache);corrupt[corrupt.length/2]^=1;Files.write(cache,corrupt);
        try(var engine=new RenderEngine(home)){var result=engine.loadResources(engine.defaultPackRoot());check(Boolean.FALSE.equals(result.get("imageCacheHit")),"corrupt image cache accepted");check(expected.equals(snapshot(atlas(engine))),"corruption fallback changed images");}
        Files.write(cache,new byte[]{1,2,3});
        try(var engine=new RenderEngine(home)){check(Boolean.FALSE.equals(engine.loadResources(engine.defaultPackRoot()).get("imageCacheHit")),"truncated image cache accepted");}
        Path red=pack(run,"red",0xffff0000),blue=pack(run,"blue",0xff0000ff);String first=CacheFingerprint.resources(home.resolve("assets/vanilla"),List.of(),List.of(red));
        Path texture=red.resolve("assets/minecraft/textures/block/stone.png");FileTime time=Files.getLastModifiedTime(texture);long length=Files.size(texture);
        try(var engine=new RenderEngine(home)){
            var result=engine.loadResources(engine.defaultPackRoot(),List.of(red.toString(),blue.toString()));check(Boolean.FALSE.equals(result.get("imageCacheHit")),"new resource order reused old cache");check(atlas(engine).getOrMissing("minecraft:block/stone").sheet.pixel(0,0)==0xff0000ff,"upper pack did not win");
            engine.loadResources(engine.defaultPackRoot(),List.of(blue.toString(),red.toString()));check(atlas(engine).getOrMissing("minecraft:block/stone").sheet.pixel(0,0)==0xffff0000,"pack reorder did not invalidate");
        }
        pack(run,"red",0xff00ff00);Files.setLastModifiedTime(texture,time);check(Files.size(texture)==length,"same-length mutation fixture changed length");check(!first.equals(CacheFingerprint.resources(home.resolve("assets/vanilla"),List.of(),List.of(red))),"same-metadata mutation was not fingerprinted");
        try(var engine=new RenderEngine(home)){var result=engine.loadResources(engine.defaultPackRoot(),List.of(blue.toString(),red.toString()));check(Boolean.FALSE.equals(result.get("imageCacheHit")),"same-metadata edit reused image cache");check(atlas(engine).getOrMissing("minecraft:block/stone").sheet.pixel(0,0)==0xff00ff00,"resource mutation did not reach pixels");}
        try(var files=Files.list(run.resolve("images"))){check(files.filter(p->p.toString().endsWith(".images")).count()<=3,"resource cache retention exceeded");}
        System.out.println("PASS: real 1900+ sprite/animation/atlas roundtrip; no generated sprites; CRC/truncation rebuild; pack order and same-size/same-mtime edits invalidate; bounded disk retention");
    }
}
