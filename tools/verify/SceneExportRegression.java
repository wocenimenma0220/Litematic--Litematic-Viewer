import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.serve.*;
import java.nio.file.*;
import java.util.*;

public class SceneExportRegression {
    static Object field(Object object,String name)throws Exception {var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),file=Path.of(args[1]),output=Path.of(args[2]);Files.createDirectories(output);
        System.setProperty("litematicrender.skins.offline","true");System.setProperty("litematicrender.home",root.toString());
        try(var engine=new RenderEngine(root)) {
            engine.loadResources(root.resolve("assets/vanilla"));engine.open(file);
            var exported=engine.exportScene(output.resolve("reference-atlas.png"),output.resolve("reference-mesh.bin"));
            SceneMesh before=(SceneMesh)field(engine,"scene");
            var atlas=(SpriteAtlas)field(engine,"atlas");var colors=(Colormaps)field(engine,"colormaps");
            var snapshot=new HashMap<String,Image>();
            var pixels=(Map<String,int[]>)field(atlas,"generatedPixels");var sizes=(Map<String,Long>)field(atlas,"generatedSizes");
            pixels.forEach((name,values)->{long dimensions=sizes.get(name);var image=new Image((int)(dimensions>>>32),(int)dimensions);System.arraycopy(values,0,image.pixels(),0,values.length);snapshot.put(name,image);});
            var precisionSettings=new RenderSettings(135,25,900,900);
            var unshifted=SchematicRenderer.render(null,before,colors,atlas,precisionSettings).pixels().clone();
            var extra=new HashMap<String,Image>(snapshot);extra.put("litematicrender:entity/unused-layout-probe",new Image(256,67));
            atlas.replaceGeneratedSprites(extra);
            var shifted=SchematicRenderer.render(null,before,colors,atlas,precisionSettings).pixels();
            int layoutDifferences=0;for(int i=0;i<shifted.length;i++)if(shifted[i]!=unshifted[i])layoutDifferences++;
            System.out.println("atlas layout changed pixels: "+layoutDifferences);
            check(layoutDifferences==0,"unrelated atlas layout changed CPU pixels");
            atlas.replaceGeneratedSprites(snapshot);
            var settings=new RenderSettings(135,30,256,256);settings.backgroundMode="checker";
            var result=engine.renderAnimation(output.resolve("reference.gif"),settings,3,5);
            check(field(engine,"scene")==before,"GIF rebuilt the cached desktop scene");
            int count=((Number)result.get("frames")).intValue();var frames=new ArrayList<Image>();
            for(int i=0;i<count;i++){settings.animationTick=i;frames.add(SchematicRenderer.render(null,before,colors,atlas,settings));}
            GifWriter.write(output.resolve("reference-expected.gif"),frames,5);
            GifWriter.writeFrames(output.resolve("reference-replay.gif"),frames.size(),5,tick->frames.get(tick));
            check(Files.mismatch(output.resolve("reference-replay.gif"),output.resolve("reference-expected.gif"))==-1,"spooling changed complex image encoding");
            check(Files.mismatch(output.resolve("reference.gif"),output.resolve("reference-expected.gif"))==-1,"desktop GIF pixels or encoding changed");
            long bytes=Files.size(output.resolve("reference-mesh.bin"));
            try(var input=new java.io.DataInputStream(Files.newInputStream(output.resolve("reference-mesh.bin")))) {
                check(new String(input.readNBytes(6),java.nio.charset.StandardCharsets.US_ASCII).equals("LMESH4"),"section visibility mesh signature missing");
            }
            var report=new LinkedHashMap<String,Object>();report.put("pass",true);report.put("sceneReused",true);report.put("gifBytesEqual",true);report.put("atlasLayoutChangedPixels",layoutDifferences);report.put("meshBytes",bytes);report.put("bspFaces",before.translucentBsp==null?0:before.translucentBsp.faces.size());report.put("bspNodes",before.translucentBsp==null?0:before.translucentBsp.nodes.size());report.put("bspMaximumDepth",before.translucentBsp==null?0:before.translucentBsp.maximumDepth);report.put("bspFallbackLeaves",before.translucentBsp==null?0:before.translucentBsp.fallbackLeaves);report.put("gif",result);
            var json=JsonRpcServer.class.getDeclaredMethod("toJson",Object.class);json.setAccessible(true);
            String text=(String)json.invoke(null,report);Files.writeString(output.resolve("scene-export.json"),text);System.out.println(text);
        }
    }
}
