package io.github.litematicrender.render;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line render: schematic → PNG. All geometry and shading decisions live in
 * {@link SchematicRenderer}; this class only loads resources, drives it and reports timings.
 */
public final class CliRenderer {
    private CliRenderer() {}

    /** Result of one render, for callers that want the numbers without parsing stdout. */
    public record Result(Image image, int blockKinds, int spriteCount, int atlasWidth, int atlasHeight,
                         int solidQuads, int cutoutQuads, int translucentQuads, long loadMillis,
                         long atlasMillis, long sceneMillis, long rasterMillis) {}

    /**
     * Loads the schematic and its resource pack, renders it and writes a PNG.
     *
     * @param schemFile   .litematic file
     * @param vanillaRoot directory containing {@code assets/}
     * @param outPng      destination PNG (may be null to skip writing)
     * @param settings    camera and output settings
     * @param verbose     print progress to stdout
     */
    /**
     * Builds the resource stack the same way the GUI engine does: vanilla, then any packs found in
     * {@code assets/builtin/}. Keeps the command line and the shell rendering identical.
     */
    public static ResourceManager openResources(Path vanillaRoot, Path home) throws IOException {
        ResourceManager res = new ResourceManager(vanillaRoot);
        io.github.litematicrender.resource.BuiltinPacks.list(home).forEach(res::importPackUnchecked);
        return res;
    }

    /**
     * Renders a single frame.
     *
     * @param schemFile   .litematic file
     * @param vanillaRoot directory containing {@code assets/}
     * @param outPng      destination PNG (may be null to skip writing)
     * @param settings    camera and output settings
     * @param verbose     print progress to stdout
     */
    public static Result renderToFile(Path schemFile, Path vanillaRoot, Path outPng,
                                      RenderSettings settings, boolean verbose) throws Exception {
        // The project layout is <home>/assets/vanilla, so the home is two levels up from the
        // vanilla root; fall back to the root itself when the layout differs.
        Path home = vanillaRoot.getParent() != null && vanillaRoot.getParent().getParent() != null
            ? vanillaRoot.getParent().getParent()
            : vanillaRoot;
        return renderToFile(schemFile, vanillaRoot, home, outPng, settings, verbose);
    }

    /** Full form: {@code home} locates the built-in packs. */
    public static Result renderToFile(Path schemFile, Path vanillaRoot, Path home, Path outPng,
                                      RenderSettings settings, boolean verbose) throws Exception {
        RenderDiagnostics.reset();
        long t0 = System.currentTimeMillis();
        LitematicaSchematic schem = LitematicaSchematic.load(schemFile);
        ResourceManager resources = openResources(vanillaRoot, home);
        try {
            BlockRegistry registry = BlockRegistry.load(resources);
            schem.materialize(registry);
            long t1 = System.currentTimeMillis();

            Colormaps colormaps = new Colormaps();
            colormaps.load(resources);
            SpriteAtlas atlas = SpriteAtlas.load(resources, "blocks");
            long t2 = System.currentTimeMillis();

            ModelBaker baker = new ModelBaker(resources, registry);

            long t3 = System.currentTimeMillis();
            Image image = SchematicRenderer.render(schem, baker, registry, colormaps, atlas, settings);
            long t4 = System.currentTimeMillis();

            if (outPng != null) {
                image.writePng(outPng);
            }

            if (verbose) {
                System.out.println("load: " + (t1 - t0) + "ms, block kinds=" + registry.blockCount());
                System.out.println("atlas: " + (t2 - t1) + "ms, sprites=" + atlas.spriteCount()
                    + " " + atlas.stitched.width + "x" + atlas.stitched.height);
                System.out.println("scene+raster: " + (t4 - t2) + "ms");
                if (outPng != null) System.out.println("written: " + outPng.toAbsolutePath());
                RenderDiagnostics.report(System.out);
            }
            return new Result(image, registry.blockCount(), atlas.spriteCount(),
                atlas.stitched.width, atlas.stitched.height, 0, 0, 0,
                t1 - t0, t2 - t1, t3 - t2, t4 - t3);
        } finally {
            resources.close();
        }
    }

