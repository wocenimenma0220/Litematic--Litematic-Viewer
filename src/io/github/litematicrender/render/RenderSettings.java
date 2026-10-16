package io.github.litematicrender.render;

/**
 * Render parameters (WikiRenderer-PropertyBundle-style bundle, simplified).
 */
public final class RenderSettings {
    public static final int DEFAULT_ANIMATION_FRAMES = 480;
    public static final int DEFAULT_ANIMATION_DELAY_CS = 2;
    public static final int MAX_ANIMATION_FRAMES = 3600;
    public float rotationDeg = 45f;      // yaw
    public float slantDeg = 30f;         // pitch (30 dimetric / 35.264 isometric)
    public int width = 1024;
    public int height = 1024;
    public boolean transparentBackground = true;
    public int backgroundColor = 0x00000000;
    public String backgroundMode = "color";
    public Image backgroundImage;
    public int checkerSize = 20;
    public float ambientShadeScale = 1.0f;   // multiplier on cardinal lighting
    public boolean lightingEnabled = true;
    public boolean realLighting = false;   // opt in to 1.0.10 sky, emission and AO
    public float previewZoom = 1f;
    public double[] selectionMin,selectionMax;
    public io.github.litematicrender.render.scene.SceneMesh selectionMesh;

    /**
     * Animation tick used for animated textures, in game ticks.
     *
     * <p>A still PNG can only show one moment of water/fire/portal, so the exported image picks a
     * frame from this tick; the interactive view cycles this value to build a GIF. Tick 0 is the
     * frame Minecraft shows when the world loads.
     */
    public int animationTick = 0;

    /**
     * Caps the long edge while the user is dragging (0 = full size).
     *
     * <p>A smaller image keeps up with the cursor; the full-resolution render follows once the drag
     * ends. See {@link SchematicRenderer#interactiveScale}.
     */
    public int interactiveMaxEdge = 0;

    public RenderSettings() {}

    public RenderSettings(float rotationDeg, float slantDeg, int width, int height) {
        this.rotationDeg = rotationDeg;
        this.slantDeg = slantDeg;
        this.width = width;
        this.height = height;
    }

    public RenderSettings rotation(float deg) { this.rotationDeg = deg; return this; }
    public RenderSettings slant(float deg) { this.slantDeg = deg; return this; }
    public RenderSettings size(int w, int h) { this.width = w; this.height = h; return this; }
    public RenderSettings tick(int t) { this.animationTick = t; return this; }

    /** wiki-style isometric preset */
    public static RenderSettings isometric(int w, int h) {
        return new RenderSettings(45f, 35.264f, w, h);
    }
}
