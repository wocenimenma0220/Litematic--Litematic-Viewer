package io.github.litematicrender.render.scene;

import io.github.litematicrender.world.BlockShape;
import io.github.litematicrender.world.BlockState;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * A single-pass fluid flow simulation, so flowing water shows its level gradient.
 *
 * <p>A litematic stores every water block as a source ({@code water} with no level). The game turns
 * that into the familiar stepped surface by ticking the fluid: a source spreads to adjacent cells
 * whose floor is at least as open as its own, losing one level per horizontal step, and drops
 * without limit where the floor is missing. {@code FlowingFluid.tick} plus {@code getNewFluid} is
 * the rule set; this class re-runs it over the schematic grid with two practical bounds:
 *
 * <ul>
 *   <li>the simulation is confined to the schematic's bounding box — nothing spills outside it,
 *         which is also what keeps the cell array bounded;</li>
 *   <li>a fixed number of sweeps bounds the work on pathological inputs.</li>
 * </ul>
 *
 * <p>The output is a level (0..8, 8 = source) for every water cell in the box, written back as the
 * blockstate's {@code level} property so the fluid renderer needs no special cases.
 */
public final class FluidSimulation {
    private final BlockState[] cells;
    private final byte[] level;         // 0 = not water; 8 = source
    private final boolean[] falling;
    private final int sx, sy, sz;
    private final BlockShape shapes;
    private final int maxSweeps;

    public FluidSimulation(BlockState[] cells, int sx, int sy, int sz, BlockShape shapes, int maxSweeps) {
        this.cells = cells;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.shapes = shapes;
        this.maxSweeps = maxSweeps;
        this.level = new byte[cells.length];
        this.falling = new boolean[cells.length];
    }

    /** The simulated level grid (8 = source … 1 = thinnest), for the renderer to sample. */
    public byte[] levels() {
        return this.level;
    }

    private int idx(int x, int y, int z) {
        return (y * this.sz + z) * this.sx + x;
    }

    private boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < this.sx && y < this.sy && z < this.sz;
    }

    private boolean isWater(int x, int y, int z) {
        return this.inside(x, y, z) && this.level[this.idx(x, y, z)] > 0;
    }

    private boolean passable(int x, int y, int z) {
        if (!this.inside(x, y, z)) return false;
        BlockState s = this.cells[this.idx(x, y, z)];
        // Water flows through anything light-permeable that is not already water: air, but also
        // waterlogged-able blocks it can replace. Schematics only carry the final state, so the
        // passable set is "the cell is air (or the water we are simulating)".
        return s == null || s.isAir();
    }

    private boolean solidFloor(int x, int y, int z) {
        // A floor blocks downward flow when it occludes — glass, slab tops and leaves do not.
        if (!this.inside(x, y, z)) return true;      // outside the box: treat as contained
        BlockState s = this.cells[this.idx(x, y, z)];
        if (s == null || s.isAir()) return false;
        return this.shapes.occludes(s);
    }

    /** Runs the simulation; afterwards {@link #apply} writes the results into {@code cells}. */
    public void run() {
        Queue<int[]> queue = new ArrayDeque<>();
        // Seed: every existing water block is a source (level 8).
        for (int i = 0; i < this.cells.length; i++) {
            BlockState s = this.cells[i];
            if (s != null && s.blockId.equals("minecraft:water")) {
                this.level[i] = 8;
            }
        }
        for (int i = 0; i < this.cells.length; i++) {
            if (this.level[i] == 8) {
                int x = i % this.sx;
                int z = (i / this.sx) % this.sz;
                int y = (i / this.sx) / this.sz;
                queue.add(new int[]{x, y, z});
            }
        }

        // Flowing water spreads from sources; falling columns mark themselves and refresh the
        // queue. A BFS by level distance reproduces the game's steady state for a static snapshot.
        for (int sweep = 0; sweep < this.maxSweeps && !queue.isEmpty(); sweep++) {
            Queue<int[]> next = new ArrayDeque<>();
            for (int[] cell : queue) {
                int x = cell[0], y = cell[1], z = cell[2];
                int my = this.level[this.idx(x, y, z)];
                if (my <= 1) continue;

                // Falling: if the cell below is passable, water drops there at full falling level.
                if (this.passable(x, y - 1, z)) {
                    int bi = this.idx(x, y - 1, z);
                    if (this.level[bi] < 8) {
                        this.level[bi] = 8;
                        this.falling[bi] = true;
                        next.add(new int[]{x, y - 1, z});
                    }
                    continue;
                }

                // Horizontal spread: each open neighbour gets level-1 if that is an upgrade.
                int nextLevel = my - 1;
                if (nextLevel <= 0) continue;
                int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
                for (int[] d : dirs) {
                    int nx = x + d[0], nz = z + d[1];
                    if (!this.passable(nx, y, nz)) continue;
                    int ni = this.idx(nx, y, nz);
                    if (this.level[ni] < nextLevel) {
                        this.level[ni] = (byte) nextLevel;
                        this.falling[ni] = false;
                        next.add(new int[]{nx, y, nz});
                    }
                }
            }
            queue = next;
        }
    }

    /** Writes the simulated levels back as {@code level} properties on the water cells. */
    public void apply() {
        for (int i = 0; i < this.cells.length; i++) {
            if (this.level[i] <= 0) continue;
            BlockState s = this.cells[i];
            if (s == null) continue;
            int lv = this.level[i] == 8 ? 0 : (8 - this.level[i]);
            // Game semantics: the blockstate "level" is 0 for a source and 1..7 flowing; our
            // simulated level counts 8 down to 1 in the opposite direction.
            BlockState withLevel = s.tryWith("level", String.valueOf(lv == 0 ? 0 : lv));
            if (this.falling[i]) withLevel = withLevel.tryWith("falling", "true");
            this.cells[i] = withLevel;
        }
    }
}
