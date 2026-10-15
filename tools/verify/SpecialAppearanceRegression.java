package io.github.litematicrender.schematic;

import io.github.litematicrender.model.*;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.serve.*;
import io.github.litematicrender.world.*;
import java.nio.file.*;
import java.util.*;

/** Behavioral fixtures for NBT appearance, activation, section ownership and frozen atlas lifetime. */
public final class SpecialAppearanceRegression {
    static Path out;static BlockRegistry registry;static ModelBaker baker;static Colormaps colors;static SpriteAtlas atlas;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static final class Fixture {
        final String name;final int sx,sy,sz;final int[] cells;final List<NbtCompound> palette=new ArrayList<>();final NbtList tiles=new NbtList();
        Fixture(String name,int sx,int sy,int sz){this.name=name;this.sx=sx;this.sy=sy;this.sz=sz;cells=new int[sx*sy*sz];palette.add(VisualFixRegression.state("air"));}
        void put(int x,int y,int z,String id,String...props){palette.add(VisualFixRegression.state(id,props));cells[(y*sz+z)*sx+x]=palette.size()-1;}
        void tag(int x,int y,int z,NbtCompound tag){tag.putInt("x",x).putInt("y",y).putInt("z",z);tiles.add(tag);}
        LitematicaSchematic load()throws Exception{Path file=VisualFixRegression.write(out.resolve(name+".litematic"),sx,sy,sz,palette,cells);var root=NbtFileUtils.readCompressed(file);root.getCompound("Regions").orElseThrow().getCompound("test").orElseThrow().put("TileEntities",tiles);NbtFileUtils.writeCompressed(file,root);var result=LitematicaSchematic.load(file);result.materialize(registry);return result;}
    }
    static SceneBuilder builder(LitematicaSchematic schematic){return LodRegression.builder(schematic,registry,baker,colors,atlas,true);}
    static List<SceneMesh.MeshQuad> quads(SceneMesh scene){return VisualFixRegression.quads(scene);}
    static Set<String> geometry(SceneMesh scene){var result=new HashSet<String>();for(var q:quads(scene))result.add(q.layer+":"+q.spriteId+":"+q.x0+","+q.y0+","+q.z0+":"+q.x1+","+q.y1+","+q.z1+":"+q.x2+","+q.y2+","+q.z2+":"+q.x3+","+q.y3+","+q.z3);return result;}
    static void stream(LitematicaSchematic schematic,String name)throws Exception{
        var source=builder(schematic);source.prepareStreaming();Image frozen=atlas.stitched;int count=atlas.spriteCount();Set<String> combined=new HashSet<>();
        for(var section:source.sections()){var mesh=source.buildSection(section);combined.addAll(geometry(mesh));for(var q:quads(mesh))if(q.spriteId!=null)check(atlas.getOrMissingEntry(q.spriteId).contents.name.equals(q.spriteId),"missing frozen sprite "+q.spriteId);}
        check(frozen==atlas.stitched&&count==atlas.spriteCount(),"section changed atlas "+name);check(combined.equals(geometry(builder(schematic).build())),"section/full geometry mismatch "+name);
        var index=MeshExporter.writeStreaming(builder(schematic),atlas,out.resolve(name+".lmesh"));atlas.stitched.writePng(out.resolve(name+"-atlas.png"));
        if(name.equals("beacons")){check(index.bounds()[4]<16,"beam enlarged automatic framing");check(index.sections().stream().anyMatch(s->s.bounds()[4]>2048),"beam missing from section culling bounds");}
    }
    static void picture(SceneMesh mesh,String name)throws Exception{SchematicRenderer.render(null,mesh,colors,atlas,new RenderSettings(35,30,1400,700)).writePng(out.resolve(name+".png"));}
    static NbtCompound pattern(String name,String color){return new NbtCompound().putString("pattern","minecraft:"+name).putString("color",color);}
    static NbtCompound patterns(boolean legacy){NbtList list=new NbtList();list.add(legacy?new NbtCompound().putString("Pattern","cr").putInt("Color",14):pattern("cross","red"));list.add(legacy?new NbtCompound().putString("Pattern","bo").putInt("Color",11):pattern("border","blue"));return new NbtCompound().put(legacy?"Patterns":"patterns",list);}
    static void conduit(Fixture f,int x,int z,boolean active){f.put(x,3,z,"conduit","waterlogged",active?"true":"false");if(!active)return;
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dy!=0||dz!=0)f.put(x+dx,3+dy,z+dz,"water");
        for(int dx=-2;dx<=2;dx++)for(int dy=-2;dy<=2;dy++)for(int dz=-2;dz<=2;dz++){int ax=Math.abs(dx),ay=Math.abs(dy),az=Math.abs(dz);if((ax>1||ay>1||az>1)&&(dx==0&&(ay==2||az==2)||dy==0&&(ax==2||az==2)||dz==0&&(ax==2||ay==2)))f.put(x+dx,3+dy,z+dz,"prismarine");}
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toAbsolutePath();out=root.resolve("tools/verify/lod-output/appearances");Files.createDirectories(out);System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)){
            registry=BlockRegistry.load(resources);baker=new ModelBaker(resources,registry);colors=new Colormaps();colors.load(resources);atlas=SpriteAtlas.load(resources,"blocks");
            Fixture banners=new Fixture("banners",64,6,12);String[] dyes={"white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black"};
            for(int i=0;i<16;i++){banners.put(2+i*3,0,3,dyes[i]+"_banner","rotation",String.valueOf(i));banners.tag(2+i*3,0,3,patterns(i%2!=0));}
            String[] facing={"north","east","south","west"};for(int i=0;i<4;i++){banners.put(4+i*12,2,8,"white_wall_banner","facing",facing[i]);banners.tag(4+i*12,2,8,patterns(i%2!=0));}
            var bannerScene=banners.load();check(bannerScene.regions().get("test").blocks.get(47,0,3).getValue("rotation").equals("15"),"banner rotation dropped by registry");var bannerMesh=builder(bannerScene).build();check(quads(bannerMesh).stream().filter(q->q.spriteId.startsWith("litematicrender:banner/")).count()==120,"flag model missing faces");
            var wallSprites=quads(bannerMesh).stream().filter(q->q.blockY==2&&q.spriteId.startsWith("litematicrender:banner/")).map(q->q.spriteId).distinct().toList();check(wallSprites.size()==1,"legacy and modern banner layers differ");
            check(quads(bannerMesh).stream().filter(q->q.spriteId.startsWith("litematicrender:banner/")).map(q->q.spriteId).distinct().count()==16,"base dye colors were ignored");picture(bannerMesh,"banners");stream(bannerScene,"banners");
            Fixture specials=new Fixture("books-conduits",24,8,16);for(int i=0;i<4;i++)specials.put(2+i*5,0,2,"lectern","facing",facing[i],"has_book","true");specials.put(22,0,2,"lectern","has_book","false");conduit(specials,4,10,false);conduit(specials,15,10,true);
            var specialScene=specials.load();var specialMesh=builder(specialScene).build();check(quads(specialMesh).stream().filter(q->q.spriteId.contains("enchanting_table_book")).count()==4*42,"lectern books missing or present on empty lectern: "+quads(specialMesh).stream().filter(q->q.spriteId.contains("enchanting_table_book")).count());check(quads(specialMesh).stream().anyMatch(q->q.spriteId.endsWith("conduit/base"))&&quads(specialMesh).stream().anyMatch(q->q.spriteId.endsWith("conduit/cage"))&&quads(specialMesh).stream().anyMatch(q->q.spriteId.endsWith("conduit/open_eye")),"inactive/active conduit state ignored");picture(specialMesh,"books-conduits");stream(specialScene,"books-conduits");
            Fixture beacons=new Fixture("beacons",24,8,9);for(int x:new int[]{4,12}){for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)beacons.put(x+dx,0,4+dz,"iron_block");beacons.put(x,1,4,"beacon");}
            beacons.put(4,3,4,"red_stained_glass");beacons.put(4,5,4,"blue_stained_glass_pane");beacons.put(12,4,4,"stone");beacons.put(20,1,4,"beacon");var beaconScene=beacons.load();var beaconMesh=builder(beaconScene).build();var beams=quads(beaconMesh).stream().filter(q->q.decorativeEffect()).toList();check(!beams.isEmpty()&&beams.stream().allMatch(q->q.blockX==4),"blocked/inactive beacon generated a beam");check(beams.stream().map(q->q.spriteId).distinct().count()==6,"beam color transitions lost");check(beams.stream().allMatch(q->q.shade==1&&q.tintR==1&&q.tintG==1&&q.tintB==1),"beam affected by lighting");check(new ScenePicker(beaconMesh,atlas).pick(new double[]{1,7,4.5},new double[]{1,0,0},7)==null,"beam intercepted selection");picture(beaconMesh,"beacons");stream(beaconScene,"beacons");
            SavedEntityRegression.registry=registry;SavedEntityRegression.baker=baker;SavedEntityRegression.colors=colors;SavedEntityRegression.atlas=atlas;SavedEntityRegression.output=out;
            var entities=new ArrayList<NbtCompound>();for(int face=0;face<6;face++)for(int rotation=0;rotation<8;rotation++)entities.add(SavedEntityRegression.entity(rotation%2==0?"item_frame":"glow_item_frame",face*2,1,rotation*2,0).putInt("Facing",face).putInt("ItemRotation",rotation).put("Item",SavedEntityRegression.item(0,rotation%3==0?"stone":rotation%3==1?"diamond":"golden_sword",1)));
            var frameScene=SavedEntityRegression.loaded(entities,Vec3i.ZERO);Files.copy(out.resolve("fixture.litematic"),out.resolve("frames.litematic"),StandardCopyOption.REPLACE_EXISTING);var frameMesh=builder(frameScene).build();check(frameMesh.entityReport.get("rendered").equals(48),"not all frame faces/rotations rendered");check(quads(frameMesh).stream().allMatch(q->q.entityIndex>=0),"frame selection identity lost");for(int face=0;face<6;face++)for(int rotation:new int[]{0,3,6}){int index=face*8+rotation;check(quads(frameMesh).stream().anyMatch(q->q.entityIndex==index&&q.spriteId.equals("minecraft:block/stone")),"3D block item missing from frame "+index);}picture(frameMesh,"frames");stream(frameScene,"frames");
            var hidden=SavedEntityRegression.single(SavedEntityRegression.entity("item_frame",0,0,0,0).putInt("Facing",3).putInt("Invisible",1).put("Item",SavedEntityRegression.item(0,"diamond",1)));check(hidden.quadCount>0&&quads(hidden).stream().noneMatch(q->q.spriteId.contains("item_frame")),"invisible frame also hid item");
            NbtCompound modernTrim=new NbtCompound().putString("pattern","minecraft:sentry").putString("material","minecraft:gold");var equipment=new NbtCompound();for(String slot:List.of("head","chest","legs","feet"))equipment.put(slot,SavedEntityRegression.item(0,"golden_"+switch(slot){case "head"->"helmet";case "chest"->"chestplate";case "legs"->"leggings";default->"boots";},1).put("components",new NbtCompound().put("minecraft:trim",modernTrim)));
            var adult=SavedEntityRegression.entity("piglin",0,0,0,180).put("equipment",equipment);var baby=SavedEntityRegression.entity("piglin",2,0,0,180).putInt("IsBaby",1).put("equipment",equipment);var armored=SavedEntityRegression.loaded(List.of(adult,baby),Vec3i.ZERO);Files.copy(out.resolve("fixture.litematic"),out.resolve("armor.litematic"),StandardCopyOption.REPLACE_EXISTING);var armorMesh=builder(armored).build();var armorQuads=quads(armorMesh).stream().filter(q->q.spriteId.startsWith("litematicrender:entity/armor/trim/")).toList();check(!armorQuads.isEmpty(),"armor trims ignored");check(quads(armorMesh).stream().anyMatch(q->q.entityIndex==1&&q.spriteId.contains("humanoid_baby/gold")),"dedicated baby armor missing");check(quads(armorMesh).stream().noneMatch(q->q.entityIndex==1&&q.spriteId.contains("/trim/")),"baby trim differs from vanilla");
            Image plain=Image.read(resources.getResource("minecraft","textures/entity/equipment/humanoid/gold.png").orElseThrow());Image trimmed=armorQuads.stream().filter(q->q.image.height==32).findFirst().orElseThrow().image;check(!Arrays.equals(plain.pixels(),trimmed.pixels()),"trim palette did not change any pixels");picture(armorMesh,"armor");stream(armored,"armor");
            System.out.println("PASS: 16 banner dyes, modern/legacy patterns, all banner/lectern facings; active/inactive conduit; activated/blocked/stained beacons and framing; 48 frame poses and invisible items; adult trims and baby armor; exact streamed geometry and frozen atlases");
        }
    }
}
