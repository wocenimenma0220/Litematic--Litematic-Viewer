package io.github.litematicrender.serve;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.render.Colormaps;
import io.github.litematicrender.render.GifWriter;
import io.github.litematicrender.render.Image;
import io.github.litematicrender.render.MeshExporter;
import io.github.litematicrender.render.RenderDiagnostics;
import io.github.litematicrender.render.RenderSettings;
import io.github.litematicrender.render.SchematicRenderer;
import io.github.litematicrender.render.SpriteAtlas;
import io.github.litematicrender.render.scene.SceneBuilder;
import io.github.litematicrender.render.scene.SceneMesh;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.Vec3i;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Render engine: loads a resource pack once, then answers render requests.
 *
 * <p>Kept deliberately free of any transport concern — {@link JsonRpcServer} owns stdin/stdout,
 * this class owns the expensive state (resources, atlas, model baker) and the render calls.
 */
public final class RenderEngine implements AutoCloseable {
    private final Path home;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private ResourceManager resources;
    private BlockRegistry registry;
    private Colormaps colormaps;
    private SpriteAtlas atlas;
    private ModelBaker baker;
    private Path loadedPackRoot;
    private List<Path> loadedBuiltins = List.of();
    private List<String> loadedBuiltinNames = List.of();
    private List<Path> loadedUserPacks = List.of();
    private List<String> loadedUserPackNames = List.of();

    /**
     * Built geometry for the open schematic, reused across renders.
     *
     * <p>Building a scene walks every block and bakes ambient occlusion and per-face lighting; it
     * depends on the schematic and resource pack but <em>not</em> on the camera. Measured on a
     * 15k-block schematic: 32 ms to build once, versus 146 000 quads worth of work thrown away on
     * every drag. This is the CPU analogue of what the game does by uploading static geometry to
     * the GPU once and only changing the model-view matrix while dragging — see
     * {@code QuickLitematicaPreviewAccess.drawMesh}, which fits-drags without rebuilding.
     */
    private SceneMesh scene;
    private String sceneKey;
    /** True when the last render reused {@link #scene} instead of rebuilding it. */
    private boolean sceneFromMesh;

    /** Cached parse of the currently open schematic, keyed by path + mtime + size. */
    private LitematicaSchematic schematic;
    private Path schematicPath;
    private long schematicStamp;

    public RenderEngine(Path home) {
        this.home = home;
    }

    public Path home() {
        return this.home;
    }

    /** Default resource pack shipped with the app. */
    public Path defaultPackRoot() {
        return this.home.resolve("assets/vanilla");
    }

