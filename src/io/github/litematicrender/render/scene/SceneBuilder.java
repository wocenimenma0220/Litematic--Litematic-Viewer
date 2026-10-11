package io.github.litematicrender.render.scene;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.model.Quad;
import io.github.litematicrender.render.Colormaps;
import io.github.litematicrender.render.SpriteAtlas;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.BlockShape;
import io.github.litematicrender.world.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a SceneMesh from a materialized schematic, mirroring vanilla
 * {@code ModelBlockRenderer.tesselateBlock} + {@code BlockModelLighter} + {@code FluidRenderer}.
 *
 * <p>Shading model (matching vanilla, so the output looks like the game):
 * <ul>
 *   <li><b>Ambient occlusion</b> — per vertex, {@code (side1 + side2 + corner + center) / 4},
 *       from {@code BlockModelLighter.prepareQuadAmbientOcclusion};</li>
 *   <li><b>Cardinal light</b> — {@code CardinalLighting.DEFAULT} per face
 *       ({@code down 0.5, up 1.0, north/south 0.8, west/east 0.6}), applied to the whole quad;</li>
 *   <li><b>Fluids</b> — per-corner height field from {@code FluidRenderer.getHeight} /
 *       {@code calculateAverageHeight}, not a flat 14/16 slab.</li>
 * </ul>
 */
public final class SceneBuilder {
    /** CardinalLighting.DEFAULT: down 0.5 / up 1.0 / north,south 0.8 / west,east 0.6. */
    public static final float[] CARDINAL = {0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f};

    private static final int[][] FACE_DELTAS = {
        {0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}
    };

    private final BlockRegistry registry;
    private final ModelBaker baker;
    private final Colormaps colormaps;
    private final SpriteAtlas atlas;
    private final BlockShape shapes;

    // world access over all regions (query across region borders)
    private final List<LitematicaSchematic.Region> regions = new ArrayList<>();
    private final List<int[]> regionOffsets = new ArrayList<>();

    public SceneBuilder(BlockRegistry registry, ModelBaker baker, Colormaps colormaps, SpriteAtlas atlas) {
        this.registry = registry;
        this.baker = baker;
        this.colormaps = colormaps;
        this.atlas = atlas;
        this.shapes = new BlockShape(baker, atlas);
    }

    public void addRegion(LitematicaSchematic.Region region, int[] worldOffset) {
        this.regions.add(region);
        this.regionOffsets.add(worldOffset);
    }

    /** World-space block lookup: outside = air (vanilla "air outside the schematic" semantics). */
    public BlockState blockAt(int x, int y, int z) {
        for (int i = 0; i < this.regions.size(); i++) {
            int[] off = this.regionOffsets.get(i);
            LitematicaSchematic.Region r = this.regions.get(i);
            int lx = x - off[0], ly = y - off[1], lz = z - off[2];
            if (lx >= 0 && ly >= 0 && lz >= 0 && lx < r.blocks.sizeX && ly < r.blocks.sizeY && lz < r.blocks.sizeZ) {
                return r.blocks.get(lx, ly, lz);
            }
        }
        return BlockState.air();
    }

    // ------------------------------------------------------------------
    // Face culling

    /**
     * Vanilla {@code Block.shouldRenderFace}:
     * <ol>
     *   <li>the neighbour occludes the whole face → hidden;</li>
     *   <li>the block and its neighbour merge into one surface ({@code skipRendering}) → hidden;</li>
     *   <li>otherwise visible.</li>
     * </ol>
     * {@code skipRendering} is what stops a wall of identical glass/leaves/ice from showing every
     * internal face; vanilla implements it per block class, so the blocks that need it are listed.
     */
    private boolean shouldRenderFace(BlockState state, int face, int x, int y, int z) {
        int[] d = FACE_DELTAS[face];
        BlockState neighbor = this.blockAt(x + d[0], y + d[1], z + d[2]);
        if (neighbor.isAir()) return true;
        if (this.shapes.occludes(neighbor)) return false;
        return !(neighbor.blockId.equals(state.blockId) && MERGING.contains(state.blockId));
    }

    /**
     * Blocks whose identical neighbours merge visually — vanilla's {@code skipRendering}
     * (glass, stained glass, ice, leaves, panes…). Without this a glass wall renders every
     * internal face, which reads as a dense grey block instead of a clear pane.
     */
    private static final java.util.Set<String> MERGING = java.util.Set.of(
        "minecraft:glass", "minecraft:tinted_glass", "minecraft:ice", "minecraft:frosted_ice",
        "minecraft:slime_block", "minecraft:honey_block",
        "minecraft:oak_leaves", "minecraft:spruce_leaves", "minecraft:birch_leaves",
        "minecraft:jungle_leaves", "minecraft:acacia_leaves", "minecraft:dark_oak_leaves",
        "minecraft:mangrove_leaves", "minecraft:cherry_leaves", "minecraft:azalea_leaves",
        "minecraft:flowering_azalea_leaves", "minecraft:pale_oak_leaves",
        "minecraft:glass_pane",
        "minecraft:white_stained_glass", "minecraft:orange_stained_glass",
        "minecraft:magenta_stained_glass", "minecraft:light_blue_stained_glass",
        "minecraft:yellow_stained_glass", "minecraft:lime_stained_glass",
        "minecraft:pink_stained_glass", "minecraft:gray_stained_glass",
        "minecraft:light_gray_stained_glass", "minecraft:cyan_stained_glass",
        "minecraft:purple_stained_glass", "minecraft:blue_stained_glass",
        "minecraft:brown_stained_glass", "minecraft:green_stained_glass",
        "minecraft:red_stained_glass", "minecraft:black_stained_glass"
    );

    // ------------------------------------------------------------------
    // Scene assembly

    public SceneMesh build() {
        SceneMesh mesh = new SceneMesh();
        for (int ri = 0; ri < this.regions.size(); ri++) {
            LitematicaSchematic.Region region = this.regions.get(ri);
            int[] off = this.regionOffsets.get(ri);
            region.blocks.forEachNonAir((lx, ly, lz, state) -> {
                int x = off[0] + lx, y = off[1] + ly, z = off[2] + lz;
                this.emitBlock(mesh, state, x, y, z);
            });
            // Blocks whose appearance comes from a renderer rather than a model contribute nothing
            // above, so they are emitted separately; see BlockEntityModels.
            this.emitBlockEntities(mesh, region, off);
        }
        mesh.quadCount = mesh.solid.size() + mesh.cutout.size() + mesh.portal.size()
            + mesh.fluid.size() + mesh.translucent.size();
        // Pre-split the blending buckets. This depends only on geometry, and doing it here instead
        // of per frame is what keeps a drag cheap: the buckets grow several-fold, and repeating
        // that allocation and bilinear interpolation on every camera change dominated the frame.
        subdivideInto(mesh.portal, mesh.portalSorted);
        subdivideInto(mesh.fluid, mesh.fluidSorted);
        subdivideInto(mesh.translucent, mesh.translucentSorted);
        return mesh;
    }

    // ------------------------------------------------------------------
    // Block entities

    /**
     * Emits geometry for blocks the model system cannot draw.
     *
     * <p>Two membership rules meet here. Chests and shulker boxes need entity-driven geometry and
     * historically were emitted per block-entity entry; heads belong on the same list but carry
     * their facing in the blockstate ({@code rotation 0..15}), which a fixture can express without
     * any tile-entity data at all. So the scan walks every block, and the per-id switch decides what
     * needs building: a hopper has entity data too, and correctly contributes nothing.
     */
    private void emitBlockEntities(SceneMesh mesh, LitematicaSchematic.Region region, int[] off) {
        region.blocks.forEachNonAir((lx, ly, lz, state) -> {
            String id = state.blockId;
            boolean isChest = id.equals("minecraft:chest") || id.equals("minecraft:trapped_chest")
                || id.equals("minecraft:ender_chest");
            boolean isShulker = id.endsWith("shulker_box");
            boolean isHead = id.endsWith("_head") || id.endsWith("_skull");
            if (!isChest && !isShulker && !isHead) return;
            this.emitModellessBlock(mesh, region, off, lx, ly, lz, state);
        });
    }

