import io.github.litematicrender.model.*;
import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.world.*;
import java.nio.file.*;
import java.util.*;
public class CompactLightRegression {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();System.setProperty("litematicrender.home",root.toString());
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)) {
            var registry=BlockRegistry.load(resources);var baker=new ModelBaker(resources,registry);var atlas=SpriteAtlas.load(resources,"blocks");var shapes=new BlockShape(baker,atlas);
            int sx=35,sy=34,sz=33;var cells=new BlockState[sx*sy*sz];Arrays.fill(cells,BlockState.air());var random=new Random(8932);
            String[] names={"stone","water","oak_leaves","torch","glass"};
            for(int i=0;i<6000;i++)cells[random.nextInt(cells.length)]=registry.getOrMissing("minecraft:"+names[random.nextInt(names.length)]).defaultState;
            for(int z=3;z<30;z++)for(int x=3;x<32;x++)cells[(25*sz+z)*sx+x]=registry.getOrMissing("minecraft:stone").defaultState;
            LightVolume.Cells source=(x,y,z)->x<0||y<0||z<0||x>=sx||y>=sy||z>=sz?BlockState.air():cells[(y*sz+z)*sx+x];
            for(boolean real:new boolean[]{false,true}) {
                var dense=new LightVolume(sx,sy,sz,cells,shapes,real);var compact=new LightVolume(sx,sy,sz,source,shapes,real);
                var sparse=new SparseLightVolume(sx,sy,sz,shapes,real);var streamed=new SparseLightVolume(sx,sy,sz,shapes,real,source);
                for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++){var state=source.at(x,y,z);if(!state.isAir()){sparse.put(x,y,z,state);streamed.put(x,y,z,state);}}
                dense.compute();compact.compute();sparse.compute();streamed.compute();
                for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++) {
                    int expected=dense.cellLight(x,y,z);if(compact.cellLight(x,y,z)!=expected||sparse.cellLight(x,y,z)!=expected||streamed.cellLight(x,y,z)!=expected)throw new AssertionError("Light mismatch at "+x+","+y+","+z+" real="+real);
                }
            }
            System.out.println("PASS: dense, compact and sparse lighting match at all 78,540 cells across partial section boundaries");
        }
    }
}
