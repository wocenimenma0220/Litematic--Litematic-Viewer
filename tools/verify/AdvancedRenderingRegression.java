package io.github.litematicrender.schematic;

import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.model.*;
import io.github.litematicrender.world.*;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import java.nio.file.*;
import java.util.*;

public final class AdvancedRenderingRegression {
    static void check(boolean result,String message){if(!result)throw new AssertionError(message);}
    static SceneMesh.MeshQuad face(float[] xs,float[] ys,float[] zs,String sprite){return new SceneMesh.MeshQuad(xs,ys,zs,0,0,1,0,1,1,0,1,sprite,-1,1,1,1,1,null,SceneMesh.Layer.TRANSLUCENT).owner(2,3,4,"probe");}
    static void set(Object object,String name,Object value)throws Exception{var field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),output=Path.of(args[1]).toAbsolutePath();Files.createDirectories(output);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)) {
            var registry=BlockRegistry.load(resources);var baker=new ModelBaker(resources,registry);var atlas=SpriteAtlas.load(resources,"blocks");var shapes=new BlockShape(baker,atlas);var colors=new Colormaps();colors.load(resources);
            var stone=registry.getOrMissing("minecraft:stone").defaultState;var water=registry.getOrMissing("minecraft:water").defaultState;var torch=registry.getOrMissing("minecraft:torch").defaultState;
            int sx=64,sy=128,sz=48;var cells=new BlockState[sx*sy*sz];Arrays.fill(cells,BlockState.air());
            for(int x=20;x<=40;x++)for(int z=12;z<=32;z++)cells[(112*sz+z)*sx+x]=stone;
            cells[(4*sz+4)*sx+4]=torch;for(int y=20;y<25;y++)cells[(y*sz+5)*sx+5]=water;
            var dense=new LightVolume(sx,sy,sz,cells,shapes);var sparse=new SparseLightVolume(sx,sy,sz,shapes);
            for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++){var state=cells[(y*sz+z)*sx+x];if(!state.isAir())sparse.put(x,y,z,state);}
            dense.compute();sparse.compute();int compared=0;
            for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++){int a=dense.cellLight(x,y,z),b=sparse.cellLight(x,y,z);check(a==b,"dense/sparse light differs at "+x+","+y+","+z+": "+a+" / "+b);compared++;}
            check(LightVolume.skyOf(dense.cellLight(30,110,22))==15,"1.0.8 covered-cell sky changed");
            check(LightVolume.blockOf(dense.cellLight(5,4,4))==13,"torch propagation wrong");
            var bottom=registry.getOrMissing("minecraft:stone_slab").defaultState.with("type","bottom");var top=bottom.with("type","top");
            var model=baker.bake(baker.selectVariants(bottom.blockId,bottom).get(0));var side=model.quadsForFace(Quad.EAST).get(0);
            check(shapes.faceOccluded(side,Quad.EAST,bottom),"matching slab boundaries not culled");
            check(!shapes.faceOccluded(side,Quad.EAST,top),"different slab halves incorrectly culled");
            check(!shapes.faceOccluded(side,Quad.EAST,registry.getOrMissing("minecraft:glass").defaultState),"glass incorrectly treated as opaque");
            var builder=new SceneBuilder(registry,baker,colors,atlas);
            float[] x={.25f,.75f,.75f,.25f},y={.5f,.5f,.5f,.5f},z={.25f,.25f,.75f,.75f};
            var ao=SceneBuilder.class.getDeclaredMethod("remapToVertices",float[].class,int.class,float[].class,float[].class,float[].class);ao.setAccessible(true);
            float[] expected={.2f,.4f,.6f,.8f},values=(float[])ao.invoke(builder,expected,Quad.UP,x,y,z);
            for(int i=0;i<4;i++)check(values[i]==expected[i],"1.0.8 partial-face AO corner mapping changed");
            var a=face(new float[]{-1,1,1,-1},new float[]{-1,-1,1,1},new float[]{-1,1,1,-1},"probe:red");
            var b=face(new float[]{-1,1,1,-1},new float[]{-1,-1,1,1},new float[]{1,-1,-1,1},"probe:blue");
            var tree=TranslucentBsp.build(List.of(a,b));check(tree.faces.size()>2&&tree.fallbackLeaves==0,"intersecting planes were not split");
            for(var split:tree.faces)check(split.blockX==2&&split.regionName.equals("probe"),"split face lost picking ownership");
            var mesh=new SceneMesh();mesh.translucent.add(a);mesh.translucent.add(b);mesh.translucentBsp=tree;
            var red=new Image(1,1);red.setPixel(0,0,0x80ff0000);var blue=new Image(1,1);blue.setPixel(0,0,0x800000ff);atlas.replaceGeneratedSprites(Map.of("probe:red",red,"probe:blue",blue));
            MeshExporter.write(mesh,atlas,output.resolve("crossing-mesh.bin"));atlas.stitched.writePng(output.resolve("crossing-atlas.png"));
            var settings=new RenderSettings(0,0,128,128);var picture=SchematicRenderer.render(null,mesh,colors,atlas,settings);picture.writePng(output.resolve("crossing-cpu.png"));
            check(Image.blue(picture.pixel(45,64))>Image.red(picture.pixel(45,64))&&Image.red(picture.pixel(83,64))>Image.blue(picture.pixel(83,64)),"BSP crossing surfaces do not reverse local front colors");
            var far=new SparseLightVolume(2001,2001,2001,shapes);far.put(0,0,0,stone);far.put(2000,2000,2000,stone);far.compute();check(far.sectionCount()<100,"distant regions expanded empty space");
            var regions=new NbtCompound();
            for(int position:List.of(0,2000)) {
                var size=new NbtCompound().putInt("x",1).putInt("y",1).putInt("z",1);
                var at=new NbtCompound().putInt("x",position).putInt("y",position).putInt("z",position);
                var palette=new NbtList();palette.add(new NbtCompound().putString("Name","minecraft:air"));palette.add(new NbtCompound().putString("Name","minecraft:stone"));
                regions.put("region"+position,new NbtCompound().put("Size",size).put("Position",at).put("BlockStatePalette",palette).put("BlockStates",new NbtScalars.NbtLongArray(new long[]{1})));
            }
            Path distantFile=output.resolve("distant-regions.litematic");NbtFileUtils.writeCompressed(distantFile,new NbtCompound().putInt("Version",6).putInt("MinecraftDataVersion",0).put("Regions",regions));
            var distant=LitematicaSchematic.load(distantFile);distant.materialize(registry);var distantBuilder=new SceneBuilder(registry,baker,colors,atlas);
            for(var region:distant.regions().values()){var origin=region.blockOrigin();distantBuilder.addRegion(region,new int[]{origin.x(),origin.y(),origin.z()});}
            var distantMesh=distantBuilder.build();var lightField=SceneBuilder.class.getDeclaredField("light");lightField.setAccessible(true);
            check(distantMesh.quadCount==12&&lightField.get(distantBuilder) instanceof SparseLightVolume,"wide schematic did not automatically select sparse lighting");
            Files.writeString(output.resolve("advanced.json"),"{\"pass\":true,\"lightCellsCompared\":"+compared+",\"sparseSections\":"+sparse.sectionCount()+",\"distantSections\":"+far.sectionCount()+",\"shapeCulling\":true,\"partialAo\":true,\"bspFaces\":"+tree.faces.size()+",\"bspFallback\":"+tree.fallbackLeaves+"}");
            System.out.println(Files.readString(output.resolve("advanced.json")));
        }
    }
}