    /** Emits one model-less block. Lives here so the scan above stays a single readable filter. */
    private void emitModellessBlock(SceneMesh mesh, LitematicaSchematic.Region region, int[] off,
                                    int lx, int ly, int lz, BlockState state) {
        String id = state.blockId;
        // A double chest is two blocks, each with type=left/right and a half-width model that
        // omits the shared face. Reading the property here is what keeps the two halves from
        // drawing full-width lids over the same space.
        String chestType = state.getValue("type");
        List<BlockEntityModels.Box> boxes = switch (id) {
            case "minecraft:chest", "minecraft:trapped_chest" ->
                BlockEntityModels.chest(chestType, chestSprite(id));
            case "minecraft:ender_chest" -> BlockEntityModels.chest(chestType, "minecraft:entity/chest/ender");
            default -> null;
        };
        if (boxes == null) {
            // Shulker boxes are named per colour, and skulls per mob, so match by suffix.
            if (id.endsWith("shulker_box")) {
                boxes = BlockEntityModels.shulkerBox(shulkerSprite(id));
            } else if (id.endsWith("_head") || id.endsWith("_skull")) {
                boxes = BlockEntityModels.skull(headSprite(id));
            }
        }
        if (boxes == null) return;
        int x = off[0] + lx, y = off[1] + ly, z = off[2] + lz;
        for (BlockEntityModels.Box box : boxes) {
            if (id.endsWith("_head") || id.endsWith("_skull")) {
                // A ground head turns with its rotation property (0..15, 22.5-degree steps,
                // 0 = facing south). The chest path needs no rotation here because emitBox
                // reads the facing directly.
                this.emitHead(mesh, box, x, y, z, state.getValue("rotation"));
            } else {
                // The chest's own facing turns its geometry; its closed interior is skipped.
                String facing = state.getValue("facing");
                this.emitBox(mesh, box, x, y, z, facing, true);
            }
        }
    }
    /**
     * Emits a head rotated by its {@code rotation} value.
     *
     * <p>Vanilla's skull entity renderer turns the model by {@code rotation * 22.5 - 180} degrees
     * about Y. The 180 offset matters: rotation 0 means the face looks south, and the skull model
     * here is authored face-north, so without the flip every head showed its back.
     */
    private void emitHead(SceneMesh mesh, BlockEntityModels.Box box, int x, int y, int z, String rotation) {
        int r;
        try {
            r = Integer.parseInt(rotation == null ? "0" : rotation);
        } catch (NumberFormatException e) {
            r = 0;
        }
        float angle = (float) Math.toRadians(r * 22.5f - 180f);
        this.emitBoxRotated(mesh, box, x, y, z, angle);
    }

    /** Sprite for a wooden chest: one sprite holds the body, lid and lock unwrap. */
    private static String chestSprite(String blockId) {
        return switch (blockId) {
            case "minecraft:trapped_chest" -> "minecraft:entity/chest/trapped";
            default -> "minecraft:entity/chest/normal";
        };
    }

    /** Shulker boxes are one sprite per dye colour, named after it. */
    private static String shulkerSprite(String blockId) {
        String name = blockId.substring("minecraft:".length(), blockId.length() - "_box".length());
        return name.equals("shulker") || name.isEmpty()
            ? "minecraft:entity/shulker/shulker"
            : "minecraft:entity/shulker/shulker_" + name;
    }

    /**
     * Head sprite for a skull block.
     *
     * <p>Skull textures live under a folder named after the mob, and the block name already carries
     * it, so no table is needed beyond a couple of historical aliases.
     */
    private static String headSprite(String blockId) {
        String name = blockId.substring("minecraft:".length());
        name = name.replace("_wall_head", "").replace("_head", "").replace("_wall_skull", "")
                   .replace("_skull", "");
        String mob = switch (name) {
            case "skeleton" -> "skeleton/skeleton";
            case "wither_skeleton" -> "skeleton/wither_skeleton";
            case "zombie" -> "zombie/zombie";
            case "creeper" -> "creeper/creeper";
            case "enderman" -> "enderman/enderman";
            case "piglin" -> "piglin/piglin";
            case "dragon" -> "enderdragon/dragon";
            default -> "skeleton/skeleton";
        };
        return "minecraft:entity/" + mob;
    }

    /**
     * Emits one box as six quads, unwrapped exactly as {@code ModelPart.Cube} does.
     *
     * <p>The unwrap lays the faces around the texture origin: the side faces occupy the band
     * {@code v1..v2} separated by depth and width, the top and bottom sit above and below it, and
     * the back face continues past the front. Copying that layout is what makes a chest's planks
     * line up instead of smearing.
     */
    /**
     * Emits a block-entity box, rotated to the block's facing.
     *
     * <p>The rotation is a quarter-turn about the block centre, applied by remapping each face's
     * corner coordinates and its face index together. Doing it here rather than inside
     * {@code emitBoxFace} keeps that method axis-aligned and lets the interior faces be skipped:
     * the game never shows the inside of a closed chest, so those faces are pure overdraw.
     *
     * @param facing the blockstate's {@code facing} value, or null for north
     */
    private void emitBox(SceneMesh mesh, BlockEntityModels.Box box, int wx, int wy, int wz,
                         String facing, boolean skipInterior) {
        float[] f = box.from();
        float[] t = box.to();
        // Model space is 0..16; world space is 0..1 per block. Blocks sit on the lattice corner.
        float x0 = wx + f[0] / 16f, x1 = wx + t[0] / 16f;
        float y0 = wy + f[1] / 16f, y1 = wy + t[1] / 16f;
        float z0 = wz + f[2] / 16f, z1 = wz + t[2] / 16f;
        float w = box.width(), h = box.height(), d = box.depth();

        // Texture-space rectangles, following ModelPart.Cube's unwrap. The unwrap is defined in the
        // box's own frame, so it is computed before the rotation and travels with each face.
        float ox = box.originX(), oy = box.originY();
        float u0 = ox, u1 = ox + d, u2 = ox + d + w, u22 = ox + d + 2 * w;
        float u3 = ox + d + w + d, u4 = ox + d + w + d + w;
        float v0 = oy, v1 = oy + d, v2 = oy + d + h;

        // Corners of each face, in ring order, in the box's own (unrotated) frame. The interior
        // faces of a closed box are never visible, so they are skipped when the caller says so.
        //            face        bottom A        bottom B        top B           top A
        face(mesh, box, facing, skipInterior, Quad.DOWN,
            x0, y0, z0,  x0, y0, z1,  x1, y0, z1,  x1, y0, z0,  u1, v0, u2, v1);
        face(mesh, box, facing, skipInterior, Quad.UP,
            x0, y1, z0,  x0, y1, z1,  x1, y1, z1,  x1, y1, z0,  u2, v1, u22, v0);
        face(mesh, box, facing, skipInterior, Quad.WEST,
            x0, y0, z0,  x0, y0, z1,  x0, y1, z1,  x0, y1, z0,  u0, v1, u1, v2);
        face(mesh, box, facing, skipInterior, Quad.NORTH,
            x1, y0, z0,  x0, y0, z0,  x0, y1, z0,  x1, y1, z0,  u1, v1, u2, v2);
        face(mesh, box, facing, skipInterior, Quad.EAST,
            x1, y0, z1,  x1, y0, z0,  x1, y1, z0,  x1, y1, z1,  u2, v1, u3, v2);
        face(mesh, box, facing, skipInterior, Quad.SOUTH,
            x0, y0, z1,  x1, y0, z1,  x1, y1, z1,  x0, y1, z1,  u3, v1, u4, v2);
    }

