package io.github.litematicrender.render;

import io.github.litematicrender.resource.ResourceManager;

import java.util.Optional;

/**
 * Grass/foliage colormap loader + sampler (GrassColor/FoliageColor/ColorMapColorUtil equivalent).
 */
public final class Colormaps {
    private int[] grass = new int[256 * 256];
    private int[] foliage = new int[256 * 256];
    private int[] dryFoliage = new int[256 * 256];

    public static final int GRASS_DEFAULT = 0x947050 & 0xFFFFFF; // plains default fallback
    public static final int FOLIAGE_DEFAULT = 0x48B518;
    /** Vanilla {@code BiomeColors} plains water colour, from {@code plains.json}. */
    public static final int WATER_DEFAULT = 0x3F76E4;

    public void load(ResourceManager resources) {
        this.grass = loadMap(resources, "textures/colormap/grass.png");
        this.foliage = loadMap(resources, "textures/colormap/foliage.png");
        this.dryFoliage = loadMap(resources, "textures/colormap/dry_foliage.png");
    }
    void writeCache(java.io.DataOutput out)throws java.io.IOException{
        for(int[] map:new int[][]{grass,foliage,dryFoliage})for(int value:map)out.writeInt(value);
    }
    static Colormaps readCache(java.io.DataInput in)throws java.io.IOException{
        var result=new Colormaps();
        for(int[] map:new int[][]{result.grass,result.foliage,result.dryFoliage})for(int i=0;i<map.length;i++)map[i]=in.readInt();
        return result;
    }

    private static int[] loadMap(ResourceManager resources, String path) {
        Optional<byte[]> data = resources.getResource(path);
        if (data.isEmpty()) return new int[256 * 256];
        try {
            Image img = Image.read(data.get());
            int[] out = new int[256 * 256];
            for (int y = 0; y < 256 && y < img.height; y++) {
                for (int x = 0; x < 256 && x < img.width; x++) {
                    out[y * 256 + x] = img.pixel(x, y) & 0xFFFFFF;
                }
            }
            return out;
        } catch (Exception e) {
            return new int[256 * 256];
        }
    }

    /** ColorMapColorUtil.get: u = (1-temp), v = (1-downfall*temp); biome colors are sampled. */
    private static int get(double temp, double rain, int[] map, int def) {
        int i = (int) ((1.0 - temp) * 255.0);
        int j = (int) ((1.0 - rain * temp) * 255.0);
        int k = j << 8 | i;
        return k >= map.length ? def : map[k];
    }

    /** Plains-like biome (temp 0.8, rain 0.4). */
    public int grassColor() {
        return get(0.8, 0.4, this.grass, 0x91BD59);
    }

    public int foliageColor() {
        return get(0.8, 0.4, this.foliage, 0x77AB2F);
    }

    public int dryFoliageColor() {
        return get(0.8, 0.4, this.dryFoliage, 0xA8A833);
    }

    public int waterColor() {
        return WATER_DEFAULT;
    }
}
