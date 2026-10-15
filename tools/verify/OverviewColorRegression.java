import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.resource.*;
import io.github.litematicrender.model.*;
import io.github.litematicrender.world.*;
import io.github.litematicrender.schematic.*;
import java.nio.file.*;
import java.util.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
public class OverviewColorRegression {
 public static void main(String[]args)throws Exception{Path root=Path.of(args[0]);System.setProperty("litematicrender.home",root.toString());try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)){
  var reg=BlockRegistry.load(resources);var baker=new ModelBaker(resources,reg);var atlas=SpriteAtlas.load(resources,"blocks");var s=LitematicaSchematic.load(Path.of(args[1]));s.materialize(reg);var counts=new HashMap<BlockState,Long>();for(var region:s.regions().values())region.blocks.forEachNonAir((x,y,z,state)->counts.merge(state,1L,Long::sum));var method=OverviewMesh.class.getDeclaredMethod("color",BlockState.class,ModelBaker.class,SpriteAtlas.class);method.setAccessible(true);
  for(var e:counts.entrySet().stream().sorted(Map.Entry.<BlockState,Long>comparingByValue().reversed()).limit(20).toList()){var variants=baker.selectVariants(e.getKey().blockId,e.getKey());var m=variants.isEmpty()?null:baker.bake(variants.getFirst());System.out.println(e.getKey()+" n="+e.getValue()+" rgb="+Integer.toHexString((int)method.invoke(null,e.getKey(),baker,atlas))+" particle="+(m==null?"none":m.particleSprite)+" quad="+(m==null||m.all.isEmpty()?"none":m.all.getFirst().spriteId));}
  int grass=(int)method.invoke(null,reg.getOrMissing("minecraft:grass_block").defaultState,baker,atlas);if(grass==0x7c007c||grass==0x999999)throw new AssertionError("unqualified particle texture used missing sprite");
  for(String id:List.of("minecraft:barrier","minecraft:structure_void","minecraft:light"))if((int)method.invoke(null,reg.getOrMissing(id).defaultState,baker,atlas)!=-1)throw new AssertionError("invisible block contributes overview occupancy");
  var fixture=Class.forName("io.github.litematicrender.schematic.LodRegression").getDeclaredMethod("fixture",Path.class,boolean.class);fixture.setAccessible(true);Path invisible=root.resolve("tools/verify/cache-output/overview-invisible.litematic");Files.createDirectories(invisible.getParent());fixture.invoke(null,invisible,true);var tag=NbtFileUtils.readCompressed(invisible);tag.getCompound("Regions").orElseThrow().getCompound("lod").orElseThrow().getList("BlockStatePalette").orElseThrow().getCompounds().get(1).putString("Name","minecraft:barrier");NbtFileUtils.writeCompressed(invisible,tag);var hidden=LitematicaSchematic.load(invisible);hidden.materialize(reg);if(!new OverviewMesh(hidden,baker,atlas).sections().isEmpty())throw new AssertionError("invisible-only fixture generated coarse walls");
  System.out.println("PASS: actual unqualified grass particle uses real texture; invisible blocks do not create coarse walls");
 }}
}
