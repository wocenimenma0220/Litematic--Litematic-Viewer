package io.github.litematicrender.render;

import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.render.scene.SceneMesh.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** LMESH7: compressed/checksummed pages plus lossless compact unit faces and full-geometry fallback. */
public final class StreamMeshExporter {
    public static final int HEADER_BYTES=64,VERTEX_BYTES=20,PAGE_QUADS=8192,LOD_COUNT=4;
    public static final int MAX_RAW_PAGE=16*1024*1024;
    public record Page(long offset,int bytes,int quads,int layer,int rawBytes,int codec,long checksum) {
        public Page(long offset,int bytes,int quads,int layer){this(offset,bytes,quads,layer,bytes,0,0);}
        public boolean compact(){return (codec&2)!=0;}
    }
    public record Entry(SectionVisibility.Position position,float[] bounds,byte[] exits,byte[] solid,List<List<Page>> levels) {}
    public record Index(List<Entry> sections,float[] bounds,long quads,int maximumSectionQuads,long bytes,Map<String,Object> skins,Map<String,Object> entities) {}
    private StreamMeshExporter() {}
    public interface Source {
        List<SectionVisibility.Position> sections();
        void prepareStreaming();
        SceneMesh buildSection(SectionVisibility.Position position);
        /** Number of 16-cell ownership sections built together on each axis. */
        default int buildUnitSections(){return 1;}
        default Map<SectionVisibility.Position,SceneMesh> buildSections(List<SectionVisibility.Position> positions){
            var result=new LinkedHashMap<SectionVisibility.Position,SceneMesh>();for(var position:positions)result.put(position,buildSection(position));return result;
        }
        default boolean fixedLevels(){return false;}
        default SceneMesh coarseSection(SectionVisibility.Position position){return null;}
    }
    public interface Progress {
        default void stage(String stage){}
        void prepared(List<SectionVisibility.Position> positions,long committedBytes)throws IOException;
        void section(Entry entry,long committedBytes,int completed)throws IOException;
        default long viewRevision(){return 0;}
        default void prioritize(List<SectionVisibility.Position> remaining){}
    }
    private record PreparedSection(SectionVisibility.Position position,SceneMesh mesh,Throwable error) {}
    public static Index write(SceneBuilder builder,SpriteAtlas atlas,Path destination) throws IOException {
        return write((Source)builder,atlas,destination);
    }
    public static Index write(Source builder,SpriteAtlas atlas,Path destination) throws IOException {
        return write(builder,atlas,destination,null);
    }
    public static Index write(Source builder,SpriteAtlas atlas,Path destination,Progress progress) throws IOException {
        if(progress!=null)progress.stage("indexing");var positions=builder.sections();if(positions.size()>200000)throw new IOException("投影分块过多，请拆分投影。");builder.prepareStreaming();
        var entries=new ArrayList<Entry>();float[] bounds={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        long total=0;int maximum=0;Map<String,Object> skins=Map.of(),entities=Map.of();
        Files.createDirectories(destination.toAbsolutePath().getParent());Path temporary=progress==null?Files.createTempFile(destination.toAbsolutePath().getParent(),".lmesh5-",".tmp"):destination;
        // Light and generated atlas tiles are complete before publishing any section. One producer
        // owns mutable builder caches; the writer consumes immutable meshes through a bounded queue.
        int buildUnit=Math.max(1,Math.min(2,builder.buildUnitSections()));
        var ready=new java.util.concurrent.ArrayBlockingQueue<PreparedSection>(Math.max(2,buildUnit*buildUnit*buildUnit));
        Thread producer=Thread.ofPlatform().name("schematic-section-builder").daemon(true).unstarted(()->{
            try{
                var ordered=new ArrayList<>(positions);long revision=Long.MIN_VALUE,lastPriority=0;
                while(!ordered.isEmpty()){
                    if(progress!=null&&revision!=progress.viewRevision()&&(lastPriority==0||System.nanoTime()-lastPriority>400_000_000L)){
                        revision=progress.viewRevision();lastPriority=System.nanoTime();progress.prioritize(ordered);
                    }
                    var first=ordered.removeFirst();int gx=Math.floorDiv(first.x(),buildUnit),gy=Math.floorDiv(first.y(),buildUnit),gz=Math.floorDiv(first.z(),buildUnit);
                    var batch=new ArrayList<SectionVisibility.Position>();batch.add(first);
                    for(var iterator=ordered.iterator();iterator.hasNext();){var candidate=iterator.next();if(Math.floorDiv(candidate.x(),buildUnit)==gx&&Math.floorDiv(candidate.y(),buildUnit)==gy&&Math.floorDiv(candidate.z(),buildUnit)==gz){batch.add(candidate);iterator.remove();}}
                    var meshes=builder.buildSections(batch);for(var position:batch){var mesh=meshes.get(position);if(mesh==null)throw new IllegalStateException("32 格构建未返回所属区块");ready.put(new PreparedSection(position,mesh,null));}
                }
            }
            catch(InterruptedException stopped){Thread.currentThread().interrupt();}
            catch(Throwable error){try{ready.put(new PreparedSection(null,null,error));}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}
        });
        try(var file=new RandomAccessFile(temporary.toFile(),"rw")) {
            file.setLength(HEADER_BYTES);file.seek(HEADER_BYTES);
            if(progress!=null)progress.prepared(positions,HEADER_BYTES);
            producer.start();
            for(int sectionIndex=0;sectionIndex<positions.size();sectionIndex++) {
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("分块导出已中断");
                PreparedSection prepared;
                try{prepared=ready.take();}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IOException("分块导出已中断",stopped);}
                if(prepared.error()!=null)throw new IOException("区块模型生成失败",prepared.error());
                var position=prepared.position();SceneMesh full=prepared.mesh();skins=full.skinReport;
                if(full.entityReport.get("rendered") instanceof Number n&&n.intValue()>0)entities=mergeEntities(entities,full.entityReport);
                var blends=full.translucentBsp==null?new ArrayList<MeshQuad>():full.translucentBsp.faces;
                if(full.translucentBsp==null){blends.addAll(full.portalSorted);blends.addAll(full.fluidSorted);blends.addAll(full.translucentSorted);}
                List<Page> common=new ArrayList<>();writePages(file,atlas,position,blends,4,common);writePages(file,atlas,position,full.waterMask,5,common);
                if(full.translucentBsp!=null)common.add(writeBsp(file,full.translucentBsp));
                List<List<Page>> levels=new ArrayList<>();float[] box=bounds(full,position);
                for(int level=0;level<LOD_COUNT;level++) {
                    if(level>0&&builder.fixedLevels()){levels.add(levels.getFirst());continue;}
                    SceneMesh coarse=level==3?builder.coarseSection(position):null;
                    SceneMesh geometry=level==0?full:coarse!=null?coarse:LodMesh.simplify(full,position,1<<level);
                    if(level>0){var proxy=bounds(geometry,position);for(int axis=0;axis<3;axis++){box[axis]=Math.min(box[axis],proxy[axis]);box[axis+3]=Math.max(box[axis+3],proxy[axis+3]);}}
                    List<Page> pages=new ArrayList<>();writePages(file,atlas,position,geometry.solid,0,pages);writePages(file,atlas,position,geometry.cutout,1,pages);
                    if(level<2)pages.addAll(common);
                    else if(coarse==null) {
                        writePages(file,atlas,position,geometry.translucentBsp.faces,4,pages);
                        for(var page:common)if(page.layer==5)pages.add(page);
                        pages.add(writeBsp(file,geometry.translucentBsp));
                    }
                    levels.add(List.copyOf(pages));
                }
                int count=levels.get(0).stream().filter(p->p.layer<6).mapToInt(Page::quads).sum();total+=count;maximum=Math.max(maximum,count);
                var fullBox=bounds(full,position,false);for(int axis=0;axis<3;axis++){bounds[axis]=Math.min(bounds[axis],fullBox[axis]);bounds[axis+3]=Math.max(bounds[axis+3],fullBox[axis+3]);}
                var visibility=full.visibility==null?null:full.visibility.sections.get(position);byte[] exits=new byte[6],solid=new byte[512];Arrays.fill(exits,(byte)63);
                if(visibility!=null){exits=visibility.exits;solid=visibility.solid;}
                var entry=new Entry(position,box,exits.clone(),solid.clone(),levels);entries.add(entry);
                if(progress!=null)progress.section(entry,file.getFilePointer(),entries.size());
            }
            if(entries.isEmpty())Arrays.fill(bounds,0);
            long catalog=file.getFilePointer();file.writeInt(entries.size());
            for(var entry:entries) {
                file.writeInt(entry.position.x());file.writeInt(entry.position.y());file.writeInt(entry.position.z());
                for(float value:entry.bounds)file.writeFloat(value);file.write(entry.exits);file.write(entry.solid);
                for(var level:entry.levels){file.writeInt(level.size());for(var page:level){file.writeLong(page.offset);file.writeInt(page.bytes);file.writeInt(page.quads);file.writeInt(page.layer);file.writeInt(page.rawBytes);file.writeInt(page.codec);file.writeInt((int)page.checksum);}}
            }
            long length=file.getFilePointer()-catalog;if(length>128L*1024*1024)throw new IOException("投影分块索引过大，请拆分投影。");
            long bytes=file.length();file.seek(0);file.write(new byte[]{'L','M','E','S','H','7',0,0});file.writeInt(1);file.writeInt(entries.size());file.writeLong(catalog);file.writeInt((int)length);file.writeInt(LOD_COUNT);file.writeLong(total);for(float value:bounds)file.writeFloat(value);
            file.getFD().sync();
        } catch(Throwable failure){Files.deleteIfExists(temporary);throw failure;}
        finally{producer.interrupt();boolean interrupted=Thread.interrupted();while(producer.isAlive())try{producer.join(50);}catch(InterruptedException stopped){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
        if(progress==null)Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);
        return new Index(List.copyOf(entries),bounds,total,maximum,Files.size(destination),skins,entities);
    }
    public static Map<String,Object> describe(Entry entry) {
        var result=new LinkedHashMap<String,Object>();var p=entry.position();result.put("position",List.of(p.x(),p.y(),p.z()));
        result.put("min",List.of(entry.bounds()[0],entry.bounds()[1],entry.bounds()[2]));result.put("max",List.of(entry.bounds()[3],entry.bounds()[4],entry.bounds()[5]));
        result.put("exits",Base64.getEncoder().encodeToString(entry.exits()));result.put("solid",Base64.getEncoder().encodeToString(entry.solid()));
        result.put("levels",entry.levels().stream().map(level->level.stream().map(page->Map.of("offset",page.offset(),"bytes",page.bytes(),"quads",page.quads(),"layer",page.layer(),"rawBytes",page.rawBytes(),"codec",page.codec(),"checksum",page.checksum())).toList()).toList());return result;
    }
    private static Map<String,Object> mergeEntities(Map<String,Object> a,Map<String,Object> b) {
        var result=new LinkedHashMap<String,Object>(a);b.forEach((key,value)->result.put(key,((Number)result.getOrDefault(key,0)).intValue()+((Number)value).intValue()));return result;
    }
    private static void writePages(RandomAccessFile file,SpriteAtlas atlas,SectionVisibility.Position position,List<MeshQuad> quads,int layer,List<Page> output)throws IOException {
        var compact=new ArrayList<MeshQuad>();var full=new ArrayList<MeshQuad>();
        boolean enabled=Boolean.parseBoolean(System.getProperty("litematicrender.mesh.compact","true"));
        for(var quad:quads)if(enabled&&layer!=4&&compactDescriptor(quad,position)!=null)compact.add(quad);else full.add(quad);
        for(int first=0;first<compact.size();first+=PAGE_QUADS){int count=Math.min(PAGE_QUADS,compact.size()-first);var bytes=ByteBuffer.allocate(count*40).order(ByteOrder.LITTLE_ENDIAN);for(int i=first;i<first+count;i++)writeCompact(bytes,compact.get(i),position,atlas);output.add(writePage(file,bytes.array(),count,layer,true));}
        for(int first=0;first<full.size();first+=PAGE_QUADS){int count=Math.min(PAGE_QUADS,full.size()-first);var bytes=ByteBuffer.allocate(count*4*VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);for(int i=first;i<first+count;i++)writeFull(bytes,full.get(i),atlas);output.add(writePage(file,bytes.array(),count,layer,false));}
    }
    private record Compact(short x,short y,short z,byte axis,byte permutation){}
    private static Compact compactDescriptor(MeshQuad q,SectionVisibility.Position position) {
        float[][] p={{q.x0,q.y0,q.z0},{q.x1,q.y1,q.z1},{q.x2,q.y2,q.z2},{q.x3,q.y3,q.z3}};float[] lo={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY},hi={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for(var point:p)for(int axis=0;axis<3;axis++){if(!Float.isFinite(point[axis]))return null;lo[axis]=Math.min(lo[axis],point[axis]);hi[axis]=Math.max(hi[axis],point[axis]);}
        int fixed=-1;for(int axis=0;axis<3;axis++)if(hi[axis]==lo[axis]){if(fixed>=0)return null;fixed=axis;}if(fixed<0)return null;
        int a=(fixed+1)%3,b=(fixed+2)%3;if(hi[a]-lo[a]!=1||hi[b]-lo[b]!=1)return null;
        int[] local={(int)lo[0]-position.x()*16,(int)lo[1]-position.y()*16,(int)lo[2]-position.z()*16};
        for(int axis=0;axis<3;axis++)if(lo[axis]!=Math.rint(lo[axis])||local[axis]<Short.MIN_VALUE||local[axis]>Short.MAX_VALUE)return null;
        int permutation=0,seen=0;for(int vertex=0;vertex<4;vertex++){float da=p[vertex][a]-lo[a],db=p[vertex][b]-lo[b];int code=da==0&&db==0?0:da==0&&db==1?1:da==1&&db==1?2:da==1&&db==0?3:-1;if(code<0)return null;seen|=1<<code;permutation|=code<<(vertex*2);}if(seen!=15)return null;
        return new Compact((short)local[0],(short)local[1],(short)local[2],(byte)fixed,(byte)permutation);
    }
    private static void writeCompact(ByteBuffer bytes,MeshQuad q,SectionVisibility.Position position,SpriteAtlas atlas) {
        var descriptor=compactDescriptor(q,position);if(descriptor==null)throw new IllegalStateException("compact face changed during encoding");
        bytes.putShort(descriptor.x).putShort(descriptor.y).putShort(descriptor.z).put(descriptor.axis).put(descriptor.permutation);writeAttributes(bytes,q,atlas);
    }
    private static void writeFull(ByteBuffer bytes,MeshQuad q,SpriteAtlas atlas) {
        float[] x={q.x0,q.x1,q.x2,q.x3},y={q.y0,q.y1,q.y2,q.y3},z={q.z0,q.z1,q.z2,q.z3};
        var sprite=atlas.getOrMissingEntry(q.spriteId==null?"minecraft:missingno":q.spriteId);float[] u={q.u0,q.u1,q.u2,q.u3},v={q.v0,q.v1,q.v2,q.v3};
        for(int i=0;i<4;i++){bytes.putFloat(x[i]).putFloat(y[i]).putFloat(z[i]);writeAttribute(bytes,q,sprite,u[i],v[i],i);}
    }
    private static void writeAttributes(ByteBuffer bytes,MeshQuad q,SpriteAtlas atlas) {
        var sprite=atlas.getOrMissingEntry(q.spriteId==null?"minecraft:missingno":q.spriteId);float[] u={q.u0,q.u1,q.u2,q.u3},v={q.v0,q.v1,q.v2,q.v3};for(int i=0;i<4;i++)writeAttribute(bytes,q,sprite,u[i],v[i],i);
    }
    private static void writeAttribute(ByteBuffer bytes,MeshQuad q,SpriteAtlas.Entry sprite,float u,float v,int vertex) {
        bytes.putShort((short)Math.round(Math.max(0,Math.min(1,sprite.u0+(sprite.u1-sprite.u0)*u))*65535));bytes.putShort((short)Math.round(Math.max(0,Math.min(1,sprite.v0+(sprite.v1-sprite.v0)*v))*65535));int color=LodMesh.color(q,vertex);bytes.put((byte)(color>>16)).put((byte)(color>>8)).put((byte)color).put((byte)255);
    }
    private static Page writeBsp(RandomAccessFile file,TranslucentBsp bsp)throws IOException {
        if(4L+bsp.nodes.size()*48L>MAX_RAW_PAGE)throw new IOException("区块透明排序页过大");
        var buffer=new ByteArrayOutputStream(4+bsp.nodes.size()*48);var data=new DataOutputStream(buffer);data.writeInt(bsp.nodes.size());
        for(var node:bsp.nodes){for(double value:node.plane)data.writeDouble(value);data.writeInt(node.first);data.writeInt(node.count);data.writeInt(node.front);data.writeInt(node.back);}
        return writePage(file,buffer.toByteArray(),0,6,false);
    }
    private static Page writePage(RandomAccessFile file,byte[] raw,int quads,int layer,boolean compact)throws IOException {
        var checksum=new java.util.zip.CRC32();checksum.update(raw);var compressor=new java.util.zip.Deflater(1);byte[] compressed=new byte[raw.length+64];int size;
        try{compressor.setInput(raw);compressor.finish();size=compressor.deflate(compressed);if(!compressor.finished())size=raw.length;}finally{compressor.end();}
        boolean deflated=size<raw.length;int codec=(deflated?1:0)|(compact?2:0);long offset=file.getFilePointer();file.write(deflated?compressed:raw,0,deflated?size:raw.length);
        return new Page(offset,deflated?size:raw.length,quads,layer,raw.length,codec,checksum.getValue());
    }
    private static float[] bounds(SceneMesh mesh,SectionVisibility.Position position) {
        return bounds(mesh,position,true);
    }
    private static float[] bounds(SceneMesh mesh,SectionVisibility.Position position,boolean effects) {
        float[] box={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for(var layer:List.of(mesh.solid,mesh.cutout,mesh.portal,mesh.fluid,mesh.translucent,mesh.waterMask))for(var q:layer) {
            if(!effects&&q.decorativeEffect())continue;
            float[][] points={{q.x0,q.y0,q.z0},{q.x1,q.y1,q.z1},{q.x2,q.y2,q.z2},{q.x3,q.y3,q.z3}};
            for(var point:points)for(int i=0;i<3;i++){box[i]=Math.min(box[i],point[i]);box[i+3]=Math.max(box[i+3],point[i]);}
        }
        if(!Float.isFinite(box[0]))for(int i=0;i<3;i++){int value=i==0?position.x():i==1?position.y():position.z();box[i]=value*16;box[i+3]=value*16+16;}
        return box;
    }
}