    /**
     * Emits a box rotated by an arbitrary angle about Y — the ground-head case.
     *
     * <p>Heads rotate in 22.5-degree steps, which no quarter-turn decomposition reaches, so this
     * rotates every corner directly. The same 16-pixel model-space box and the same
     * {@code ModelPart.Cube} unwrap are used; only the corner placement differs from {@link #emitBox}.
     */
    private void emitBoxRotated(SceneMesh mesh, BlockEntityModels.Box box, int wx, int wy, int wz,
                                float angle) {
        float[] f = box.from();
        float[] t = box.to();
        float x0 = wx + f[0] / 16f, x1 = wx + t[0] / 16f;
        float y0 = wy + f[1] / 16f, y1 = wy + t[1] / 16f;
        float z0 = wz + f[2] / 16f, z1 = wz + t[2] / 16f;
        float w = box.width(), h = box.height(), d = box.depth();

        float ox = box.originX(), oy = box.originY();
        float u0 = ox, u1 = ox + d, u2 = ox + d + w, u22 = ox + d + 2 * w;
        float u3 = ox + d + w + d, u4 = ox + d + w + d + w;
        float v0 = oy, v1 = oy + d, v2 = oy + d + h;

        // Rotate about the block centre: (x, z) -> (cx + dx·cos - dz·sin, cz + dx·sin + dz·cos).
        float cx = wx + 0.5f, cz = wz + 0.5f;
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        java.util.function.UnaryOperator<float[]> rot = (p) -> {
            float dx = p[0] - cx, dz = p[2] - cz;
            return new float[]{cx + dx * c - dz * s, p[1], cz + dx * s + dz * c};
        };

        // Each face keeps its own cardinal lighting from the UNROTATED orientation; at 22.5-degree
        // steps the difference is small, and the vanilla entity renderer does the same.
        emitBoxFaceRotated(mesh, box, Quad.DOWN, rot,
            x0, y0, z0,  x0, y0, z1,  x1, y0, z1,  x1, y0, z0,  u1, v0, u2, v1);
        emitBoxFaceRotated(mesh, box, Quad.UP, rot,
            x0, y1, z0,  x0, y1, z1,  x1, y1, z1,  x1, y1, z0,  u2, v1, u22, v0);
        emitBoxFaceRotated(mesh, box, Quad.WEST, rot,
            x0, y0, z0,  x0, y0, z1,  x0, y1, z1,  x0, y1, z0,  u0, v1, u1, v2);
        emitBoxFaceRotated(mesh, box, Quad.NORTH, rot,
            x1, y0, z0,  x0, y0, z0,  x0, y1, z0,  x1, y1, z0,  u1, v1, u2, v2);
        emitBoxFaceRotated(mesh, box, Quad.EAST, rot,
            x1, y0, z1,  x1, y0, z0,  x1, y1, z0,  x1, y1, z1,  u2, v1, u3, v2);
        emitBoxFaceRotated(mesh, box, Quad.SOUTH, rot,
            x0, y0, z1,  x1, y0, z1,  x1, y1, z1,  x0, y1, z1,  u3, v1, u4, v2);
    }

    /** Emits one face of an arbitrarily-rotated box, applying the rotation to its four corners. */
    private void emitBoxFaceRotated(SceneMesh mesh, BlockEntityModels.Box box, int face,
                                    java.util.function.UnaryOperator<float[]> rot,
                                    float ax, float ay, float az, float bx, float by, float bz,
                                    float cx, float cy, float cz, float dx, float dy, float dz,
                                    float tu0, float tv0, float tu1, float tv1) {
        float su0 = tu0 / box.texW(), su1 = tu1 / box.texW();
        float sv0 = tv0 / box.texH(), sv1 = tv1 / box.texH();
        float[] pa = rot.apply(new float[]{ax, ay, az});
        float[] pb = rot.apply(new float[]{bx, by, bz});
        float[] pc = rot.apply(new float[]{cx, cy, cz});
        float[] pd = rot.apply(new float[]{dx, dy, dz});
        float[] xs = {pa[0], pb[0], pc[0], pd[0]};
        float[] ys = {pa[1], pb[1], pc[1], pd[1]};
        float[] zs = {pa[2], pb[2], pc[2], pd[2]};
        float[] pu = {su0, su0, su1, su1};
        float[] pv = {sv1, sv0, sv0, sv1};
        SceneMesh.MeshQuad q = new SceneMesh.MeshQuad(xs, ys, zs,
            pu[0], pv[0], pu[1], pv[1], pu[2], pv[2], pu[3], pv[3],
            box.sprite(), 0xFFFFFFFF, null, 1f, 1f, 1f, CARDINAL[face], null,
            SceneMesh.Layer.CUTOUT, false);
        mesh.cutout.add(q);
    }

    /**
     * Rotates one face to the block's facing and emits it.
     *
     * <p>A quarter-turn about the block centre maps the axes as (x, z) -> (-z, x) per step, which
     * carries the face index with it: NORTH -> WEST -> SOUTH -> EAST, while DOWN and UP are fixed.
     * Applying the turn to the corners here keeps {@code emitBoxFace} axis-aligned.
     *
     * <p>When {@code skipInterior} is set, faces whose visible side faces into the closed box are
     * dropped. Which ones those are depends on the facing — the back face and, for a chest, the
     * lid's underside — so the caller's flag is resolved against the rotated direction.
     */
    private void face(SceneMesh mesh, BlockEntityModels.Box box, String facing, boolean skipInterior,
                      int unrotatedFace,
                      float ax, float ay, float az, float bx, float by, float bz,
                      float cx, float cy, float cz, float dx2, float dy2, float dz2,
                      float tu0, float tv0, float tu1, float tv1) {
        // One turn maps (x, z) -> (-z, x), the same quarter-turn as vanilla BLOCK_ROT_Y_90, which
        // sends east to north. The count per facing therefore mirrors the blockstate table: north
        // needs none, east one, south two, west three.
        int turns = switch (facing == null ? "north" : facing) {
            case "east" -> 1;
            case "south" -> 2;
            case "west" -> 3;
            default -> 0;                       // north
        };
        // Rotate each corner about the block centre, one quarter-turn at a time.
        //
        // The centre is the corner block's own middle, NOT (0.5, 0.5): the incoming coordinates are
        // already world space, offset by the block's integer position. Centring on 0.5 rotated every
        // chest about the world origin, which flung the geometry far outside the build and inflated
        // the bounding box that the camera fit uses.
        float[] xs = {ax, bx, cx, dx2};
        float[] ys = {ay, by, cy, dy2};
        float[] zs = {az, bz, cz, dz2};
        float cxm = (float) Math.floor(xs[0]) + 0.5f;
        float czm = (float) Math.floor(zs[0]) + 0.5f;
        for (int i = 0; i < 4; i++) {
            float px = xs[i] - cxm, pz = zs[i] - czm;
            for (int s = 0; s < turns; s++) {
                // BLOCK_ROT_Y_90 as a 2D quarter-turn: +X (east) -> -Z (north).
                float tmp = px;
                px = pz;
                pz = -tmp;
            }
            xs[i] = px + cxm;
            zs[i] = pz + czm;
        }
        // Carry the face index with the same turn: NORTH -> WEST -> SOUTH -> EAST.
        int f = unrotatedFace;
        for (int s = 0; s < turns; s++) {
            // Same quarter-turn as the corners: +X (east) -> -Z (north), i.e. north -> east.
            f = switch (f) {
                case Quad.NORTH -> Quad.EAST;
                case Quad.EAST -> Quad.SOUTH;
                case Quad.SOUTH -> Quad.WEST;
                case Quad.WEST -> Quad.NORTH;
                default -> f;                   // DOWN / UP do not move
            };
        }
        // The inside of a closed box: the face pointing away from the viewer is the back of the
        // box, and its outward normal points into the cavity. Only skip when asked.
        if (skipInterior && isInteriorFace(box, f)) return;

        emitBoxFace(mesh, box, f, xs[0], ys[0], zs[0], xs[1], ys[1], zs[1],
            xs[2], ys[2], zs[2], xs[3], ys[3], zs[3], tu0, tv0, tu1, tv1);
    }

