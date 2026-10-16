package io.github.litematicrender.schematic;
import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.serve.RenderEngine;
import java.nio.file.*;
import java.util.*;

/** Pixel opacity, bounds propagation, fast voxel selection and export preparation reuse. */
public final class SelectionRenderingRegression {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),out=root.resolve("tools/verify/selection-output");Files.createDirectories(out);
        var atlas=SpriteAtlas.overview();var mesh=new SceneMesh();
        for(int x:new int[]{0,3})mesh.solid.add(new SceneMesh.MeshQuad(new float[]{x,x+1,x+1,x},new float[]{0,0,1,1},new float[]{1,1,1,1},0,0,1,0,1,1,0,1,"litematicrender:overview",0xffffff,1,1,1,1,null,SceneMesh.Layer.SOLID));
        var settings=new RenderSettings(0,0,128,64);settings.selectionMin=new double[]{0,0,0};settings.selectionMax=new double[]{1,1,1};
        var image=SchematicRenderer.render(null,mesh,null,atlas,settings);int opaque=0,ghost=0;
        for(int pixel:image.pixels()){int alpha=pixel>>>24;if(alpha==255)opaque++;if(alpha==26)ghost++;}
        check(opaque>20&&ghost>20,"selected and outside opacity not 100% / 10%: "+opaque+" / "+ghost);image.writePng(out.resolve("opacity.png"));
        settings.interactiveMaxEdge=64;var preview=SchematicRenderer.render(null,mesh,null,atlas,settings);check(Arrays.stream(preview.pixels()).anyMatch(p->p>>>24==26),"interactive settings dropped selection");
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        try(var engine=new RenderEngine(root)){
            Path fixture=root.resolve("tools/verify/feature-output/water-trapdoors.litematic");engine.loadResources(root.resolve("assets/vanilla"));engine.open(fixture);
            var hit=engine.selectBlock(new double[]{4,4,8},new double[]{0,-1,0},64);check(Boolean.TRUE.equals(hit.get("hit")),"voxel selection missed saved blocks");
            check(Boolean.FALSE.equals(engine.selectBlock(new double[]{-10,4,-10},new double[]{0,-1,0},64).get("hit")),"empty ray selected a block");
            engine.exportStreamScene(out.resolve("atlas.png"),out.resolve("mesh.lmesh"),true,true,false);
            var builderField=RenderEngine.class.getDeclaredField("streamBuilder");builderField.setAccessible(true);var prepared=builderField.get(engine);
            var volume=SceneBuilder.class.getDeclaredField("light");volume.setAccessible(true);check(volume.get(prepared)==null,"disabled light allocated during stream");
            var export=new RenderSettings(45,30,96,96);export.realLighting=true;export.lightingEnabled=false;
            engine.renderAnimation(out.resolve("flat.gif"),export,3,2);
            var lightingField=RenderEngine.class.getDeclaredField("sceneLightingEnabled");lightingField.setAccessible(true);check(!lightingField.getBoolean(engine),"GIF reenabled lighting");
            var rendered=((SceneBuilder)prepared).build();
            for(var q:rendered.solid)check(q.shade==1&&q.ao==null&&q.vertexColors==null,"GIF reenabled lighting");
            check(builderField.get(engine)==prepared,"export discarded the already prepared stream builder");
            check(volume.get(prepared)==null,"export recalculated lighting despite disabled toggle");
        }
        System.out.println("PASS: exact outside alpha 26/255, opaque focus, interactive bounds, voxel selection, prepared stream reuse, GIF lighting-off geometry");
    }
}
