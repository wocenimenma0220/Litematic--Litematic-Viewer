package io.github.litematicrender.render;

import io.github.litematicrender.resource.ResourceManager;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Content-keyed base images and animation metadata; corrupt/missing caches rebuild normally. */
public final class ResourceImageCache {
    private ResourceImageCache(){}
    public record Images(Colormaps colors,SpriteAtlas atlas,boolean hit){}
    public static Images load(Path home,String key,ResourceManager resources)throws IOException{
        if(!key.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid resource image key");
        Path root=null,file=null;
        if(!"false".equals(System.getProperty("litematicrender.resources.cache")))try{
            Path base=home.toAbsolutePath().normalize().toRealPath();String override=System.getProperty("litematicrender.resources.cache.directory");
            root=override==null?base.resolve("cache/resources"):Path.of(override).toAbsolutePath().normalize();
            if(!root.startsWith(base)||root.equals(base))throw new IOException("Resource cache outside home");
            Files.createDirectories(root);if(!root.toRealPath().startsWith(base))throw new IOException("Resource cache link outside home");
            file=root.resolve(key+".images");
            if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)<=64L*1024*1024)try{return read(file,key);}catch(IOException|RuntimeException damaged){if(Boolean.getBoolean("litematicrender.resources.cache.debug"))System.err.println("[ResourceImageCache] rebuilding: "+damaged);Files.deleteIfExists(file);}
        }catch(IOException|RuntimeException unavailable){if(Boolean.getBoolean("litematicrender.resources.cache.debug"))System.err.println("[ResourceImageCache] unavailable: "+unavailable);root=null;file=null;}
        var colors=new Colormaps();colors.load(resources);var atlas=SpriteAtlas.load(resources,"blocks");var result=new Images(colors,atlas,false);
        if(root!=null)try{write(root,file,key,result);prune(root,file);}catch(IOException|RuntimeException ignored){if(Boolean.getBoolean("litematicrender.resources.cache.debug"))System.err.println("[ResourceImageCache] not stored: "+ignored);}
        return result;
    }
    private static Images read(Path file,String key)throws IOException{
        try(var zip=new ZipInputStream(Files.newInputStream(file));var input=new DataInputStream(new BufferedInputStream(new LimitedInput(zip)))){
            var entry=zip.getNextEntry();if(entry==null||!entry.getName().equals("images")||entry.isDirectory())throw new IOException("Invalid resource cache entry");
            if(input.readInt()!=0x4c524931||!input.readUTF().equals(key))throw new IOException("Invalid resource cache version");
            var colors=Colormaps.readCache(input);var atlas=SpriteAtlas.readCache(input);
            // EOF drains the entry and verifies ZIP CRC, including its final buffered bytes.
            if(input.read()!=-1||zip.getNextEntry()!=null)throw new IOException("Unexpected resource cache data");
            return new Images(colors,atlas,true);
        }
    }
    private static void write(Path root,Path target,String key,Images images)throws IOException{
        Path temporary=Files.createTempFile(root,".images-",".tmp");
        try{
            try(var zip=new ZipOutputStream(Files.newOutputStream(temporary));var output=new DataOutputStream(new BufferedOutputStream(zip))){
                zip.setLevel(1);zip.putNextEntry(new ZipEntry("images"));output.writeInt(0x4c524931);output.writeUTF(key);
                images.colors.writeCache(output);images.atlas.writeCache(output);output.flush();zip.closeEntry();
            }
            if(Files.size(temporary)>64L*1024*1024)return;
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
    private static void prune(Path root,Path current)throws IOException{
        try(var paths=Files.list(root)){
            var files=paths.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.images")&&!p.equals(current)).sorted(Comparator.comparingLong(ResourceImageCache::modified).reversed()).toList();
            for(int i=2;i<files.size();i++)try{Files.deleteIfExists(files.get(i));}catch(IOException ignored){}
        }
    }
    private static long modified(Path file){try{return Files.getLastModifiedTime(file).toMillis();}catch(IOException error){return 0;}}
    private static final class LimitedInput extends FilterInputStream{
        private long remaining=160L*1024*1024;
        LimitedInput(InputStream input){super(input);}
        @Override public int read()throws IOException{int value=super.read();if(value>=0&&--remaining<0)throw new IOException("Resource cache too large");return value;}
        @Override public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,(int)Math.min(len,remaining+1));if(n>0&&(remaining-=n)<0)throw new IOException("Resource cache too large");return n;}
    }
}