    /**
     * Renders an animation: the same scene at successive ticks, written as an animated GIF.
     *
     * <p>Only animated textures change between frames (geometry, camera and lighting are identical),
     * so every frame is a full re-render of the atlas contents against one already-built mesh.
     *
     * @param ticks maximum frames; used texture cycles are combined when they fit in this limit
     */
    public static void renderAnimation(Path schemFile, Path vanillaRoot, Path outGif,
                                       RenderSettings settings, int ticks, int delayCs,
                                       boolean verbose) throws Exception {
        RenderDiagnostics.reset();
        LitematicaSchematic schem = LitematicaSchematic.load(schemFile);
        Path home = vanillaRoot.getParent() != null && vanillaRoot.getParent().getParent() != null
            ? vanillaRoot.getParent().getParent() : vanillaRoot;
        ResourceManager resources = openResources(vanillaRoot, home);
        try {
            BlockRegistry registry = BlockRegistry.load(resources);
            schem.materialize(registry);
            Colormaps colormaps = new Colormaps();
            colormaps.load(resources);
            SpriteAtlas atlas = SpriteAtlas.load(resources, "blocks");
            ModelBaker baker = new ModelBaker(resources, registry);

            // A pack has animations of wildly different periods (water 64 ticks, prismarine 6000),
            // so a GIF can't loop all of them. Animate over the shortest period that actually moves,
            // which is what a viewer perceives as motion; sampling consecutive ticks of a 6000-tick
            // cycle would look frozen.
            var mesh=SchematicRenderer.buildScene(schem,baker,registry,colormaps,atlas,settings.realLighting,settings.lightingEnabled);
            var cycle=atlas.animationCycle(mesh,ticks);
            int frameCount=cycle.frames();
            if (verbose) {
                System.out.println("scene animation: " + frameCount + " frames, complete cycle="+cycle.complete());
            }

            long t0 = System.currentTimeMillis();
            GifWriter.writeFrames(outGif,frameCount,delayCs,i -> {
                RenderSettings s = new RenderSettings(settings.rotationDeg, settings.slantDeg,
                    settings.width, settings.height);
                s.transparentBackground = settings.transparentBackground;
                s.backgroundColor = settings.backgroundColor;
                s.backgroundMode = settings.backgroundMode;
                s.backgroundImage = settings.backgroundImage;
                s.checkerSize = settings.checkerSize;
                s.ambientShadeScale = settings.ambientShadeScale;
                s.animationTick = i;
                return SchematicRenderer.render(schem,mesh,colormaps,atlas,s);
            });
            long t2 = System.currentTimeMillis();

            if (verbose) {
                System.out.println("rendered and encoded " + frameCount + " frames in " + (t2 - t0) + "ms");
                System.out.println("written: " + outGif.toAbsolutePath());
                RenderDiagnostics.report(System.out);
            }
        } finally {
            resources.close();
        }
    }

    /**
     * Command-line entry point used by {@code Main --render} and by {@code LitematicRender.exe --cli}:
     * {@code <file.litematic> <assets/vanilla> <out.png> [width] [height] [rotation] [slant]}.
     *
     * <p>The camera angles are positional rather than flags so the historical width/height
     * invocation keeps working unchanged; both default to the dimetric view the GUI opens on.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: CliRenderer <file.litematic> <assets/vanilla> <out.png> "
                + "[width] [height] [rotationDeg] [slantDeg]");
            System.exit(2);
        }
        int width = args.length > 3 ? Integer.parseInt(args[3]) : 1024;
        int height = args.length > 4 ? Integer.parseInt(args[4]) : 1024;
        float rotation = args.length > 5 ? Float.parseFloat(args[5]) : 45f;
        float slant = args.length > 6 ? Float.parseFloat(args[6]) : 30f;
        RenderSettings settings = new RenderSettings(rotation, slant, width, height);
        renderToFile(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), settings, true);
    }
}
