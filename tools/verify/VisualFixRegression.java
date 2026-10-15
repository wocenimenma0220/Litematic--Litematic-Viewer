package io.github.litematicrender.schematic;

import io.github.litematicrender.model.*;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.world.*;
import java.nio.file.*;
import java.util.*;

public class VisualFixRegression {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static NbtCompound state(String name,String... props){var result=new NbtCompound().putString("Name","minecraft:"+name);var values=new NbtCompound();for(int i=0;i<props.length;i+=2)values.putString(props[i],props[i+1]);return result.put("Properties",values);}
    static Path write(Path file,int sx,int sy,int sz,List<NbtCompound> palette,int[] cells)throws Exception {
        int bits=Math.max(2,32-Integer.numberOfLeadingZeros(palette.size()-1));long[] words=new long[(cells.length*bits+63)/64];
        for(int i=0;i<cells.length;i++){int word=i*bits/64,shift=i*bits%64;words[word]|=(long)cells[i]<<shift;if(shift+bits>64)words[word+1]|=(long)cells[i]>>>(64-shift);}
        var entries=new NbtList();palette.forEach(entries::add);var size=LodRegression.xyz(sx,sy,sz);
        var region=new NbtCompound().put("Position",LodRegression.xyz(0,0,0)).put("Size",size).put("BlockStatePalette",entries).put("BlockStates",new NbtScalars.NbtLongArray(words));
        NbtFileUtils.writeCompressed(file,new NbtCompound().putInt("Version",6).putInt("MinecraftDataVersion",0).put("Metadata",new NbtCompound().put("EnclosingSize",size)).put("Regions",new NbtCompound().put("test",region)));return file;
    }
    static List<SceneMesh.MeshQuad> quads(SceneMesh mesh){var out=new ArrayList<SceneMesh.MeshQuad>();for(var layer:List.of(mesh.solid,mesh.cutout,mesh.translucent,mesh.fluid,mesh.portal))out.addAll(layer);return out;}
    static Set<String> wires(SceneMesh mesh){var out=new HashSet<String>();for(var q:quads(mesh))if(q.spriteId!=null&&q.spriteId.startsWith("litematicrender:redstone/"))out.add(q.spriteId+":"+q.x0+","+q.y0+","+q.z0+":"+q.x1+","+q.y1+","+q.z1+":"+q.x2+","+q.y2+","+q.z2+":"+q.x3+","+q.y3+","+q.z3);return out;}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),out=root.resolve("tools/verify/lod-output");Files.createDirectories(out);System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)) {
            var registry=BlockRegistry.load(resources);var baker=new ModelBaker(resources,registry);var colors=new Colormaps();colors.load(resources);var atlas=SpriteAtlas.load(resources,"blocks");
            int sx=64,sy=4,sz=44;var palette=new ArrayList<NbtCompound>(List.of(state("air"),state("stone")));int[] cells=new int[sx*sy*sz];
            for(int z=0;z<sz;z++)for(int x=0;x<sx;x++)cells[z*sx+x]=1;
            String[] values={"none","side","up"};int wireCount=0;
            for(int power:new int[]{0,15})for(int shape=0;shape<81;shape++) {
                int x=1+(wireCount%16)*4,z=1+(wireCount/16)*4,v=shape;String[] directions=new String[4];for(int i=0;i<4;i++){directions[i]=values[v%3];v/=3;}
                palette.add(state("redstone_wire","power",""+power,"north",directions[0],"east",directions[1],"south",directions[2],"west",directions[3]));cells[(sz+z)*sx+x]=palette.size()-1;
                int[][] offsets={{0,-1},{1,0},{0,1},{-1,0}};for(int i=0;i<4;i++)if(directions[i].equals("up"))cells[(sz+z+offsets[i][1])*sx+x+offsets[i][0]]=1;
                wireCount++;
            }
            Path wireFile=write(out.resolve("redstone-shapes.litematic"),sx,sy,sz,palette,cells);var schematic=LitematicaSchematic.load(wireFile);schematic.materialize(registry);
            var stream=LodRegression.builder(schematic,registry,baker,colors,atlas,true);stream.prepareStreaming();int spriteCount=atlas.spriteCount();Image frozen=atlas.stitched;var sectionWires=new HashSet<String>();int generated=0;
            for(var section:stream.sections())for(var q:quads(stream.buildSection(section)))if(q.spriteId!=null&&q.spriteId.startsWith("litematicrender:redstone/")){check(atlas.getOrMissingEntry(q.spriteId).contents.name.equals(q.spriteId),"late redstone sprite missing: "+q.spriteId);generated++;}
            for(var section:stream.sections())sectionWires.addAll(wires(stream.buildSection(section)));
            check(atlas.stitched==frozen&&atlas.spriteCount()==spriteCount,"streaming redstone changed the frozen atlas");check(generated>300,"wire fixture did not cover corners and vertical surfaces");
            var full=LodRegression.builder(schematic,registry,baker,colors,atlas,true).build();check(wires(full).equals(sectionWires),"wall/corner redstone geometry changed across sections");
            MeshExporter.writeStreaming(LodRegression.builder(schematic,registry,baker,colors,atlas,true),atlas,out.resolve("redstone-shapes.lmesh"));atlas.stitched.writePng(out.resolve("redstone-atlas.png"));
            SchematicRenderer.render(null,full,colors,atlas,new RenderSettings(45,45,1024,768)).writePng(out.resolve("redstone-shapes.png"));
            String[] lamps={"ochre_froglight","verdant_froglight","pearlescent_froglight","glowstone","sea_lantern","shroomlight","redstone_lamp","redstone_lamp","furnace","furnace","torch","soul_torch"};int[] levels={15,15,15,15,15,15,15,0,13,0,14,10};
            sx=lamps.length*11;sy=11;sz=11;cells=new int[sx*sy*sz];palette=new ArrayList<>(List.of(state("air"),state("stone")));
            for(int lamp=0;lamp<lamps.length;lamp++) {
                for(int y=0;y<11;y++)for(int z=0;z<11;z++)for(int x=0;x<11;x++)if(x==0||x==10||y==0||y==10||z==0||z==10)cells[(y*sz+z)*sx+lamp*11+x]=1;
                palette.add(state(lamps[lamp],"lit",levels[lamp]==0?"false":"true","axis",new String[]{"x","y","z"}[lamp%3]));cells[(5*sz+5)*sx+lamp*11+5]=palette.size()-1;
            }
            Path lampFile=write(out.resolve("emissive-rooms.litematic"),sx,sy,sz,palette,cells);var rooms=LitematicaSchematic.load(lampFile);rooms.materialize(registry);
            var roomBuilder=LodRegression.builder(rooms,registry,baker,colors,atlas,true);var roomMesh=roomBuilder.build();var lightField=SceneBuilder.class.getDeclaredField("light");lightField.setAccessible(true);LightSampler light=(LightSampler)lightField.get(roomBuilder);
            int[][] delta={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};int testedFaces=0;
            for(int lamp=0;lamp<lamps.length;lamp++) {
                int x=lamp*11+5;for(var d:delta){int value=light.cellLight(x+d[0],5+d[1],5+d[2]);check(LightVolume.skyOf(value)==0&&LightVolume.blockOf(value)==Math.max(0,levels[lamp]-1),"lamp did not illuminate every axis: "+lamps[lamp]+Arrays.toString(d));}
                if(levels[lamp]!=15)continue;
                int faces=0;for(var q:quads(roomMesh))if(q.blockX==x&&q.blockY==5&&q.blockZ==5){faces++;check(q.ao==null&&q.vertexColors!=null,"emitter was darkened by AO");float[] floor=new float[3];LightVolume.brightness(15,floor);for(int c:q.vertexColors){check(((c>>16)&255)>=Math.round(q.shade*floor[0]*255)-1&&((c>>8)&255)>=Math.round(q.shade*floor[1]*255)-1&&(c&255)>=Math.round(q.shade*floor[2]*255)-1,"dark emitter face: "+lamps[lamp]);}}
                check(faces==6,"emitter lost a face: "+lamps[lamp]);testedFaces+=faces;
            }
            System.out.println("PASS: "+wireCount+" redstone states, frozen atlas and exact wall/corner geometry; "+testedFaces+" bright emitter faces and six-axis light propagation");
        }
    }
}
