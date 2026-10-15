package io.github.litematicrender.schematic;

import io.github.litematicrender.model.*;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.serve.RenderEngine;
import io.github.litematicrender.world.*;
import java.nio.file.*;
import java.util.*;

public final class LodRegression {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static NbtCompound xyz(int x,int y,int z){return new NbtCompound().putInt("x",x).putInt("y",y).putInt("z",z);}
    static Path fixture(Path file,boolean dense)throws Exception {
        int sx=dense?320:24,sy=dense?16:4,sz=dense?16:4;int[] cells=new int[sx*sy*sz];
        var names=List.of("air","stone","glass","water","player_head","oak_sign","torch");
        if(dense){for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++)if(((x+y+z)&1)==0)cells[(y*sz+z)*sx+x]=1;}
        else {
            for(int x=0;x<sx;x++)for(int z=0;z<sz;z++)cells[z*sx+x]=1;
            cells[(1*sz+1)*sx]=4;cells[(1*sz+1)*sx+3]=5;cells[(1*sz+1)*sx+5]=6;
            for(int x=14;x<19;x++)cells[(1*sz+1)*sx+x]=2;
            for(int x=10;x<19;x++)cells[(1*sz+2)*sx+x]=3;
        }
        var palette=new NbtList();for(String name:names)palette.add(new NbtCompound().putString("Name","minecraft:"+name));
        int bits=3;long[] packed=new long[(cells.length*bits+63)/64];
        for(int i=0;i<cells.length;i++){int word=i*bits/64,shift=i*bits%64;packed[word]|=(long)cells[i]<<shift;if(shift+bits>64)packed[word+1]|=(long)cells[i]>>>(64-shift);}
        var tiles=new NbtList();
        if(!dense){var messages=new NbtList();for(String text:List.of("平滑光照 LOD","中文告示牌","","透明边界"))messages.add(new NbtScalars.NbtString(text));tiles.add(xyz(3,1,1).put("front_text",new NbtCompound().put("messages",messages).putString("color","white")));}
        var size=xyz(sx,sy,sz);var region=new NbtCompound().put("Position",xyz(0,0,0)).put("Size",size).put("BlockStatePalette",palette).put("BlockStates",new NbtScalars.NbtLongArray(packed)).put("TileEntities",tiles);
        NbtFileUtils.writeCompressed(file,new NbtCompound().putInt("Version",6).putInt("MinecraftDataVersion",0).put("Metadata",new NbtCompound().putString("Name",dense?"LOD working set":"LOD transparent and text").put("EnclosingSize",size)).put("Regions",new NbtCompound().put("lod",region)));return file;
    }
    static SceneBuilder builder(LitematicaSchematic schematic,BlockRegistry registry,ModelBaker baker,Colormaps colors,SpriteAtlas atlas,boolean real) {
        var result=new SceneBuilder(registry,baker,colors,atlas).realLighting(real);for(var region:schematic.regions().values()){var origin=region.blockOrigin();result.addRegion(region,new int[]{origin.x(),origin.y(),origin.z()});}return result;
    }
    static Set<String> geometry(SceneMesh mesh) {
        var keys=new HashSet<String>();for(var layer:List.of(mesh.solid,mesh.cutout))for(var q:layer)keys.add(q.spriteId+":"+q.x0+","+q.y0+","+q.z0+":"+q.x1+","+q.y1+","+q.z1+":"+q.x2+","+q.y2+","+q.z2+":"+q.x3+","+q.y3+","+q.z3+":"+Arrays.toString(q.vertexColors));
        return keys;
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),out=Path.of(args[1]).toAbsolutePath();Files.createDirectories(out);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        Path small=fixture(out.resolve("small.litematic"),false),dense=fixture(out.resolve("dense.litematic"),true);
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)) {
            var registry=BlockRegistry.load(resources);var baker=new ModelBaker(resources,registry);var colors=new Colormaps();colors.load(resources);var atlas=SpriteAtlas.load(resources,"blocks");
            var schematic=LitematicaSchematic.load(small);schematic.materialize(registry);
            var full=builder(schematic,registry,baker,colors,atlas,true).build();MeshExporter.write(full,atlas,out.resolve("legacy.bin"));atlas.stitched.writePng(out.resolve("atlas.png"));
            var generator=builder(schematic,registry,baker,colors,atlas,true);var index=MeshExporter.writeStreaming(generator,atlas,out.resolve("small.lmesh"));
            Set<String> actual=new HashSet<>();for(var entry:index.sections())actual.addAll(geometry(generator.buildSection(entry.position())));
            check(geometry(full).equals(actual),"section boundaries lost or duplicated full-detail faces");
            check(index.sections().size()==2,"fixture did not cross a section boundary");
            check(index.sections().stream().flatMap(e->e.levels().get(0).stream()).allMatch(p->p.layer()==6||p.rawBytes()==p.quads()*80),"compact vertex encoding mismatch");
            for(var entry:index.sections())for(int level=1;level<4;level++)for(var page:entry.levels().get(0))if(page.layer()==5||level==1&&page.layer()>=4)check(entry.levels().get(level).contains(page),"near blended geometry or boat masks changed across LODs");
            var smooth=full.solid.stream().filter(q->q.vertexColors!=null&&Arrays.stream(q.vertexColors).distinct().count()>1).count();
            check(smooth>0,"torch light has no per-vertex gradient");
            SchematicRenderer.render(null,full,colors,atlas,new RenderSettings(45,30,512,384)).writePng(out.resolve("smooth.png"));
            var negativeTag=NbtFileUtils.readCompressed(small);negativeTag.getCompound("Regions").orElseThrow().getCompound("lod").orElseThrow().put("Position",xyz(-17,-3,-1));
            Path negative=out.resolve("negative.litematic");NbtFileUtils.writeCompressed(negative,negativeTag);var negativeSchematic=LitematicaSchematic.load(negative);negativeSchematic.materialize(registry);
            var negativeFull=builder(negativeSchematic,registry,baker,colors,atlas,true).build();var negativeGenerator=builder(negativeSchematic,registry,baker,colors,atlas,true);
            var negativeIndex=MeshExporter.writeStreaming(negativeGenerator,atlas,out.resolve("negative.lmesh"));var negativeFaces=new HashSet<String>();
            for(var entry:negativeIndex.sections())negativeFaces.addAll(geometry(negativeGenerator.buildSection(entry.position())));
            check(geometry(negativeFull).equals(negativeFaces),"negative section coordinates changed geometry or light");
            var overlapFull=new SceneBuilder(registry,baker,colors,atlas).realLighting(true);var overlapStream=new SceneBuilder(registry,baker,colors,atlas).realLighting(true);
            for(var region:schematic.regions().values())for(var offset:new int[][]{{0,0,0},{8,0,0}}){overlapFull.addRegion(region,offset);overlapStream.addRegion(region,offset);}
            var expectedOverlap=geometry(overlapFull.build());var overlapIndex=MeshExporter.writeStreaming(overlapStream,atlas,out.resolve("overlap.lmesh"));var overlapFaces=new HashSet<String>();
            for(var entry:overlapIndex.sections())overlapFaces.addAll(geometry(overlapStream.buildSection(entry.position())));
            check(expectedOverlap.equals(overlapFaces),"overlapping regions changed compact light sampling");
            var denseSchematic=LitematicaSchematic.load(dense);denseSchematic.materialize(registry);
            var denseIndex=MeshExporter.writeStreaming(builder(denseSchematic,registry,baker,colors,atlas,false),atlas,out.resolve("dense.lmesh"));
            long[] counts=new long[4];for(var entry:denseIndex.sections())for(int level=0;level<4;level++)counts[level]+=entry.levels().get(level).stream().mapToLong(StreamMeshExporter.Page::quads).sum();
            check(counts[1]<counts[0]/4&&counts[2]<counts[1]&&counts[3]<counts[2],"LOD did not simplify the actual surface");
            check(denseIndex.maximumSectionQuads()<=16*16*16*6,"exporter retained more than one section's geometry");
            System.out.println("PASS: exact section faces, compact pages, shared transparent pages, smooth corners, actual LOD counts="+Arrays.toString(counts));
        }
        try(var engine=new RenderEngine(root)) {
            engine.loadResources(engine.defaultPackRoot());engine.open(small);
            var exported=engine.exportStreamScene(out.resolve("engine-atlas.png"),out.resolve("engine.lmesh"),false,true);
            var scene=RenderEngine.class.getDeclaredField("scene");scene.setAccessible(true);
            check(scene.get(engine)==null,"stream export retained the complete Java mesh");
            var picked=engine.pickBlock(new double[]{.05,3,.05},new double[]{0,-1,0},10);
            check(Boolean.TRUE.equals(picked.get("hit")),"streaming point selection missed stone");check(scene.get(engine)==null,"point selection rebuilt full mesh");
            engine.render(out.resolve("full-export.png"),new RenderSettings(45,30,128,128),true);
            check(scene.get(engine)==null,"PNG export left full geometry resident");
            boolean failed=false;try{engine.render(out,new RenderSettings(45,30,32,32),false);}catch(Exception expected){failed=true;}
            check(failed&&scene.get(engine)==null,"failed PNG export left full geometry resident");
            check(Boolean.TRUE.equals(engine.pickBlock(new double[]{.05,3,.05},new double[]{0,-1,0},10).get("hit")),"picking after PNG export lost streaming state");
            check(Boolean.FALSE.equals(exported.get("fullMeshResident")),"streaming export did not report residency");
            System.out.println("PASS: streamed engine export and exact picking retain no full mesh, including after PNG export");
        }
    }
}
