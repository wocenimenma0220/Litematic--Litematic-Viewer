package io.github.litematicrender.schematic;

import io.github.litematicrender.model.*;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.serve.BlockInspector;
import io.github.litematicrender.world.*;
import java.nio.file.*;
import java.util.*;

public final class CopperGolemStatueRegression {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static String owner(SceneMesh.MeshQuad q){return q.blockX+","+q.blockY+","+q.blockZ;}
    static SceneBuilder builder(LitematicaSchematic schematic,BlockRegistry registry,ModelBaker baker,Colormaps colors,SpriteAtlas atlas){
        var builder=new SceneBuilder(registry,baker,colors,atlas).realLighting(true);
        for(var region:schematic.regions().values()){var p=region.blockOrigin();builder.addRegion(region,new int[]{p.x(),p.y(),p.z()});}return builder;
    }
    static float[][] vertices(SceneMesh.MeshQuad q){return new float[][]{{q.x0,q.y0,q.z0},{q.x1,q.y1,q.z1},{q.x2,q.y2,q.z2},{q.x3,q.y3,q.z3}};}
    static Map<String,List<SceneMesh.MeshQuad>> owners(SceneMesh mesh){
        var result=new TreeMap<String,List<SceneMesh.MeshQuad>>();for(var q:mesh.cutout)result.computeIfAbsent(owner(q),k->new ArrayList<>()).add(q);return result;
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toAbsolutePath(),input=Path.of(args[1]),out=root.resolve("tools/verify/copper-statue-output");Files.createDirectories(out);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)){
            var registry=BlockRegistry.load(resources);var baker=new ModelBaker(resources,registry);var colors=new Colormaps();colors.load(resources);var atlas=SpriteAtlas.load(resources,"blocks");
            var actual=LitematicaSchematic.load(input);actual.materialize(registry);check(actual.regions().values().stream().allMatch(r->r.entities.isEmpty()),"reference unexpectedly has living entities");
            var full=builder(actual,registry,baker,colors,atlas).build();var byOwner=owners(full);check(byOwner.size()==32,"not all 32 statues rendered: "+byOwner.size());check(full.quadCount==1824&&full.cutout.size()==1824,"missing or duplicate statue geometry: "+full.quadCount);
            Set<String> poses=new HashSet<>(),ids=new HashSet<>();var inspector=new BlockInspector(actual,resources);var picker=new ScenePicker(full,atlas);int[] seen={0};
            for(var region:actual.regions().values())region.blocks.forEachNonAir((x,y,z,state)->{
                seen[0]++;String pose=state.getValue("copper_golem_pose");poses.add(pose);ids.add(state.blockId);
                check("west".equals(state.getValue("facing")),"stored facing lost");check(pose!=null,"stored pose lost");
                var p=region.blockOrigin();int wx=p.x()+x,wy=p.y()+y,wz=p.z()+z;var quads=byOwner.get(wx+","+wy+","+wz);
                check(quads!=null&&quads.size()==(pose.equals("sitting")?66:54),"wrong pose geometry "+state);
                String suffix=state.blockId.contains("exposed_")?"_exposed":state.blockId.contains("weathered_")?"_weathered":state.blockId.contains("oxidized_")?"_oxidized":"";
                for(var q:quads){check(q.entityIndex==-1,"statue incorrectly marked as living entity");check(q.spriteId.equals("minecraft:entity/copper_golem/copper_golem"+suffix),"oxidation texture mismatch");check(q.image!=null,"unresolved statue texture");for(float uv:new float[]{q.u0,q.v0,q.u1,q.v1,q.u2,q.v2,q.u3,q.v3})check(uv>=0&&uv<=1,"invalid statue UV");}
                var hit=picker.pick(new double[]{wx+.5,wy+3,wz+.5},new double[]{0,-1,0},5);check(hit!=null,"statue picking missed");var info=inspector.inspect(hit);check(Boolean.TRUE.equals(info.get("hit"))&&state.blockId.equals(info.get("id")),"statue picker did not resolve owning block");
            });
            check(seen[0]==32&&poses.size()==4&&ids.size()==8,"reference variants missing");
            var settings=new RenderSettings(135,30,1000,800);settings.transparentBackground=false;settings.backgroundMode="checker";
            SchematicRenderer.render(null,full,colors,atlas,settings).writePng(out.resolve("32-statues.png"));MeshExporter.write(full,atlas,out.resolve("mesh.bin"));atlas.stitched.writePng(out.resolve("atlas.png"));
            Map<String,List<SceneMesh.MeshQuad>> north=null;
            for(String facing:List.of("north","east","south","west")){
                var tag=NbtFileUtils.readCompressed(input);for(var region:tag.getCompound("Regions").orElseThrow().getKeys()){
                    var r=tag.getCompound("Regions").orElseThrow().getCompound(region).orElseThrow();r.getCompound("Position").orElseThrow().putInt("x",-17).putInt("z",-17);
                    for(var entry:r.getList("BlockStatePalette").orElseThrow().getCompounds())entry.getCompound("Properties").ifPresent(p->p.putString("facing",facing));
                }
                Path file=out.resolve(facing+".litematic");NbtFileUtils.writeCompressed(file,tag);var schematic=LitematicaSchematic.load(file);schematic.materialize(registry);
                var mesh=builder(schematic,registry,baker,colors,atlas).build();var grouped=owners(mesh);check(grouped.size()==32&&mesh.quadCount==1824,"facing/negative position lost models");
                if(north==null)north=grouped;double angle=switch(facing){case "east"->-Math.PI/2;case "south"->Math.PI;case "west"->Math.PI/2;default->0;};
                for(var entry:grouped.entrySet())for(int i=0;i<entry.getValue().size();i++){
                    var q=entry.getValue().get(i);var a=vertices(north.get(entry.getKey()).get(i));var b=vertices(q);
                    for(int j=0;j<4;j++){double dx=a[j][0]-q.blockX-.5,dz=a[j][2]-q.blockZ-.5;check(Math.abs(b[j][0]-(q.blockX+.5+Math.cos(angle)*dx+Math.sin(angle)*dz))<.00001&&Math.abs(b[j][2]-(q.blockZ+.5-Math.sin(angle)*dx+Math.cos(angle)*dz))<.00001,"facing rotation mismatch");}
                }
                var stream=builder(schematic,registry,baker,colors,atlas);stream.prepareStreaming();var stitched=atlas.stitched;Set<String> expected=LodRegression.geometry(mesh),combined=new HashSet<>();
                for(var section:stream.sections())combined.addAll(LodRegression.geometry(stream.buildSection(section)));
                check(stitched==atlas.stitched,"statue texture changed atlas after freeze");check(expected.equals(combined),"statue crossing sections lost/duplicated geometry");
                var index=StreamMeshExporter.write(stream,atlas,out.resolve(facing+".lmesh"));check(index.quads()==1824,"streamed statue face count differs");
            }
            check(RenderDiagnostics.snapshot().isEmpty(),"missing resources: "+RenderDiagnostics.snapshot());
            System.out.println("PASS: supplied 32 statues, 8 finishes x 4 poses x 4 facings, negative section boundaries, stable atlas, CPU PNG, picking and streamed geometry");
        }
    }
}
