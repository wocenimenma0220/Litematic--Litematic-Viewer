package io.github.litematicrender.serve;

import io.github.litematicrender.resource.ResourcePack;
import java.nio.file.*;
import java.util.*;

/** Resource directory snapshots must preserve listings and refresh after content changes. */
public final class ResourceDirectoryRegression {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();Files.createDirectories(root);
        Path pack=Files.createTempDirectory(root,"directory-");
        var expected=List.of("minecraft/models/block/one.json","minecraft/models/block/nested/two.json","minecraft/blockstates/one.json","example/models/block/模型.json");
        for(String name:expected){Path file=pack.resolve("assets").resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,"{}");}
        try(var resource=ResourcePack.ofDirectory("fixture",pack)){
            check(resource.namespaces().equals(Set.of("minecraft","example")),"namespace listing changed");
            check(new HashSet<>(resource.listResources("minecraft","models/block",".json")).equals(Set.of("one.json","nested/two.json")),"nested/prefix listing changed");
            check(resource.listAllNamespaced("blockstates",".json").size()==1,"unrelated directory included");
            check(resource.listAllNamespaced("models",".json").size()==3,"snapshot lost namespace or Unicode path");
        }
        String before=CacheFingerprint.tree(pack);Path one=pack.resolve("assets/minecraft/models/block/one.json");var time=Files.getLastModifiedTime(one);
        Files.writeString(one,"[]");Files.setLastModifiedTime(one,time);check(!before.equals(CacheFingerprint.tree(pack)),"same-size/same-time content edit ignored");
        Files.delete(pack.resolve("assets/minecraft/models/block/nested/two.json"));
        Files.writeString(pack.resolve("assets/minecraft/models/block/new.json"),"{}");
        try(var fresh=ResourcePack.ofDirectory("fixture",pack)){
            check(new HashSet<>(fresh.listResources("minecraft","models/block",".json")).equals(Set.of("one.json","new.json")),"fresh session kept old directory snapshot");
            check(new String(fresh.read("minecraft","models/block/one.json"),java.nio.charset.StandardCharsets.UTF_8).equals("[]"),"fresh session kept old content");
        }
        System.out.println("PASS: directory prefixes, namespaces, nested/Unicode files, same-metadata content changes and fresh-session additions/removals");
    }
}
