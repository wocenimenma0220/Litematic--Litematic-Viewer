package io.github.litematicrender.render.scene;

import io.github.litematicrender.model.Quad;
import io.github.litematicrender.world.*;

/** Cached face-local corner light shared by adjacent blocks, interpolated for inset geometry. */
public final class SmoothLighting {
    @FunctionalInterface public interface Blocks { BlockState at(int x,int y,int z); }
    private static final int[][] NORMAL={{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
    private static final float[][] LIGHT=new float[256][3];
    static {for(int sky=0;sky<16;sky++)for(int block=0;block<16;block++)LightVolume.brightness((sky<<8)|block,LIGHT[sky*16+block]);}
    private LightSampler light;private final int[] origin;private final Blocks blocks;private final BlockShape shapes;
    private final float[][] corners=new float[12][12];private int lastX,lastY,lastZ,valid;
    public SmoothLighting(LightSampler light,int[] origin,Blocks blocks,BlockShape shapes){this.light=light;this.origin=origin;this.blocks=blocks;this.shapes=shapes;}
    public void bind(LightSampler sampler){this.light=sampler;valid=0;}
    public int[] colors(int x,int y,int z,Quad q,SceneMesh.MeshQuad face,float tintR,float tintG,float tintB) {
        return colors(x,y,z,q,face,tintR,tintG,tintB,q.lightEmission);
    }
    public int[] colors(int x,int y,int z,Quad q,SceneMesh.MeshQuad face,float tintR,float tintG,float tintB,int emission) {
        if(x!=lastX||y!=lastY||z!=lastZ){lastX=x;lastY=y;lastZ=z;valid=0;}
        int direction=q.face,normal=direction<2?1:direction<4?2:0,a=normal==0?2:0,b=normal==1?2:1;
        float[] inner=face(x,y,z,direction,false,a,b),outer=face(x,y,z,direction,true,a,b);
        int[] result=new int[4];float[] emitted=LIGHT[Math.min(15,emission)];
        for(int i=0;i<4;i++) {
            float px=i==0?q.x0:i==1?q.x1:i==2?q.x2:q.x3,py=i==0?q.y0:i==1?q.y1:i==2?q.y2:q.y3,pz=i==0?q.z0:i==1?q.z1:i==2?q.z2:q.z3;
            float u=clamp(a==0?px:pz),v=clamp(b==1?py:pz),depth=clamp(normal==0?px:normal==1?py:pz),weight=(direction&1)==0?1-depth:depth;
            int color=0;
            for(int c=0;c<3;c++) {
                float value=bilinear(inner,u,v,c)*(1-weight)+bilinear(outer,u,v,c)*weight;
                if(emission>0)value=Math.max(value,emitted[c]);
                value*=(c==0?tintR:c==1?tintG:tintB)*face.shade*(face.ao==null?1:face.ao[i]);
                color|=Math.round(clamp(value)*255)<<(16-c*8);
            }
            result[i]=color;
        }
        return result;
    }
    private float[] face(int x,int y,int z,int face,boolean outer,int a,int b) {
        int key=face+(outer?6:0);float[] result=corners[key];if((valid&(1<<key))!=0)return result;valid|=1<<key;
        int[] n=NORMAL[face];int fx=x+(outer?n[0]:0),fy=y+(outer?n[1]:0),fz=z+(outer?n[2]:0);
        for(int v=0;v<2;v++)for(int u=0;u<2;u++) {
            int su=u==0?-1:1,sv=v==0?-1:1;
            int ax=fx+(a==0?su:0),az=fz+(a==2?su:0),by=fy+(b==1?sv:0),bz=fz+(b==2?sv:0);
            int diagonal=shapes.occludes(blocks.at(ax,fy,az))&&shapes.occludes(blocks.at(fx,by,bz))?packed(ax,fy,az):packed(ax,by,az+bz-fz);
            float[] base=LIGHT[packed(fx,fy,fz)],sideA=LIGHT[packed(ax,fy,az)],sideB=LIGHT[packed(fx,by,bz)],corner=LIGHT[diagonal];
            for(int c=0;c<3;c++)result[(u+v*2)*3+c]=(base[c]+sideA[c]+sideB[c]+corner[c])*.25f;
        }
        return result;
    }
    private int packed(int x,int y,int z){int value=light.cellLight(x-origin[0],y-origin[1],z-origin[2]);return ((value>>8)&15)*16+(value&15);}
    private static float bilinear(float[] c,float u,float v,int color){return (c[color]*(1-u)+c[3+color]*u)*(1-v)+(c[6+color]*(1-u)+c[9+color]*u)*v;}
    private static float clamp(float value){return Math.max(0,Math.min(1,value));}
}
