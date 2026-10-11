package io.github.litematicrender.render.scene;

import io.github.litematicrender.world.BlockShape;
import io.github.litematicrender.world.BlockState;

/**
 * Offline light propagation for a schematic: sky light and block light, stored per cell and later
 * sampled as a per-vertex light value.
 *
 * <p>Follows the vanilla engine's semantics ({@code LightEngine} + {@code SkyLightEngine}) reduced
 * to a closed world:
 *
 * <ul>
 *   <li><b>Sky light</b> seeds 15 on every column top that sees the sky (nothing occluding above
 *       it), propagates <em>downward without attenuation</em> through light-permeable cells, and
 *       loses one level per step in every other direction. Water costs two per step, per
 *       {@code WaterFluid}.</li>
 *   <li><b>Block light</b> seeds each light-emitting block with its own emission value and loses
 *       one level per step.</li>
 * </ul>
 *
 * <p>Both channels run the vanilla two-phase queue: increases spread outward while a neighbour can
 * raise them, then decreases sweep in from every removal point. On a closed snapshot a single
 * pass of each is enough, so the removal phase is only needed for block light when an emitter is
 * enclosed — which the increase phase handles anyway, because enclosed light simply never spreads.
 *
 * <p>The result is two nibble grids. {@link #vertexLight} combines them the way the game does when
 * it builds its lightmap: the brighter of the two channels.
 */
public final class LightVolume {
    private final int sx, sy, sz;
    private final BlockState[] cells;
    private final BlockShape shapes;
    private final byte[] sky;
    private final byte[] block;

    /** Highest block-light emission present in the scene; 0 means no emitters at all. */
    private int maxEmission;

