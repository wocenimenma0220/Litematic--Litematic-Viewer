import io.github.litematicrender.render.StreamMeshIndex;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Completed legacy catalogs and adversarial catalog boundaries must survive buffered reading. */
public final class MeshIndexRegression {
    static byte[] fixture(char version){
        var data=ByteBuffer.allocate(64+574);data.put(new byte[]{'L','M','E','S','H',(byte)version,0,0});
        data.putInt(1).putInt(1).putLong(64).putInt(574).putInt(4).putLong(0);
        for(int i=0;i<6;i++)data.putFloat(0);data.putInt(1); // one empty ownership section
        return data.array();
    }
    static void invalid(Path file,byte[] bytes)throws Exception{
        Files.write(file,bytes);try{StreamMeshIndex.read(file,Map.of(),Map.of());throw new AssertionError("malformed catalog accepted: "+file);}catch(java.io.IOException expected){}
    }
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        for(char version:new char[]{'5','6','7'}){
            byte[] good=fixture(version);Path file=out.resolve("legacy-"+version+".lmesh");Files.write(file,good);
            var index=StreamMeshIndex.read(file,Map.of(),Map.of());if(index.sections().size()!=1||index.quads()!=0)throw new AssertionError("legacy catalog changed");
            invalid(out.resolve("truncated.lmesh"),Arrays.copyOf(good,good.length-1));
            invalid(out.resolve("trailing.lmesh"),Arrays.copyOf(good,good.length+1));
            var wrong=good.clone();ByteBuffer.wrap(wrong).putInt(64,2);invalid(out.resolve("wrong-count.lmesh"),wrong);
            wrong=good.clone();ByteBuffer.wrap(wrong).putLong(16,0);invalid(out.resolve("bad-offset.lmesh"),wrong);
            wrong=good.clone();ByteBuffer.wrap(wrong).putInt(622,Integer.MAX_VALUE);invalid(out.resolve("huge-pages.lmesh"),wrong);
            wrong=good.clone();ByteBuffer.wrap(wrong).putInt(12,0).putInt(64,0);invalid(out.resolve("unread-tail.lmesh"),wrong);
            wrong=good.clone();ByteBuffer.wrap(wrong).putFloat(40,Float.NaN);invalid(out.resolve("nan-bounds.lmesh"),wrong);
        }
        System.out.println("PASS: LMESH5/6/7 compatibility; truncated/trailing catalogs, counts, offsets, page lengths, unread tails and non-finite bounds rejected");
    }
}
