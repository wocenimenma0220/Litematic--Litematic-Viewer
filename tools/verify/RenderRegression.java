package io.github.litematicrender.schematic;

import io.github.litematicrender.render.*;
import io.github.litematicrender.render.raster.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.world.*;
import java.nio.file.Path;
import java.util.*;

public class RenderRegression {
    static BlockRegistry registry;
    static ModelBaker baker;
    static Colormaps colors;
    static SpriteAtlas atlas;
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static SceneMesh scene(BlockState... cells) {
        long[] storage = new long[(cells.length * 8 + 63) / 64];
        BlockState[] palette = new BlockState[cells.length + 1];
        palette[0] = BlockState.air();
        for (int i = 0; i < cells.length; i++) {
            palette[i + 1] = cells[i];
            storage[i / 8] |= (long)(i + 1) << ((i % 8) * 8);
        }
        var blocks = new LitematicaSchematic.BlockContainer(cells.length, 1, 1, 8, storage, palette);
        var region = new LitematicaSchematic.Region("fixture", Vec3i.ZERO, new Vec3i(cells.length, 1, 1), blocks, Map.of());
        var builder = new SceneBuilder(registry, baker, colors, atlas);
        builder.addRegion(region, new int[]{0, 0, 0});
        return builder.build();
    }
    static List<SceneMesh.MeshQuad> water(SceneMesh mesh) {
        return mesh.translucent.stream().filter(q -> q.spriteId.contains("water")).toList();
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        try (ResourceManager resources = CliRenderer.openResources(root.resolve("assets/vanilla"), root)) {
            registry = BlockRegistry.load(resources);
            baker = new ModelBaker(resources, registry);
            colors = new Colormaps(); colors.load(resources);
            atlas = SpriteAtlas.load(resources, "blocks");
            var dry = registry.getOrMissing("minecraft:oak_leaves").defaultState.with("waterlogged", "false");
            var wet = dry.with("waterlogged", "true");
            var water = registry.getOrMissing("minecraft:water").defaultState.with("level", "0");
            check(water(scene(dry)).isEmpty(), "dry leaves must have no fluid");
            var wetMesh = scene(wet);
            check(!wetMesh.cutout.isEmpty(), "waterlogged leaves must retain their leaf geometry");
            var wetQuads = water(wetMesh);
            check(!wetQuads.isEmpty(), "waterlogged leaves must contain water");
            float maxY = -1;
            for (var q : wetQuads) {
                for (float y : new float[]{q.y0, q.y1, q.y2, q.y3}) {
                    check(Float.isFinite(y) && y >= 0 && y <= 1, "waterlogged water escaped its cell: " + y);
                    maxY = Math.max(maxY, y);
                }
                check(!q.doubleSided, "explicit fluid front/back faces must be culled individually");
            }
            check(maxY > 0.7f, "waterlogged source surface was collapsed");
            var joined = water(scene(wet, water));
            check(joined.stream().noneMatch(q -> q.x0 == 1 && q.x1 == 1 && q.x2 == 1 && q.x3 == 1),
                "water and waterlogged leaves must share a continuous fluid");
            var overlay = water(scene(water, dry));
            check(overlay.stream().anyMatch(q -> q.spriteId.equals("minecraft:block/water_overlay")),
                "water beside leaves must use the overlay material");
            var camera = new Camera(); camera.rotationDeg = 0; camera.slantDeg = 0; camera.zoom = 8; camera.update();
            var raster = new Rasterizer(32, 32, camera, atlas);
            var image = new Image(1, 1); image.pixels()[0] = 0xFFFF0000;
            draw(raster, image, true);
            check(raster.depth[16 * 32 + 16] == Float.NEGATIVE_INFINITY,
                "opaque texels in translucent material must not write depth");
            raster.clear(0); image.pixels()[0] = 0x10FFFFFF;
            draw(raster, image, false);
            check(raster.color.pixel(16, 16) == 0, "cutout texels below the cutoff must be discarded");
            float[] light = new float[3]; LightVolume.brightness(0, light);
            check(Arrays.stream(new double[]{light[0], light[1], light[2]}).allMatch(v -> v > 0),
                "unlit geometry must preserve the ambient light floor");
            System.out.println("PASS: waterlogged height, fluid continuity, leaf overlay, face culling, depth writes, cutout, ambient light");
        }
    }
    static void draw(Rasterizer r, Image image, boolean translucent) {
        r.drawQuad(-1,-1,0, 1,-1,0, 1,1,0, -1,1,0,
            0,0, 1,0, 1,1, 0,1, null, 0, image, 1,1,1,1,null,translucent,false);
    }
}
