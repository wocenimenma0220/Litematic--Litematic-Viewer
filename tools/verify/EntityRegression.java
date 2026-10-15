package io.github.litematicrender.schematic;

import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.model.*;
import io.github.litematicrender.resource.*;
import io.github.litematicrender.world.*;
import io.github.litematicrender.nbt.*;
import java.nio.file.*;
import java.util.*;

public class EntityRegression {
    static BlockRegistry registry;
    static ModelBaker baker;
    static Colormaps colors;
    static SpriteAtlas atlas;
    static Path output;
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static NbtScalars.NbtString string(String value) { return new NbtScalars.NbtString(value); }
    static NbtCompound side(String color, boolean glow, String... lines) {
        var messages = new NbtList();
        for (String text : lines) messages.add(string(text));
        return new NbtCompound().put("messages", messages).put("color",string(color))
            .put("has_glowing_text",new NbtScalars.NbtByte((byte)(glow ? 1 : 0)));
    }
    static NbtCompound sign(String color, boolean glow) {
        return new NbtCompound().put("front_text",side(color,glow,"中文 Steve","","荧光文字","Hello 123"))
            .put("back_text",side("white",true,"背面 Back","","中文 English","Glow ink"));
    }
    static SceneMesh fixture(BlockState[] cells, Map<Long,NbtCompound> entities) {
        var palette = new BlockState[cells.length+1]; palette[0]=BlockState.air();
        var storage = new long[(cells.length+7)/8];
        for (int i=0;i<cells.length;i++) { palette[i+1]=cells[i]; storage[i/8]|=(long)(i+1)<<((i%8)*8); }
        var container = new LitematicaSchematic.BlockContainer(cells.length,1,1,8,storage,palette);
        var region = new LitematicaSchematic.Region("entities",Vec3i.ZERO,new Vec3i(cells.length,1,1),container,entities);
        var builder = new SceneBuilder(registry,baker,colors,atlas);
        builder.addRegion(region,new int[]{0,0,0});
        return builder.build();
    }
    static void picture(SceneMesh mesh, String name, int width, int height, float rotation, float slant) throws Exception {
        var settings = new RenderSettings(rotation,slant,width,height);
        settings.transparentBackground=false; settings.backgroundColor=0x262C34;
        SchematicRenderer.render(null,mesh,colors,atlas,settings).writePng(output.resolve(name));
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]); output=Path.of(args[1]); Files.createDirectories(output);
        try (ResourceManager resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)) {
            registry=BlockRegistry.load(resources); baker=new ModelBaker(resources,registry);
            colors=new Colormaps(); colors.load(resources); atlas=SpriteAtlas.load(resources,"blocks");
            FontRenderer font=FontRenderer.load(resources);
            for (int cp : "中文告示牌荧光English123，。".codePoints().toArray()) check(font.hasGlyph(cp),"Missing default MC glyph: "+Integer.toHexString(cp));
            check(!font.hasGlyph('한') && !font.hasGlyph('あ') && !font.hasGlyph(0x1F600),"Unsupported languages must stay hidden");
            check(font.renderLine("A A",-1,90,null).width-font.renderLine("AA",-1,90,null).width==8,"MC space width must be four logical pixels");
            check(BlockEntityData.plainText("{\"text\":\"中文\",\"extra\":[{\"text\":\"English\",\"color\":\"red\"}]}").equals("中文English"),"JSON component parsing leaked structural keys");
            check(BlockEntityData.plainText("\"\\u4e2d\\u6587\"").equals("中文"),"Unicode escapes were corrupted");
            var nbt=sign("lime",true); var parsed=BlockEntityData.readSign(nbt);
            check(parsed.frontLines().size()==4 && parsed.frontLines().get(1).isEmpty(),"Empty sign rows must preserve their position");
            check(parsed.glowingFront() && parsed.glowingBack(),"Front/back glow flags lost");
            var nbtLines=new NbtList(); nbtLines.add(new NbtCompound().put("text",string("直接 NBT")));
            check(BlockEntityData.readSign(new NbtCompound().put("front_text",new NbtCompound().put("messages",nbtLines))).frontLines().get(0).equals("直接 NBT"),"Modern NBT text components missing");
            var legacy=new NbtCompound().put("Text1",string("{\"text\":\"旧版 Legacy\"}"))
                .put("Text2",string("\"\"")).put("Text3",string("\"第三行\""))
                .put("GlowingText",new NbtScalars.NbtByte((byte)1));
            check(BlockEntityData.readSign(legacy).frontLines().get(2).equals("第三行"),"Legacy JSON or blank rows lost");
            var textMesh=new SceneMesh(); var generated=new LinkedHashMap<String,Image>();
            var signState=registry.getOrMissing("minecraft:oak_sign").defaultState;
            SignGeometry.emit(textMesh,signState,parsed,font,0,0,0,new float[]{.1f,.1f,.1f},generated);
            check(textMesh.cutout.stream().allMatch(q->q.tintR==1 && q.tintG==1 && q.tintB==1 && q.shade==1),"Glow ink must ignore world lighting");
            var ordinaryMesh=new SceneMesh(); var ordinarySprites=new LinkedHashMap<String,Image>();
            SignGeometry.emit(ordinaryMesh,signState,BlockEntityData.readSign(sign("red",false)),font,0,0,0,new float[]{.1f,.1f,.1f},ordinarySprites);
            check(ordinaryMesh.cutout.get(0).tintR==.1f,"Ordinary sign text must follow world light");
            var blackMesh=new SceneMesh(); var blackSprites=new LinkedHashMap<String,Image>();
            SignGeometry.emit(blackMesh,signState,BlockEntityData.readSign(sign("black",true)),font,0,0,0,new float[]{.1f,.1f,.1f},blackSprites);
            check(Arrays.stream(blackSprites.values().iterator().next().pixels()).anyMatch(p->p==0xFFF0EBCC),"Black glow text requires its vanilla pale outline");
            var head=registry.getOrMissing("minecraft:player_head").defaultState.with("rotation","0");
            var headMesh=fixture(new BlockState[]{head},Map.of());
            check(headMesh.cutout.size()==12 && headMesh.cutout.stream().allMatch(q->q.spriteId.equals("minecraft:entity/player/wide/steve")),"Steve base and hat model missing");
            picture(headMesh,"steve-front.png",256,256,0,15);
            picture(headMesh,"steve-back.png",256,256,180,15);
            var wall=registry.getOrMissing("minecraft:player_wall_head").defaultState;
            for (String facing : List.of("north","south","east","west")) {
                var wallMesh=fixture(new BlockState[]{wall.with("facing",facing)},Map.of());
                double minY=wallMesh.cutout.stream().mapToDouble(q->Math.min(Math.min(q.y0,q.y1),Math.min(q.y2,q.y3))).min().orElseThrow();
                check(Math.abs(minY-.234375)<.001,"Wall head attachment height incorrect");
                double centerX=wallMesh.cutout.stream().mapToDouble(q->q.cx).average().orElseThrow();
                double centerZ=wallMesh.cutout.stream().mapToDouble(q->q.cz).average().orElseThrow();
                double expectedX=facing.equals("east")?.25:facing.equals("west")?.75:.5;
                double expectedZ=facing.equals("south")?.25:facing.equals("north")?.75:.5;
                check(Math.abs(centerX-expectedX)<.001 && Math.abs(centerZ-expectedZ)<.001,"Wall head offset incorrect: "+facing);
            }
            var states=new BlockState[]{head,BlockState.air(),signState,BlockState.air(),signState,
                BlockState.air(),signState,BlockState.air(),registry.getOrMissing("minecraft:oak_wall_sign").defaultState.with("facing","south"),
                BlockState.air(),registry.getOrMissing("minecraft:oak_hanging_sign").defaultState.with("rotation","0")};
            var entities=new LinkedHashMap<Long,NbtCompound>();
            entities.put(LitematicaSchematic.pack(2,0,0),sign("white",false));
            entities.put(LitematicaSchematic.pack(4,0,0),sign("lime",true));
            entities.put(LitematicaSchematic.pack(6,0,0),sign("black",true));
            entities.put(LitematicaSchematic.pack(8,0,0),sign("orange",true));
            entities.put(LitematicaSchematic.pack(10,0,0),sign("white",true));
            var scene=fixture(states,entities);
            check(scene.cutout.stream().filter(q->q.spriteId.startsWith("litematicrender:sign/")).count()==10,"Both sides of each sign must exist");
            for (var q : scene.cutout) if (q.spriteId.startsWith("litematicrender:sign/")) {
                check(atlas.get(q.spriteId).isPresent(),"WebGL atlas missing sign text");
                var entry=atlas.get(q.spriteId).orElseThrow();
                check(entry.contents.sheet==q.image,"CPU and WebGL sign textures differ");
            }
            picture(scene,"signs-front.png",1400,280,0,8);
            picture(scene,"signs-back.png",1400,280,180,8);
            atlas.stitched.writePng(output.resolve("entity-atlas.png"));
            MeshExporter.write(scene,atlas,output.resolve("entity-mesh.bin"));
            writeSchematic(states,entities);
            font.renderLine("Minecraft 中文告示牌 荧光墨囊",0xFFBFFF00,180,0xFF263300).writePng(output.resolve("font-sample.png"));
            fixture(new BlockState[]{head},Map.of());
            check(atlas.get("litematicrender:sign/2_0_0_0").isEmpty(),"Stale generated text must be removed between schematics");
            System.out.println("PASS: official Chinese/English fonts, JSON/NBT/legacy text, empty rows, front/back glow, black outlines, Steve UV and wall placement, shared CPU/GPU atlas");
            System.out.println("Font glyphs="+font.glyphsLoaded()+", providers="+font.providersLoaded());
        }
    }

    static void writeSchematic(BlockState[] cells, Map<Long,NbtCompound> entities) throws Exception {
        var palette=new NbtList();
        palette.add(new NbtCompound().put("Name",string("minecraft:air")));
        for (var state : cells) {
            var properties=new NbtCompound();
            for (var property : state.properties) properties.put(property.name(),string(state.getValue(property.name())));
            palette.add(new NbtCompound().put("Name",string(state.blockId)).put("Properties",properties));
        }
        int bits=Math.max(2,32-Integer.numberOfLeadingZeros(palette.size()-1));
        long[] packed=new long[(cells.length*bits+63)/64];
        for (int i=0;i<cells.length;i++) packed[(i*bits)/64]|=(long)(i+1)<<((i*bits)%64);
        var tes=new NbtList();
        for (var entry : entities.entrySet()) tes.add(entry.getValue().put("x",new NbtScalars.NbtInt(LitematicaSchematic.unpackX(entry.getKey())))
            .put("y",new NbtScalars.NbtInt(0)).put("z",new NbtScalars.NbtInt(0)));
        var zero=new NbtCompound().put("x",new NbtScalars.NbtInt(0)).put("y",new NbtScalars.NbtInt(0)).put("z",new NbtScalars.NbtInt(0));
        var size=new NbtCompound().put("x",new NbtScalars.NbtInt(cells.length)).put("y",new NbtScalars.NbtInt(1)).put("z",new NbtScalars.NbtInt(1));
        var region=new NbtCompound().put("Position",zero).put("Size",size).put("BlockStatePalette",palette)
            .put("BlockStates",new NbtScalars.NbtLongArray(packed)).put("TileEntities",tes);
        var root=new NbtCompound().put("Version",new NbtScalars.NbtInt(6)).put("MinecraftDataVersion",new NbtScalars.NbtInt(0))
            .put("Metadata",new NbtCompound().put("Name",string("Steve 中文告示牌荧光验证"))
                .put("RegionCount",new NbtScalars.NbtInt(1)).put("TotalBlocks",new NbtScalars.NbtInt(cells.length))
                .put("TotalVolume",new NbtScalars.NbtInt(cells.length)).put("EnclosingSize",size))
            .put("Regions",new NbtCompound().put("entities",region));
        io.github.litematicrender.nbt.io.NbtFileUtils.writeCompressed(output.resolve("entities.litematic"),root);
    }
}
