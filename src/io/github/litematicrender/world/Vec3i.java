package io.github.litematicrender.world;

/**
 * Vec3i replacement (pure data). Mirrors net.minecraft.core.Vec3i.
 */
public record Vec3i(int x, int y, int z) {
    public static final Vec3i ZERO = new Vec3i(0, 0, 0);

    public Vec3i offset(Vec3i other) {
        return new Vec3i(this.x + other.x, this.y + other.y, this.z + other.z);
    }

    public long volume() {
        return Math.abs((long) this.x * this.y * this.z);
    }
}
