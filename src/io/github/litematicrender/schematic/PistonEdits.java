package io.github.litematicrender.schematic;

import io.github.litematicrender.nbt.NbtCompound;
import io.github.litematicrender.world.Vec3i;
import java.util.*;

/** Keeps edited piston bases and their separate head cells in one atomic change. */
public final class PistonEdits {
    private PistonEdits() {}

    private static String id(NbtCompound state) { return state.getStringOrDefault("Name", "minecraft:air"); }
    private static String property(NbtCompound state, String name, String fallback) {
        return state.getCompoundOrDefault("Properties", new NbtCompound()).getStringOrDefault(name, fallback);
    }
    private static String facing(NbtCompound state) { return property(state, "facing", "north"); }
    private static boolean extended(NbtCompound state) {
        return Set.of("minecraft:piston", "minecraft:sticky_piston").contains(id(state))
            && property(state, "extended", "false").equals("true");
    }
    private static String type(NbtCompound base) { return id(base).equals("minecraft:sticky_piston") ? "sticky" : "normal"; }
    private static boolean fittingHead(NbtCompound head, NbtCompound base) {
        return extended(base) && id(head).equals("minecraft:piston_head")
            && facing(head).equals(facing(base)) && property(head, "type", "normal").equals(type(base));
    }
    private static boolean sameHead(NbtCompound before, NbtCompound after) {
        return id(after).equals("minecraft:piston_head") && facing(before).equals(facing(after))
            && property(before, "type", "normal").equals(property(after, "type", "normal"));
    }
    private static boolean air(NbtCompound state) {
        return Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air").contains(id(state));
    }
    private static NbtCompound empty() { return new NbtCompound().putString("Name", "minecraft:air"); }
    private static NbtCompound head(NbtCompound base) {
        return new NbtCompound().putString("Name", "minecraft:piston_head").put("Properties",
            new NbtCompound().putString("facing", facing(base)).putString("type", type(base)).putString("short", "false"));
    }
    private static Vec3i offset(Vec3i pos, String direction, int sign) {
        Vec3i delta = switch (direction) {
            case "north" -> new Vec3i(0, 0, -1); case "south" -> new Vec3i(0, 0, 1);
            case "east" -> new Vec3i(1, 0, 0); case "west" -> new Vec3i(-1, 0, 0);
            case "up" -> new Vec3i(0, 1, 0); case "down" -> new Vec3i(0, -1, 0);
            default -> throw new IllegalArgumentException("无效活塞朝向：" + direction);
        };
        Vec3i result = new Vec3i(pos.x() + sign * delta.x(), pos.y() + sign * delta.y(), pos.z() + sign * delta.z());
        if (Math.abs((long)result.x()) > 30000000 || Math.abs((long)result.y()) > 30000000 || Math.abs((long)result.z()) > 30000000)
            throw new IllegalArgumentException("活塞联动位置超出坐标范围");
        return result;
    }
    private static NbtCompound at(ProjectionEdits edits, Vec3i pos) { return edits.state(new int[]{pos.x(), pos.y(), pos.z()}); }

    public static LinkedHashMap<Vec3i, NbtCompound> plan(ProjectionEdits edits, Map<Vec3i, NbtCompound> requested) {
        var result = new LinkedHashMap<>(requested);
        // Retraction, rotation, replacement and deletion release only the old matching head.
        for (var entry : requested.entrySet()) {
            Vec3i pos = entry.getKey(); var before = at(edits, pos); var after = entry.getValue();
            if (extended(before) && !(extended(after) && id(before).equals(id(after)) && facing(before).equals(facing(after)))) {
                Vec3i previousHead = offset(pos, facing(before), 1);
                if (!requested.containsKey(previousHead) && fittingHead(at(edits, previousHead), before)) result.put(previousHead, empty());
            }
            // Vanilla head removal also removes its matching base; unrelated neighbors survive.
            if (id(before).equals("minecraft:piston_head") && !sameHead(before, after)) {
                Vec3i base = offset(pos, facing(before), -1);
                if (!requested.containsKey(base) && fittingHead(before, at(edits, base))) result.put(base, empty());
            }
        }
        // Use the final batch state so an explicitly cleared cell can receive a new head.
        for (var entry : new ArrayList<>(result.entrySet())) {
            var base = entry.getValue(); if (!extended(base)) continue;
            Vec3i target = offset(entry.getKey(), facing(base), 1);
            var occupant = result.containsKey(target) ? result.get(target) : at(edits, target);
            if (!air(occupant) && !fittingHead(occupant, base))
                throw new IllegalArgumentException("活塞伸出位置被占用：" + target.x() + ", " + target.y() + ", " + target.z() + "（" + id(occupant) + "）");
            result.put(target, head(base));
        }
        return result;
    }
}
