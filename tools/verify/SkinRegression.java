package io.github.litematicrender.schematic;

import io.github.litematicrender.render.*;
import io.github.litematicrender.render.skin.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.resource.*;
import io.github.litematicrender.model.*;
import io.github.litematicrender.world.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SkinRegression {
    static final String HASH="292009a4925b58f02c77dadc3ecef07ea4c7472f64e0fdc32ce5522489362680";
    static final String URL="https://textures.minecraft.net/texture/"+HASH;
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static NbtScalars.NbtString text(String value) { return new NbtScalars.NbtString(value); }
    static String textureValue() {
        return Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\""+URL+"\"}}}").getBytes(StandardCharsets.UTF_8));
    }
    static NbtCompound modern() {
        var properties=new NbtList(); properties.add(new NbtCompound().put("name",text("textures")).put("value",text(textureValue())));
        return new NbtCompound().put("profile",new NbtCompound().put("name",text("Notch")).put("properties",properties));
    }
    static NbtCompound legacy() {
        var values=new NbtList(); values.add(new NbtCompound().put("Value",text(textureValue())));
        return new NbtCompound().put("SkullOwner",new NbtCompound().put("Name",text("Notch"))
            .put("Properties",new NbtCompound().put("textures",values)));
    }
    public static void main(String[] args) throws Exception {
        Path project=Path.of(args[0]).toAbsolutePath(), output=Path.of(args[1]).toAbsolutePath(); Files.createDirectories(output);
        if (args.length>2 && args[2].equals("online")) { live(project,output); return; }
        Image pixels=new Image(64,64);
        for (int y=0;y<16;y++) for (int x=0;x<32;x++) pixels.setPixel(x,y,0xFF0066FF);
        for (int y=0;y<16;y++) for (int x=32;x<64;x++) pixels.setPixel(x,y,0x8000FF66);
        Path fixture=output.resolve("local-fixture.png"); pixels.writePng(fixture); byte[] png=Files.readAllBytes(fixture);
        check(SkinResolver.readRequest(modern()).textureUrl().equals(URL),"modern texture property not decoded");
        check(SkinResolver.readRequest(legacy()).textureUrl().equals(URL),"legacy texture property not decoded");
        check(SkinResolver.readRequest(new NbtCompound().put("SkullOwner",text("Notch"))).name().equals("notch"),"legacy username missing");
        var uuidHead=new NbtCompound().put("profile",new NbtCompound().put("id",new NbtScalars.NbtIntArray(new int[]{0,1,-1,2})));
        check(SkinResolver.readRequest(uuidHead).uuid().equals("0000000000000001ffffffff00000002"),"UUID int-array conversion failed");
        check(SkinResolver.decodeTexture("not-base64")==null,"malformed texture property must fall back");
        try { SkinResolver.textureUri("https://example.com/texture/"+HASH); throw new AssertionError("untrusted host accepted"); } catch (IOException expected) { }
        try { SkinResolver.importLocal(output,"../../escape",fixture); throw new AssertionError("unsafe import identity accepted"); } catch (IOException expected) { }
        AtomicInteger requests=new AtomicInteger();
        Path fakeHome=Files.createTempDirectory(output,"cache-fixture-");
        SkinResolver resolver=new SkinResolver(fakeHome,true,(uri,limit)->{
            requests.incrementAndGet(); check(uri.toString().equals(URL),"texture-only head queried player API"); return png;
        });
        var alternate=modern(); alternate.getCompound("profile").orElseThrow().put("name",text("OtherPlayer"));
        resolver.prepare(List.of(modern(),modern(),legacy(),alternate));
        var skin=resolver.resolved(modern());
        check(skin!=null && skin.translucentOverlay(),"downloaded skin/hat missing");
        check(requests.get()==1,"identical skin downloads not deduplicated");
        check(Files.isRegularFile(fakeHome.resolve("skins/textures/"+HASH+".png")),"skin not cached on disk");
        SkinResolver offline=new SkinResolver(fakeHome,false,(uri,limit)->{throw new AssertionError("offline mode performed a request");});
        check(offline.load(legacy())!=null,"offline cached skin missing");
        Path nameHome=Files.createTempDirectory(output,"name-fixture-");
        AtomicInteger nameRequests=new AtomicInteger();
        SkinResolver named=new SkinResolver(nameHome,true,(uri,limit)->{
            nameRequests.incrementAndGet();
            if (uri.getHost().equals("api.mojang.com")) return "{\"id\":\"069a79f444e94726a5befca90e38aaf5\"}".getBytes(StandardCharsets.UTF_8);
            if (uri.getHost().equals("sessionserver.mojang.com")) return ("{\"properties\":[{\"name\":\"textures\",\"value\":\""+textureValue()+"\"}]}").getBytes(StandardCharsets.UTF_8);
            return png;
        });
        var nameHead=new NbtCompound().put("profile",text("Notch"));
        check(named.load(nameHead)!=null && nameRequests.get()==3,"username lookup/profile/download chain failed");
        check(new SkinResolver(nameHome,false,(uri,limit)->{throw new AssertionError("offline name lookup contacted server");}).load(nameHead)!=null,"offline profile alias failed");
        SkinResolver.importLocal(fakeHome,"Notch",fixture);
        check(offline.load(modern()).spriteId().contains("/local_"),"local PNG must override cached remote skin");
        SkinResolver.setOnline(fakeHome,false); check(!SkinResolver.onlineEnabled(fakeHome),"offline preference was not saved");
        var failing=new SkinResolver(output.resolve("failed-fixture"),true,(uri,limit)->{throw new IOException("simulated timeout");});
        failing.prepare(List.of(modern())); check(failing.resolved(modern())==null,"network failure must keep Steve");
        Image old=new Image(64,32); Arrays.fill(old.pixels(),0xFF884422); Path legacyPng=output.resolve("legacy.png"); old.writePng(legacyPng);
        Image oldFixed=SkinResolver.normalizeSkin(Files.readAllBytes(legacyPng));
        check(oldFixed.height==64 && Image.alpha(oldFixed.pixel(32,0))==0 && Image.alpha(oldFixed.pixel(8,8))==255,"legacy hat repair failed");
        scene(project,output,fakeHome,modern(),"cached-skin.png");
        System.out.println("PASS: modern/legacy/UUID profiles, one download per texture, offline cache, name alias, PNG override, network failure, legacy skin, translucent hat and CPU/GPU atlas");
    }
    static void live(Path project, Path output) throws Exception {
        Path home=Files.createTempDirectory(output,"online-cache-"); SkinResolver.setOnline(home,true);
        var named=new NbtCompound().put("profile",text("Notch"));
        SkinResolver first=new SkinResolver(home);
        var skin=first.load(named); check(skin!=null,"live username/profile/texture fetch failed");
        check((int)first.summary().get("downloaded")==1,"live texture was not downloaded");
        skin.image().writePng(output.resolve("downloaded-notch.png"));
        SkinResolver.setOnline(home,false);
        var offline=new SkinResolver(home);
        check(offline.load(named)!=null && (int)offline.summary().get("downloaded")==0,"downloaded profile cannot render offline");
        scene(project,output,home,named,"real-player-offline.png");
        // Schematic fixture for the real desktop preview, using the downloaded profile identity.
        var state=EntityRegression.registry.getOrMissing("minecraft:player_head").defaultState.with("rotation","0");
        EntityRegression.output=output;
        EntityRegression.writeSchematic(new BlockState[]{state},Map.of(LitematicaSchematic.pack(0,0,0),named));
        Files.move(output.resolve("entities.litematic"),output.resolve("player-skin.litematic"),StandardCopyOption.REPLACE_EXISTING);
        System.out.println("PASS LIVE: public username lookup, profile texture download, persisted cache, subsequent offline scene render");
    }
    static void scene(Path project,Path output,Path cacheHome,NbtCompound head,String name) throws Exception {
        System.setProperty("litematicrender.home",cacheHome.toString());
        try (ResourceManager resources=CliRenderer.openResources(project.resolve("assets/vanilla"),project)) {
            EntityRegression.registry=BlockRegistry.load(resources); EntityRegression.baker=new ModelBaker(resources,EntityRegression.registry);
            EntityRegression.colors=new Colormaps(); EntityRegression.colors.load(resources); EntityRegression.atlas=SpriteAtlas.load(resources,"blocks");
            var state=EntityRegression.registry.getOrMissing("minecraft:player_head").defaultState.with("rotation","0");
            var mesh=EntityRegression.fixture(new BlockState[]{state},Map.of(LitematicaSchematic.pack(0,0,0),head));
            check(mesh.cutout.stream().anyMatch(q->q.spriteId.startsWith("litematicrender:skin/")),"scene did not attach the real skin");
            for (var q:mesh.cutout) if(q.spriteId.startsWith("litematicrender:skin/")) check(EntityRegression.atlas.get(q.spriteId).isPresent(),"GPU atlas is missing cached skin");
            var settings=new RenderSettings(0,15,360,360); settings.transparentBackground=false; settings.backgroundColor=0x262C34;
            SchematicRenderer.render(null,mesh,EntityRegression.colors,EntityRegression.atlas,settings).writePng(output.resolve(name));
            EntityRegression.atlas.stitched.writePng(output.resolve("skin-atlas.png"));
            MeshExporter.write(mesh,EntityRegression.atlas,output.resolve("skin-mesh.bin"));
        } finally { System.clearProperty("litematicrender.home"); }
    }
}
