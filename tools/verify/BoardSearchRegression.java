import io.github.litematicrender.schematic.*;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import io.github.litematicrender.serve.RenderEngine;
import java.nio.file.*;
import java.util.*;

public class BoardSearchRegression {
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  public static void main(String[] args)throws Exception{
    Path home=Path.of(args[0]),out=home.resolve("tools/verify/board-search-output");Files.createDirectories(out);
    var cells=new ArrayList<Object>();
    // 32 distinct states force values across packed-long boundaries.
    for(int i=0;i<32;i++)cells.add(Map.of("x",i-16,"y",-2,"z",-3,"id","note_block","states",Map.of("note",String.valueOf(i%25),"powered",String.valueOf(i>=25))));
    cells.add(Map.of("x",-16,"y",-1,"z",-3,"id","oak_stairs","states",Map.of("facing","east","half","top","shape","inner_left","waterlogged","true")));
    Path round=out.resolve("roundtrip.litematic");BoardSchematic.write(round,cells);
    try(var engine=new RenderEngine(home)){
      engine.loadResources(engine.defaultPackRoot());var metadata=engine.open(round);check(((Number)metadata.get("totalBlocks")).intValue()==33,"block count");
      var stairs=engine.searchBlocks("橡木楼梯",0);check(((Number)stairs.get("total")).intValue()==1,"Chinese name search");
      var hits=(List<Map<String,Object>>)stairs.get("results");check(hits.getFirst().get("position").equals(List.of(-16,-1,-3)),"signed position");check(hits.getFirst().get("state").toString().contains("waterlogged=true"),"state preservation");
      check(((Number)engine.searchBlocks("minecraft:note_block",0).get("total")).intValue()==32,"ID count");

      // Read raw NBT to verify every packed value, including words split at 64 bits.
      var root=NbtFileUtils.readCompressed(round);var region=root.getCompound("Regions").orElseThrow().getCompound("设计").orElseThrow();
      var palette=region.getList("BlockStatePalette").orElseThrow();var packed=region.getLongArray("BlockStates").orElseThrow().value;int bits=Math.max(2,32-Integer.numberOfLeadingZeros(palette.size()-1));
      for(int i=0;i<32;i++){var state=(NbtCompound)palette.get(LitematicaSchematic.getAt(packed,bits,i));check(state.getCompound("Properties").orElseThrow().getStringOrDefault("note","").equals(String.valueOf(i%25)),"packed state "+i);}
      var many=new ArrayList<Object>();for(int i=0;i<10005;i++)many.add(Map.of("x",i%101-50,"y",i/101-50,"z",-7,"id","stone"));
      Path large=out.resolve("many.litematic");BoardSchematic.write(large,many);engine.open(large);
      var first=engine.searchBlocks("stone",0);var second=engine.searchBlocks("stone",10000);check(((Number)first.get("total")).intValue()==10005,"total beyond page");check(((List<?>)first.get("results")).size()==10000,"bounded page");check(((List<?>)second.get("results")).size()==5,"last page");
      check(((Number)engine.searchBlocks("不存在的方块",0).get("total")).intValue()==0,"empty results");
      // Negative region sizes use blockOrigin rather than the raw Position.
      var raw=NbtFileUtils.readCompressed(large);var reg=raw.getCompound("Regions").orElseThrow().getCompound("设计").orElseThrow();reg.put("Size",new NbtCompound().putInt("x",-101).putInt("y",100).putInt("z",1));Path negative=out.resolve("negative.litematic");NbtFileUtils.writeCompressed(negative,raw);engine.open(negative);var negativeHits=(List<Map<String,Object>>)engine.searchBlocks("stone",0).get("results");check(((List<?>)negativeHits.getFirst().get("position")).getFirst().equals(-150),"negative-size origin");
      BoardSchematic.write(round,List.of());check(((Number)engine.open(round).get("totalBlocks")).intValue()==0,"empty export");
      boolean rejected=false;try{BoardSchematic.write(round,List.of(Map.of("x",193,"y",0,"z",0,"id","stone")));}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"invalid bounds rejected");check(((Number)engine.open(round).get("totalBlocks")).intValue()==0,"failed export preserved target");
    }
    System.out.println("PASS: export roundtrip, all states, packed words, signed coordinates, Chinese / ID search, bounded pagination, negative regions, empty / invalid export");
  }
}
