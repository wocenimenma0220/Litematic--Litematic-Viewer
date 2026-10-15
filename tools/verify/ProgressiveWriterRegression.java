package io.github.litematicrender.serve;

import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class ProgressiveWriterRegression {
    static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]);Files.createDirectories(out);var atlas=SpriteAtlas.overview();var positions=new ArrayList<SectionVisibility.Position>();for(int x=-8;x<8;x++)positions.add(new SectionVisibility.Position(x,0,0));
        var source=new StreamMeshExporter.Source(){
            public List<SectionVisibility.Position> sections(){return positions;}
            public void prepareStreaming(){}
            public SceneMesh buildSection(SectionVisibility.Position p){var mesh=new SceneMesh();float x=p.x()*16,y=p.y()*16,z=p.z()*16;mesh.solid.add(new SceneMesh.MeshQuad(new float[]{x,x,x+1,x+1},new float[]{y,y,y,y},new float[]{z+1,z,z,z+1},0,0,0,1,1,1,1,0,"litematicrender:overview",0xabcdef,1,1,1,1,null,SceneMesh.Layer.SOLID));mesh.quadCount=1;return mesh;}
        };
        var priority=new PreviewPriority(Map.of("eye",List.of(111,8,8),"orbit",true));var sorted=new ArrayList<>(positions);priority.sort(sorted);check(sorted.getFirst().x()==6,"distance priority did not select closest section");
        var clip=new PreviewPriority(Map.of("eye",List.of(111,8,8),"orbit",true,"planes",List.of(-1,0,0,-1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,1)));clip.sort(sorted);check(sorted.getFirst().x()<0,"offscreen sections outranked visible sections");
        Path file=out.resolve("live.lmesh");int[] seen={0};
        var progress=new StreamMeshExporter.Progress(){
            public void prepared(List<SectionVisibility.Position> p,long committed)throws IOException{check(committed==64&&Files.size(file)==64,"live file was not visible before generation");}
            public void prioritize(List<SectionVisibility.Position> pending){priority.sort(pending);}
            public void section(StreamMeshExporter.Entry entry,long committed,int completed)throws IOException{
                if(completed==1)check(entry.position().x()==6,"writer ignored camera priority");
                check(Files.size(file)>=committed,"published bytes not present");
                try(var input=new RandomAccessFile(file.toFile(),"r")){
                    for(var page:entry.levels().getFirst()){
                        check(page.offset()+page.bytes()<=committed,"future bytes were published");byte[] encoded=new byte[page.bytes()];input.seek(page.offset());input.readFully(encoded);byte[] raw=page.codec()==0?encoded:new InflaterInputStream(new ByteArrayInputStream(encoded)).readAllBytes();var crc=new CRC32();crc.update(raw);check(raw.length==page.rawBytes()&&crc.getValue()==page.checksum(),"published page could not be decoded");
                    }
                }
                boolean incomplete=false;try{StreamMeshIndex.read(file,Map.of(),Map.of());}catch(IOException expected){incomplete=true;}check(incomplete,"incomplete mesh was accepted as a completed cache");seen[0]++;
            }
        };
        var index=StreamMeshExporter.write(source,atlas,file,progress);check(seen[0]==positions.size(),"some sections were not published");check(StreamMeshIndex.read(file,Map.of(),Map.of()).quads()==index.quads(),"completed live file is not a regular readable cache");
        Path cancelled=out.resolve("cancelled.lmesh");boolean failed=false;
        try{StreamMeshExporter.write(source,atlas,cancelled,new StreamMeshExporter.Progress(){public void prepared(List<SectionVisibility.Position> p,long bytes){}public void section(StreamMeshExporter.Entry e,long bytes,int count)throws IOException{throw new IOException("cancel test");}});}catch(IOException expected){failed=true;}check(failed&&!Files.exists(cancelled),"cancelled work leaked a partial mesh");
        check(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("schematic-section-builder")),"section producer survived cancellation");
        System.out.println("PASS: view/frustum priority, readable published pages before completion, complete LMESH6 compatibility, partial-index rejection and producer cancellation");
    }
}
