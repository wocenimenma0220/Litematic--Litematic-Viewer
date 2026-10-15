package io.github.litematicrender.schematic;

import io.github.litematicrender.model.*;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.world.*;
import java.nio.file.*;
import java.util.*;

public final class StateModelRegression {
    static BlockRegistry registry;static ModelBaker baker;static Colormaps colors;static SpriteAtlas atlas;static Path output;
    record Cell(int x,int y,int z) {}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static BlockState state(String id){return registry.getOrMissing("minecraft:"+id).defaultState;}
    static NbtList numbers(double... values){var list=new NbtList();for(double v:values)list.add(new NbtScalars.NbtDouble(v));return list;}
    static NbtCompound cart(String kind,double x,double y,double z){return new NbtCompound().putString("id","minecraft:"+kind).put("Pos",numbers(x,y,z)).put("Rotation",numbers(37,0));}
    static NbtCompound xyz(int x,int y,int z){return new NbtCompound().putInt("x",x).putInt("y",y).putInt("z",z);}
    static SceneMesh fixture(String name,Map<Cell,BlockState> cells,List<NbtCompound> entities) throws Exception {
        int sx=cells.keySet().stream().mapToInt(Cell::x).max().orElse(0)+2,sy=cells.keySet().stream().mapToInt(Cell::y).max().orElse(0)+2,sz=cells.keySet().stream().mapToInt(Cell::z).max().orElse(0)+2;
        var palette=new ArrayList<BlockState>();palette.add(BlockState.air());for(var s:cells.values())if(!palette.contains(s))palette.add(s);
        var tags=new NbtList();for(var s:palette){var props=new NbtCompound();for(var p:s.properties)props.putString(p.name(),s.getValue(p.name()));tags.add(new NbtCompound().putString("Name",s.blockId).put("Properties",props));}
        int bits=Math.max(2,32-Integer.numberOfLeadingZeros(palette.size()-1));long[] storage=new long[(sx*sy*sz*bits+63)/64];
        for(var e:cells.entrySet()){var c=e.getKey();int index=(c.y*sz+c.z)*sx+c.x,word=index*bits/64,shift=index*bits%64;long value=palette.indexOf(e.getValue());storage[word]|=value<<shift;if(shift+bits>64)storage[word+1]|=value>>>(64-shift);}
        var list=new NbtList();entities.forEach(list::add);var size=xyz(sx,sy,sz);
        var region=new NbtCompound().put("Position",xyz(0,0,0)).put("Size",size).put("BlockStatePalette",tags).put("BlockStates",new NbtScalars.NbtLongArray(storage)).put("Entities",list);
        Path file=output.resolve(name+".litematic");NbtFileUtils.writeCompressed(file,new NbtCompound().putInt("Version",6).putInt("MinecraftDataVersion",0).put("Metadata",new NbtCompound().putString("Name",name).put("EnclosingSize",size)).put("Regions",new NbtCompound().put("states",region)));
        var schematic=LitematicaSchematic.load(file);schematic.materialize(registry);var builder=new SceneBuilder(registry,baker,colors,atlas);
        for(var r:schematic.regions().values())builder.addRegion(r,new int[]{0,0,0});return builder.build();
    }
    static List<SceneMesh.MeshQuad> all(SceneMesh mesh){var quads=new ArrayList<SceneMesh.MeshQuad>();for(var layer:List.of(mesh.solid,mesh.cutout,mesh.translucent))quads.addAll(layer);return quads;}
    static List<SceneMesh.MeshQuad> at(SceneMesh mesh,Cell cell){return all(mesh).stream().filter(q->q.entityIndex<0&&q.blockX==cell.x&&q.blockY==cell.y&&q.blockZ==cell.z).toList();}
    static void picture(SceneMesh mesh,String name)throws Exception{SchematicRenderer.render(null,mesh,colors,atlas,new RenderSettings(45,30,1600,1000)).writePng(output.resolve(name+".png"));}
    static void pistons()throws Exception {
        Map<Cell,BlockState> cells=new LinkedHashMap<>();int count=0;
        var faces=List.of("down","up","north","south","west","east");
        for(String facing:faces) {
            for(String id:List.of("piston","sticky_piston"))for(String extended:List.of("false","true"))cells.put(new Cell((count%12)*4+2,2,(count++/12)*4+2),state(id).with("facing",facing).with("extended",extended));
            for(String type:List.of("normal","sticky"))for(String shorter:List.of("false","true"))cells.put(new Cell((count%12)*4+2,2,(count++/12)*4+2),state("piston_head").with("facing",facing).with("type",type).with("short",shorter));
        }
        var mesh=fixture("pistons",cells,List.of());
        for(var e:cells.entrySet()) {
            var s=e.getValue();var baked=baker.bake(baker.selectVariant(s.blockId,s));var actual=at(mesh,e.getKey());
            check(actual.size()==baked.all.size(),"piston replaced by another renderer: "+s+" "+actual.size()+" vs "+baked.all.size());
            check(actual.stream().noneMatch(q->q.spriteId.contains("steve")),"piston emitted as a player head");
            if(s.blockId.endsWith("piston_head")||"false".equals(s.getValue("extended"))) {
                int face=faces.indexOf(s.getValue("facing"));
                String sprite=s.blockId.contains("sticky")||"sticky".equals(s.getValue("type"))?"piston_top_sticky":"piston_top";
                check(baked.all.stream().anyMatch(q->q.face==face&&q.spriteId.contains(sprite)),"piston front texture/facing mismatch: "+s);
            }
        }
        picture(mesh,"pistons");
        // A head's full plate may hide its neighbour; this must remain distinct from a skull.
        var shape=new BlockShape(baker,atlas);var stone=baker.bake(baker.selectVariant("minecraft:stone",state("stone"))).quadsForFace(Quad.NORTH).get(0);
        check(shape.faceOccluded(stone,Quad.NORTH,state("piston_head").with("facing","south")),"piston head plate lost its boundary coverage");
        System.out.println("PASS: 48 piston/base/head states, 6 facings, normal/sticky and short/long mapping");
    }
    static void heads()throws Exception {
        Map<Cell,BlockState> cells=new LinkedHashMap<>();var supports=new ArrayList<Cell>();int index=0;
        for(String id:List.of("player_head","skeleton_skull","wither_skeleton_skull","zombie_head","creeper_head","piglin_head","dragon_head")) {
            for(int rotation=0;rotation<16;rotation++) {
                int x=(index%14)*4+2,z=(index++/14)*4+2;var below=new Cell(x,0,z);supports.add(below);cells.put(below,state("stone"));cells.put(new Cell(x,1,z),state(id).with("rotation",Integer.toString(rotation)));
            }
            String wall=id.replace("_head","_wall_head").replace("_skull","_wall_skull");
            for(String facing:List.of("north","south","east","west")) {
                int x=(index%14)*4+2,z=(index++/14)*4+2;var below=new Cell(x,0,z);supports.add(below);cells.put(below,state("stone"));cells.put(new Cell(x,1,z),state(wall).with("facing",facing));
            }
        }
        var mesh=fixture("heads-on-stone",cells,List.of());var shapes=new BlockShape(baker,atlas);
        for(var support:supports)check(at(mesh,support).size()==6,"head removed supporting stone face at "+support);
        for(var s:cells.values())if(BlockRegistry.isHead(s.blockId))check(!shapes.occludes(s)&&!shapes.isShapeFullBlock(s),"skull classified as full block: "+s);
        picture(mesh,"heads-on-stone");
        // Resource-pack proxy geometry must not override the skull block entity's real shape.
        Path pack=output.resolve("head-proxy-pack"),definition=pack.resolve("assets/minecraft/blockstates/player_head.json");
        Files.createDirectories(definition.getParent());Files.writeString(definition,"{\"variants\":{\"\":{\"model\":\"minecraft:block/stone\"}}}");
        var resources=baker.resources();var imported=resources.importPack(pack);var oldRegistry=registry;var oldBaker=baker;
        try {
            registry=BlockRegistry.load(resources);baker=new ModelBaker(resources,registry);
            var proxy=fixture("head-proxy",Map.of(new Cell(0,0,0),state("stone"),new Cell(0,1,0),state("player_head")),List.of());
            check(at(proxy,new Cell(0,0,0)).size()==6,"resource-pack head proxy removed support face");
            check(at(proxy,new Cell(0,1,0)).size()==12,"resource-pack proxy replaced actual head geometry");
        } finally {resources.removePack(imported);registry=oldRegistry;baker=oldBaker;}
        System.out.println("PASS: 140 ground/wall skull states retain all 6 support faces");
    }
    static void minecarts()throws Exception {
        var shapes=List.of("north_south","east_west","ascending_east","ascending_west","ascending_north","ascending_south","south_east","south_west","north_west","north_east");
        var types=List.of("minecart","chest_minecart","furnace_minecart","hopper_minecart","tnt_minecart","command_block_minecart","spawner_minecart");
        var cells=new LinkedHashMap<Cell,BlockState>();var entities=new ArrayList<NbtCompound>();
        for(int row=0;row<types.size();row++)for(int column=0;column<shapes.size();column++) {
            int x=column*5+2,z=row*5+2;var rail=state("rail").with("shape",shapes.get(column));cells.put(new Cell(x,1,z),rail);cells.put(new Cell(x,0,z),state("stone"));entities.add(cart(types.get(row),x+.5,1.5625,z+.5));
        }
        var mesh=fixture("rail-minecarts",cells,entities);
        for(int i=0;i<entities.size();i++) {
            final int entityIndex=i;
            var shape=shapes.get(i%shapes.size());var quads=all(mesh).stream().filter(q->q.entityIndex==entityIndex).toList();
            check(!quads.isEmpty(),"minecart missing: "+i);
            boolean tilted=quads.stream().filter(q->q.spriteId.equals("minecraft:entity/minecart/minecart")).anyMatch(q->Math.abs(q.y0-q.y1)>.1&&Math.abs(q.x0-q.x1)+Math.abs(q.z0-q.z1)>.1);
            check(tilted==shape.startsWith("ascending"),"minecart tilt wrong for "+shape+" / "+types.get(i/shapes.size()));
        }
        var sample=new SchematicEntity("minecraft:minecart",new NbtCompound(),.5,.5,.5,37,12,false);
        var off=MinecartPose.resolve(sample,.5,.5,.5,(x,y,z)->BlockState.air());check(off.yaw()==37&&off.pitch()==12&&off.y()==.5,"off-rail saved pose lost");
        for(String id:List.of("rail","powered_rail","detector_rail","activator_rail"))for(String slope:shapes.subList(2,6)) {
            var rail=state(id).with("shape",slope);var pose=MinecartPose.resolve(sample,.5,.5625,.5,(x,y,z)->x==0&&y==0&&z==0?rail:BlockState.air());
            check(Math.abs(pose.pitch())>44&&Math.abs(pose.pitch())<46,"slope ignored: "+id+" "+slope);
            check(Math.abs(pose.y()-.5625)<1e-8,"slope position not snapped to rail");
            boolean positive=slope.equals("ascending_west")||slope.equals("ascending_north");check((pose.pitch()>0)==positive,"slope tilted downhill: "+slope);
        }
        // Sample across the joint: the front and rear wheels can occupy different track blocks.
        for(double x:new double[]{.85,1.0,1.15}) {
            var pose=MinecartPose.resolve(sample,x,x<1?.0625:x-1+.0625,.5,(bx,by,bz)->bz!=0?BlockState.air():bx==0&&by==0?state("rail").with("shape","east_west"):bx==1&&by==0?state("rail").with("shape","ascending_east"):BlockState.air());
            check(pose.pitch()<-10&&pose.pitch()>-46,"flat/slope joint failed: "+x+" "+pose);
        }
        picture(mesh,"rail-minecarts");MeshExporter.write(mesh,atlas,output.resolve("rail-minecarts.bin"));atlas.stitched.writePng(output.resolve("atlas.png"));
        System.out.println("PASS: 7 minecart types on 10 rail shapes, 4 rail types, all slopes and transition joints");
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();output=Path.of(args[1]).toAbsolutePath();Files.createDirectories(output);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)) {
            registry=BlockRegistry.load(resources);baker=new ModelBaker(resources,registry);colors=new Colormaps();colors.load(resources);atlas=SpriteAtlas.load(resources,"blocks");
            pistons();heads();minecarts();check(RenderDiagnostics.snapshot().isEmpty(),"unresolved model resources: "+RenderDiagnostics.snapshot());
        }
    }
}
