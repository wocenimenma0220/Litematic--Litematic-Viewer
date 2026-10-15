package io.github.litematicrender.render.scene;

import io.github.litematicrender.world.BlockShape;
import io.github.litematicrender.world.BlockState;

/**
 * Offline light propagation for a schematic: sky light and block light, stored per cell and later
 * sampled as a per-vertex light value.
 *
 * <p>Defaults to the lighting appearance shipped in 1.0.8; the optional realLighting mode
 * restores 1.0.10 sky occlusion and state-dependent emission. Dense and sparse volumes
 * share the same sampling contract, with compact queues and allocation guards.
 *
 * <ul>
 *   <li><b>Sky light</b> uses the old full-sky seed for permeable cells, including covered cells.</li>
 *   <li><b>Block light</b> uses the 1.0.8 emission table and loses one level per step.</li>
 * </ul>
 *
 * <p>FIFO propagation preserves the old bounded-queue order while storing one voxel index
 * per entry. Interactive removal/decrease propagation is not needed.
 *
 * <p>The result is two nibble grids plus the lightmap composition in {@link #brightness}, which
 * mirrors the game's {@code core/lightmap.fsh}. Sky and block light stay separate until that final
 * step: the shader samples them as the two axes of a 16x16 texture and adds them, so merging them
 * earlier is a lossy shortcut and is what used to turn unlit faces pure black.
 */
public final class LightVolume implements LightSampler {
    private final int sx, sy, sz;
    private final BlockState[] cells;
    @FunctionalInterface public interface Cells {BlockState at(int x,int y,int z);}
    private final Cells source;
    private final BlockShape shapes;
    private final LightGrid sky;
    private final LightGrid block;
    private final boolean realLighting;

    /** Highest block-light emission present in the scene; 0 means no emitters at all. */
    private int maxEmission;

    /** Reserve room for references, light channels and propagation queues before allocating. */
    public static int checkedCellCount(long sx, long sy, long sz) {
        long count;
        try { count = Math.multiplyExact(Math.multiplyExact(sx, sy), sz); }
        catch (ArithmeticException error) { throw new IllegalArgumentException("投影区域跨度过大，无法计算光照；请缩小区域之间的距离。", error); }
        if (sx <= 0 || sy <= 0 || sz <= 0 || count > Integer.MAX_VALUE - 8)
            throw new IllegalArgumentException("投影区域跨度过大，无法计算光照；请缩小区域之间的距离。");
        Runtime runtime = Runtime.getRuntime();
        long available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        // Conservative references/channels/queue estimate with headroom for geometry and caches.
        if (count > Math.max(1, available / 32))
            throw new IllegalArgumentException("投影区域跨度过大，光照所需内存不足；请拆分投影或缩小空白区域。");
        return (int)count;
    }

    public LightVolume(int sx, int sy, int sz, BlockState[] cells, BlockShape shapes) {
        this(sx, sy, sz, cells, shapes, false);
    }

