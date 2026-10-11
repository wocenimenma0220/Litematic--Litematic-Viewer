package io.github.litematicrender.render.raster;

/**
 * View rotation matrix + orthographic projection for the render camera.
 *
 * <p>Mirrors the matrix order used by WikiRenderer's {@code DefaultPropertyBundle.applyToViewMatrix}
 * and by quickcraft's preview: <em>rotateX(slant) then rotateY(rotation)</em>, giving
 * {@code view = RotX(slant) · RotY(rotation)}.
 *
 * <p>The screen mapping in {@code Rasterizer} is {@code sy = cy - viewY * zoom}, i.e. screen Y
 * grows downwards. A world point above the camera centre must therefore land at a
 * <em>positive</em> view Y, or the picture comes out vertically mirrored. {@code DiagCamera}
 * asserts this: the matrix must be orthonormal, right-handed (det = +1) and map +Y to +viewY.
 */
public final class Camera {
    public float rotationDeg = 45f;   // yaw around Y
    public float slantDeg = 30f;      // pitch (30 dimetric / 35.264 isometric)
    public float zoom = 1.0f;
    public float offsetX = 0, offsetY = 0;   // pan in view space (pixels)
    /** world point mapped to the screen center */
    public double centerX = 0, centerY = 0, centerZ = 0;

    private final double[] rot = new double[9];  // 3x3 row-major view rotation

    public void update() {
        double yaw = Math.toRadians(this.rotationDeg);
        double pitch = Math.toRadians(this.slantDeg);
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        // view = RotX(pitch) * RotY(yaw)
        //
        // RotX(pitch) maps world Y to view Y with +cos(pitch), so "up" stays "up"; the rasterizer
        // then flips it for screen coordinates.
        double m00 = cy,       m01 = 0,  m02 = sy;
        double m10 = sp * sy,  m11 = cp, m12 = -sp * cy;
        double m20 = -cp * sy, m21 = sp, m22 = cp * cy;
        this.rot[0] = m00; this.rot[1] = m01; this.rot[2] = m02;
        this.rot[3] = m10; this.rot[4] = m11; this.rot[5] = m12;
        this.rot[6] = m20; this.rot[7] = m21; this.rot[8] = m22;
    }

    /** world (x,y,z) -> view space relative to center */
    public void toView(double x, double y, double z, double[] out) {
        double wx = x - this.centerX, wy = y - this.centerY, wz = z - this.centerZ;
        out[0] = this.rot[0] * wx + this.rot[1] * wy + this.rot[2] * wz;
        out[1] = this.rot[3] * wx + this.rot[4] * wy + this.rot[5] * wz;
        out[2] = this.rot[6] * wx + this.rot[7] * wy + this.rot[8] * wz;
    }

    /** Depth in view space (bigger = closer to viewer). */
    public double viewDepth(double x, double y, double z) {
        double wx = x - this.centerX, wy = y - this.centerY, wz = z - this.centerZ;
        return this.rot[6] * wx + this.rot[7] * wy + this.rot[8] * wz;
    }

    /** Standard isometric-ish presets */
    public void presetIsometric() {
        this.slantDeg = 35.264f;
        this.rotationDeg = 45f;
    }

    public void presetDimetric() {
        this.slantDeg = 30f;
        this.rotationDeg = 45f;
    }
}
