package io.github.litematicrender.render;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.render.raster.Camera;
import io.github.litematicrender.render.raster.Rasterizer;
import io.github.litematicrender.render.scene.SceneBuilder;
import io.github.litematicrender.render.scene.SceneMesh;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Schematic → Image pipeline: scene build + camera fit + rasterize.
 */
public final class SchematicRenderer {
    private SchematicRenderer() {}

    /**
     * Renders a scene that has already been built.
     *
     * <p>Building is camera-independent, so dragging should call this with a cached mesh: quickcraft
     * does the same by keeping static geometry resident and only changing the model-view matrix per
     * frame (its {@code drawMesh} fits drags without touching the mesh).
     */
    public static Image render(LitematicaSchematic schematic, SceneMesh mesh,
                               Colormaps colormaps, SpriteAtlas atlas, RenderSettings settings) {
        Camera cam = new Camera();
        cam.rotationDeg = settings.rotationDeg;
        cam.slantDeg = settings.slantDeg;
        fitCameraToMesh(cam, mesh, settings.width, settings.height);
        cam.zoom *= settings.previewZoom;
        cam.offsetX *= settings.previewZoom;
        cam.offsetY *= settings.previewZoom;
        cam.update();
        return renderWithCamera(mesh, atlas, settings, cam);
    }

    public static Image render(LitematicaSchematic schematic,
                               ModelBaker baker,
                               BlockRegistry registry,
                               Colormaps colormaps,
                               SpriteAtlas atlas,
                               RenderSettings settings) {
        return render(schematic,buildScene(schematic,baker,registry,colormaps,atlas,settings.realLighting,settings.lightingEnabled),colormaps,atlas,settings);
    }

    public static SceneMesh buildScene(LitematicaSchematic schematic, ModelBaker baker,
                                      BlockRegistry registry, Colormaps colormaps, SpriteAtlas atlas) {
        return buildScene(schematic,baker,registry,colormaps,atlas,false);
    }

    public static SceneMesh buildScene(LitematicaSchematic schematic, ModelBaker baker,
                                      BlockRegistry registry, Colormaps colormaps, SpriteAtlas atlas, boolean realLighting) {
        return buildScene(schematic,baker,registry,colormaps,atlas,realLighting,true);
    }
    public static SceneMesh buildScene(LitematicaSchematic schematic,ModelBaker baker,BlockRegistry registry,Colormaps colormaps,SpriteAtlas atlas,boolean realLighting,boolean lightingEnabled) {
        SceneBuilder builder = new SceneBuilder(registry, baker, colormaps, atlas).realLighting(realLighting).lightingEnabled(lightingEnabled);
        for (var e : schematic.regions().entrySet()) {
            var r = e.getValue();
            var origin=r.blockOrigin();builder.addRegion(r, new int[]{origin.x(),origin.y(),origin.z()});
        }
        return builder.build();
    }

    /**
     * Scales a preview down while the user is dragging, so feedback keeps up with the cursor.
     *
     * <p>quickcraft gets a responsive drag by keeping geometry resident on the GPU and changing
     * only the model-view matrix — its {@code drawMesh} fits drags without ever rebuilding. With a
     * CPU rasteriser the cheapest equivalent knob is pixel count, because rasterize cost is
     * dominated by fill: a half-size render is roughly four times cheaper. The full-resolution
     * render replaces it once the drag stops.
     *
     * @param maxEdge longest edge allowed while interacting; {@code <= 0} means full size
     */
    public static RenderSettings interactiveScale(RenderSettings settings, int maxEdge) {
        if (maxEdge <= 0) return settings;
        int edge = Math.max(settings.width, settings.height);
        if (edge <= maxEdge) return settings;
        double f = (double) maxEdge / edge;
        RenderSettings s = new RenderSettings(settings.rotationDeg, settings.slantDeg,
            Math.max(1, (int) Math.round(settings.width * f)),
            Math.max(1, (int) Math.round(settings.height * f)));
        s.transparentBackground = settings.transparentBackground;
        s.backgroundColor = settings.backgroundColor;
        s.backgroundMode = settings.backgroundMode;
        s.backgroundImage = settings.backgroundImage;
        s.checkerSize = settings.checkerSize;
        s.ambientShadeScale = settings.ambientShadeScale;
        s.realLighting = settings.realLighting;
        s.lightingEnabled = settings.lightingEnabled;
        s.previewZoom = settings.previewZoom;
        s.animationTick = settings.animationTick;
        return s;
    }

