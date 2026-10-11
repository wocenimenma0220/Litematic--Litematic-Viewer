package io.github.litematicrender.render;

import io.github.litematicrender.world.BlockState;

/**
 * Per-block and per-face tinting, ported from vanilla's {@code BlockColors.createDefault()} and
 * {@code BlockTintSources}.
 *
 * <p>Tint is selected by the model's {@code tintindex}, not by texture name. That distinction
 * matters: redstone dust's tint comes from its {@code power} property, so an unpowered wire is a
 * dark, almost black red while a powered one is bright — matching on the sprite name alone cannot
 * express that, and produced uniform bright red for every wire.
 */
public final class BlockTints {
    /**
     * Vanilla {@code BlockTintSources.constant(-10380959)} for spruce leaves: {@code 0xFF61A261}.
     */
    public static final int SPRUCE = (-10380959) & 0xFFFFFF;
    /**
     * Vanilla {@code BlockTintSources.constant(-8345771)} for birch leaves: {@code 0xFF80A755}.
     */
    public static final int BIRCH = (-8345771) & 0xFFFFFF;
    /** Vanilla {@code BlockTintSources.constant(-2046180)} for attached stems. */
    public static final int ATTACHED_STEM = (-2046180) & 0xFFFFFF;
    /** Vanilla {@code BlockColors.LILY_PAD_DEFAULT} = -9321636. */
    public static final int LILY_PAD_DEFAULT = (-9321636) & 0xFFFFFF;
    /** Vanilla {@code BlockColors.LILY_PAD_IN_WORLD} = -14647248. */
    public static final int LILY_PAD_IN_WORLD = (-14647248) & 0xFFFFFF;

    /**
     * Vanilla {@code RedstoneWireBlock.COLORS}: a 16-entry ramp from dark red (power 0) to bright
     * red (power 15), computed as
     * <pre>
     *   power = i / 15
     *   red   = power * 0.6 + (power &gt; 0 ? 0.4 : 0.3)
     *   green = clamp(power^2 * 0.7 - 0.5, 0, 1)
     *   blue  = clamp(power^2 * 0.6 - 0.7, 0, 1)
     * </pre>
     */
    private static final int[] REDSTONE_COLORS = buildRedstoneColors();

    private static int[] buildRedstoneColors() {
        int[] out = new int[16];
        for (int i = 0; i <= 15; i++) {
            float power = i / 15.0f;
            float red = power * 0.6f + (power > 0f ? 0.4f : 0.3f);
            float green = Math.max(0f, Math.min(1f, power * power * 0.7f - 0.5f));
            float blue = Math.max(0f, Math.min(1f, power * power * 0.6f - 0.7f));
            out[i] = (Math.round(red * 255f) << 16) | (Math.round(green * 255f) << 8) | Math.round(blue * 255f);
        }
        return out;
    }

    private BlockTints() {}

    /**
     * Colour for a face, or -1 when nothing tints it.
     *
     * @param layer the model's {@code tintindex}
     * @param spriteId the resolved sprite, used to tell apart blocks that share one tint layer
     *                 (all leaves use layer 0 but differ in colour)
     */
    public static int colorFor(BlockState state, String spriteId, int layer) {
        if (layer < 0) return -1;
        String id = state.blockId;

        // --- vanilla BlockTintSources.redstone(): depends on the POWER property -------------
        if (id.equals("minecraft:redstone_wire")) {
            return redstoneColor(state);
        }

        // --- grass family: one flat colour, since a schematic has no biome ------------------
        if (id.equals("minecraft:grass_block")) {
            // grassBlock(): the top face is tinted, the sides are not.
            return layer == 0 && spriteId != null && spriteId.contains("grass_block_top")
                ? Colormaps.GRASS_DEFAULT : -1;
        }
        if (id.equals("minecraft:short_grass") || id.equals("minecraft:tall_grass")
            || id.equals("minecraft:fern") || id.equals("minecraft:large_fern")
            || id.equals("minecraft:potted_fern") || id.equals("minecraft:bush")) {
            return Colormaps.GRASS_DEFAULT;
        }
        if (id.equals("minecraft:pink_petals") || id.equals("minecraft:wildflowers")) {
            return layer == 1 ? Colormaps.GRASS_DEFAULT : -1;      // BLANK_LAYER, grass()
        }

        // --- leaves: per species, matching BlockColors.register() ---------------------------
        if (id.endsWith("_leaves")) {
            return switch (id) {
                case "minecraft:spruce_leaves" -> SPRUCE;
                case "minecraft:birch_leaves" -> BIRCH;
                case "minecraft:oak_leaves", "minecraft:jungle_leaves", "minecraft:acacia_leaves",
                     "minecraft:dark_oak_leaves", "minecraft:mangrove_leaves" -> Colormaps.FOLIAGE_DEFAULT;
                default -> -1;                                     // cherry, azalea, pale oak: untinted
            };
        }
        if (id.equals("minecraft:vine") || id.equals("minecraft:leaf_litter")) {
            return Colormaps.FOLIAGE_DEFAULT;
        }
        if (id.equals("minecraft:sugar_cane")) {
            return Colormaps.GRASS_DEFAULT;
        }

        // --- water ---------------------------------------------------------------------------
        if (id.equals("minecraft:water") || id.equals("minecraft:bubble_column")
            || id.equals("minecraft:water_cauldron")) {
            return Colormaps.WATER_DEFAULT;
        }

        // --- stems: colour ramps with AGE ----------------------------------------------------
        if (id.equals("minecraft:melon_stem") || id.equals("minecraft:pumpkin_stem")) {
            return stemColor(state);
        }
        if (id.equals("minecraft:attached_melon_stem") || id.equals("minecraft:attached_pumpkin_stem")) {
            return ATTACHED_STEM;
        }
        if (id.equals("minecraft:lily_pad")) {
            return layer == 1 ? LILY_PAD_IN_WORLD : LILY_PAD_DEFAULT;
        }

        // Anything else with a tintindex (models that ask for tint but have no registered source)
        // is left untinted, which is what vanilla's BLANK_LAYER does.
        return -1;
    }

    /** Vanilla {@code RedstoneWireBlock.getColorForPower(power)}. */
    public static int redstoneColor(BlockState state) {
        String p = state.getValue("power");
        int power = 0;
        if (p != null) {
            try {
                power = Math.max(0, Math.min(15, Integer.parseInt(p)));
            } catch (NumberFormatException ignored) {
                power = 0;
            }
        }
        return REDSTONE_COLORS[power];
    }

    /** Vanilla {@code BlockTintSources.stem()}: {@code ARGB.color(age*32, 255-age*8, age*4)}. */
    private static int stemColor(BlockState state) {
        String a = state.getValue("age");
        int age = 0;
        if (a != null) {
            try {
                age = Math.max(0, Math.min(15, Integer.parseInt(a)));
            } catch (NumberFormatException ignored) {
                age = 0;
            }
        }
        int r = Math.min(255, age * 32);
        int g = Math.max(0, 255 - age * 8);
        int b = Math.min(255, age * 4);
        return (r << 16) | (g << 8) | b;
    }
}
