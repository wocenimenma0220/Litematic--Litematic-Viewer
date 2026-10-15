package io.github.litematicrender.render.scene;

import java.util.*;
import io.github.litematicrender.render.scene.SceneMesh.*;

/** Distant surface proxies; entities and generated text/skin sprites retain full geometry. */
public final class LodMesh {
    private LodMesh() {}
    private static final int[][] NORMAL={{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
    private static final int[][][] CORNERS={
        {{0,0,1},{0,0,0},{1,0,0},{1,0,1}},{{0,1,0},{0,1,1},{1,1,1},{1,1,0}},
        {{1,1,0},{1,0,0},{0,0,0},{0,1,0}},{{0,1,1},{0,0,1},{1,0,1},{1,1,1}},
        {{0,1,0},{0,0,0},{0,0,1},{0,1,1}},{{1,1,1},{1,0,1},{1,0,0},{1,1,0}}};
    private static boolean eligible(MeshQuad q){return !q.lodProtected&&q.entityIndex<0&&q.spriteId!=null&&q.spriteId.startsWith("minecraft:block/");}
    public static SceneMesh simplify(SceneMesh full,SectionVisibility.Position section,int step) {
        SceneMesh result=new SceneMesh();int n=16/step;MeshQuad[][] voxels=new MeshQuad[n*n*n][];
        for(var layer:List.of(full.solid,full.cutout))for(var q:layer) {
            if(!eligible(q)){(q.layer==Layer.SOLID?result.solid:result.cutout).add(q);continue;}
            int x=Math.floorDiv(q.blockX-section.x()*16,step),y=Math.floorDiv(q.blockY-section.y()*16,step),z=Math.floorDiv(q.blockZ-section.z()*16,step);
            if(x<0||y<0||z<0||x>=n||y>=n||z>=n)continue;
            int key=(y*n+z)*n+x;if(voxels[key]==null)voxels[key]=new MeshQuad[6];
            int face=direction(q);if(voxels[key][face]==null)voxels[key][face]=q;
        }
        for(int y=0;y<n;y++)for(int z=0;z<n;z++)for(int x=0;x<n;x++) {
            var voxel=voxels[(y*n+z)*n+x];if(voxel==null)continue;
            MeshQuad fallback=Arrays.stream(voxel).filter(Objects::nonNull).findFirst().orElseThrow();
            for(int face=0;face<6;face++) {
                int nx=x+NORMAL[face][0],ny=y+NORMAL[face][1],nz=z+NORMAL[face][2];
                boolean boundary=nx<0||ny<0||nz<0||nx>=n||ny>=n||nz>=n;
                if(!boundary&&voxels[(ny*n+nz)*n+nx]!=null)continue;
                MeshQuad source=voxel[face]==null?fallback:voxel[face];
                float[] px=new float[4],py=new float[4],pz=new float[4];
                for(int i=0;i<4;i++){px[i]=section.x()*16+(x+CORNERS[face][i][0])*step;py[i]=section.y()*16+(y+CORNERS[face][i][1])*step;pz[i]=section.z()*16+(z+CORNERS[face][i][2])*step;}
                int color=averageColor(source);int[] colors={color,color,color,color};
                var quad=new MeshQuad(px,py,pz,source.u0,source.v0,source.u1,source.v1,source.u2,source.v2,source.u3,source.v3,
                    source.spriteId,source.rgb,source.image,1,1,1,1,null,source.layer,false).colors(colors);
                (source.layer==Layer.SOLID?result.solid:result.cutout).add(quad);
            }
        }
        if(step>=4)simplifyBlending(full,result,section,step);
        return result;
    }
    /** Retain a representative surface plane, so a shallow water tile does not become a cube. */
    private static void simplifyBlending(SceneMesh full,SceneMesh result,SectionVisibility.Position section,int step) {
        int n=16/step;MeshQuad[][] voxels=new MeshQuad[n*n*n][];
        for(var layer:List.of(full.portal,full.fluid,full.translucent))for(var q:layer) {
            if(!eligible(q)||q.spriteId.startsWith("minecraft:block/water")||q.spriteId.startsWith("minecraft:block/lava")){result.translucent.add(q);continue;}
            int x=Math.floorDiv(q.blockX-section.x()*16,step),y=Math.floorDiv(q.blockY-section.y()*16,step),z=Math.floorDiv(q.blockZ-section.z()*16,step);
            if(x<0||y<0||z<0||x>=n||y>=n||z>=n){result.translucent.add(q);continue;}
            int key=(y*n+z)*n+x;if(voxels[key]==null)voxels[key]=new MeshQuad[6];
            int face=direction(q);if(voxels[key][face]==null)voxels[key][face]=q;
        }
        for(int y=0;y<n;y++)for(int z=0;z<n;z++)for(int x=0;x<n;x++) {
            var voxel=voxels[(y*n+z)*n+x];if(voxel==null)continue;
            for(int face=0;face<6;face++) {
                var source=voxel[face];if(source==null)continue;
                float[] px=new float[4],py=new float[4],pz=new float[4];int axis=face<2?1:face<4?2:0;
                for(int i=0;i<4;i++){
                    px[i]=axis==0?source.cx:section.x()*16+(x+CORNERS[face][i][0])*step;
                    py[i]=axis==1?source.cy:section.y()*16+(y+CORNERS[face][i][1])*step;
                    pz[i]=axis==2?source.cz:section.z()*16+(z+CORNERS[face][i][2])*step;
                }
                int color=averageColor(source);
                result.translucent.add(new MeshQuad(px,py,pz,source.u0,source.v0,source.u1,source.v1,source.u2,source.v2,source.u3,source.v3,
                    source.spriteId,source.rgb,source.image,((color>>16)&255)/255f,((color>>8)&255)/255f,(color&255)/255f,1,null,Layer.TRANSLUCENT,false));
            }
        }
        result.translucentBsp=TranslucentBsp.build(result.translucent);
    }
    private static int direction(MeshQuad q) {
        float ax=q.x1-q.x0,ay=q.y1-q.y0,az=q.z1-q.z0,bx=q.x2-q.x0,by=q.y2-q.y0,bz=q.z2-q.z0;
        float x=ay*bz-az*by,y=az*bx-ax*bz,z=ax*by-ay*bx;
        return Math.abs(y)>=Math.max(Math.abs(x),Math.abs(z))?(y>=0?1:0):Math.abs(z)>=Math.abs(x)?(z>=0?3:2):(x>=0?5:4);
    }
    public static int color(MeshQuad q,int corner) {
        if(q.vertexColors!=null)return q.vertexColors[corner];
        float light=q.shade*(q.ao==null?1:q.ao[corner]);
        return (byteColor(q.tintR*light)<<16)|(byteColor(q.tintG*light)<<8)|byteColor(q.tintB*light);
    }
    private static int averageColor(MeshQuad q) {
        int r=0,g=0,b=0;for(int i=0;i<4;i++){int color=color(q,i);r+=(color>>16)&255;g+=(color>>8)&255;b+=color&255;}
        return ((r/4)<<16)|((g/4)<<8)|(b/4);
    }
    private static int byteColor(float value){return Math.round(Math.max(0,Math.min(1,value))*255);}
}
