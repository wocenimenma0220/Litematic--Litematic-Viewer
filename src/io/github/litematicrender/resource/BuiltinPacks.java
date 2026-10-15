package io.github.litematicrender.resource;

import io.github.litematicrender.util.MiniJson;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Bundled appearance packs may be removed; renderer support resources always remain loaded. */
public final class BuiltinPacks {
    private BuiltinPacks() {}
    private static final Set<String> SUPPORT=Set.of("fast-chest","block-entity-textures","minecraft-font","static-block-entities");
    public static boolean visible(Path pack){return !SUPPORT.contains(pack.getFileName().toString().toLowerCase(Locale.ROOT));}
    public static List<Path> list(Path home) {
        Set<String> removed=new HashSet<>();
        try {
            var values=MiniJson.asArray(MiniJson.parse(Files.readString(home.resolve("settings/removed-builtins.json"))));
            if(values!=null)for(Object value:values)if(value instanceof String name)removed.add(name);
        } catch(IOException|RuntimeException ignored) { /* first launch or invalid preference */ }
        Path directory=home.resolve("assets/builtin");
        if(!Files.isDirectory(directory))return List.of();
        try(Stream<Path> files=Files.list(directory)) {
            return files.filter(p->Files.isDirectory(p)||p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                .filter(p->!visible(p)||!removed.contains(p.getFileName().toString())).sorted().toList();
        } catch(IOException ignored) {return List.of();}
    }
}
