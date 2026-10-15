import io.github.litematicrender.schematic.LitematicaSchematic;
import java.nio.file.Path;
public class LodMetadata {
    public static void main(String[] args)throws Exception {
        var world=LitematicaSchematic.load(Path.of(args[0]));var m=world.metadata();
        System.out.println("bounds="+m.enclosingSize+" volume="+m.totalVolume+" nonAir="+m.totalBlocks);
        for(var region:world.regions().values())System.out.println(region.name+" origin="+region.blockOrigin()+" size="+region.size);
    }
}
