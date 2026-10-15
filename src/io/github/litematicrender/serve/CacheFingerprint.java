package io.github.litematicrender.serve;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Content identities, independent of mtimes, directory enumeration order and session objects. */
final class CacheFingerprint {
    private CacheFingerprint(){}
    static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    static String hex(MessageDigest digest){return HexFormat.of().formatHex(digest.digest());}
    static String file(Path file)throws IOException {
        var digest=digest();try(var input=Files.newInputStream(file)){byte[] buffer=new byte[65536];for(int n;(n=input.read(buffer))!=-1;){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("缓存校验已取消");digest.update(buffer,0,n);}}return hex(digest);
    }
    static String tree(Path root)throws IOException {
        if(!Files.exists(root))return "absent";
        if(!Files.isDirectory(root))return file(root);
        var digest=digest();List<Path> files;
        try(var paths=Files.walk(root)){files=paths.filter(Files::isRegularFile).sorted(Comparator.comparing(p->root.relativize(p).toString().replace('\\','/'))).toList();}
        for(Path path:files){digest.update(root.relativize(path).toString().replace('\\','/').getBytes(StandardCharsets.UTF_8));digest.update((byte)0);digest.update(file(path).getBytes(StandardCharsets.US_ASCII));}
        return hex(digest);
    }
    static String resources(Path vanilla,List<Path> builtins,List<Path> imports)throws IOException {
        var parts=new ArrayList<String>();parts.add("resources-v1");parts.add(tree(vanilla.resolve("assets")));parts.add(tree(vanilla.resolve("pack.mcmeta")));
        for(Path pack:builtins)parts.add(tree(pack));parts.add("user-packs");for(Path pack:imports)parts.add(tree(pack));return parts(parts.toArray(String[]::new));
    }
    static String parts(String... parts){var digest=digest();for(String part:parts){digest.update(part.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}return hex(digest);}
}
