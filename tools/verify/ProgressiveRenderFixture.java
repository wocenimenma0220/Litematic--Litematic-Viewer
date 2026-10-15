package io.github.litematicrender.schematic;
import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.model.*;
import io.github.litematicrender.world.*;
import io.github.litematicrender.serve.JsonRpcServer;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import java.nio.file.*;
import java.util.*;
public class ProgressiveRenderFixture {
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),out=root.resolve("tools/verify/progressive-output/pixels");Files.createDirectories(out);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        Path small=LodRegression.fixture(out.resolve("fixture.litematic"),false);
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)){
            var registry=BlockRegistry.load(resources);var baker=new ModelBaker(resources,registry);var atlas=SpriteAtlas.load(resources,"blocks");var colors=new Colormaps();colors.load(resources);
            var schematic=LitematicaSchematic.load(small);schematic.materialize(registry);var overview=new OverviewMesh(schematic,baker,atlas);var coarseAtlas=SpriteAtlas.overview();
            StreamMeshExporter.write(overview,coarseAtlas,out.resolve("overview.lmesh"));coarseAtlas.stitched.writePng(out.resolve("overview.png"));
            var full=LodRegression.builder(schematic,registry,baker,colors,atlas,true).build();
            var generator=LodRegression.builder(schematic,registry,baker,colors,atlas,true).overview(overview);var index=StreamMeshExporter.write(generator,atlas,out.resolve("detail.lmesh"));
            MeshExporter.write(full,atlas,out.resolve("legacy.bin"));atlas.stitched.writePng(out.resolve("atlas.png"));
            var json=JsonRpcServer.class.getDeclaredMethod("toJson",Object.class);json.setAccessible(true);
            Files.writeString(out.resolve("catalog.json"),(String)json.invoke(null,Map.of("sections",index.sections().stream().map(StreamMeshExporter::describe).toList(),"quads",index.quads(),"bytes",index.bytes())));
            var tag=NbtFileUtils.readCompressed(small);var region=tag.getCompound("Regions").orElseThrow().getCompound("lod").orElseThrow();
            var air=new NbtList();air.add(new NbtCompound().putString("Name","minecraft:air"));var saved=new NbtList();var pos=new NbtList();for(double coordinate:new double[]{100,2,-100})pos.add(new NbtScalars.NbtDouble(coordinate));
            saved.add(new NbtCompound().putString("id","minecraft:piglin").put("Pos",pos));region.put("Size",new NbtCompound().putInt("x",1).putInt("y",1).putInt("z",1)).put("BlockStatePalette",air).putLongArray("BlockStates",new long[1]).put("Entities",saved);
            Path entities=out.resolve("entity-only.litematic");NbtFileUtils.writeCompressed(entities,tag);var entityOnly=LitematicaSchematic.load(entities);entityOnly.materialize(registry);var frame=new OverviewMesh(entityOnly,baker,atlas);
            if(!Arrays.equals(frame.bounds(),new float[]{98,2,-102,102,5,-98})||!frame.sections().isEmpty())throw new AssertionError("entity-only preview lost its frame bounds");
            System.out.println("PASS: progressive color proxy, detailed atlas, signs/water/head/light and full-model reference generated");
        }
    }
}
