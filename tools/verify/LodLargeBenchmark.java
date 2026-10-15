import io.github.litematicrender.serve.*;
import java.nio.file.*;
import java.util.*;

public final class LodLargeBenchmark {
    static void write(Path file,Map<String,Object> values)throws Exception {
        var method=JsonRpcServer.class.getDeclaredMethod("toJson",Object.class);method.setAccessible(true);Files.writeString(file,(String)method.invoke(null,values));
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),schematic=Path.of(args[1]).toAbsolutePath(),out=Path.of(args[2]).toAbsolutePath();Files.createDirectories(out);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        var report=new LinkedHashMap<String,Object>();report.put("pid",ProcessHandle.current().pid());report.put("phase","resources");write(out.resolve("progress.json"),report);
        long begin=System.currentTimeMillis();
        try(var engine=new RenderEngine(root)) {
            engine.loadResources(engine.defaultPackRoot());report.put("phase","opening");write(out.resolve("progress.json"),report);engine.open(schematic);
            report.put("phase","stream export");write(out.resolve("progress.json"),report);
            var result=engine.exportStreamScene(out.resolve("atlas.png"),out.resolve("scene.lmesh"),true,true);report.putAll(result);
            var field=RenderEngine.class.getDeclaredField("scene");field.setAccessible(true);if(field.get(engine)!=null)throw new AssertionError("Full mesh retained");
            System.gc();Thread.sleep(250);
            report.put("heapUsedAfterGc",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());report.put("heapLimit",Runtime.getRuntime().maxMemory());
            report.put("elapsedMillis",System.currentTimeMillis()-begin);report.put("phase","complete");report.put("pass",true);write(out.resolve("result.json"),report);write(out.resolve("progress.json"),report);
            System.out.println("PASS: large LMESH7 export; faces="+report.get("quads")+", sections="+report.get("sections")+", heap="+report.get("heapUsedAfterGc")+" / "+report.get("heapLimit"));
        }catch(Throwable error){report.put("pass",false);report.put("error",error.toString());write(out.resolve("result.json"),report);throw error;}
    }
}