    /**
     * Packs bundled with the app, loaded on top of vanilla and below anything the user imports.
     *
     * <p>They live in {@code assets/builtin/} as standard zips (or folders) with the usual
     * {@code assets/<namespace>/...} layout. A pack only needs to cover the blocks it changes —
     * anything missing falls through to vanilla.
     */
    public List<Path> builtinPacks() {
        List<Path> out = new ArrayList<>();
        Path dir = this.home.resolve("assets/builtin");
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".zip") || Files.isDirectory(p);
                    })
                .sorted()
                .forEach(out::add);
        } catch (IOException e) {
            // a missing builtin folder is a valid configuration
        }
        return out;
    }

    /** Loads (or reloads) the resource stack: vanilla, then built-ins, then the given pack root. */
    /**
     * Loads the resource stack: vanilla, then built-in packs, then user-imported packs.
     *
     * @param packRoot vanilla root, i.e. a directory containing {@code assets/}
     * @param imports  user pack paths (zip or folder) in priority order, highest last
     */
    public synchronized Map<String, Object> loadResources(Path packRoot, List<String> imports) throws Exception {
        List<Path> builtin = builtinPacks();
        List<Path> user = imports == null ? List.of() : imports.stream().map(Path::of).toList();
        boolean same = this.resources != null && packRoot.equals(this.loadedPackRoot)
            && builtin.equals(this.loadedBuiltins) && user.equals(this.loadedUserPacks);
        if (same) {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("blockKinds", this.registry.blockCount());
            hit.put("sprites", this.atlas.spriteCount());
            hit.put("atlasWidth", this.atlas.stitched.width);
            hit.put("atlasHeight", this.atlas.stitched.height);
            hit.put("builtinPacks", this.loadedBuiltinNames);
            hit.put("importedPacks", this.loadedUserPackNames);
            hit.put("cached", true);
            return hit;
        }
        closeResources();
        ResourceManager res = new ResourceManager(packRoot);
        // Built-ins sit above vanilla, user packs above built-ins, so an import wins over a built-in.
        List<String> builtinNames = new ArrayList<>();
        for (Path p : builtin) {
            res.importPackUnchecked(p);
            builtinNames.add(p.getFileName().toString());
        }
        List<String> userNames = new ArrayList<>();
        for (Path p : user) {
            res.importPackUnchecked(p);
            userNames.add(p.getFileName().toString());
        }
        BlockRegistry reg = BlockRegistry.load(res);
        Colormaps cm = new Colormaps();
        cm.load(res);
        SpriteAtlas at = SpriteAtlas.load(res, "blocks");
        this.resources = res;
        this.registry = reg;
        this.colormaps = cm;
        this.atlas = at;
        this.baker = new ModelBaker(res, reg);
        this.loadedPackRoot = packRoot;
        this.loadedBuiltins = builtin;
        this.loadedBuiltinNames = builtinNames;
        this.loadedUserPacks = user;
        this.loadedUserPackNames = userNames;
        // A different pack invalidates any materialised schematic.
        this.schematic = null;
        this.schematicPath = null;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("blockKinds", reg.blockCount());
        out.put("sprites", at.spriteCount());
        out.put("atlasWidth", at.stitched.width);
        out.put("atlasHeight", at.stitched.height);
        out.put("builtinPacks", builtinNames);
        out.put("importedPacks", userNames);
        out.put("cached", false);
        return out;
    }

    /** Convenience form with no user imports. */
    public synchronized Map<String, Object> loadResources(Path packRoot) throws Exception {
        return loadResources(packRoot, List.of());
    }

    /** Reads a .litematic and reports its metadata; caches the parse for the next render. */
    public synchronized Map<String, Object> open(Path file) throws Exception {
        ensureResources();
        LitematicaSchematic schem = loadSchematic(file);
        LitematicaSchematic.Metadata md = schem.metadata();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", file.toAbsolutePath().toString());
        out.put("name", md.name);
        out.put("author", md.author);
        out.put("description", md.description);
        out.put("regionCount", md.regionCount);
        out.put("totalBlocks", md.totalBlocks);
        out.put("totalVolume", md.totalVolume);
        Vec3i size = md.enclosingSize;
        out.put("size", Map.of("x", size.x(), "y", size.y(), "z", size.z()));
        out.put("schematicVersion", md.schematicVersion);
        out.put("minecraftDataVersion", md.minecraftDataVersion);
        out.put("hasPreviewImage", md.previewImageData != null && md.previewImageData.length > 0);

        java.util.List<Map<String, Object>> regions = new java.util.ArrayList<>();
        for (var e : schem.regions().entrySet()) {
            var r = e.getValue();
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("name", e.getKey());
            rm.put("size", Map.of("x", r.blocks.sizeX, "y", r.blocks.sizeY, "z", r.blocks.sizeZ));
            rm.put("position", Map.of("x", r.position.x(), "y", r.position.y(), "z", r.position.z()));
            rm.put("paletteSize", r.blocks.palette.length);
            rm.put("blockEntities", r.blockEntities.size());
            regions.add(rm);
        }
        out.put("regions", regions);
        return out;
    }

    /** Renders the open schematic to a PNG. */
    public synchronized Map<String, Object> render(Path outPng, RenderSettings settings, boolean reportDiagnostics)
            throws Exception {
        ensureResources();
        if (this.schematic == null) {
            throw new IllegalStateException("no schematic open; call open first");
        }
        this.cancelled.set(false);
        RenderDiagnostics.reset();

        long t0 = System.currentTimeMillis();
        SceneMesh mesh = sceneMesh();
        // "interactive" trades resolution for latency: see SchematicRenderer.interactiveScale.
        RenderSettings effective = settings.interactiveMaxEdge > 0
            ? SchematicRenderer.interactiveScale(settings, settings.interactiveMaxEdge)
            : settings;
        Image image = SchematicRenderer.render(this.schematic, mesh, this.colormaps,
            this.atlas, effective);
        long t1 = System.currentTimeMillis();
        image.writePng(outPng);
        long t2 = System.currentTimeMillis();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", outPng.toAbsolutePath().toString());
        out.put("width", image.width);
        out.put("height", image.height);
        out.put("renderMillis", t1 - t0);
        out.put("sceneCached", this.sceneFromMesh);
        out.put("encodeMillis", t2 - t1);
        if (reportDiagnostics) {
            Map<String, Long> diag = RenderDiagnostics.snapshot();
            if (!diag.isEmpty()) out.put("unresolved", diag);
        }
        return out;
    }

    /**
     * The scene for the open schematic, rebuilt only when something it depends on changes.
     *
     * <p>The key covers everything that feeds the bake: which file is open, and which resource
     * packs are stacked. Anything camera-related (rotation, slant, size) is deliberately absent —
     * that is the whole point, since those are exactly what a drag changes.
     */
    private SceneMesh sceneMesh() {
        String key = this.schematicPath + "|" + this.loadedBuiltinNames + "|" + this.loadedUserPackNames
            + "|" + System.identityHashCode(this.baker);
        if (this.scene != null && key.equals(this.sceneKey)) {
            this.sceneFromMesh = true;
            return this.scene;
        }
        SceneBuilder builder = new SceneBuilder(this.registry, this.baker, this.colormaps, this.atlas);
        for (var e : this.schematic.regions().entrySet()) {
            var r = e.getValue();
            builder.addRegion(r, new int[]{r.position.x(), r.position.y(), r.position.z()});
        }
        this.scene = builder.build();
        this.sceneKey = key;
        this.sceneFromMesh = false;
        return this.scene;
    }

    /** Renders without writing a file; used for fast previews of a crop or a thumbnail. */
    public synchronized long renderStamp(Path outPng, RenderSettings settings) throws Exception {
        return ((Number) render(outPng, settings, false).get("renderMillis")).longValue();
    }

    /**
     * Exports the scene for the WebGL viewer: the atlas as a texture, and the mesh as a vertex
     * buffer.
     *
     * <p>Everything camera-independent is resolved here — models, lighting, ambient occlusion,
     * layer assignment, translucent subdivision — so the viewer only ever re-projects vertices.
     * That is the division of labour the game uses, and the reason quickcraft's preview drags
     * smoothly: it draws from resident buffers and changes nothing but the model-view matrix.
     */
    public synchronized Map<String, Object> exportScene(Path atlasOut, Path meshOut) throws Exception {
        ensureResources();
        if (this.schematic == null) {
            throw new IllegalStateException("no schematic open; call open first");
        }
        SceneMesh mesh = sceneMesh();
        long t0 = System.currentTimeMillis();
        this.atlas.stitched.writePng(atlasOut);
        int vertices = MeshExporter.write(mesh, this.atlas, meshOut);
        long t1 = System.currentTimeMillis();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("atlas", atlasOut.toAbsolutePath().toString());
        out.put("mesh", meshOut.toAbsolutePath().toString());
        out.put("atlasWidth", this.atlas.stitched.width);
        out.put("atlasHeight", this.atlas.stitched.height);
        out.put("vertices", vertices);
        out.put("quads", vertices / 4);
        out.put("exportMillis", t1 - t0);
        out.put("meshBytes", java.nio.file.Files.size(meshOut));
        float[] b = sceneBounds(mesh);
        out.put("min", List.of(b[0], b[1], b[2]));
        out.put("max", List.of(b[3], b[4], b[5]));
        return out;
    }

    /** Axis-aligned bounds of every quad: minX, minY, minZ, maxX, maxY, maxZ. */
    private static float[] sceneBounds(SceneMesh mesh) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (List<SceneMesh.MeshQuad> layer : List.of(mesh.solid, mesh.cutout,
                mesh.portalSorted, mesh.fluidSorted, mesh.translucentSorted)) {
            for (SceneMesh.MeshQuad q : layer) {
                minX = Math.min(minX, Math.min(Math.min(q.x0, q.x1), Math.min(q.x2, q.x3)));
                maxX = Math.max(maxX, Math.max(Math.max(q.x0, q.x1), Math.max(q.x2, q.x3)));
                minY = Math.min(minY, Math.min(Math.min(q.y0, q.y1), Math.min(q.y2, q.y3)));
                maxY = Math.max(maxY, Math.max(Math.max(q.y0, q.y1), Math.max(q.y2, q.y3)));
                minZ = Math.min(minZ, Math.min(Math.min(q.z0, q.z1), Math.min(q.z2, q.z3)));
                maxZ = Math.max(maxZ, Math.max(Math.max(q.z0, q.z1), Math.max(q.z2, q.z3)));
            }
        }
        return new float[]{minX, minY, minZ, maxX, maxY, maxZ};
    }


    /**
     * Renders an animation to an animated GIF.
     *
     * <p>Only animated textures differ between frames, so the frame count follows the pack's
     * shortest animation cycle (usually water) rather than the longest.
     *
     * @param ticks maximum frames; the actual count is capped to one full animation cycle
     */
    public synchronized Map<String, Object> renderAnimation(Path outGif, RenderSettings settings,
                                                            int ticks, int delayCs) throws Exception {
        ensureResources();
        if (this.schematic == null) {
            throw new IllegalStateException("no schematic open; call open first");
        }
        RenderDiagnostics.reset();

        int period = this.atlas.shortestAnimationPeriodTicks();
        int frameCount = Math.max(1, Math.min(ticks, period > 0 ? period : 1));

        List<Image> frames = new ArrayList<>(frameCount);
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < frameCount; i++) {
            RenderSettings s = new RenderSettings(settings.rotationDeg, settings.slantDeg,
                settings.width, settings.height);
            s.transparentBackground = settings.transparentBackground;
            s.backgroundColor = settings.backgroundColor;
            s.ambientShadeScale = settings.ambientShadeScale;
            s.animationTick = i;
            frames.add(SchematicRenderer.render(this.schematic, this.baker, this.registry,
                this.colormaps, this.atlas, s));
        }
        long t1 = System.currentTimeMillis();
        GifWriter.write(outGif, frames, delayCs);
        long t2 = System.currentTimeMillis();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", outGif.toAbsolutePath().toString());
        out.put("width", settings.width);
        out.put("height", settings.height);
        out.put("frames", frameCount);
        out.put("animationPeriodTicks", period);
        out.put("delayCentiseconds", delayCs);
        out.put("renderMillis", t1 - t0);
        out.put("encodeMillis", t2 - t1);
        out.put("bytes", java.nio.file.Files.size(outGif));
        Map<String, Long> diag = RenderDiagnostics.snapshot();
        if (!diag.isEmpty()) out.put("unresolved", diag);
        return out;
    }

    public void cancel() {
        this.cancelled.set(true);
    }

    public boolean isCancelled() {
        return this.cancelled.get();
    }

    // ------------------------------------------------------------------

    private void ensureResources() throws Exception {
        if (this.resources == null) {
            loadResources(defaultPackRoot());
        }
    }

    private LitematicaSchematic loadSchematic(Path file) throws Exception {
        long stamp = java.nio.file.Files.getLastModifiedTime(file).toMillis()
            ^ java.nio.file.Files.size(file);
        if (this.schematic != null && file.equals(this.schematicPath) && stamp == this.schematicStamp) {
            return this.schematic;
        }
        LitematicaSchematic schem = LitematicaSchematic.load(file);
        schem.materialize(this.registry);
        this.schematic = schem;
        this.schematicPath = file;
        this.schematicStamp = stamp;
        return schem;
    }

    private void closeResources() {
        if (this.resources != null) {
            this.resources.close();
            this.resources = null;
        }
        this.registry = null;
        this.colormaps = null;
        this.atlas = null;
        this.baker = null;
        this.loadedPackRoot = null;
    }

    @Override
    public synchronized void close() {
        closeResources();
    }
}
