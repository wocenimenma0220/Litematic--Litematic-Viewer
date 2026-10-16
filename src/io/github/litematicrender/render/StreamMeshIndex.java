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
            file.seek(catalog);
            // available() is exact for this bounded source, including buffered but unread bytes.
            var source=new InputStream(){
                int remaining=length;
                @Override public int available(){return remaining;}
                @Override public int read()throws IOException{if(remaining==0)return -1;int value=file.read();if(value>=0)remaining--;return value;}
                @Override public int read(byte[] bytes,int offset,int count)throws IOException{if(count==0)return 0;if(remaining==0)return -1;int n=file.read(bytes,offset,Math.min(remaining,count));if(n>0)remaining-=n;return n;}
            };
            var data=new DataInputStream(new BufferedInputStream(source,65536));
            require(data.readInt()==count);var entries=new ArrayList<StreamMeshExporter.Entry>();var positions=new HashSet<SectionVisibility.Position>();var descriptors=new HashMap<Long,StreamMeshExporter.Page>();long total=0;int maximum=0;
            for(int i=0;i<count;i++) {
                require(data.available()>=570);var position=new SectionVisibility.Position(data.readInt(),data.readInt(),data.readInt());require(positions.add(position));
                float[] box=new float[6];for(int a=0;a<6;a++)box[a]=data.readFloat();bounds(box);byte[] exits=new byte[6],solid=new byte[512];data.readFully(exits);data.readFully(solid);
                var levels=new ArrayList<List<StreamMeshExporter.Page>>();
                for(int level=0;level<4;level++) {
                    require(data.available()>=4);int n=data.readInt();require(n>=0&&n<=100000&&(compressed?32L:20L)*n<=data.available());var pages=new ArrayList<StreamMeshExporter.Page>();
                    for(int p=0;p<n;p++) {
                        long offset=data.readLong();int bytes=data.readInt(),faces=data.readInt(),layer=data.readInt();
                        require(offset>=64&&bytes>0&&offset<=catalog-bytes&&faces>=0&&faces<=8192&&Set.of(0,1,4,5,6).contains(layer));
                        int raw=compressed?data.readInt():bytes,codec=compressed?data.readInt():0;long checksum=compressed?Integer.toUnsignedLong(data.readInt()):0;
                        boolean compactPage=compact&&(codec&2)!=0,deflated=(codec&1)!=0;require(raw>=1&&raw<=StreamMeshExporter.MAX_RAW_PAGE&&codec>=0&&codec<=(compact?3:1)&&(deflated&&bytes<raw||!deflated&&bytes==raw));
                        require(layer==6?faces==0&&!compactPage&&raw>=4&&(raw-4)%48==0:raw==faces*(compactPage?40:80));
                        var page=new StreamMeshExporter.Page(offset,bytes,faces,layer,raw,codec,checksum);var previous=descriptors.putIfAbsent(offset,page);require(previous==null||previous.equals(page));pages.add(previous==null?page:previous);
                    }
                    levels.add(List.copyOf(pages));
                }
                int faces=levels.getFirst().stream().mapToInt(StreamMeshExporter.Page::quads).sum();total=Math.addExact(total,faces);maximum=Math.max(maximum,faces);entries.add(new StreamMeshExporter.Entry(position,box,exits,solid,List.copyOf(levels)));
            }
            require(data.available()==0&&total==quads);return new StreamMeshExporter.Index(List.copyOf(entries),bounds,quads,maximum,size,skins,entities);
        }catch(ArithmeticException|IllegalArgumentException e){throw new IOException("场景缓存索引损坏",e);}
    }
    private static void bounds(float[] bounds)throws IOException{for(int i=0;i<3;i++)require(Float.isFinite(bounds[i])&&Float.isFinite(bounds[i+3])&&bounds[i]<=bounds[i+3]);}
}
