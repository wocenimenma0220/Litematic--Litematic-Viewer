package io.github.litematicrender.render;

import io.github.litematicrender.render.scene.SectionVisibility;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Reopens a completed mesh without rebuilding light, generated sprites or section geometry. */
public final class StreamMeshIndex {
    private StreamMeshIndex(){}
    private static void require(boolean valid)throws IOException{if(!valid)throw new IOException("场景缓存索引损坏");}
    public static StreamMeshExporter.Index read(Path path,Map<String,Object> skins,Map<String,Object> entities)throws IOException {
        try(var file=new RandomAccessFile(path.toFile(),"r")) {
            long size=file.length();require(size>=64);byte[] magic=new byte[8];file.readFully(magic);boolean compact=Arrays.equals(magic,new byte[]{'L','M','E','S','H','7',0,0}),compressed=compact||Arrays.equals(magic,new byte[]{'L','M','E','S','H','6',0,0});require(compressed||Arrays.equals(magic,new byte[]{'L','M','E','S','H','5',0,0}));
            require(file.readInt()==1);int count=file.readInt();long catalog=file.readLong();int length=file.readInt();require(file.readInt()==4);long quads=file.readLong();
            require(count>=0&&count<=200000&&length>=4&&length<=128*1024*1024&&catalog>=64&&catalog<=size-length&&catalog+length==size&&quads>=0);
            float[] bounds=new float[6];for(int i=0;i<6;i++)bounds[i]=file.readFloat();bounds(bounds);
            file.seek(catalog);require(file.readInt()==count);var entries=new ArrayList<StreamMeshExporter.Entry>();var positions=new HashSet<SectionVisibility.Position>();var descriptors=new HashMap<Long,StreamMeshExporter.Page>();long total=0;int maximum=0;
            for(int i=0;i<count;i++) {
                require(file.getFilePointer()+570<=size);var position=new SectionVisibility.Position(file.readInt(),file.readInt(),file.readInt());require(positions.add(position));
                float[] box=new float[6];for(int a=0;a<6;a++)box[a]=file.readFloat();bounds(box);byte[] exits=new byte[6],solid=new byte[512];file.readFully(exits);file.readFully(solid);
                var levels=new ArrayList<List<StreamMeshExporter.Page>>();
                for(int level=0;level<4;level++) {
                    require(file.getFilePointer()+4<=size);int n=file.readInt();require(n>=0&&n<=100000&&file.getFilePointer()+(compressed?32L:20L)*n<=size);var pages=new ArrayList<StreamMeshExporter.Page>();
                    for(int p=0;p<n;p++) {
                        long offset=file.readLong();int bytes=file.readInt(),faces=file.readInt(),layer=file.readInt();
                        require(offset>=64&&bytes>0&&offset<=catalog-bytes&&faces>=0&&faces<=8192&&Set.of(0,1,4,5,6).contains(layer));
                        int raw=compressed?file.readInt():bytes,codec=compressed?file.readInt():0;long checksum=compressed?Integer.toUnsignedLong(file.readInt()):0;
                        boolean compactPage=compact&&(codec&2)!=0,deflated=(codec&1)!=0;require(raw>=1&&raw<=StreamMeshExporter.MAX_RAW_PAGE&&codec>=0&&codec<=(compact?3:1)&&(deflated&&bytes<raw||!deflated&&bytes==raw));
                        require(layer==6?faces==0&&!compactPage&&raw>=4&&(raw-4)%48==0:raw==faces*(compactPage?40:80));
                        var page=new StreamMeshExporter.Page(offset,bytes,faces,layer,raw,codec,checksum);var previous=descriptors.putIfAbsent(offset,page);require(previous==null||previous.equals(page));pages.add(previous==null?page:previous);
                    }
                    levels.add(List.copyOf(pages));
                }
                int faces=levels.getFirst().stream().mapToInt(StreamMeshExporter.Page::quads).sum();total=Math.addExact(total,faces);maximum=Math.max(maximum,faces);entries.add(new StreamMeshExporter.Entry(position,box,exits,solid,List.copyOf(levels)));
            }
            require(file.getFilePointer()==size&&total==quads);return new StreamMeshExporter.Index(List.copyOf(entries),bounds,quads,maximum,size,skins,entities);
        }catch(ArithmeticException|IllegalArgumentException e){throw new IOException("场景缓存索引损坏",e);}
    }
    private static void bounds(float[] bounds)throws IOException{for(int i=0;i<3;i++)require(Float.isFinite(bounds[i])&&Float.isFinite(bounds[i+3])&&bounds[i]<=bounds[i+3]);}
}