    /**
     * True when this face of the box faces into the box's own cavity, so it can be skipped.
     *
     * <p>For the chest this is the bottom of the lid and the top of the body — the two surfaces that
     * touch when the lid is closed. Skipping them is safe because the cavity is sealed by the lid,
     * and it removes a quarter of the chest's quads.
     */
    private boolean isInteriorFace(BlockEntityModels.Box box, int face) {
        // Identify the lid by its height: it is the part above y=9 in the vanilla chest model.
        boolean isLid = box.from()[1] >= 9f;
        if (isLid) return face == Quad.DOWN;     // underside of the lid
        return face == Quad.UP;                  // top of the body, under the lid
    }

    /** Emits one axis-aligned face of a block-entity box. */
    private void emitBoxFace(SceneMesh mesh, BlockEntityModels.Box box, int face,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float tu0, float tv0, float tu1, float tv1) {        // Texture pixels to atlas UV. The box carries its own texture size, because entity atlases
        // are 64 wide rather than a block sprite's 16.
        float su0 = tu0 / box.texW(), su1 = tu1 / box.texW();
        float sv0 = tv0 / box.texH(), sv1 = tv1 / box.texH();
        float[] xs = {ax, bx, cx, dx};
        float[] ys = {ay, by, cy, dy};
        float[] zs = {az, bz, cz, dz};
        // Corners map to the UV rectangle in ring order: (u0,v0) (u0,v1) (u1,v1) (u1,v0).
        // ModelPart.Cube hands the texture its V axis flipped relative to model space, which the
        // swapped pairs below reproduce.
        float[] pu = {su0, su0, su1, su1};
        float[] pv = {sv1, sv0, sv0, sv1};

        float shade = CARDINAL[face];
        SceneMesh.MeshQuad q = new SceneMesh.MeshQuad(xs, ys, zs,
            pu[0], pv[0], pu[1], pv[1], pu[2], pv[2], pu[3], pv[3],
            box.sprite(), 0xFFFFFFFF, null, 1f, 1f, 1f, shade, null,
            SceneMesh.Layer.CUTOUT, false);
        mesh.cutout.add(q);
    }

    /**
     * Splits each quad into an n×n grid, so back-to-front ordering still holds where two
     * translucent surfaces cross. A single big water surface or glass wall sorted by its centroid
     * can land on the wrong side of a smaller quad it intersects; smaller pieces cannot.
     */
    public static void subdivideInto(List<SceneMesh.MeshQuad> in, List<SceneMesh.MeshQuad> out) {
        for (SceneMesh.MeshQuad q : in) subdivideQuad(q, out);    }

