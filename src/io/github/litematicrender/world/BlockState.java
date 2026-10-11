package io.github.litematicrender.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable block state: block id + per-property value indices.
 * Mirrors the semantics of net.minecraft.world.level.block.state.BlockState but is a pure data object.
 */
public final class BlockState {
    public final String blockId;                       // "minecraft:oak_planks"
    public final BlockProperty[] properties;           // parallel to propertyValues
    public final int[] propertyValues;                 // index into each BlockProperty

    private static final BlockState AIR = new BlockState("minecraft:air", new BlockProperty[0], new int[0]);

    public BlockState(String blockId, BlockProperty[] properties, int[] propertyValues) {
        this.blockId = blockId;
        this.properties = properties;
        this.propertyValues = propertyValues;
    }

    public static BlockState air() { return AIR; }

    public boolean isAir() {
        return this.blockId.equals("minecraft:air")
            || this.blockId.equals("minecraft:cave_air")
            || this.blockId.equals("minecraft:void_air");
    }

    /** Returns value string of a property, or null if the state doesn't have it. */
    public String getValue(String propertyName) {
        for (int i = 0; i < this.properties.length; i++) {
            if (this.properties[i].name().equals(propertyName)) {
                return this.properties[i].valueName(this.propertyValues[i]);
            }
        }
        return null;
    }

    /** Returns a new state with one property replaced; throws if unknown property/value. */
    public BlockState with(String propertyName, String value) {
        for (int i = 0; i < this.properties.length; i++) {
            if (this.properties[i].name().equals(propertyName)) {
                Integer idx = this.properties[i].indexOf(value).orElseThrow(
                    () -> new IllegalArgumentException("Invalid value " + value + " for property " + propertyName + " on " + this.blockId));
                int[] newValues = this.propertyValues.clone();
                newValues[i] = idx;
                return new BlockState(this.blockId, this.properties, newValues);
            }
        }
        throw new IllegalArgumentException("Unknown property " + propertyName + " on " + this.blockId);
    }

    /** Like {@link #with} but silently ignores unknown property/value (used by data-driven rotation code). */
    public BlockState tryWith(String propertyName, String value) {
        try {
            return this.with(propertyName, value);
        } catch (IllegalArgumentException e) {
            return this;
        }
    }

    /** "[facing=north,lit=true]" part, without the block id. */
    public String propertyString() {
        if (this.properties.length == 0) return "";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < this.properties.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(this.properties[i].name()).append('=').append(this.properties[i].valueName(this.propertyValues[i]));
        }
        return sb.append(']').toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockState other)) return false;
        if (!this.blockId.equals(other.blockId)) return false;
        if (this.properties.length != other.properties.length) return false;
        for (int i = 0; i < this.properties.length; i++) {
            if (!this.properties[i].name().equals(other.properties[i].name())) return false;
            if (this.propertyValues[i] != other.propertyValues[i]) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = this.blockId.hashCode();
        for (int v : this.propertyValues) h = 31 * h + v;
        return h;
    }

    @Override
    public String toString() {
        return this.blockId + this.propertyString();
    }
}
