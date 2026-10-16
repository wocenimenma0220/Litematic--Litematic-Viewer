import io.github.litematicrender.serve.*;
import java.nio.file.*;
import java.util.*;

/** Cold preparation stages and warm cache reuse, without conflating file-ready with a UI frame. */
public final class LargeOpenBenchmark {
    static void save(Path out, Map<String,Object> report) throws Exception {
        var method=JsonRpcServer.class.getDeclaredMethod("toJson",Object.class);method.setAccessible(true);
        Files.writeString(out.resolve("result.json"),(String)method.invoke(null,report));
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),out=Path.of(args[2]).toAbsolutePath();Files.createDirectories(out);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        System.setProperty("litematicrender.cache.directory",out.resolve("scenes").toString());
        var report=new LinkedHashMap<String,Object>();report.put("file",args[1]);report.put("heapLimit",Runtime.getRuntime().maxMemory());
        try(var engine=new RenderEngine(root)) {
            long start=System.nanoTime();report.put("resources",engine.loadResources(engine.defaultPackRoot()));
            report.put("resourceMillis",(System.nanoTime()-start)/1e6);System.out.println("resources "+report.get("resourceMillis"));
            start=System.nanoTime();report.put("schematic",engine.open(Path.of(args[1])));report.put("readMillis",(System.nanoTime()-start)/1e6);
            System.out.println("read "+report.get("readMillis"));save(out,report);
            start=System.nanoTime();String id=(String)engine.beginPreview(true,true,true).get("id"),previous="";
            var stages=new LinkedHashMap<String,Object>();report.put("stages",stages);
            while(true) {
                var status=engine.previewStatus(id);String stage=status.get("stage").toString();double elapsed=(System.nanoTime()-start)/1e6;
                if(!stage.equals(previous)){stages.putIfAbsent(stage,elapsed);previous=stage;System.out.println(stage+" "+elapsed);save(out,report);}
                if(Boolean.TRUE.equals(status.get("hasOverview"))&&!report.containsKey("overviewMillis"))report.put("overviewMillis",elapsed);
                if(((Number)status.get("published")).intValue()>0&&!report.containsKey("firstSectionMillis"))report.put("firstSectionMillis",elapsed);
                if(stage.equals("failed"))throw new Exception(status.get("error").toString());
                if(Boolean.TRUE.equals(status.get("hasFull")))break;Thread.sleep(10);
            }
            report.put("fullMillis",(System.nanoTime()-start)/1e6);report.put("cold",engine.previewResult(id,false));save(out,report);
            start=System.nanoTime();id=(String)engine.beginPreview(false,true,true).get("id");
            while(!Boolean.TRUE.equals(engine.previewStatus(id).get("hasFull"))){var s=engine.previewStatus(id);if(s.get("stage").equals("failed"))throw new Exception(s.toString());Thread.sleep(10);}
            report.put("warmMillis",(System.nanoTime()-start)/1e6);report.put("warm",engine.previewResult(id,false));
            if(!Boolean.TRUE.equals(((Map<?,?>)report.get("warm")).get("diskCacheHit")))throw new AssertionError("warm cache miss");
            report.put("pass",true);save(out,report);System.out.println("PASS full="+report.get("fullMillis")+" warm="+report.get("warmMillis"));
        } catch(Throwable e){report.put("error",e.toString());save(out,report);throw e;}
    }
}