    private static void subdivideQuad(SceneMesh.MeshQuad q, List<SceneMesh.MeshQuad> out) {
        float ex = Math.abs(q.x1 - q.x0) + Math.abs(q.x2 - q.x1);
        float ey = Math.abs(q.y1 - q.y0) + Math.abs(q.y2 - q.y1);
        float ez = Math.abs(q.z1 - q.z0) + Math.abs(q.z2 - q.z1);
        float extent = Math.max(ex, Math.max(ey, ez));
        int n = extent > 1.5f ? 4 : (extent > 0.75f ? 2 : 1);
        if (n == 1) {
            out.add(q);
            return;
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                float u0 = i / (float) n, u1 = (i + 1) / (float) n;
                float v0 = j / (float) n, v1 = (j + 1) / (float) n;
                float[] xs = new float[4], ys = new float[4], zs = new float[4];
                float[] pu = new float[4], pv = new float[4];
                float[][] uv = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
                for (int k = 0; k < 4; k++) {
                    float a = uv[k][0], b = uv[k][1];
                    xs[k] = bilerp(q.x0, q.x1, q.x2, q.x3, a, b);
                    ys[k] = bilerp(q.y0, q.y1, q.y2, q.y3, a, b);
                    zs[k] = bilerp(q.z0, q.z1, q.z2, q.z3, a, b);
                    pu[k] = bilerp(q.u0, q.u1, q.u2, q.u3, a, b);
                    pv[k] = bilerp(q.v0, q.v1, q.v2, q.v3, a, b);
                }
                out.add(new SceneMesh.MeshQuad(xs, ys, zs,
                    pu[0], pv[0], pu[1], pv[1], pu[2], pv[2], pu[3], pv[3],
                    q.spriteId, q.rgb, q.image, q.tintR, q.tintG, q.tintB, q.shade,
                    aoAt(q.ao, u0, v0, u1, v1), q.layer, q.doubleSided));
            }
        }
    }

    /** Interpolates the 4 corner AO values at a sub-quad's corners (ring order). */
    private static float[] aoAt(float[] ao, float u0, float v0, float u1, float v1) {
        if (ao == null) return null;
        float[][] uv = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
        float[] out = new float[4];
        for (int k = 0; k < 4; k++) {
            float a = uv[k][0], b = uv[k][1];
            float top = ao[0] + (ao[1] - ao[0]) * a;
            float bottom = ao[3] + (ao[2] - ao[3]) * a;
            out[k] = top + (bottom - top) * b;
        }
        return out;
    }

    private static float bilerp(float a, float b, float c, float d, float u, float v) {
        // Corners are in ring order, so blend along both axes and combine.
        float top = a + (b - a) * u;
        float bottom = d + (c - d) * u;
        return top + (bottom - top) * v;
    }

    private void emitBlock(SceneMesh mesh, BlockState state, int x, int y, int z) {
        String id = state.blockId;

        // Fluid first, and for every block rather than only the fluid blocks.
        //
        // A waterlogged block — leaves, stairs, fences, coral — holds a fluid state of its own, and
        // the game draws that fluid as part of rendering the block. Dispatching on the block id
        // therefore misses every waterlogged block, which is why water inside leaves never appeared.
        // quickcraft's preview asks each block for its fluid state and bails when it is empty
        // (`renderFluidIfPresent`), which covers both cases with one rule.
        if (FLUIDS.contains(id)) {
            this.emitFluid(mesh, state, x, y, z);
            return;
        }
        if (isWaterlogged(state)) {
            // Pass the block's own state: fluidKeyOrNull() recognises it as water, and its lack of
            // a level property makes ownHeight() report a full source, which is what a waterlogged
            // block holds. The block's model is still emitted below, because the game draws both.
            this.emitFluid(mesh, state, x, y, z);
        }

        // A blockstate may resolve to several models (multipart: fences, walls, panes,
        // redstone wire, brewing stands, ...). Emit every applicable one.
        List<ModelBaker.Variant> variants = this.baker.selectVariants(id, state);
        if (variants.isEmpty()) {
            ModelBaker.Variant dv = this.baker.selectVariant(id, this.registry.getOrMissing(id).defaultState);
            if (dv != null) variants = List.of(dv);
        }
        if (variants.isEmpty()) {
            io.github.litematicrender.render.RenderDiagnostics.note("no model for blockstate", id + state.propertyString());
            this.emitModel(mesh, this.baker.bake("minecraft:block/missingno", 0, 0, 0, false), state, x, y, z);
            return;
        }
        for (ModelBaker.Variant v : variants) {
            this.emitModel(mesh, this.baker.bake(v), state, x, y, z);
        }
    }

    private void emitModel(SceneMesh mesh, ModelBaker.BakedModel model, BlockState state, int x, int y, int z) {
        if (model == null) return;
        boolean ao = model.useAmbientOcclusion;
        for (int face = 0; face < 6; face++) {
            for (Quad q : model.quadsForFace(face)) {
                if (!this.shouldRenderFace(state, face, x, y, z)) continue;
                this.emitQuad(mesh, q, face, x, y, z, state, ao);
            }
        }
        for (Quad q : model.unculled) {
            this.emitQuad(mesh, q, -1, x, y, z, state, ao);
        }
    }

    private void emitQuad(SceneMesh mesh, Quad q, int faceDir, int x, int y, int z, BlockState state, boolean ao) {
        int effectiveFace = faceDir >= 0 ? faceDir : Quad.UP;
        float shade = q.shadeFaceOverride >= 0 ? CARDINAL[q.shadeFaceOverride] : CARDINAL[effectiveFace];

        float[] xs = {q.x0 + x, q.x1 + x, q.x2 + x, q.x3 + x};
        float[] ys = {q.y0 + y, q.y1 + y, q.y2 + y, q.y3 + y};
        float[] zs = {q.z0 + z, q.z1 + z, q.z2 + z, q.z3 + z};

        // Per-vertex ambient occlusion (vanilla BlockModelLighter.prepareQuadAmbientOcclusion).
        // Light emission disables AO in vanilla; unculled quads (crosses, rails) use flat lighting.
        float[] aoV = null;
        if (ao && faceDir >= 0 && q.lightEmission == 0) {
            aoV = this.ambientOcclusion(x, y, z, effectiveFace, q, xs, ys, zs, state);
        }

        // Tint is chosen by the model's tintindex against the block's registered tint source
        // (vanilla BlockColors), not by guessing from the texture name.
        float tr = 1, tg = 1, tb = 1;
        if (q.tintIndex >= 0) {
            int tint = io.github.litematicrender.render.BlockTints.colorFor(state, q.spriteId, q.tintIndex);
            if (tint >= 0) {
                tr = ((tint >> 16) & 0xFF) / 255f;
                tg = ((tint >> 8) & 0xFF) / 255f;
                tb = (tint & 0xFF) / 255f;
            }
        }

        SceneMesh.Layer layer = this.layerOf(state.blockId, q.spriteId, q.forceTranslucent);
        SceneMesh.MeshQuad mq = new SceneMesh.MeshQuad(xs, ys, zs,
            q.u0, q.v0, q.u1, q.v1, q.u2, q.v2, q.u3, q.v3,
            q.spriteId, 0xFFFFFFFF, null, tr, tg, tb, shade, aoV, layer, q.face == Quad.NO_FACE);
        switch (layer) {
            case SOLID -> mesh.solid.add(mq);
            case CUTOUT -> mesh.cutout.add(mq);
            case PORTAL -> mesh.portal.add(mq);
            case FLUID -> mesh.fluid.add(mq);
            case TRANSLUCENT -> mesh.translucent.add(mq);
        }
    }

    // ------------------------------------------------------------------
    // Ambient occlusion

    /**
     * Vanilla per-vertex AO ({@code BlockModelLighter.prepareQuadAmbientOcclusion}).
     *
     * <p>For every corner of the face we sample the two edge neighbours and the diagonal:
     * {@code shade = (side1 + side2 + diagonal + center) * 0.25}. Vanilla's degenerate rule is
     * kept exactly: when <em>both</em> edge neighbours are light-permeable the diagonal sample is
     * skipped and {@code side1}'s value is used instead of it.
     */
    private float[] ambientOcclusion(int x, int y, int z, int face, Quad q,
                                     float[] xs, float[] ys, float[] zs, BlockState state) {
        int[] n = FACE_DELTAS[face];
        int[][] axes = faceAxes(face);
        int[] u = axes[0], v = axes[1];

        // Vanilla faceCubic: a flat face lying on the block's own boundary of the cube samples the
        // neighbour cell; an inset face (slab top, farmland, snow layer) samples around its own
        // cell, because the neighbouring cell is not what shadows it.
        boolean faceCubic = isFaceCubic(q, face, state);
        int fx = faceCubic ? x + n[0] : x;
        int fy = faceCubic ? y + n[1] : y;
        int fz = faceCubic ? z + n[2] : z;

        // The face's own cell contributes its shade value; vanilla uses the base cell's block.
        float center = faceCubic
            ? this.shades(this.blockAt(fx, fy, fz))
            : this.shades(state);

        float[] out = new float[4];
        for (int i = 0; i < 4; i++) {
            // Face-local corners in the order (-u,-v) (+u,-v) (+u,+v) (-u,+v)
            int su = (i == 1 || i == 2) ? 1 : -1;
            int sv = (i >= 2) ? 1 : -1;
            int s1x = fx + u[0] * su, s1y = fy + u[1] * su, s1z = fz + u[2] * su;
            int s2x = fx + v[0] * sv, s2y = fy + v[1] * sv, s2z = fz + v[2] * sv;

            BlockState b1 = this.blockAt(s1x, s1y, s1z);
            BlockState b2 = this.blockAt(s2x, s2y, s2z);
            float side1 = this.shades(b1);
            float side2 = this.shades(b2);
            boolean p1 = this.permits(b1);
            boolean p2 = this.permits(b2);

            float diagonal;
            if (!p2 && !p1) {
                diagonal = side1;                       // vanilla: reuse side1, don't sample
            } else {
                diagonal = this.shades(this.blockAt(s1x + v[0] * sv, s1y + v[1] * sv, s1z + v[2] * sv));
            }
            out[i] = Math.min(1f, (side1 + side2 + diagonal + center) * 0.25f);
        }
        return remapToVertices(out, face, xs, ys, zs);
    }

    /**
     * Vanilla {@code BlockModelLighter.faceCubic}: the face is flat on the cube boundary and the
     * block fills the cube, or it simply lies on the outer plane of the model.
     */
    private boolean isFaceCubic(Quad q, int face, BlockState state) {
        boolean flat = switch (face) {
            case Quad.DOWN -> allEqual(q.y0, q.y1, q.y2, q.y3);
            case Quad.UP -> allEqual(q.y0, q.y1, q.y2, q.y3);
            case Quad.NORTH, Quad.SOUTH -> allEqual(q.z0, q.z1, q.z2, q.z3);
            default -> allEqual(q.x0, q.x1, q.x2, q.x3);
        };
        if (!flat) return false;
        boolean atOuterPlane = switch (face) {
            case Quad.DOWN -> min4(q.y0, q.y1, q.y2, q.y3) < 1e-4f;
            case Quad.UP -> max4(q.y0, q.y1, q.y2, q.y3) > 0.9999f;
            case Quad.NORTH -> min4(q.z0, q.z1, q.z2, q.z3) < 1e-4f;
            case Quad.SOUTH -> max4(q.z0, q.z1, q.z2, q.z3) > 0.9999f;
            case Quad.WEST -> min4(q.x0, q.x1, q.x2, q.x3) < 1e-4f;
            default -> max4(q.x0, q.x1, q.x2, q.x3) > 0.9999f;
        };
        return atOuterPlane || this.shapes.isShapeFullBlock(state);
    }

    private static boolean allEqual(float a, float b, float c, float d) {
        return Math.abs(a - b) < 1e-4f && Math.abs(b - c) < 1e-4f && Math.abs(c - d) < 1e-4f;
    }

    private static float min4(float a, float b, float c, float d) { return Math.min(Math.min(a, b), Math.min(c, d)); }
    private static float max4(float a, float b, float c, float d) { return Math.max(Math.max(a, b), Math.max(c, d)); }

    /**
     * The two in-plane axes of a face, in the winding order used by the baked quads.
     * Index 0 is the horizontal axis, index 1 the vertical one.
     */
    private static int[][] faceAxes(int face) {
        return switch (face) {
            case Quad.DOWN, Quad.UP -> new int[][]{{1, 0, 0}, {0, 0, 1}};
            case Quad.NORTH, Quad.SOUTH -> new int[][]{{1, 0, 0}, {0, 1, 0}};
            default -> new int[][]{{0, 0, 1}, {0, 1, 0}};
        };
    }

    /**
     * Vanilla {@code AmbientVertexRemap}: AO is computed in face-local corner space, but a baked
     * quad may be wound either way (the model baker reverses the winding when a transform flips
     * it), so each vertex takes the value of the corner it actually sits at. Projecting the real
     * vertex positions onto the face axes makes this independent of winding.
     */
    private float[] remapToVertices(float[] c, int face, float[] xs, float[] ys, float[] zs) {
        int[][] axes = faceAxes(face);
        int[] u = axes[0], v = axes[1];
        // Quad centre in world space, used as the face-local origin.
        float ox = (xs[0] + xs[1] + xs[2] + xs[3]) * 0.25f;
        float oy = (ys[0] + ys[1] + ys[2] + ys[3]) * 0.25f;
        float oz = (zs[0] + zs[1] + zs[2] + zs[3]) * 0.25f;

        float[] out = new float[4];
        for (int i = 0; i < 4; i++) {
            float du = (xs[i] - ox) * u[0] + (ys[i] - oy) * u[1] + (zs[i] - oz) * u[2];
            float dv = (xs[i] - ox) * v[0] + (ys[i] - oy) * v[1] + (zs[i] - oz) * v[2];
            boolean hi = du > 0f, hv = dv > 0f;
            out[i] = c[hv ? (hi ? 2 : 3) : (hi ? 1 : 0)];
        }
        return out;
    }

    private float shades(BlockState s) { return this.shapes.shadeBrightness(s); }

    /** Vanilla {@code isLightPermeable}: used to skip the AO diagonal sample. */
    private boolean permits(BlockState s) {
        return this.shapes.lightPermeable(s);
    }

    // ------------------------------------------------------------------
    // Fluids
    //
    // Port of net.minecraft.client.renderer.block.FluidRenderer.tesselate. Every quantity below
    // has a line-for-line counterpart in that file; deviating from it is what made earlier
    // versions render a flat slab with the flow texture pointing the wrong way.

    private static final java.util.Set<String> FLUIDS = java.util.Set.of(
        "minecraft:water", "minecraft:lava", "minecraft:bubble_column");

    /**
     * True when the block declares {@code waterlogged=true}, i.e. it contains a water fluid.
     *
     * <p>The game renders that fluid as part of the block, so a waterlogged leaf or stair shows
     * water on its faces. Dispatching the fluid pass on the block id alone misses all of them.
     */
    private static boolean isWaterlogged(BlockState s) {
        String v = s.getValue("waterlogged");
        return v != null && Boolean.parseBoolean(v);
    }

    /** FluidRenderer.MAX_FLUID_HEIGHT: the tallest a non-falling fluid ever gets. */
    private static final float MAX_FLUID_HEIGHT = 0.8888889f;

    private void emitFluid(SceneMesh mesh, BlockState state, int x, int y, int z) {
        boolean lava = state.blockId.equals("minecraft:lava");
        String self = fluidKey(state);        BlockState above = this.blockAt(x, y + 1, z);

        // renderUp = !isNeighborSameFluid(fluidState, fluidStateUp)
        boolean renderUp = !isSameFluid(x, y + 1, z, self);
        // renderDown = shouldRenderFace(DOWN) && !isFaceOccludedByNeighbor(DOWN, MAX_FLUID_HEIGHT, below)
        boolean renderDown = !isSameFluid(x, y - 1, z, self)
            && !isFluidFaceOccludedBy(Direction.DOWN, MAX_FLUID_HEIGHT, this.blockAt(x, y - 1, z));
        //
        // The four horizontal faces use shouldRenderFace(), which only consults the block's OWN
        // occlusion shape (isFaceOccludedBySelf); the neighbour is checked later, at draw time,
        // with the fluid's real height. Rolling both tests into one here — and using "is the
        // neighbour solid" as the test — made water vanish behind glass: glass never occludes, so
        // the face must be emitted, and the depth buffer is what sorts the two out.
        boolean renderNorth = shouldRenderFluidFace(x, y, z - 1, self);
        boolean renderSouth = shouldRenderFluidFace(x, y, z + 1, self);
        boolean renderWest = shouldRenderFluidFace(x - 1, y, z, self);
        boolean renderEast = shouldRenderFluidFace(x + 1, y, z, self);

        if (!(renderUp || renderDown || renderEast || renderWest || renderNorth || renderSouth)) return;

        float tr = 1, tg = 1, tb = 1;
        if (!lava) {
            int tint = this.colormaps.waterColor();
            tr = ((tint >> 16) & 0xFF) / 255f;
            tg = ((tint >> 8) & 0xFF) / 255f;
            tb = (tint & 0xFF) / 255f;
        }

        float heightSelf = this.fluidHeight(x, y, z, self);
        float heightNorthEast, heightNorthWest, heightSouthEast, heightSouthWest;
        if (heightSelf >= 1f) {
            heightNorthEast = heightNorthWest = heightSouthEast = heightSouthWest = 1f;
        } else {
            float heightNorth = this.fluidHeight(x, y, z - 1, self);
            float heightSouth = this.fluidHeight(x, y, z + 1, self);
            float heightEast = this.fluidHeight(x + 1, y, z, self);
            float heightWest = this.fluidHeight(x - 1, y, z, self);
            heightNorthEast = avgHeight(self, heightSelf, heightNorth, heightEast, x + 1, y, z - 1);
            heightNorthWest = avgHeight(self, heightSelf, heightNorth, heightWest, x - 1, y, z - 1);
            heightSouthEast = avgHeight(self, heightSelf, heightSouth, heightEast, x + 1, y, z + 1);
            heightSouthWest = avgHeight(self, heightSelf, heightSouth, heightWest, x - 1, y, z + 1);
        }

        float fx = x, fy = y, fz = z;
        float offs = 0.001f;
        float bottomOffs = renderDown ? 0.001f : 0f;

        String stillSprite = lava ? "minecraft:block/lava_still" : "minecraft:block/water_still";
        String flowSprite = lava ? "minecraft:block/lava_flow" : "minecraft:block/water_flow";

        // ---- top face -------------------------------------------------------
        if (renderUp && !isFluidFaceOccludedBy(Direction.UP,
                Math.min(Math.min(heightNorthWest, heightSouthWest), Math.min(heightSouthEast, heightNorthEast)),
                above)) {
            heightNorthWest -= 0.001f;
            heightSouthWest -= 0.001f;
            heightSouthEast -= 0.001f;
            heightNorthEast -= 0.001f;

            // Vanilla rotates the still/flow texture by the flow direction so the streaks point
            // where the water is going; a still fluid keeps the sprite axis-aligned.
            double[] flow = this.fluidFlow(x, y, z, self);
            float u00, v00, u01, v01, u10, v10, u11, v11;
            if (flow[0] == 0.0 && flow[1] == 0.0) {
                // still sprite, full frame, corner order copied from addFace's call site
                u00 = 0f; v00 = 0f;
                u01 = 0f; v01 = 1f;
                u10 = 1f; v10 = 1f;
                u11 = 1f; v11 = 0f;
            } else {
                float angle = (float) Math.atan2(flow[1], flow[0]) - (float) (Math.PI / 2);
                float s = (float) Math.sin(angle) * 0.25f;
                float c = (float) Math.cos(angle) * 0.25f;
                u00 = 0.5f + (-c - s);
                v00 = 0.5f + (-c + s);
                u01 = 0.5f + (-c + s);
                v01 = 0.5f + (c + s);
                u10 = 0.5f + (c + s);
                v10 = 0.5f + (c - s);
                u11 = 0.5f + (c - s);
                v11 = 0.5f + (-c - s);
            }
            float[] xs = {fx, fx, fx + 1, fx + 1};
            float[] ys = {fy + heightNorthWest, fy + heightSouthWest, fy + heightSouthEast, fy + heightNorthEast};
            float[] zs = {fz, fz + 1, fz + 1, fz};
            this.addFluidQuad(mesh, xs, ys, zs, u00, v00, u01, v01, u10, v10, u11, v11,
                flow[0] == 0.0 && flow[1] == 0.0 ? stillSprite : flowSprite,
                tr, tg, tb, CARDINAL[Quad.UP]);
        }

        // ---- bottom face ----------------------------------------------------
        if (renderDown) {
            float[] xs = {fx, fx + 1, fx + 1, fx};
            float[] ys = {fy + bottomOffs, fy + bottomOffs, fy + bottomOffs, fy + bottomOffs};
            float[] zs = {fz, fz, fz + 1, fz + 1};
            this.addFluidQuad(mesh, xs, ys, zs, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f,
                stillSprite, tr, tg, tb, CARDINAL[Quad.DOWN]);
        }

        // ---- side faces -----------------------------------------------------
        // Vanilla samples only the lower half of the (16x512) flow sprite, with v tracking the
        // fluid height, so a shallow fluid shows the bottom slice of the texture rather than a
        // squashed full frame. Side shading is `up * (axis == Z ? north : west)`.
        for (int faceDir = 0; faceDir < 6; faceDir++) {
            float hh0, hh1, x0, z0, x1, z1;
            boolean renderCondition;
            BlockState faceState;
            switch (faceDir) {
                case Quad.NORTH -> {
                    hh0 = heightNorthWest; hh1 = heightNorthEast;
                    x0 = fx; x1 = fx + 1; z0 = fz + offs; z1 = fz + offs;
                    renderCondition = renderNorth; faceState = this.blockAt(x, y, z - 1);
                }
                case Quad.SOUTH -> {
                    hh0 = heightSouthEast; hh1 = heightSouthWest;
                    x0 = fx + 1; x1 = fx; z0 = fz + 1 - offs; z1 = fz + 1 - offs;
                    renderCondition = renderSouth; faceState = this.blockAt(x, y, z + 1);
                }
                case Quad.WEST -> {
                    hh0 = heightSouthWest; hh1 = heightNorthWest;
                    x0 = fx + offs; x1 = fx + offs; z0 = fz + 1; z1 = fz;
                    renderCondition = renderWest; faceState = this.blockAt(x - 1, y, z);
                }
                case Quad.EAST -> {
                    hh0 = heightNorthEast; hh1 = heightSouthEast;
                    x0 = fx + 1 - offs; x1 = fx + 1 - offs; z0 = fz; z1 = fz + 1;
                    renderCondition = renderEast; faceState = this.blockAt(x + 1, y, z);
                }
                default -> { continue; }
            }
            if (!renderCondition) continue;
            if (isFluidFaceOccludedBy(faceDir, Math.max(hh0, hh1), faceState)) continue;

            float u0 = 0f, u1 = 0.5f;
            float v01 = (1f - hh0) * 0.5f;
            float v02 = (1f - hh1) * 0.5f;
            float v1 = 0.5f;
            boolean alongZ = (faceDir == Quad.NORTH || faceDir == Quad.SOUTH);
            float shadeSide = alongZ ? CARDINAL[Quad.NORTH] : CARDINAL[Quad.WEST];
            float faceShade = CARDINAL[Quad.UP] * shadeSide;

            float[] xs = {x0, x1, x1, x0};
            float[] ys = {fy + hh0, fy + hh1, fy + bottomOffs, fy + bottomOffs};
            float[] zs = {z0, z1, z1, z0};
            this.addFluidQuad(mesh, xs, ys, zs, u0, v01, u1, v02, u1, v1, u0, v1,
                flowSprite, tr, tg, tb, faceShade);
        }
    }

    /** Six explicit directions, matching {@code Direction} ordinal order used above. */
    private static final class Direction {
        static final int DOWN = Quad.DOWN, UP = Quad.UP, NORTH = Quad.NORTH;
        static final int SOUTH = Quad.SOUTH, WEST = Quad.WEST, EAST = Quad.EAST;
    }

    private void addFluidQuad(SceneMesh mesh, float[] xs, float[] ys, float[] zs,
                              float u0, float v0, float u1, float v1,
                              float u2, float v2, float u3, float v3,
                              String sprite, float tr, float tg, float tb, float shade) {
        // Water is translucent, so its quads belong on the TRANSLUCENT layer with the glass — the
        // same bucket vanilla puts them in. A separate FLUID pass drew them after cutout had already
        // written depth, which hid the water inside waterlogged leaves behind the leaf's own pixels.
        SceneMesh.MeshQuad mq = new SceneMesh.MeshQuad(xs, ys, zs,
            u0, v0, u1, v1, u2, v2, u3, v3,
            sprite, 0xFFFFFFFF, tr, tg, tb, shade, null, SceneMesh.Layer.TRANSLUCENT);
        mesh.translucent.add(mq);
    }

    private static String fluidKey(BlockState s) {
        return s.blockId.equals("minecraft:bubble_column") ? "minecraft:water" : s.blockId;
    }

    /**
     * The fluid a block holds, or null when it holds none.
     *
     * <p>Two ways a block can hold fluid: it <em>is</em> a fluid block, or it is waterlogged. The
     * second is what makes water inside leaves, stairs and fences render at all. The returned key is
     * the fluid's identity, which is what the neighbour comparisons in {@code FluidRenderer} use.
     */
    private static String fluidKeyOrNull(BlockState s) {
        if (FLUIDS.contains(s.blockId)) return fluidKey(s);
        // A waterlogged block holds still water. It has no level property, and ownHeight() defaults
        // such a state to a full source, which is exactly right.
        if (isWaterlogged(s)) return "minecraft:water";
        return null;
    }

    private boolean isSameFluid(int x, int y, int z, String self) {
        String k = fluidKeyOrNull(this.blockAt(x, y, z));
        return k != null && k.equals(self);
    }

    /**
     * Vanilla {@code FluidRenderer.getHeight}: the fluid's own height, {@code 1} when the same
     * fluid sits directly above (a falling column is full height), {@code -1} for a solid neighbour
     * (that side is hidden) and {@code 0} for a passable one.
     */
    private float fluidHeight(int x, int y, int z, String self) {
        BlockState s = this.blockAt(x, y, z);
        String k = fluidKeyOrNull(s);
        if (k != null && k.equals(self)) {
            if (isSameFluid(x, y + 1, z, self)) return 1f;
            return ownHeight(s);
        }
        return this.shapes.occludes(s) ? -1f : 0f;
    }

    /**
     * Vanilla {@code FlowingFluid.getOwnHeight()} = {@code getAmount() / 9.0F}, where
     * {@code WaterFluid.Source.getAmount()} is <b>8</b> and {@code WaterFluid.Flowing.getAmount()}
     * is the {@code level} property itself (1..8). So a source is 8/9 tall and a level-8 flowing
     * block is also 8/9; treating level 0 as a full block renders every source one pixel too high.
     */
    private static float ownHeight(BlockState s) {
        String level = s.getValue("level");
        if (level == null) return 8f / 9f;
        try {
            int l = Integer.parseInt(level);
            int amount = (l == 0) ? 8 : Math.min(8, l);
            return amount / 9f;
        } catch (NumberFormatException e) {
            return 8f / 9f;
        }
    }

    /** Vanilla {@code FluidRenderer.calculateAverageHeight}: heights ≥ 0.8 count ten times. */
    private float avgHeight(String self, float hSelf, float h1, float h2,
                            int cornerX, int cornerY, int cornerZ) {
        if (h1 >= 1f || h2 >= 1f) return 1f;
        float sum = 0, weight = 0;
        if (h1 > 0f || h2 > 0f) {
            float hCorner = this.fluidHeight(cornerX, cornerY, cornerZ, self);
            if (hCorner >= 1f) return 1f;
            if (hCorner >= 0.8f) { sum += hCorner * 10f; weight += 10f; }
            else if (hCorner >= 0f) { sum += hCorner; weight += 1f; }
        }
        if (hSelf >= 0.8f) { sum += hSelf * 10f; weight += 10f; } else if (hSelf >= 0f) { sum += hSelf; weight += 1f; }
        if (h1 >= 0.8f) { sum += h1 * 10f; weight += 10f; } else if (h1 >= 0f) { sum += h1; weight += 1f; }
        if (h2 >= 0.8f) { sum += h2 * 10f; weight += 10f; } else if (h2 >= 0f) { sum += h2; weight += 1f; }
        return weight == 0f ? 0f : sum / weight;
    }

    /**
     * Vanilla {@code FlowingFluid.getFlow}: the horizontal direction a fluid is moving.
     *
     * <p>Two details are easy to get wrong and both decide whether water animates at all:
     *
     * <ul>
     *   <li>{@code affectsFlow} counts a neighbour that is <em>empty</em> as well as one holding the
     *       same fluid. Skipping air — comparing only against other fluid blocks — makes the vector
     *       come out zero at every edge and drop, and a zero vector selects the still sprite, so all
     *       water renders as a source block.</li>
     *   <li>A fluid with {@code FALLING} set adds a downward component when a horizontal neighbour
     *       exposes a solid face, which is what orients the texture in a waterfall.</li>
     * </ul>
     *
     * <p>The result only feeds the texture rotation, so its magnitude is irrelevant; the caller
     * tests it against zero.
     */
    private double[] fluidFlow(int x, int y, int z, String self) {
        double flowX = 0, flowZ = 0;
        float selfHeight = ownHeight(this.blockAt(x, y, z));
        int[][] dirs = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};   // north, south, west, east
        for (int[] d : dirs) {
            int nx = x + d[0], nz = z + d[2];
            BlockState ns = this.blockAt(nx, y, nz);
            // affectsFlow: empty or the same fluid. A waterlogged neighbour holds water too, so it
            // participates in the flow calculation like any other water block.
            String nk = fluidKeyOrNull(ns);
            boolean sameFluid = nk != null && nk.equals(self);
            if (!ns.isAir() && !sameFluid) continue;

            float neighborHeight = sameFluid ? ownHeight(ns) : 0f;
            float distance = 0f;
            if (neighborHeight == 0f) {
                BlockState belowNeighbor = this.blockAt(nx, y - 1, nz);
                String bk = fluidKeyOrNull(belowNeighbor);
                if (bk != null && bk.equals(self)) {
                    neighborHeight = ownHeight(belowNeighbor);
                    if (neighborHeight > 0f) {
                        distance = selfHeight - (neighborHeight - MAX_FLUID_HEIGHT);
                    }
                }
            } else {
                distance = selfHeight - neighborHeight;
            }
            if (distance != 0f) {
                flowX += d[0] * distance;
                flowZ += d[2] * distance;
            }
        }

        // FALLING: a fluid pouring down is pulled toward any side that shows a solid face, because
        // that is the side it spills from.
        if (isFalling(this.blockAt(x, y, z))) {
            for (int[] d : dirs) {
                int nx = x + d[0], nz = z + d[2];
                if (isSolidFace(nx, y, nz, self) || isSolidFace(nx, y + 1, nz, self)) {
                    double len = Math.sqrt(flowX * flowX + flowZ * flowZ);
                    if (len > 1e-6) {
                        flowX /= len;
                        flowZ /= len;
                    }
                    flowZ -= 6.0;
                    break;
                }
            }
        }
        double len = Math.sqrt(flowX * flowX + flowZ * flowZ);
        if (len > 1e-6) {
            flowX /= len;
            flowZ /= len;
        }
        return new double[]{flowX, flowZ};
    }

    /** True when the fluid block carries {@code falling=true}, or has no fluid below it. */
    private boolean isFalling(BlockState s) {
        String f = s.getValue("falling");
        if (f != null) return Boolean.parseBoolean(f);
        return false;
    }

    /**
     * Vanilla {@code FlowingFluid.isSolidFace}: the side counts as solid when the neighbour is not
     * the same fluid and its face shape is a full block.
     */
    private boolean isSolidFace(int x, int y, int z, String self) {
        BlockState s = this.blockAt(x, y, z);
        String k = fluidKeyOrNull(s);
        if (k != null && k.equals(self)) return false;
        if (s.isAir()) return false;
        return this.shapes.occludes(s);
    }

    /**
     * Vanilla {@code FluidRenderer.shouldRenderFace}: the neighbour must be a different fluid, and
     * the block's <em>own</em> occlusion shape must not cover this side. Deliberately does not look
     * at the neighbour — that test happens at draw time with the fluid's real height, because the
     * question there is "does the neighbour's shape cover a box of this height", which is what
     * {@link #isFluidFaceOccludedBy} answers.
     */
    private boolean shouldRenderFluidFace(int x, int y, int z, String self) {
        return !isSameFluid(x, y, z, self) && !this.shapes.occludes(this.blockAt(x, y, z));
    }

    /**
     * Vanilla {@code FluidRenderer.isFaceOccludedByState}: the neighbour's face-occlusion shape is
     * compared against a box the size of the fluid (0..height). Three cases:
     * <ul>
     *   <li>empty shape (glass, leaves, anything built with {@code noOcclusion}) — never hides;</li>
     *   <li>full block — hides every side of the fluid, and the top only when the fluid is full;</li>
     *   <li>partial shape — a real BooleanOp we approximate by treating any partial neighbour as
     *       non-covering, which is right for the blocks that appear in schematics.</li>
     * </ul>
     */
    private boolean isFluidFaceOccludedBy(int face, float height, BlockState neighbor) {
        if (neighbor == null || neighbor.isAir()) return false;
        if (!this.shapes.occludes(neighbor)) return false;
        boolean fullBlock = height == 1.0f;
        return face != Quad.UP || fullBlock;
    }

    // ------------------------------------------------------------------
    // Layers

    /**
     * Chooses the render layer, following vanilla's {@code ChunkSectionLayer.byTransparency}
     * (translucent wins, then cutout, else solid) on the resolved sprite, plus quickcraft's split
     * of the translucent bucket into PORTAL / FLUID / TRANSLUCENT.
     *
     * <p>The split is not cosmetic. Water, glass and portals all blend, and without an
     * order-independent transparency pass a single bucket sorted by depth cannot get every
     * viewpoint right. quickcraft's v17 note records exactly this fix: "传送门、流体和普通半透明
     * 模型拆分缓存，避免玻璃遮挡传送门及流体断层".
     *
     * <p>A model may also declare {@code "force_translucent": true} on a texture (redstone dust and
     * its overlay both do). Vanilla honours that flag over the sprite's own transparency, which is
     * why dust draws in the translucent pass even though its texture has hard alpha edges.
     */
    private SceneMesh.Layer layerOf(String blockId, String spriteId, boolean forceTranslucent) {
        if (blockId.equals("minecraft:nether_portal")) return SceneMesh.Layer.PORTAL;
        // Fluids join TRANSLUCENT, not a layer of their own.
        //
        // Vanilla has exactly three terrain layers — SOLID, CUTOUT, TRANSLUCENT — and sorts a fluid
        // into them by its texture's transparency (ChunkSectionLayer.byTransparency). Water is
        // translucent, so it shares the glass layer and is drawn after cutout. Giving fluid its own
        // later pass drew it after cutout had already written depth, and the water inside a
        // waterlogged leaf then failed the depth test against the leaf's own opaque pixels.
        if (FLUIDS.contains(blockId)) return SceneMesh.Layer.TRANSLUCENT;
        if (forceTranslucent) return SceneMesh.Layer.TRANSLUCENT;
        if (TRANSLUCENT_IDS.contains(blockId)) return SceneMesh.Layer.TRANSLUCENT;
        if (spriteId != null) {
            SpriteAtlas.Entry e = this.atlas.get(spriteId).orElse(null);
            if (e != null) {
                if (e.contents.hasTranslucent) return SceneMesh.Layer.TRANSLUCENT;
                if (e.contents.hasTransparent) return SceneMesh.Layer.CUTOUT;
                return SceneMesh.Layer.SOLID;
            }
        }
        return SceneMesh.Layer.SOLID;
    }

    /** Blocks the game always draws in the translucent pass regardless of texture alpha. */
    private static final java.util.Set<String> TRANSLUCENT_IDS = java.util.Set.of(
        "minecraft:ice", "minecraft:frosted_ice", "minecraft:slime_block", "minecraft:honey_block"
    );
}
