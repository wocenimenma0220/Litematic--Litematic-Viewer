package io.github.litematicrender.schematic;

import io.github.litematicrender.model.*;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.serve.*;
import io.github.litematicrender.world.*;
import java.nio.file.*;
import java.util.*;

/** Real palette states, partial geometry across LODs, and independent lighting/cache controls. */
public final class RenderingControlsRegression {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static List<SceneMesh.MeshQuad> faces(SceneMesh m){return VisualFixRegression.quads(m);}
    static Set<String> sensitive(SceneMesh m) {
        var result=new HashSet<String>();
        for(var q:faces(m))if(q.lodProtected||q.spriteId.contains("block/water"))
            result.add(q.spriteId+":"+q.x0+","+q.y0+","+q.z0+":"+q.x1+","+q.y1+","+q.z1+":"+q.x2+","+q.y2+","+q.z2+":"+q.x3+","+q.y3+","+q.z3);
        return result;
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),out=root.resolve("tools/verify/feature-output");Files.createDirectories(out);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)) {
            var registry=BlockRegistry.load(resources);var baker=new ModelBaker(resources,registry);var atlas=SpriteAtlas.load(resources,"blocks");var colors=new Colormaps();colors.load(resources);
            int tested=0;
            for(String[] resource:resources.listResources("blockstates",".json")) {
                String id=resource[0]+":"+resource[1].substring(12,resource[1].length()-5);
                boolean wall=id.endsWith("_wall"),fence=id.endsWith("_fence"),pane=id.endsWith("_pane")||id.equals("minecraft:iron_bars");
                if(!wall&&!fence&&!pane)continue;
                var info=registry.get(id);var properties=new HashMap<String,String>();
                for(String side:List.of("north","east","south","west"))properties.put(side,wall?"none":"false");
                properties.put("up","true");var state=info.state(properties);
                for(String side:List.of("north","east","south","west"))check(state.getValue(side).equals(wall?"none":"false"),id+" lost disconnected "+side);
                if(!pane)check(baker.selectVariants(id,state).size()==1,id+" rendered disconnected arms");
                for(String side:List.of("north","east","south","west")) {
                    var connected=state.tryWith(side,wall?"low":"true");if(!pane)check(baker.selectVariants(id,connected).size()==2,id+" missing single connected arm");
                }
                tested++;
            }
            int sx=32,sy=6,sz=16;int[] cells=new int[sx*sy*sz];
            var palette=new ArrayList<NbtCompound>(List.of(VisualFixRegression.state("air"),VisualFixRegression.state("stone"),VisualFixRegression.state("water","level","0"),
                VisualFixRegression.state("oak_trapdoor","facing","north","half","top","open","false","waterlogged","true"),
                VisualFixRegression.state("oak_trapdoor","facing","west","half","bottom","open","true","waterlogged","true"),
                VisualFixRegression.state("cobblestone_wall","up","true","north","none","east","low","south","none","west","none"),
                VisualFixRegression.state("oak_fence","north","false","east","true","south","false","west","false"),VisualFixRegression.state("player_head","rotation","0"),VisualFixRegression.state("chest","facing","north","type","single"),VisualFixRegression.state("white_banner","rotation","0")));
            for(int z=0;z<sz;z++)for(int x=0;x<sx;x++)cells[z*sx+x]=1;
            for(int x=2;x<30;x++){cells[(sz+7)*sx+x]=2;cells[(sz+6)*sx+x]=x%2==0?3:4;}
            cells[(sz+10)*sx+15]=5;cells[(sz+10)*sx+16]=6;
            cells[(sz+12)*sx+5]=7;cells[(sz+12)*sx+9]=8;cells[(sz+12)*sx+13]=9;
            Path fixture=VisualFixRegression.write(out.resolve("water-trapdoors.litematic"),sx,sy,sz,palette,cells);
            var schematic=LitematicaSchematic.load(fixture);schematic.materialize(registry);
            var builder=LodRegression.builder(schematic,registry,baker,colors,atlas,true);builder.prepareStreaming();int sections=0;
            for(var section:builder.sections()) {
                var full=builder.buildSection(section);var expected=sensitive(full);check(!expected.isEmpty(),"fixture has no sensitive faces");
                for(int step:new int[]{4,8})check(sensitive(LodMesh.simplify(full,section,step)).equals(expected),"LOD expanded water/thin boundaries at step "+step);
                sections++;
            }
            var flatBuilder=LodRegression.builder(schematic,registry,baker,colors,atlas,true).lightingEnabled(false);var flat=flatBuilder.build();
            var light=SceneBuilder.class.getDeclaredField("light");light.setAccessible(true);check(light.get(flatBuilder)==null,"disabled lighting allocated volume");
            for(var q:faces(flat))check(q.shade==1&&q.ao==null&&q.vertexColors==null,"disabled lighting retained shading: "+q.spriteId+" shade="+q.shade+" ao="+(q.ao!=null)+" colors="+(q.vertexColors!=null));
            MeshExporter.write(flat,atlas,out.resolve("flat.bin"));
            var full=LodRegression.builder(schematic,registry,baker,colors,atlas,true).build();MeshExporter.write(full,atlas,out.resolve("water-trapdoors.bin"));
            MeshExporter.writeStreaming(LodRegression.builder(schematic,registry,baker,colors,atlas,true),atlas,out.resolve("water-trapdoors.lmesh"));atlas.stitched.writePng(out.resolve("atlas.png"));
            for(int angle:new int[]{45,135,225,315})SchematicRenderer.render(null,full,colors,atlas,new RenderSettings(angle,25,960,540)).writePng(out.resolve("water-"+angle+".png"));
            try(var engine=new RenderEngine(root)) {
                engine.open(fixture);engine.exportScene(out.resolve("cache-atlas.png"),out.resolve("cache.bin"),false,true,false);
                check((boolean)engine.exportScene(out.resolve("cache-atlas.png"),out.resolve("cache.bin"),false,true,false).get("sceneCached"),"flat cache miss");
                check(!(boolean)engine.exportScene(out.resolve("cache-atlas.png"),out.resolve("cache.bin"),false,true,true).get("sceneCached"),"lighting toggle reused stale scene");
                engine.exportStreamScene(out.resolve("cache-atlas.png"),out.resolve("cache.lmesh"),false,true,false);
                check(!(boolean)engine.exportStreamScene(out.resolve("cache-atlas.png"),out.resolve("cache.lmesh"),false,true,true).get("sceneCached"),"stream lighting toggle reused stale scene");
            }
            System.out.println("PASS: "+tested+" walls/fences/panes, water/trapdoor geometry in "+sections+" sections and all LODs, lighting off allocation/shading, full and stream cache invalidation, four rendered angles");
        }
    }
}
