package io.github.litematicrender.resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Stacked resource manager: vanilla first, then imported packs in user order.
 * Later packs override earlier ones (like MultiPackResourceManager).
 */
public final class ResourceManager implements AutoCloseable {
    private final List<ResourcePack> packs = new ArrayList<>();
    private final Path vanillaRoot;

    public ResourceManager(Path vanillaRoot) throws IOException {
        this.vanillaRoot = vanillaRoot;
        ResourcePack vanilla = ResourcePack.vanilla(vanillaRoot);
        this.packs.add(vanilla);
    }

    public ResourcePack vanillaPack() {
        return this.packs.getFirst();
    }

    /** Imported packs (everything after vanilla), in priority order (later = higher priority). */
    public List<ResourcePack> importedPacks() {
        return List.copyOf(this.packs.subList(1, this.packs.size()));
    }

    /** Adds a pack at the highest priority, failing loudly if it cannot be read. */
    public ResourcePack importPack(Path path) throws IOException {
        ResourcePack pack = openPack(path);
        this.packs.add(pack);
        return pack;
    }

    /**
     * Same as {@link #importPack} but skips unreadable packs with a warning instead of throwing.
     * Used for built-ins, where one bad zip should not stop the app from starting.
     */
    public void importPackUnchecked(Path path) {
        try {
            this.importPack(path);
        } catch (IOException e) {
            System.err.println("[ResourceManager] skipping pack " + path + ": " + e.getMessage());
        }
    }

    private static ResourcePack openPack(Path path) throws IOException {
        String id = path.getFileName().toString();
        if (id.toLowerCase().endsWith(".zip")) id = id.substring(0, id.length() - 4);
        return Files.isDirectory(path)
            ? ResourcePack.ofDirectory(id, path)
            : ResourcePack.ofZip(id, path);
    }

    public void removePack(ResourcePack pack) {
        if (pack == this.vanillaPack()) return;
        this.packs.remove(pack);
        pack.close();
    }

    public void moveUp(ResourcePack pack) {
        int i = this.packs.indexOf(pack);
        if (i > 1) {
            this.packs.remove(i);
            this.packs.add(i - 1, pack);
        }
    }

    public void moveDown(ResourcePack pack) {
        int i = this.packs.indexOf(pack);
        if (i >= 1 && i < this.packs.size() - 1) {
            this.packs.remove(i);
            this.packs.add(i + 1, pack);
        }
    }

    public List<ResourcePack> allPacks() {
        return List.copyOf(this.packs);
    }

    /** Highest-priority-first lookup. */
    public Optional<byte[]> getResource(String namespace, String path) {
        for (int i = this.packs.size() - 1; i >= 0; i--) {
            byte[] data = this.packs.get(i).read(namespace, path);
            if (data != null) return Optional.of(data);
        }
        return Optional.empty();
    }

    public Optional<byte[]> getResource(String path) {
        return this.getResource("minecraft", path);
    }

    /** All resource stacks for a path across all packs, highest priority first (for override logic). */
    public List<byte[]> getResourceStack(String namespace, String path) {
        List<byte[]> result = new ArrayList<>();
        for (int i = this.packs.size() - 1; i >= 0; i--) {
            byte[] data = this.packs.get(i).read(namespace, path);
            if (data != null) result.add(data);
        }
        return result;
    }

    public boolean resourceExists(String namespace, String path) {
        for (ResourcePack pack : this.packs) {
            if (pack.exists(namespace, path)) return true;
        }
        return false;
    }

    /** Union listing across packs (namespace, relPath under dir). Later packs' entries listed first. */
    public List<String[]> listResources(String dir, String suffix) {
        // collect with dedupe, later packs win on duplicates
        var seen = new java.util.LinkedHashSet<String>();
        List<String[]> result = new ArrayList<>();
        for (int i = this.packs.size() - 1; i >= 0; i--) {
            for (String[] pair : this.packs.get(i).listAllNamespaced(dir, suffix)) {
                String key = pair[0] + ":" + pair[1];
                if (seen.add(key)) result.add(pair);
            }
        }
        return result;
    }

    public List<String> listNamespaces() {
        var set = new java.util.LinkedHashSet<String>();
        for (ResourcePack pack : this.packs) set.addAll(pack.namespaces());
        return List.copyOf(set);
    }

    public Path vanillaRoot() {
        return this.vanillaRoot;
    }

    @Override
    public void close() {
        for (ResourcePack pack : this.packs) pack.close();
        this.packs.clear();
    }
}
