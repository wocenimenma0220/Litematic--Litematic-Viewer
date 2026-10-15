package io.github.litematicrender.schematic;

import io.github.litematicrender.serve.RenderEngine;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import io.github.litematicrender.render.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.io.*;
import java.util.*;

/** Exercises real restart, same-metadata edits, corrupt bundles and normal export/picking. */
public final class SceneCacheRegression {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static String hash(Path path)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    static void pad(Path file)throws Exception{try(var output=new RandomAccessFile(file.toFile(),"rw")){check(output.length()<16384,"fixture grew too large");output.setLength(16384);}}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),out=root.resolve("tools/verify/cache-output");Files.createDirectories(out);
        System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");System.setProperty("litematicrender.cache.directory",out.resolve("scenes").toString());
        Path fixture=out.resolve("cache.litematic");
        if(args.length>1&&args[1].equals("restart")) {
            try(var engine=new RenderEngine(root)){engine.open(fixture);var result=engine.exportCachedStreamScene(false,true,true);check(Boolean.TRUE.equals(result.get("diskCacheHit")),"new process failed to reopen disk cache");check(result.get("meshBuildCount").equals(0),"restart rebuilt mesh");Files.writeString(out.resolve("restart-ok.txt"),result.get("cacheKey").toString());}
            return;
        }
        LodRegression.fixture(fixture,false);pad(fixture);
        String originalKey,meshHash,atlasHash;
        try(var engine=new RenderEngine(root)) {
            engine.open(fixture);engine.configureSceneCache(256,true);var cold=engine.exportCachedStreamScene(true,true,true);
            check(Boolean.FALSE.equals(cold.get("diskCacheHit"))&&cold.get("meshBuildCount").equals(1),"cold build not measured");originalKey=cold.get("cacheKey").toString();meshHash=hash(Path.of(cold.get("mesh").toString()));atlasHash=hash(Path.of(cold.get("atlas").toString()));
            var warm=engine.exportCachedStreamScene(false,true,true);check(Boolean.TRUE.equals(warm.get("diskCacheHit"))&&warm.get("meshBuildCount").equals(0),"same-session warm hit rebuilt geometry");
            check(meshHash.equals(hash(Path.of(warm.get("mesh").toString())))&&atlasHash.equals(hash(Path.of(warm.get("atlas").toString()))),"warm files changed");
            check(Boolean.TRUE.equals(engine.pickBlock(new double[]{.05,3,.05},new double[]{0,-1,0},10).get("hit")),"warm-cache picking missed geometry");
            engine.render(out.resolve("cached-export.png"),new RenderSettings(45,30,128,128),true);
            engine.renderAnimation(out.resolve("cached-export.gif"),new RenderSettings(45,30,64,64),2,5);
        }
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-cp",System.getProperty("java.class.path"),SceneCacheRegression.class.getName(),root.toString(),"restart").redirectErrorStream(true).redirectOutput(out.resolve("restart.log").toFile()).start();
        check(process.waitFor()==0,"restart process failed: "+Files.readString(out.resolve("restart.log")));check(Files.readString(out.resolve("restart-ok.txt")).equals(originalKey),"restart changed content identity");System.out.println("PASS: cold/warm/restarted mesh builds = 1/0/0, identical files, exact picking, PNG and GIF");
        try(var engine=new RenderEngine(root)) {
            engine.open(fixture);var warm=engine.exportCachedStreamScene(false,true,true);Path mesh=Path.of(warm.get("mesh").toString());try(var edit=new RandomAccessFile(mesh.toFile(),"rw")){edit.seek(64);int value=edit.read();edit.seek(64);edit.write(value^1);}
            var repaired=engine.exportCachedStreamScene(false,true,true);check(Boolean.FALSE.equals(repaired.get("diskCacheHit"))&&meshHash.equals(hash(Path.of(repaired.get("mesh").toString()))),"corrupt mesh was not rebuilt identically");
            var alternate=engine.exportCachedStreamScene(false,false,true);check(!originalKey.equals(alternate.get("cacheKey")),"lighting setting did not invalidate cache");
            FileTime time=Files.getLastModifiedTime(fixture);long length=Files.size(fixture);var tag=NbtFileUtils.readCompressed(fixture);var palette=tag.getCompound("Regions").orElseThrow().getCompound("lod").orElseThrow().getList("BlockStatePalette").orElseThrow();palette.getCompounds().get(1).putString("Name","minecraft:glass");NbtFileUtils.writeCompressed(fixture,tag);pad(fixture);Files.setLastModifiedTime(fixture,time);check(Files.size(fixture)==length,"same-size fixture failed");
            engine.open(fixture);var changed=engine.exportCachedStreamScene(false,true,true);check(!originalKey.equals(changed.get("cacheKey"))&&Boolean.FALSE.equals(changed.get("diskCacheHit")),"same size/mtime content edit reused stale cache");
            Path pack=out.resolve("pack/assets/minecraft/textures/block");Files.createDirectories(pack);var image=new Image(16,16);Arrays.fill(image.pixels(),0xffff0000);Path png=pack.resolve("glass.png");image.writePng(png);try(var edit=new RandomAccessFile(png.toFile(),"rw")){edit.setLength(4096);}FileTime pngTime=Files.getLastModifiedTime(png);
            engine.loadResources(engine.defaultPackRoot(),List.of(out.resolve("pack").toString()));engine.open(fixture);var red=engine.exportCachedStreamScene(false,true,true);
            Arrays.fill(image.pixels(),0xff00ff00);image.writePng(png);try(var edit=new RandomAccessFile(png.toFile(),"rw")){edit.setLength(4096);}Files.setLastModifiedTime(png,pngTime);
            var resources=engine.loadResources(engine.defaultPackRoot(),List.of(out.resolve("pack").toString()));check(Boolean.FALSE.equals(resources.get("cached")),"same-metadata pack edit did not reload resources");engine.open(fixture);var green=engine.exportCachedStreamScene(false,true,true);check(!red.get("cacheKey").equals(green.get("cacheKey")),"pack pixels did not invalidate disk scene");check(!hash(Path.of(red.get("atlas").toString())).equals(hash(Path.of(green.get("atlas").toString()))),"updated pack pixels not used in atlas");
            engine.configureSceneCache(0,false);var uncached=engine.exportCachedStreamScene(false,true,true);check(Boolean.FALSE.equals(uncached.get("cacheStored")),"disabled cache retained a new scene");engine.configureSceneCache(256,false);
            System.out.println("PASS: checksums rebuild corrupt meshes; lighting, same-size/same-mtime schematic and pack edits invalidate; disabled cache uses temporary scene");
        }
    }
}
