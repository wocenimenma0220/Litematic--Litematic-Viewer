package io.github.litematicrender.render;

/**
 * Vanilla {@code com.mojang.blaze3d.platform.Transparency}: what kinds of alpha a texture region
 * contains, which is the sole input to {@code ChunkSectionLayer.byTransparency}.
 *
 * <p>Two independent flags rather than an enum, because a sheet can be both — vanilla models that
 * as {@link #TRANSPARENT_AND_TRANSLUCENT}, and the distinction survives into the layer decision,
 * where "has translucent" wins over "has transparent".
 */
public record Transparency(boolean hasTransparent, boolean hasTranslucent) {
    public static final Transparency NONE = new Transparency(false, false);
    public static final Transparency TRANSPARENT = new Transparency(true, false);
    public static final Transparency TRANSLUCENT = new Transparency(false, true);
    public static final Transparency TRANSPARENT_AND_TRANSLUCENT = new Transparency(true, true);

    public static Transparency of(boolean hasTransparent, boolean hasTranslucent) {
        if (hasTransparent && hasTranslucent) return TRANSPARENT_AND_TRANSLUCENT;
        if (hasTransparent) return TRANSPARENT;
        return hasTranslucent ? TRANSLUCENT : NONE;
    }

    public boolean isOpaque() {
        return !this.hasTransparent && !this.hasTranslucent;
    }
}