    public LightVolume(int sx, int sy, int sz, BlockState[] cells, BlockShape shapes) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.cells = cells;
        this.shapes = shapes;
        this.sky = new byte[sx * sy * sz];
        this.block = new byte[sx * sy * sz];
    }

    private int idx(int x, int y, int z) {
        return (y * this.sz + z) * this.sx + x;
    }

    private boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < this.sx && y < this.sy && z < this.sz;
    }

    private BlockState at(int x, int y, int z) {
        return this.cells[this.idx(x, y, z)];
    }

    private boolean opaque(int x, int y, int z) {
        return this.shapes.occludes(this.at(x, y, z));
    }

    private boolean permeable(int x, int y, int z) {
        return this.shapes.lightPermeable(this.at(x, y, z));
    }

    /** Runs both channels. Call once, after construction. */
    public void compute() {
        this.seedSky();
        this.spreadSky();
        this.seedBlock();
        this.spreadBlock();
    }

    // ------------------------------------------------------------------ sky light

    /**
     * Seeds 15 on the highest light-permeable cell of each column and everything open above it.
     *
     * <p>A column whose top cell is opaque has no sky access at all; one capped by water starts the
     * seed below the water surface, and the water itself attenuates as the spread runs.
     */
    private void seedSky() {
        for (int z = 0; z < this.sz; z++) {
            for (int x = 0; x < this.sx; x++) {
                int y = this.sy - 1;
                // Skip opaque cells from the top; the first permeable one is the lit surface.
                while (y >= 0 && !this.permeable(x, y, z)) y--;
                for (; y >= 0; y--) {
                    if (this.permeable(x, y, z)) this.sky[this.idx(x, y, z)] = 15;
                }
            }
        }
    }

    private void spreadSky() {
        // Vanilla propagates sky downward for free. That is equivalent to seeding every open column
        // at 15 (done above) and then running one uniform BFS with down-steps free while the light
        // is at 15. The queue here implements exactly that: a downward step does not attenuate when
        // the source level is 15 and the target is permeable.
        int[] qx = new int[this.sky.length];
        int[] qy = new int[this.sky.length];
        int[] qz = new int[this.sky.length];
        int head = 0, tail = 0;
        for (int i = 0; i < this.sky.length; i++) {
            if (this.sky[i] > 1) { qx[tail] = i % this.sx; qy[tail] = (i / this.sx) / this.sz; qz[tail] = (i / this.sx) % this.sz; tail++; }
        }
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while (head < tail) {
            int x = qx[head], y = qy[head], z = qz[head];
            head++;
            int level = this.sky[this.idx(x, y, z)];
            if (level <= 1) continue;
            for (int[] d : dirs) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (!this.inside(nx, ny, nz) || !this.permeable(nx, ny, nz)) continue;
                boolean down = d[1] == -1 && level == 15;
                BlockState target = this.at(nx, ny, nz);
                int cost = down ? 0 : (target != null && target.blockId.equals("minecraft:water") ? 2 : 1);
                int next = level - cost;
                if (next > this.sky[this.idx(nx, ny, nz)]) {
                    this.sky[this.idx(nx, ny, nz)] = (byte) next;
                    if (next > 1 && tail < qx.length) {
                        qx[tail] = nx; qy[tail] = ny; qz[tail] = nz; tail++;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ block light

    private void seedBlock() {
        for (int i = 0; i < this.cells.length; i++) {
            BlockState s = this.cells[i];
            if (s == null) continue;
            int emission = EMISSIONS.getOrDefault(s.blockId, 0);
            if (emission > 0) {
                this.block[i] = (byte) emission;
                this.maxEmission = Math.max(this.maxEmission, emission);
            }
        }
    }

    private void spreadBlock() {
        if (this.maxEmission == 0) return;
        int[] qx = new int[this.block.length];
        int[] qy = new int[this.block.length];
        int[] qz = new int[this.block.length];
        int head = 0, tail = 0;
        for (int i = 0; i < this.block.length; i++) {
            if (this.block[i] > 1) { qx[tail] = i % this.sx; qy[tail] = (i / this.sx) / this.sz; qz[tail] = (i / this.sx) % this.sz; tail++; }
        }
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while (head < tail) {
            int x = qx[head], y = qy[head], z = qz[head];
            head++;
            int level = this.block[this.idx(x, y, z)];
            if (level <= 1) continue;
            for (int[] d : dirs) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (!this.inside(nx, ny, nz) || !this.permeable(nx, ny, nz)) continue;
                int next = level - 1;
                if (next > this.block[this.idx(nx, ny, nz)]) {
                    this.block[this.idx(nx, ny, nz)] = (byte) next;
                    if (next > 1 && tail < qx.length) {
                        qx[tail] = nx; qy[tail] = ny; qz[tail] = nz; tail++;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ sampling

    /**
     * The light value a face should use: the brighter of the two channels sampled one step toward
     * the face, matching how vanilla samples the neighbour cell for a flush face.
     *
     * @param dx,dy,dz the face normal in steps (-1, 0 or +1)
     */
    public int faceLight(int x, int y, int z, int dx, int dy, int dz) {
        int nx = x + dx, ny = y + dy, nz = z + dz;
        if (!this.inside(nx, ny, nz)) return 15;               // outside the build: full sky
        int i = this.idx(nx, ny, nz);
        // A flush face on an opaque neighbour samples the light IN FRONT of it, which is the cell
        // the face looks into — that is (nx,ny,nz) itself.
        return Math.max(this.sky[i] & 0xFF, this.block[i] & 0xFF);
    }

    /** The combined light at a cell, used for unculled geometry (crosses, torches). */
    public int cellLight(int x, int y, int z) {
        if (!this.inside(x, y, z)) return 15;
        int i = this.idx(x, y, z);
        return Math.max(this.sky[i] & 0xFF, this.block[i] & 0xFF);
    }

    /** Light-emitting blocks by id; the game's luminance table, reduced to what schematics carry. */
    private static final java.util.Map<String, Integer> EMISSIONS = java.util.Map.ofEntries(
        java.util.Map.entry("minecraft:torch", 14),
        java.util.Map.entry("minecraft:soul_torch", 10),
        java.util.Map.entry("minecraft:redstone_torch", 7),
        java.util.Map.entry("minecraft:wall_torch", 14),
        java.util.Map.entry("minecraft:soul_wall_torch", 10),
        java.util.Map.entry("minecraft:lantern", 15),
        java.util.Map.entry("minecraft:soul_lantern", 10),
        java.util.Map.entry("minecraft:glowstone", 15),
        java.util.Map.entry("minecraft:sea_lantern", 15),
        java.util.Map.entry("minecraft:shroomlight", 15),
        java.util.Map.entry("minecraft:ochre_froglight", 15),
        java.util.Map.entry("minecraft:verdant_froglight", 15),
        java.util.Map.entry("minecraft:pearlescent_froglight", 15),
        java.util.Map.entry("minecraft:beacon", 15),
        java.util.Map.entry("minecraft:end_rod", 14),
        java.util.Map.entry("minecraft:campfire", 15),
        java.util.Map.entry("minecraft:soul_campfire", 10),
        java.util.Map.entry("minecraft:jack_o_lantern", 15),
        java.util.Map.entry("minecraft:lava", 15),
        java.util.Map.entry("minecraft:magma_block", 3),
        java.util.Map.entry("minecraft:glow_lichen", 7),
        java.util.Map.entry("minecraft:amethyst_cluster", 5),
        java.util.Map.entry("minecraft:crying_obsidian", 10),
        java.util.Map.entry("minecraft:light", 15),
        java.util.Map.entry("minecraft:ochre_candle", 3)   // candles vary; 3 is the lit baseline
    );
}
