package io.github.litematicrender.serve;
import io.github.litematicrender.render.*;
import io.github.litematicrender.render.skin.SkinResolver;
import io.github.litematicrender.nbt.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

public final class CachePolicyRegression {
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    static String key(String s){return CacheFingerprint.parts(s);}
    static SceneCache.Entry add(SceneCache cache,String key)throws Exception{Path work=cache.begin();Files.write(work.resolve("mesh.lmesh"),new byte[1024]);Files.write(work.resolve("atlas.png"),new byte[64]);return cache.publish(key,work,Map.of("quads",1));}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),out=root.resolve("tools/verify/cache-policy-output");Files.createDirectories(out);System.setProperty("litematicrender.cache.directory",out.resolve("scenes").toString());
        var cache=new SceneCache(root);cache.configure(256,true);var limit=SceneCache.class.getDeclaredField("limit");limit.setAccessible(true);limit.setLong(cache,4000);
        var a=add(cache,key("a"));Files.setLastModifiedTime(a.directory().resolve("manifest.json"),FileTime.fromMillis(100));var b=add(cache,key("b"));Files.setLastModifiedTime(b.directory().resolve("manifest.json"),FileTime.fromMillis(200));
        check(cache.load(key("a"))!=null,"cache touch failed");var c=add(cache,key("c"));check(Files.exists(a.directory())&&!Files.exists(b.directory())&&Files.exists(c.directory()),"LRU did not evict oldest unused entry");check(((Number)cache.stats().get("bytes")).longValue()<=4000,"actual disk bytes exceeded quota");
        cache.configure(256,true);check(Files.exists(c.directory())&&!Files.exists(a.directory()),"clear removed active scene or retained unused scene");
        Path partial=cache.begin();Files.write(partial.resolve("mesh.lmesh"),new byte[1]);check(cache.load(key("partial"))==null,"partial scene published");boolean failed=false;try{cache.publish(key("partial"),partial,Map.of());}catch(Exception e){failed=true;}check(failed&&cache.load(key("partial"))==null,"failed publication visible");cache.discard(partial);
        cache.configure(0,true);var temporary=add(cache,key("temporary"));check(Boolean.FALSE.equals(temporary.result().get("cacheStored")),"disabled cache retained entry");cache.protect(null);check(!Files.exists(temporary.directory()),"released temporary bundle leaked");
        boolean outside=false;try{cache.discard(root);}catch(Exception e){outside=true;}check(outside,"unsafe cleanup path accepted");
        Path vanilla=out.resolve("vanilla"),p1=out.resolve("p1"),p2=out.resolve("p2");Files.createDirectories(vanilla.resolve("assets"));Files.createDirectories(p1);Files.createDirectories(p2);Files.writeString(p1.resolve("a"),"red");Files.writeString(p2.resolve("a"),"blue");
        String ordered=CacheFingerprint.resources(vanilla,List.of(),List.of(p1,p2)),reversed=CacheFingerprint.resources(vanilla,List.of(),List.of(p2,p1));check(!ordered.equals(reversed),"pack order absent from identity");
        Path skinHome=out.resolve("skin-home");Files.createDirectories(skinHome);var image=new Image(64,64);Arrays.fill(image.pixels(),0xffff0000);Path skin=out.resolve("skin.png");image.writePng(skin);SkinResolver.importLocal(skinHome,"cachetest",skin);var head=new NbtCompound().putString("SkullOwner","cachetest");
        var first=new SkinResolver(skinHome,false,(uri,cap)->{throw new AssertionError("network");});first.prepare(List.of(head));String skinKey=first.contentFingerprint();Arrays.fill(image.pixels(),0xff00ff00);image.writePng(skin);SkinResolver.importLocal(skinHome,"cachetest",skin);var second=new SkinResolver(skinHome,false,(uri,cap)->{throw new AssertionError("network");});second.prepare(List.of(head));check(!skinKey.equals(second.contentFingerprint()),"resolved skin pixel change absent from identity");check(skinKey.equals(first.contentFingerprint()),"existing skin snapshot mutated");
        System.out.println("PASS: actual-byte LRU, active protection, partial/failed publication, disabled-cache cleanup, safe deletion, pack order and immutable resolved skin identities");
    }
}
