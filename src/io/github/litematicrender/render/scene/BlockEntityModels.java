package io.github.litematicrender.render.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry for blocks the game draws in code rather than through the model system.
 *
 * <p>A few blocks ship a blockstate model containing only a particle texture and no elements:
 * chests, shulker boxes and skulls. The model pipeline produces nothing for them, so they vanish
 * from a render unless built here. {@code DiagModelless} finds them by that symptom, which keeps
 * this list tied to evidence rather than to guesswork — hoppers, furnaces, dispensers, droppers,
 * crafters, brewing stands, jukeboxes, comparators, chiseled bookshelves, beacons, enchanting
 * tables and bells all resolve to real models and are deliberately absent.
 *
 * <p>Every coordinate below is copied from the vanilla renderer that defines the shape, and the
 * per-face UV rectangles follow {@code ModelPart.Cube}, which unwraps a box around its texture
 * origin as
 * <pre>
 *   u0 = ox            u1 = ox+d          u2 = ox+d+w        u22 = ox+d+2w      u3 = ox+d+w+d   u4 = ox+d+w+d+w
 *   v0 = oy            v1 = oy+d          v2 = oy+d+h
 *   DOWN  u1..u2  v0..v1     UP    u2..u22 v1..v0
 *   WEST  u0..u1  v1..v2     NORTH u1..u2  v1..v2
 *   EAST  u2..u3  v1..v2     SOUTH u3..u4  v1..v2
 * </pre>
 * with {@code d}, {@code w}, {@code h} the box depth, width and height.
 */
public final class BlockEntityModels {
    private BlockEntityModels() {}

    /**
     * A box in 0..16 model space plus the texture it unwraps from.
     *
     * @param from    minimum corner
     * @param to      maximum corner
     * @param originX texture origin x, in texture pixels
     * @param originY texture origin y
     * @param texW    texture width used to normalise UVs (64 for entity atlases)
     * @param texH    texture height
     * @param sprite  sprite id holding that texture
     */
    public record Box(float[] from, float[] to, int originX, int originY,
                      int texW, int texH, String sprite) {
        public float width() { return to[0] - from[0]; }
        public float height() { return to[1] - from[1]; }
        public float depth() { return to[2] - from[2]; }
    }

    /**
     * A chest, from {@code ChestModel}'s three layer definitions.
     *
     * <p>Vanilla declares one single-width layer and two half-width ones for the halves of a double
     * chest. The halves span 15/16 of the block — the missing sixteenth is the seam — and each omits
     * the face that touches its neighbour, which is why a naive two-full-chests approximation shows
     * doubled textures along the seam: both halves drew a full-width lid over the same space.
     *
     * <p>The vanilla {@code PartPose} offsets are folded into the coordinates here, so the caller
     * sees plain block-space boxes. The lid is modelled closed, which is what a schematic needs: the
     * open state lives in block entity data, not the blockstate.
     *
     * @param chestType one of {@code single}, {@code left}, {@code right} — the blockstate's
     *                  {@code type} property
     */
    public static List<Box> chest(String chestType, String sprite) {
        List<Box> boxes = new ArrayList<>(3);
        // Older schematics omit the type property entirely; treat that as a single chest rather
        // than failing, since a null here is data variation and not a caller error.
        switch (chestType == null ? "single" : chestType) {
            // createDoubleBodyLeftLayer: visibleFaces = all except WEST, boxes span x 0..15.
            case "left" -> {
                boxes.add(new Box(new float[]{0, 0, 1}, new float[]{15, 10, 15}, 0, 19, 64, 64, sprite));
                boxes.add(new Box(new float[]{0, 9, 1}, new float[]{15, 14, 15}, 0, 0, 64, 64, sprite));
                boxes.add(new Box(new float[]{0, 7, 15}, new float[]{1, 11, 16}, 0, 0, 64, 64, sprite));
            }
            // createDoubleBodyRightLayer: visibleFaces = all except EAST, boxes span x 1..16.
            case "right" -> {
                boxes.add(new Box(new float[]{1, 0, 1}, new float[]{16, 10, 15}, 0, 19, 64, 64, sprite));
                boxes.add(new Box(new float[]{1, 9, 1}, new float[]{16, 14, 15}, 0, 0, 64, 64, sprite));
                boxes.add(new Box(new float[]{15, 7, 15}, new float[]{16, 11, 16}, 0, 0, 64, 64, sprite));
            }
            // createSingleBodyLayer: the ordinary one-block chest.
            default -> {
                boxes.add(new Box(new float[]{1, 0, 1}, new float[]{15, 10, 15}, 0, 19, 64, 64, sprite));
                boxes.add(new Box(new float[]{1, 9, 1}, new float[]{15, 14, 15}, 0, 0, 64, 64, sprite));
                boxes.add(new Box(new float[]{7, 7, 15}, new float[]{9, 11, 16}, 0, 0, 64, 64, sprite));
            }
        }
        return boxes;
    }

    /** The shulker box: a body and a lid that overhangs it by a pixel on each side. */
    public static List<Box> shulkerBox(String sprite) {
        List<Box> boxes = new ArrayList<>(2);
        boxes.add(new Box(new float[]{1, 0, 1}, new float[]{15, 10, 15}, 0, 28, 64, 64, sprite));
        boxes.add(new Box(new float[]{0, 10, 0}, new float[]{16, 16, 16}, 0, 0, 64, 64, sprite));
        return boxes;
    }

    /**
     * A skull or head: an 8x8x8 cube on the block floor, inset to 4..12 like the game.
     *
     * <p>Heads use a 64x32 texture and the same unwrap, so the caller passes the right sprite.
     */
    public static List<Box> skull(String sprite) {
        List<Box> boxes = new ArrayList<>(1);
        boxes.add(new Box(new float[]{4, 0, 4}, new float[]{12, 8, 12}, 0, 0, 64, 32, sprite));
        return boxes;
    }
}