    /** Renders with an already fitted camera, without changing its center, scale or offsets. */
    public static Image renderWithCamera(SceneMesh mesh, SpriteAtlas atlas,
                                         RenderSettings settings, Camera cam) {
        // Animated textures (water, lava, fire, portal, …) live in the atlas, so pick the frame for
        // the requested tick before rasterizing. Geometry and UVs are unaffected — only pixels move.
        atlas.rebakeAtTick(settings.animationTick);

        Rasterizer raster = new Rasterizer(settings.width, settings.height, cam, atlas);
        raster.clear(RenderBackground.isTransparent(settings) ? 0 : (settings.backgroundColor | 0xFF000000));
        RenderBackground.fill(raster.color, settings);

        // Opaque passes first (depth-tested, order independent). Cutout writes depth for its opaque
        // texels, which is what hides translucent geometry behind a solid wall.
        for (SceneMesh.MeshQuad q : mesh.solid) draw(raster, q, settings, false);
        for (SceneMesh.MeshQuad q : mesh.cutout) draw(raster, q, settings, false);
        raster.writeColor=false;
        for(SceneMesh.MeshQuad q:mesh.waterMask)draw(raster,q,settings,false);
        raster.writeColor=true;

        // Sort all blending materials together; fixed material passes reverse the order at some angles.
        if(mesh.translucentBsp!=null) {
            double[] origin=new double[3],point=new double[3],towardEye=new double[3];cam.toView(0,0,0,origin);
            for(int axis=0;axis<3;axis++){cam.toView(axis==0?1:0,axis==1?1:0,axis==2?1:0,point);towardEye[axis]=point[2]-origin[2];}
            mesh.translucentBsp.visit(towardEye,true,q->draw(raster,q,settings,true));
        } else {
            List<SceneMesh.MeshQuad> blended = new ArrayList<>(mesh.portalSorted);
            blended.addAll(mesh.fluidSorted);blended.addAll(mesh.translucentSorted);drawSorted(raster,blended,cam,settings);
        }

        return raster.color;
    }

    /**
     * Sorts one translucent bucket back-to-front and rasterizes it.
     *
     * <p>The list arrives pre-subdivided from {@link SceneBuilder}, so this only reorders it — the
     * sort is the one part genuinely tied to the camera. A copy is taken because sorting the cached
     * list in place is fine but the comparator reads the camera, and concurrent frames would race.
     */
    private static void drawSorted(Rasterizer raster, List<SceneMesh.MeshQuad> quads,
                                   Camera cam, RenderSettings settings) {
        if (quads.isEmpty()) return;
        List<SceneMesh.MeshQuad> sorted = new ArrayList<>(quads);
        sorted.sort(Comparator.comparingDouble(q -> cam.viewDepth(q.cx, q.cy, q.cz)));
        for (SceneMesh.MeshQuad q : sorted) draw(raster, q, settings, true);
    }

    private static void draw(Rasterizer r, SceneMesh.MeshQuad q, RenderSettings s, boolean translucent) {
        int[] colors=q.vertexColors;
        if(colors!=null&&s.ambientShadeScale!=1){colors=colors.clone();for(int i=0;i<4;i++){int value=0;for(int shift=0;shift<=16;shift+=8)value|=Math.round(Math.max(0,Math.min(255,((colors[i]>>shift)&255)*s.ambientShadeScale)))<<shift;colors[i]=value;}}
        r.drawQuad(q.x0, q.y0, q.z0, q.x1, q.y1, q.z1, q.x2, q.y2, q.z2, q.x3, q.y3, q.z3,
            q.u0, q.v0, q.u1, q.v1, q.u2, q.v2, q.u3, q.v3,
            q.spriteId, q.rgb, q.image, q.tintR, q.tintG, q.tintB,
            q.shade * s.ambientShadeScale, q.ao, translucent, q.doubleSided, colors);
    }

