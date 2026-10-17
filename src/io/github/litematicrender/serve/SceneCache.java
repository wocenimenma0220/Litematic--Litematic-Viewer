package io.github.litematicrender.serve;

import io.github.litematicrender.util.MiniJson;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

/** Immutable completed scene bundles, checksum validation and bounded disk retention. */
final class SceneCache {
    record Entry(Path directory,Map<String,Object> result){}
    private final Path root,settings;
    private Path protectedDirectory;
    private long limit=4096L*1024*1024;
    private String warning="";
    SceneCache(Path home)throws IOException {
        Path base=home.toAbsolutePath().normalize().toRealPath();String override=System.getProperty("litematicrender.cache.directory");root=override==null?base.resolve("cache/scenes"):Path.of(override).toAbsolutePath().normalize();settings=override==null?base.resolve("settings/scene-cache.json"):root.resolve("settings.json");
        if(!root.startsWith(base))throw new IOException("场景缓存路径不在软件目录内");Files.createDirectories(root);if(!root.toRealPath().startsWith(base))throw new IOException("场景缓存路径不在软件目录内");
        try{var data=MiniJson.parseObject(Files.readString(settings));long value=((Number)data.get("limitBytes")).longValue();if(value==0||value>=256L*1024*1024&&value<=131072L*1024*1024)limit=value;}catch(Exception ignored){}
        prune();
    }
    Path begin()throws IOException{return Files.createTempDirectory(root,".work-"+ProcessHandle.current().pid()+"-");}
    void protect(Path path){Path previous=protectedDirectory;protectedDirectory=path==null?null:path.toAbsolutePath().normalize();if(previous!=null&&!previous.equals(protectedDirectory)&&previous.getFileName().toString().startsWith(".work-"))try{discard(previous);}catch(IOException ignored){}}
    @SuppressWarnings("unchecked") Entry load(String key) {
        if(limit==0)return null;Path directory=directory(key);Path manifest=directory.resolve("manifest.json");
        try {
            if(!Files.isRegularFile(manifest)||Files.size(manifest)>1024*1024)return null;
            var data=MiniJson.parseObject(Files.readString(manifest));if(!key.equals(data.get("key"))||MiniJson.asInt(data.get("schema"),0)!=1)return null;
            for(String part:List.of("mesh","atlas")){Path file=directory.resolve(part.equals("mesh")?"mesh.lmesh":"atlas.png");if(!Files.isRegularFile(file)||Files.size(file)!=((Number)data.get(part+"Bytes")).longValue()||!CacheFingerprint.file(file).equals(data.get(part+"Hash")))throw new IOException("缓存文件校验失败");}
            var result=new LinkedHashMap<>((Map<String,Object>)data.get("result"));paths(result,directory);result.put("diskCacheHit",true);result.put("sceneCached",true);result.put("cacheKey",key);
            try{Files.setLastModifiedTime(manifest,FileTime.fromMillis(System.currentTimeMillis()));}catch(IOException ignored){}
            return new Entry(directory,result);
        }catch(Exception error){warning="缓存校验未通过，已从投影重建。";return null;}
    }
    Entry publish(String key,Path work,Map<String,Object> metadata)throws IOException {
        var result=new LinkedHashMap<String,Object>(metadata);result.remove("atlas");result.remove("mesh");
        var manifest=new LinkedHashMap<String,Object>();manifest.put("schema",1);manifest.put("key",key);manifest.put("created",System.currentTimeMillis());manifest.put("result",result);
        long bytes=0;for(String part:List.of("mesh","atlas")){Path file=work.resolve(part.equals("mesh")?"mesh.lmesh":"atlas.png");long size=Files.size(file);bytes=Math.addExact(bytes,size);manifest.put(part+"Bytes",size);manifest.put(part+"Hash",CacheFingerprint.file(file));}
        String manifestText=JsonRpcServer.toJson(manifest);bytes=Math.addExact(bytes,manifestText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        Path target=directory(key);boolean retained=limit>0&&bytes<=limit;
        if(retained) {
            Files.writeString(work.resolve("manifest.json"),manifestText);
            if(Files.exists(target)) {
                var existing=load(key);
                if(existing!=null){discard(work);protect(existing.directory);prune();return existing;}
                if(!target.equals(protectedDirectory))discard(target);else retained=false;
            }
            if(retained) {
                try{Files.move(work,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(work,target);}catch(FileAlreadyExistsException race){var existing=load(key);if(existing!=null){discard(work);protect(existing.directory);return existing;}throw race;}
            }
        }
        Path directory=retained?target:work;paths(result,directory);result.put("diskCacheHit",false);result.put("cacheStored",retained);result.put("cacheKey",key);result.put("cacheWarning",retained?warning:limit==0?"磁盘场景缓存已关闭。":"场景超过磁盘缓存限额，仅本次使用。");protect(directory);prune();return new Entry(directory,result);
    }
    private static void paths(Map<String,Object> result,Path directory){result.put("mesh",directory.resolve("mesh.lmesh").toString());result.put("atlas",directory.resolve("atlas.png").toString());}
    private Path directory(String key){if(!key.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("无效场景缓存键");return root.resolve(key);}
    void discard(Path directory)throws IOException {
        Path path=directory.toAbsolutePath().normalize();if(!path.startsWith(root)||path.equals(root)||!path.getParent().equals(root))throw new IOException("拒绝清理缓存目录外的路径");
        if(!Files.exists(path))return;
        List<Path> files;try(var walk=Files.walk(path)){files=walk.sorted(Comparator.reverseOrder()).toList();}
        for(Path file:files)if(!file.toRealPath().startsWith(root.toRealPath()))throw new IOException("缓存目录包含外部链接，未清理");
        for(Path file:files)Files.deleteIfExists(file);
    }
    private long size(Path directory){try(var files=Files.walk(directory)){return files.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(IOException e){return 0;}}).sum();}catch(IOException e){return 0;}}
    private List<Path> entries()throws IOException {try(var list=Files.list(root)){return list.filter(p->Files.isDirectory(p)&&p.getFileName().toString().matches("[a-f0-9]{64}")).toList();}}
    private long access(Path directory){try{return Files.getLastModifiedTime(directory.resolve("manifest.json")).toMillis();}catch(IOException e){return 0;}}
    void prune()throws IOException {
        var entries=new ArrayList<>(entries());entries.sort(Comparator.comparingLong(this::access));long used=0;for(Path directory:entries)used=Math.addExact(used,size(directory));
        for(Path directory:entries)if(used>limit&&!directory.equals(protectedDirectory)){long bytes=size(directory);try{discard(directory);used-=bytes;}catch(IOException ignored){}}
        try(var list=Files.list(root)){for(Path path:list.filter(p->p.getFileName().toString().startsWith(".work-")).toList())if(!path.equals(protectedDirectory)&&System.currentTimeMillis()-Files.getLastModifiedTime(path).toMillis()>24L*60*60*1000)try{discard(path);}catch(IOException ignored){}}
    }
    Map<String,Object> configure(long megabytes,boolean clear)throws IOException {
        if(megabytes!=0&&(megabytes<256||megabytes>131072))throw new IllegalArgumentException("磁盘缓存限额应为 0 或 256–131072 MB");
        limit=megabytes*1024*1024;Files.createDirectories(settings.getParent());Path temporary=Files.createTempFile(settings.getParent(),".scene-cache-",".tmp");
        try{Files.writeString(temporary,JsonRpcServer.toJson(Map.of("limitBytes",limit)));Files.move(temporary,settings,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
        if(clear)for(Path path:entries())if(!path.equals(protectedDirectory))try{discard(path);}catch(IOException ignored){}
        prune();return stats();
    }
    Map<String,Object> stats()throws IOException {long bytes=0,temporary=0;var entries=entries();for(Path path:entries)bytes+=size(path);try(var list=Files.list(root)){for(Path path:list.filter(p->p.getFileName().toString().startsWith(".work-")).toList())temporary+=size(path);}return Map.of("limitMB",limit/1048576,"bytes",bytes,"temporaryBytes",temporary,"entries",entries.size(),"warning",warning);}
}
