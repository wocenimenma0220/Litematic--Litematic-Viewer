package io.github.litematicrender.render.raster;

import io.github.litematicrender.render.Image;
import io.github.litematicrender.render.SpriteAtlas;

/**
 * Software rasterizer: scans quads with a depth buffer into an RGBA image.
 *
 * <p>Opaque geometry is depth-tested against a view-space depth buffer. Translucent geometry is
 * drawn back-to-front with plain source-over blending and does not write depth, matching how a
 * renderer composites glass/water on top of the already-finished opaque pass.
 */
public final class Rasterizer {
    public final Image color;
    public final float[] depth;   // view-space depth per pixel, larger = closer
    public final Camera camera;
    private final SpriteAtlas atlas;
    public boolean writeColor=true;
    public double[] selectionMin,selectionMax;
    public int selectionPass;

    public Rasterizer(int width, int height, Camera camera, SpriteAtlas atlas) {
        this.color = new Image(width, height);
        this.depth = new float[width * height];
        java.util.Arrays.fill(this.depth, Float.NEGATIVE_INFINITY);
        this.camera = camera;
        this.atlas = atlas;
    }

    public void clear(int argb) {
        java.util.Arrays.fill(this.color.pixels(), argb);
        java.util.Arrays.fill(this.depth, Float.NEGATIVE_INFINITY);
    }

    private final double[] V0 = new double[3];
    private final double[] V1 = new double[3];
    private final double[] V2 = new double[3];
    private final double[] V3 = new double[3];

    /**
     * Rasterizes one world-space quad.
     *
     * @param ao per-vertex ambient occlusion (may be null for flat shading)
     * @param translucent when true, blend source-over without depth writes
     */
    public void drawQuad(double wx0, double wy0, double wz0,
                         double wx1, double wy1, double wz1,
                         double wx2, double wy2, double wz2,
                         double wx3, double wy3, double wz3,
                         float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3,
                         String spriteId, int rgb, float tintR, float tintG, float tintB,
                         float shade, float[] ao, boolean translucent) {
        drawQuad(wx0, wy0, wz0, wx1, wy1, wz1, wx2, wy2, wz2, wx3, wy3, wz3,
            u0, v0, u1, v1, u2, v2, u3, v3, spriteId, rgb, null, tintR, tintG, tintB,
            shade, ao, translucent);
    }

    /**
     * Rasterizes one world-space quad.
     *
     * @param direct when non-null, sampled straight from this image with UV in [0,1]
     *               (used for text drawn onto signs)
     * @param ao per-vertex ambient occlusion (may be null for flat shading)
     * @param translucent when true, blend source-over without depth writes
     */
    public void drawQuad(double wx0, double wy0, double wz0,
                         double wx1, double wy1, double wz1,
                         double wx2, double wy2, double wz2,
                         double wx3, double wy3, double wz3,
                         float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3,
                         String spriteId, int rgb, Image direct, float tintR, float tintG, float tintB,
                         float shade, float[] ao, boolean translucent) {
        drawQuad(wx0, wy0, wz0, wx1, wy1, wz1, wx2, wy2, wz2, wx3, wy3, wz3,
            u0, v0, u1, v1, u2, v2, u3, v3, spriteId, rgb, direct, tintR, tintG, tintB,
            shade, ao, translucent, false);
    }

