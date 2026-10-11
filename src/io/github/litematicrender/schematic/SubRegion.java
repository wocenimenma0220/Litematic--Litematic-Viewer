package io.github.litematicrender.schematic;

import io.github.litematicrender.nbt.NbtCompound;
import io.github.litematicrender.world.Vec3i;

/**
 * Sub-region of a schematic: relative position + size.
 */
public final class SubRegion {
    public final String name;
    public final Vec3i position;   // relative Position of the region (from NBT "Position")
    public final Vec3i size;       // Size (can contain negative components in some files)

    public SubRegion(String name, Vec3i position, Vec3i size) {
        this.name = name;
        this.position = position;
        this.size = size;
    }

    public Vec3i absSize() {
        return new Vec3i(Math.abs(this.size.x()), Math.abs(this.size.y()), Math.abs(this.size.z()));
    }

    @Override
    public String toString() {
        return "SubRegion[" + this.name + " pos=" + this.position + " size=" + this.size + "]";
    }

    public static Vec3i readBlockPos(NbtCompound tag, String key) {
        NbtCompound pos = tag.getCompound(key).orElse(null);
        if (pos == null) return Vec3i.ZERO;
        return new Vec3i(pos.getIntOrDefault("x", 0), pos.getIntOrDefault("y", 0), pos.getIntOrDefault("z", 0));
    }
}