    public LightVolume(int sx, int sy, int sz, BlockState[] cells, BlockShape shapes, boolean realLighting) {
        this.realLighting = realLighting;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.cells = cells;
        this.source = null;
        this.shapes = shapes;
        int count = checkedCellCount(sx, sy, sz);
        if (cells.length != count) throw new IllegalArgumentException("light volume dimensions do not match cells");
        this.sky = new LightGrid(count);
        this.block = new LightGrid(count);
    }
    /** Streamed geometry needs only light channels and queues; block states stay bit-packed. */
    public static int checkedCompactCellCount(long sx,long sy,long sz) {
        long count;try{count=Math.multiplyExact(Math.multiplyExact(sx,sy),sz);}catch(ArithmeticException error){throw new IllegalArgumentException("光照范围过大",error);}
        if(sx<=0||sy<=0||sz<=0||count>Integer.MAX_VALUE-8)throw new IllegalArgumentException("光照范围过大");
        Runtime runtime=Runtime.getRuntime();long needed=count*12+32L*1024*1024;
        if(needed>runtime.maxMemory()*.85)throw new IllegalArgumentException("光照网格超出内存预算");
        long available=runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory());
        if(available<needed){System.gc();available=runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory());}
        if(available<needed)throw new IllegalArgumentException("光照网格内存不足");return (int)count;
    }
    public LightVolume(int sx,int sy,int sz,Cells source,BlockShape shapes,boolean realLighting) {
        this.sx=sx;this.sy=sy;this.sz=sz;this.source=source;this.cells=null;this.shapes=shapes;this.realLighting=realLighting;
        int count=checkedCompactCellCount(sx,sy,sz);this.sky=new LightGrid(count);this.block=new LightGrid(count);
    }

    public long payloadBytes(){return sky.payloadBytes()+block.payloadBytes();}

    private int idx(int x, int y, int z) {
        return (y * this.sz + z) * this.sx + x;
    }

    private boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < this.sx && y < this.sy && z < this.sz;
    }

    private BlockState at(int x, int y, int z) {
        return source==null?this.cells[this.idx(x, y, z)]:source.at(x,y,z);
    }

    private boolean opaque(int x, int y, int z) {
        return this.shapes.occludes(this.at(x, y, z));
    }

    private boolean permeable(int x, int y, int z) {
        return this.shapes.lightPermeable(this.at(x, y, z));
    }

    /** Runs both channels. Call once, after construction. */
    public void compute() {
        if (this.realLighting) {
            seedRealSky();
            spreadReal(this.sky, true);
            seedBlock();
            spreadReal(this.block, false);
            sky.compact();block.compact();return;
        }
        this.seedSky();
        this.spreadSky();
        this.seedBlock();
        this.spreadBlock();sky.compact();block.compact();
    }

    // ------------------------------------------------------------------ sky light

    /** 1.0.10 direct sky: roofs block columns; water and leaves absorb light. */
    private void seedRealSky() {
        sky.fill(15);
        for (int z=0;z<sz;z++) for (int x=0;x<sx;x++) {
            if(x==0&&Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            int level=15;
            for (int y=sy-1;y>=0;y--) {
                BlockState state=at(x,y,z);
                int opacity=skyOpacity(state,shapes);
                level=Math.max(0,level-(opacity==0 && level==15?0:Math.max(1,opacity)));
                sky.set(idx(x,y,z),shapes.lightPermeable(state)?level:0);
            }
        }
    }

    private static final int[][] DIRECTIONS={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    /** Increasing FIFO with one pending entry per cell and storage proportional to the frontier. */
    private void spreadReal(LightGrid values, boolean skylight) {
        var queue=new IntQueue(values.length);
        var queued=new java.util.BitSet(values.length);
        for(int i=0;i<values.length;i++)if(values.get(i)>1) {
            int x=i%sx,y=i/sx/sz,z=i/sx%sz;
            if(couldIncrease(values,x,y,z,values.get(i),skylight)){queue.add(i);queued.set(i);}
        }
        while(queue.size>0) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            int position=queue.remove();queued.clear(position);
            int x=position%sx,y=position/sx/sz,z=position/sx%sz,level=values.get(position);
            if(level<=1)continue;
            for(int d=0;d<6;d++) {
                int nx=x+DIRECTIONS[d][0],ny=y+DIRECTIONS[d][1],nz=z+DIRECTIONS[d][2];
                if(!inside(nx,ny,nz))continue;
                int destination=idx(nx,ny,nz),upper=skylight&&d==3&&level==15?15:level-1;
                if(values.get(destination)>=upper)continue;
                BlockState target=at(nx,ny,nz);
                if(!shapes.lightPermeable(target))continue;
                int opacity=skylight?skyOpacity(target,shapes):blockOpacity(target,shapes);
                int cost=skylight&&d==3&&level==15&&opacity==0?0:Math.max(1,opacity);
                int next=level-cost;
                if(next>values.get(destination)) {
                    values.set(destination,next);
                    if(next>1&&!queued.get(destination)){queue.add(destination);queued.set(destination);}
                }
            }
        }
    }
    private boolean couldIncrease(LightGrid values,int x,int y,int z,int level,boolean skylight) {
        for(int d=0;d<6;d++) {
            int nx=x+DIRECTIONS[d][0],ny=y+DIRECTIONS[d][1],nz=z+DIRECTIONS[d][2];
            if(inside(nx,ny,nz)&&values.get(idx(nx,ny,nz))<(skylight&&d==3&&level==15?15:level-1))return true;
        }
        return false;
    }
    private static final class IntQueue {
        private final int limit;
        private int[] values=new int[0];private int head,size;
        IntQueue(int limit){this.limit=limit;}
        void add(int value) {
            if(size==values.length) {
                int capacity=(int)Math.min(limit,Math.max(1024L,(long)values.length*2));
                if(capacity<=values.length)throw new IllegalStateException("light queue overflow");
                int[] grown=new int[capacity];
                for(int i=0;i<size;i++)grown[i]=values[(head+i)%values.length];
                values=grown;head=0;
            }
            values[(head+size)%values.length]=value;size++;
        }
        int remove(){int result=values[head];head=(head+1)%values.length;size--;return result;}
    }

    /**
     * Seeds 15 on the highest light-permeable cell of each column and everything open above it.
     *
     * <p>A column whose top cell is opaque has no sky access at all; one capped by water starts the
     * seed below the water surface, and the water itself attenuates as the spread runs.
     */
    private void seedSky() {
        for (int z = 0; z < this.sz; z++) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            for (int x = 0; x < this.sx; x++) {
                // Preserve the brighter 1.0.8 snapshot, including covered permeable cells.
                int y = this.sy - 1;
                while (y >= 0 && !this.permeable(x, y, z)) y--;
                for (; y >= 0; y--) if (this.permeable(x, y, z)) this.sky.set(this.idx(x, y, z),15);
            }
        }
    }

    private void spreadSky() {
        // Vanilla propagates sky downward for free. That is equivalent to seeding every open column
        // at 15 (done above) and then running one uniform BFS with down-steps free while the light
        // is at 15. The queue here implements exactly that: a downward step does not attenuate when
        // the source level is 15 and the target is permeable.
        int[] queue = new int[this.sky.length];
        int head=0,tail=0;
        for (int i = 0; i < this.sky.length; i++) {
            if (this.sky.get(i) > 1) queue[tail++]=i;
        }
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while (head<tail) {
            if((head&4095)==0&&Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            int position=queue[head++];
            int x=position%this.sx,y=(position/this.sx)/this.sz,z=(position/this.sx)%this.sz;
            int level = this.sky.get(this.idx(x, y, z));
            if (level <= 1) continue;
            for (int[] d : dirs) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (!this.inside(nx, ny, nz) || !this.permeable(nx, ny, nz)) continue;
                boolean down = d[1] == -1 && level == 15;
                BlockState target = this.at(nx, ny, nz);
                int cost = down ? 0 : (target != null && target.blockId.equals("minecraft:water") ? 2 : 1);
                int next = level - cost;
                if (next > this.sky.get(this.idx(nx, ny, nz))) {
                    this.sky.set(this.idx(nx, ny, nz),next);
                    int destination=this.idx(nx,ny,nz);
                    if(next>1 && tail<queue.length) queue[tail++]=destination;
                }
            }
        }
    }

    // ------------------------------------------------------------------ block light

    private void seedBlock() {
        for (int i = 0; i < this.block.length; i++) {
            BlockState s = source==null?this.cells[i]:source.at(i%sx,(i/sx)/sz,(i/sx)%sz);
            if (s == null) continue;
            int emission = this.realLighting ? realEmission(s) : emission(s);
            if (emission > 0) {
                this.block.set(i,emission);
                this.maxEmission = Math.max(this.maxEmission, emission);
            }
        }
    }

    private void spreadBlock() {
        if (this.maxEmission == 0) return;
        int[] queue = new int[this.block.length];
        int head=0,tail=0;
        for (int i = 0; i < this.block.length; i++) {
            if (this.block.get(i) > 1) queue[tail++]=i;
        }
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while(head<tail) {
            if((head&4095)==0&&Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            int position=queue[head++];
            int x=position%this.sx,y=(position/this.sx)/this.sz,z=(position/this.sx)%this.sz;
            int level = this.block.get(this.idx(x, y, z));
            if (level <= 1) continue;
            for (int[] d : dirs) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (!this.inside(nx, ny, nz) || !this.permeable(nx, ny, nz)) continue;
                int next = level - 1;
                if (next > this.block.get(this.idx(nx, ny, nz))) {
                    this.block.set(this.idx(nx, ny, nz),next);
                    int destination=this.idx(nx,ny,nz);
                    if(next>1 && tail<queue.length) queue[tail++]=destination;
                }
            }
        }
    }

    // ------------------------------------------------------------------ sampling

    /**
     * The light value a face should use, sampled one step toward the face — the cell the face looks
     * into, which is how vanilla picks the lightmap coordinates for a flush face.
     *
     * <p>Sky and block light are returned as two separate channels, not collapsed to their maximum.
     * The game keeps them apart all the way to the shader (they are the two axes of the 16x16
     * lightmap texture, packed as {@code UV2} and sampled independently), and collapsing them here
     * throws away information the brightness curve needs — see {@link #brightness}.
     *
     * @param dx,dy,dz the face normal in steps (-1, 0 or +1)
     * @return packed {@code (sky << 8) | block}, both 0..15
     */
    public int faceLight(int x, int y, int z, int dx, int dy, int dz) {
        int nx = x + dx, ny = y + dy, nz = z + dz;
        if (!this.inside(nx, ny, nz)) return pack(15, 0);       // outside the build: full sky
        int i = this.idx(nx, ny, nz);
        return pack(this.sky.get(i) & 0xFF, this.block.get(i) & 0xFF);
    }

    /** The two light channels at a cell, used for unculled geometry (crosses, torches). */
    public int cellLight(int x, int y, int z) {
        if (!this.inside(x, y, z)) return pack(15, 0);
        int i = this.idx(x, y, z);
        return pack(this.sky.get(i) & 0xFF, this.block.get(i) & 0xFF);
    }

    private static int pack(int sky, int block) {
        return ((sky & 0xFF) << 8) | (block & 0xFF);
    }
    static int emission(BlockState state) {
        return state == null ? 0 : EMISSIONS.getOrDefault(state.blockId,0);
    }

    static int skyOpacity(BlockState state, BlockShape shapes) {
        if(state==null||state.isAir())return 0;
        if(state.blockId.equals("minecraft:tinted_glass")||shapes.occludes(state))return 15;
        return state.blockId.equals("minecraft:water")||"true".equals(state.getValue("waterlogged"))||state.blockId.endsWith("_leaves")?1:0;
    }

    // Block absorption is independent of render texture transparency.
    static int blockOpacity(BlockState state,BlockShape shapes) { return skyOpacity(state,shapes); }

    /** State-dependent emission table restored from 1.0.10. */
    static int realEmission(BlockState state) {
        if(state==null||"false".equals(state.getValue("lit")))return 0;
        String id=state.blockId;
        if(id.equals("minecraft:light")) {
            try{return Math.max(0,Math.min(15,Integer.parseInt(state.getValue("level"))));}catch(Exception ignored){return 15;}
        }
        if(id.equals("minecraft:redstone_lamp"))return "true".equals(state.getValue("lit"))?15:0;
        if(id.equals("minecraft:furnace")||id.equals("minecraft:smoker")||id.equals("minecraft:blast_furnace"))return "true".equals(state.getValue("lit"))?13:0;
        if(id.equals("minecraft:candle")||id.endsWith("_candle")) {
            if(!"true".equals(state.getValue("lit")))return 0;
            try{return Math.min(12,Integer.parseInt(state.getValue("candles"))*3);}catch(Exception ignored){return 3;}
        }
        return switch(id) {
            case "minecraft:fire" -> 15;
            case "minecraft:soul_fire" -> 10;
            case "minecraft:redstone_wall_torch" -> 7;
            default -> emission(state);
        };
    }

    /**
     * The channel-wise maximum of two packed light values.
     *
     * <p>Vanilla {@code FluidRenderer.getLightCoords} is
     * {@code LightCoordsUtil.max(getLightCoords(pos), getLightCoords(pos.above()))}: a fluid is lit
     * by whichever of its own cell and the cell above is brighter, so a waterfall's own surface is
     * not shadowed by the water it is falling into. {@code LightCoordsUtil.max} takes the two
     * channels separately, which is what this reproduces.
     */
    public static int max(int a, int b) {
        return pack(Math.max(skyOf(a), skyOf(b)), Math.max(blockOf(a), blockOf(b)));
    }

    /** Sky channel of a packed light value, 0..15. */
    public static int skyOf(int packed) { return (packed >> 8) & 0xFF; }

    /** Block channel of a packed light value, 0..15. */
    public static int blockOf(int packed) { return packed & 0xFF; }

    // ------------------------------------------------------------------ lightmap

    /** Vanilla {@code Lightmap.getBrightness}: {@code v/(4-3v)} with {@code v = level/15}. */
    public static float curve(int level) {
        float v = Math.max(0, Math.min(15, level)) / 15f;
        return v / (4f - 3f * v);
    }

    /**
     * The ambient floor colour, matching the overworld's {@code visual/ambient_light_color}
     * ({@code #0a0a0a} = 10/255), with a minimum of one 255th so a completely unlit surface stays
     * faintly visible rather than collapsing to pure black.
     *
     * <p>Vanilla's {@code LightmapRenderStateExtractor} takes the larger of this ambient colour and
     * the dimension's {@code ambient_light} scalar-derived colour; the {@code max(1/255, …)} is the
     * same clamp the game applies when it packs the lightmap texture, and it is what vanilla's own
     * lightmap shader relies on for its {@code max(AmbientColor, NightVisionColor)} term.
     */
    private static final float AMBIENT = Math.max(1f / 255f, 10f / 255f);

    /** {@code EnvironmentAttributes.BLOCK_LIGHT_TINT}: {@code ARGB.vector3fFromRGB24(-10100)}. */
    private static final float BLOCK_TINT_R = ((-10100 >> 16) & 0xFF) / 255f;
    private static final float BLOCK_TINT_G = ((-10100 >> 8) & 0xFF) / 255f;
    private static final float BLOCK_TINT_B = (-10100 & 0xFF) / 255f;

    /**
     * A packed light value turned into the RGB factor a texture is multiplied by.
     *
     * <p>Port of vanilla's lightmap shader ({@code core/lightmap.fsh}), which is what actually
     * converts light levels into colour. Reproducing it matters more than it looks: the common
     * shortcut — take the brighter of the two channels and run it through {@code getBrightness} —
     * is <em>not</em> equivalent. It loses water attenuation and the block-light tint, and
     * most visibly it sends any cell with sky 0 and block 0 to a literal zero, which is what made
     * scattered faces render as solid black squares.
     *
     * <p>Two deviations from the shader, both deliberate: the {@code gamma} option (BrightnessFactor)
     * is left at 0, since the offline renderer has no options screen, and night vision, the darkness
     * effect and the boss-overlay darkening are all absent from a schematic snapshot.
     *
     * @param packed a value from {@link #faceLight} or {@link #cellLight}
     * @param out    receives r, g, b in 0..1
     */
    public static void brightness(int packed, float[] out) {
        float blockLevel = blockOf(packed) / 15f;
        float skyLevel = skyOf(packed) / 15f;
        float blockBrightness = curve(blockOf(packed));
        float skyBrightness = curve(skyOf(packed));

        // color = ambient; color += skyLightColor * skyBrightness; color += blockLightColor * blockBrightness
        // SkyLightColor is white (EnvironmentAttributes.SKY_LIGHT_COLOR default 0xFFFFFF).
        float r = AMBIENT + skyBrightness;
        float g = AMBIENT + skyBrightness;
        float b = AMBIENT + skyBrightness;

        // BlockLightColor = mix(BlockLightTint, white, 0.9 * parabolicMixFactor(block_level))
        float mix = 0.9f * (2f * blockLevel - 1f) * (2f * blockLevel - 1f);
        float br = BLOCK_TINT_R + (1f - BLOCK_TINT_R) * mix;
        float bg = BLOCK_TINT_G + (1f - BLOCK_TINT_G) * mix;
        float bb = BLOCK_TINT_B + (1f - BLOCK_TINT_B) * mix;
        r += br * blockBrightness;
        g += bg * blockBrightness;
        b += bb * blockBrightness;

        out[0] = Math.min(1f, r);
        out[1] = Math.min(1f, g);
        out[2] = Math.min(1f, b);
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