    /**
     * Rasterizes one world-space quad.
     *
     * @param direct when non-null, sampled straight from this image with UV in [0,1]
     * @param ao per-vertex ambient occlusion (may be null for flat shading)
     * @param translucent when true, blend source-over without depth writes
     * @param doubleSided skip back-face culling, for faces with no fixed orientation
     *                    (cross-shaped plants, torch sides) — the inverse of vanilla's
     *                    {@code withCull(true)}, which terrain uses by default
     */
    public void drawQuad(double wx0, double wy0, double wz0,
                         double wx1, double wy1, double wz1,
                         double wx2, double wy2, double wz2,
                         double wx3, double wy3, double wz3,
                         float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3,
                         String spriteId, int rgb, Image direct, float tintR, float tintG, float tintB,
                         float shade, float[] ao, boolean translucent, boolean doubleSided) {
        drawQuad(wx0,wy0,wz0,wx1,wy1,wz1,wx2,wy2,wz2,wx3,wy3,wz3,
            u0,v0,u1,v1,u2,v2,u3,v3,spriteId,rgb,direct,tintR,tintG,tintB,shade,ao,translucent,doubleSided,null);
    }
    public void drawQuad(double wx0,double wy0,double wz0,double wx1,double wy1,double wz1,
            double wx2,double wy2,double wz2,double wx3,double wy3,double wz3,
            float u0,float v0,float u1,float v1,float u2,float v2,float u3,float v3,
            String spriteId,int rgb,Image direct,float tintR,float tintG,float tintB,
            float shade,float[] ao,boolean translucent,boolean doubleSided,int[] vertexColors) {
        if(selectionPass==2)translucent=true;
        double biasX=0,biasY=0,biasZ=0;
        if(selectionPass!=0){double ax=wx1-wx0,ay=wy1-wy0,az=wz1-wz0,bx=wx2-wx0,by=wy2-wy0,bz=wz2-wz0;
            biasX=ay*bz-az*by;biasY=az*bx-ax*bz;biasZ=ax*by-ay*bx;double size=Math.max(1e-9,Math.sqrt(biasX*biasX+biasY*biasY+biasZ*biasZ));biasX*=.001/size;biasY*=.001/size;biasZ*=.001/size;}
        Camera cam = this.camera;
        cam.toView(wx0, wy0, wz0, V0);
        cam.toView(wx1, wy1, wz1, V1);
        cam.toView(wx2, wy2, wz2, V2);
        cam.toView(wx3, wy3, wz3, V3);

        int w = this.color.width, h = this.color.height;
        double cx = w / 2.0 + cam.offsetX, cy = h / 2.0 + cam.offsetY;
        double z = cam.zoom;

        double sx0 = cx + V0[0] * z, sy0 = cy - V0[1] * z;
        double sx1 = cx + V1[0] * z, sy1 = cy - V1[1] * z;
        double sx2 = cx + V2[0] * z, sy2 = cy - V2[1] * z;
        double sx3 = cx + V3[0] * z, sy3 = cy - V3[1] * z;

        Image atlasImg = direct != null ? direct : (spriteId != null ? this.atlas.stitched : null);
        SpriteAtlas.Entry entry = null;
        if (direct == null && atlasImg != null) {
            entry = this.atlas.getOrMissingEntry(spriteId);
        }

        double minX = Math.min(Math.min(sx0, sx1), Math.min(sx2, sx3));
        double maxX = Math.max(Math.max(sx0, sx1), Math.max(sx2, sx3));
        double minY = Math.min(Math.min(sy0, sy1), Math.min(sy2, sy3));
        double maxY = Math.max(Math.max(sy0, sy1), Math.max(sy2, sy3));
        int x0 = Math.max(0, (int) Math.floor(minX));
        int x1 = Math.min(w - 1, (int) Math.ceil(maxX));
        int y0 = Math.max(0, (int) Math.floor(minY));
        int y1 = Math.min(h - 1, (int) Math.ceil(maxY));
        if (x1 < x0 || y1 < y0) return;

        double d1 = (sy1 - sy2) * (sx0 - sx2) + (sx2 - sx1) * (sy0 - sy2);
        double d2 = (sy2 - sy3) * (sx0 - sx3) + (sx3 - sx2) * (sy0 - sy3);
        boolean triangle1=Math.abs(d1)>=1e-9,triangle2=Math.abs(d2)>=1e-9;
        if (!triangle1 && !triangle2) return;

        // Back-face culling: a quad whose projected winding faces away from the camera cannot
        // contribute a pixel. Vanilla enables this for terrain (MC 26.3's TERRAIN_SNIPPET keeps the
        // default withCull(true) for SOLID_TERRAIN and CUTOUT_TERRAIN) and it discards 52-67% of
        // this schematic's quads. The signed area is the cheapest form of the test, since the
        // projected corners are already computed. Double-sided faces — cross plants, torch sides,
        // which the baker tags NO_FACE — opt out, matching vanilla's withCull(false).
        if (!doubleSided) {
            double area = (sx1 - sx0) * (sy2 - sy0) - (sy1 - sy0) * (sx2 - sx0);
            if (area >= 0) return;
        }

        int[] px = this.color.pixels();
        float[] dep = this.depth;

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double pxs = x + 0.5, pys = y + 0.5;
                double uFrac, vFrac, vz;
                float vShade;
                double weight0,weight1,weight2;int corner1,corner2;
                // tri (p0,p1,p2)
                double l0 = ((sy1 - sy2) * (pxs - sx2) + (sx2 - sx1) * (pys - sy2)) / d1;
                double l1 = ((sy2 - sy0) * (pxs - sx2) + (sx0 - sx2) * (pys - sy2)) / d1;
                double l2 = 1 - l0 - l1;
                if (triangle1 && l0 >= -1e-7 && l1 >= -1e-7 && l2 >= -1e-7) {
                    uFrac = l0 * u0 + l1 * u1 + l2 * u2;
                    vFrac = l0 * v0 + l1 * v1 + l2 * v2;
                    vz = l0 * V0[2] + l1 * V1[2] + l2 * V2[2];
                    vShade = ao == null ? shade : (float) (l0 * ao[0] + l1 * ao[1] + l2 * ao[2]) * shade;
                    weight0=l0;weight1=l1;weight2=l2;corner1=1;corner2=2;
                } else {
                    if(!triangle2)continue;
                    double m0 = ((sy2 - sy3) * (pxs - sx3) + (sx3 - sx2) * (pys - sy3)) / d2;
                    double m1 = ((sy3 - sy0) * (pxs - sx3) + (sx0 - sx3) * (pys - sy3)) / d2;
                    double m2 = 1 - m0 - m1;
                    if (!(m0 >= -1e-7 && m1 >= -1e-7 && m2 >= -1e-7)) continue;
                    uFrac = m0 * u0 + m1 * u2 + m2 * u3;
                    vFrac = m0 * v0 + m1 * v2 + m2 * v3;
                    vz = m0 * V0[2] + m1 * V2[2] + m2 * V3[2];
                    vShade = ao == null ? shade : (float) (m0 * ao[0] + m1 * ao[2] + m2 * ao[3]) * shade;
                    weight0=m0;weight1=m1;weight2=m2;corner1=2;corner2=3;
                }
                if(selectionPass!=0){
                    double wx=weight0*wx0+weight1*(corner1==1?wx1:wx2)+weight2*(corner2==2?wx2:wx3);
                    double wy=weight0*wy0+weight1*(corner1==1?wy1:wy2)+weight2*(corner2==2?wy2:wy3);
                    double wz=weight0*wz0+weight1*(corner1==1?wz1:wz2)+weight2*(corner2==2?wz2:wz3);
                    wx-=biasX;wy-=biasY;wz-=biasZ;
                    boolean inside=wx>=selectionMin[0]-.0001&&wx<=selectionMax[0]+.0001&&wy>=selectionMin[1]-.0001&&wy<=selectionMax[1]+.0001&&wz>=selectionMin[2]-.0001&&wz<=selectionMax[2]+.0001;
                    if(selectionPass==1&&!inside||selectionPass==2&&inside)continue;
                }
                if (vShade > 1f) vShade = 1f; else if (vShade < 0f) vShade = 0f;
                uFrac = Math.min(0.9999f, Math.max(0f, uFrac));
                vFrac = Math.min(0.9999f, Math.max(0f, vFrac));

                int argb;
                float cr=tintR*vShade,cg=tintG*vShade,cb=tintB*vShade;
                if(vertexColors!=null) {
                    int c0=vertexColors[0],c1=vertexColors[corner1],c2=vertexColors[corner2];
                    cr=(float)((Image.red(c0)*weight0+Image.red(c1)*weight1+Image.red(c2)*weight2)/255);
                    cg=(float)((Image.green(c0)*weight0+Image.green(c1)*weight1+Image.green(c2)*weight2)/255);
                    cb=(float)((Image.blue(c0)*weight0+Image.blue(c1)*weight1+Image.blue(c2)*weight2)/255);
                }
                if (atlasImg != null) {
                    int tx, ty;
                    if (entry == null) {
                        // direct image: UV is already image space
                        tx = Math.min(atlasImg.width - 1, Math.max(0, (int) ((float) uFrac * atlasImg.width)));
                        ty = Math.min(atlasImg.height - 1, Math.max(0, (int) ((float) vFrac * atlasImg.height)));
                    } else {
                        // Keep sampling in sprite pixel space. Normalised float atlas UVs can round
                        // a texture-edge sample differently after unrelated generated tiles move.
                        tx = entry.pixelX + Math.min(entry.contents.frameWidth-1,Math.max(0,(int)(uFrac*entry.contents.frameWidth)));
                        ty = entry.pixelY + Math.min(entry.contents.frameHeight-1,Math.max(0,(int)(vFrac*entry.contents.frameHeight)));
                    }
                    int texel = atlasImg.pixel(tx, ty);
                    int ta = Image.alpha(texel);
                    if (ta == 0) continue;   // fully transparent: no write, no depth
                    int tr = clamp255(Image.red(texel) * cr);
                    int tg = clamp255(Image.green(texel) * cg);
                    int tb = clamp255(Image.blue(texel) * cb);
                    argb = Image.argb(ta, tr, tg, tb);
                } else {
                    int tr = clamp255(Image.red(rgb) * vShade);
                    int tg = clamp255(Image.green(rgb) * vShade);
                    int tb = clamp255(Image.blue(rgb) * vShade);
                    argb = Image.argb(255, tr, tg, tb);
                }

                int idx = y * w + x;
                if(selectionPass==2)argb=(Math.round((argb>>>24)*.1f)<<24)|(argb&0xffffff);
                int srcA = argb >>> 24;
                if (!translucent) {
                    if (srcA < 26) continue;
                    float dVal = (float) vz;
                    if (dVal >= dep[idx]) {
                        dep[idx] = dVal;
                        if(writeColor)px[idx] = argb;
                    }
                } else {
                    // Translucent geometry, matching how quickcraft draws its translucent layers
                    // with depth testing enabled but no depth writes:
                    //   * it is HIDDEN by opaque geometry in front of it (depth test, dVal < dep),
                    //     otherwise glass/water would paint over the whole model;
                    //   * it does NOT write depth, so translucent surfaces behind it still draw
                    //     and blend in back-to-front order.
                    if (vz < dep[idx]) continue;
                    px[idx] = srcOver(px[idx], argb);
                }
            }
        }
    }

    private static int clamp255(float v) {
        int i = (int) v;
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    /** Straight (non-premultiplied) source-over compositing. */
    private static int srcOver(int dst, int src) {
        int sa = (src >>> 24) & 0xFF;
        if (sa == 255) return src;
        if (sa == 0) return dst;
        int da = (dst >>> 24) & 0xFF;
        int outA = sa + da * (255 - sa) / 255;
        if (outA == 0) return 0;
        int sr = (src >>> 16) & 0xFF, sg = (src >>> 8) & 0xFF, sb = src & 0xFF;
        int dr = (dst >>> 16) & 0xFF, dg = (dst >>> 8) & 0xFF, db = dst & 0xFF;
        int rr = (sr * sa * 255 + dr * da * (255 - sa)) / (outA * 255);
        int rg = (sg * sa * 255 + dg * da * (255 - sa)) / (outA * 255);
        int rb = (sb * sa * 255 + db * da * (255 - sa)) / (outA * 255);
        return (outA << 24) | (clamp255(rr) << 16) | (clamp255(rg) << 8) | clamp255(rb);
    }
}
