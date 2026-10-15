import io.github.litematicrender.serve.*;
import java.nio.file.*;
import java.util.*;

/** Real cold/warm timings; the first overview timestamp means file-ready, not a UI frame. */
public final class ProgressiveCacheBenchmark {
    static void write(Path file,Map<String,Object> values)throws Exception{var method=JsonRpcServer.class.getDeclaredMethod("toJson",Object.class);method.setAccessible(true);Files.writeString(file,(String)method.invoke(null,values));}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),out=root.resolve("tools/verify/cache-large-output");Files.createDirectories(out);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");System.setProperty("litematicrender.cache.directory",out.resolve("scenes").toString());
        var report=new LinkedHashMap<String,Object>();report.put("pid",ProcessHandle.current().pid());
        try(var engine=new RenderEngine(root)) {
            long start=System.nanoTime();engine.loadResources(engine.defaultPackRoot());report.put("resourceMillis",(System.nanoTime()-start)/1e6);
            start=System.nanoTime();report.put("schematic",engine.open(Path.of(args[1])));report.put("readMillis",(System.nanoTime()-start)/1e6);write(out.resolve("progress.json"),report);
            start=System.nanoTime();String id=(String)engine.beginPreview(true,true,true).get("id");boolean overview=false;
            while(true){var status=engine.previewStatus(id);if(Boolean.TRUE.equals(status.get("hasOverview"))&&!overview){report.put("overviewReadyMillis",(System.nanoTime()-start)/1e6);report.put("overview",engine.previewResult(id,true));overview=true;write(out.resolve("progress.json"),report);}if(status.get("stage").equals("failed"))throw new Exception(status.get("error").toString());if(Boolean.TRUE.equals(status.get("hasFull")))break;Thread.sleep(40);}
            report.put("fullReadyMillis",(System.nanoTime()-start)/1e6);report.put("cold",engine.previewResult(id,false));write(out.resolve("progress.json"),report);
            start=System.nanoTime();id=(String)engine.beginPreview(false,true,true).get("id");while(!Boolean.TRUE.equals(engine.previewStatus(id).get("hasFull"))){if(engine.previewStatus(id).get("stage").equals("failed"))throw new Exception(engine.previewStatus(id).toString());Thread.sleep(20);}
            report.put("warmReadyMillis",(System.nanoTime()-start)/1e6);var warm=engine.previewResult(id,false);report.put("warm",warm);if(!Boolean.TRUE.equals(warm.get("diskCacheHit"))||!warm.get("meshBuildCount").equals(0))throw new AssertionError("warm rebuilt geometry");
            var scene=RenderEngine.class.getDeclaredField("scene");scene.setAccessible(true);if(scene.get(engine)!=null)throw new AssertionError("full graph retained");
            System.gc();Thread.sleep(250);report.put("retainedHeapBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());report.put("heapLimit",Runtime.getRuntime().maxMemory());report.put("cache",engine.sceneCacheStats());report.put("pass",true);write(out.resolve("result.json"),report);System.out.println("PASS "+report.get("overviewReadyMillis")+" ms overview / "+report.get("fullReadyMillis")+" ms full / "+report.get("warmReadyMillis")+" ms warm");
        }catch(Throwable e){report.put("pass",false);report.put("error",e.toString());write(out.resolve("result.json"),report);throw e;}
    }
}