    /** Fits every yaw at once, so a full orbit has one fixed center and orthographic scale. */
    public static Camera fitRotationCamera(SceneMesh mesh, RenderSettings settings) {
        Camera cam = new Camera();
        cam.rotationDeg = settings.rotationDeg;
        cam.slantDeg = settings.slantDeg;
        double[] bounds = meshBounds(mesh);
        if (bounds != null) {
            cam.centerX = (bounds[0] + bounds[3]) / 2;
            cam.centerY = (bounds[1] + bounds[4]) / 2;
            cam.centerZ = (bounds[2] + bounds[5]) / 2;
            double radius = 0;
            for (var layer : List.of(mesh.solid, mesh.cutout, mesh.translucent, mesh.portal, mesh.fluid)) {
                for (var q : layer) {
                    if (q.decorativeEffect()) continue;
                    radius = Math.max(radius, Math.hypot(q.x0 - cam.centerX, q.z0 - cam.centerZ));
                    radius = Math.max(radius, Math.hypot(q.x1 - cam.centerX, q.z1 - cam.centerZ));
                    radius = Math.max(radius, Math.hypot(q.x2 - cam.centerX, q.z2 - cam.centerZ));
                    radius = Math.max(radius, Math.hypot(q.x3 - cam.centerX, q.z3 - cam.centerZ));
                }
            }
            double pitch = Math.toRadians(cam.slantDeg);
            double halfHeight = (bounds[4] - bounds[1]) / 2;
            double spanX = Math.max(1e-3, 2 * radius);
            double spanY = Math.max(1e-3, 2 * (Math.abs(Math.cos(pitch)) * halfHeight + Math.abs(Math.sin(pitch)) * radius));
            cam.zoom = (float)(Math.min(settings.width / spanX, settings.height / spanY) * 0.92 * settings.previewZoom);
        }
        cam.update();
        return cam;
    }

    private static double[] meshBounds(SceneMesh mesh) {
        double minXw = Double.MAX_VALUE, maxXw = -Double.MAX_VALUE;
        double minYw = Double.MAX_VALUE, maxYw = -Double.MAX_VALUE;
        double minZw = Double.MAX_VALUE, maxZw = -Double.MAX_VALUE;
        for (var layer : List.of(mesh.solid, mesh.cutout, mesh.translucent, mesh.portal, mesh.fluid)) {
            for (SceneMesh.MeshQuad q : layer) {
                if(q.decorativeEffect())continue;
                minXw = Math.min(minXw, Math.min(Math.min(q.x0, q.x1), Math.min(q.x2, q.x3)));
                maxXw = Math.max(maxXw, Math.max(Math.max(q.x0, q.x1), Math.max(q.x2, q.x3)));
                minYw = Math.min(minYw, Math.min(Math.min(q.y0, q.y1), Math.min(q.y2, q.y3)));
                maxYw = Math.max(maxYw, Math.max(Math.max(q.y0, q.y1), Math.max(q.y2, q.y3)));
                minZw = Math.min(minZw, Math.min(Math.min(q.z0, q.z1), Math.min(q.z2, q.z3)));
                maxZw = Math.max(maxZw, Math.max(Math.max(q.z0, q.z1), Math.max(q.z2, q.z3)));
            }
        }
        return minXw == Double.MAX_VALUE ? null : new double[]{minXw, minYw, minZw, maxXw, maxYw, maxZw};
    }

    /** Positions the camera so the mesh bounding box fits the viewport. */
    public static void fitCameraToMesh(Camera cam, SceneMesh mesh, int width, int height) {
        double[] bounds = meshBounds(mesh);
        if (bounds == null) {
            cam.centerX = cam.centerY = cam.centerZ = 0;
            cam.zoom = 1;
            cam.offsetX = cam.offsetY = 0;
            return;
        }
        double minXw=bounds[0], minYw=bounds[1], minZw=bounds[2], maxXw=bounds[3], maxYw=bounds[4], maxZw=bounds[5];
        cam.centerX = (minXw + maxXw) / 2.0;
        cam.centerY = (minYw + maxYw) / 2.0;
        cam.centerZ = (minZw + maxZw) / 2.0;
        cam.update();

        double dx = (maxXw - minXw) / 2.0, dy = (maxYw - minYw) / 2.0, dz = (maxZw - minZw) / 2.0;
        double[] v = new double[3];
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int cx = 0; cx <= 1; cx++) for (int cy = 0; cy <= 1; cy++) for (int cz = 0; cz <= 1; cz++) {
            cam.toView(cam.centerX + (cx == 0 ? -dx : dx), cam.centerY + (cy == 0 ? -dy : dy), cam.centerZ + (cz == 0 ? -dz : dz), v);
            minX = Math.min(minX, v[0]); maxX = Math.max(maxX, v[0]);
            minY = Math.min(minY, v[1]); maxY = Math.max(maxY, v[1]);
        }
        double spanX = Math.max(1e-3, maxX - minX), spanY = Math.max(1e-3, maxY - minY);
        double fit = Math.min(width / spanX, height / spanY) * 0.92;
        cam.zoom = (float) fit;
        cam.offsetX = (float) (-(minX + maxX) / 2.0 * fit);
        cam.offsetY = (float) (-(minY + maxY) / 2.0 * fit);
    }
}
