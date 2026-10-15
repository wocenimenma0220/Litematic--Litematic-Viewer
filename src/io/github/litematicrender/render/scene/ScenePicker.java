package io.github.litematicrender.render.scene;

import io.github.litematicrender.render.*;
import java.util.*;

/** BVH of the actual visible triangles; preserves the owning block across inset/overhanging models. */
public final class ScenePicker {
    public record Hit(SceneMesh.MeshQuad quad,double distance) {}
    private final SceneMesh.MeshQuad[] quads;
    private final SpriteAtlas atlas;
    private final SceneMesh.MeshQuad[] waterMasks;
    private final Node root;
    private static final class Node {
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        int begin,end; Node left,right;
    }
    public ScenePicker(SceneMesh mesh,SpriteAtlas atlas) {
        this.atlas=atlas;
        this.waterMasks=mesh.waterMask.toArray(SceneMesh.MeshQuad[]::new);
        var all=new ArrayList<SceneMesh.MeshQuad>();
        for(var layer:List.of(mesh.solid,mesh.cutout,
            mesh.portalSorted.isEmpty()?mesh.portal:mesh.portalSorted,
            mesh.fluidSorted.isEmpty()?mesh.fluid:mesh.fluidSorted,
            mesh.translucentSorted.isEmpty()?mesh.translucent:mesh.translucentSorted)) {
            for(var quad:layer) if(quad.regionName!=null&&!quad.decorativeEffect()) all.add(quad);
        }
        this.quads=all.toArray(SceneMesh.MeshQuad[]::new);
        this.root=quads.length==0?null:build(0,quads.length);
    }
    private Node build(int begin,int end) {
        Node node=new Node(); node.begin=begin; node.end=end;
        for(int i=begin;i<end;i++) {
            var q=quads[i];
            node.minX=Math.min(node.minX,Math.min(Math.min(q.x0,q.x1),Math.min(q.x2,q.x3)));
            node.minY=Math.min(node.minY,Math.min(Math.min(q.y0,q.y1),Math.min(q.y2,q.y3)));
            node.minZ=Math.min(node.minZ,Math.min(Math.min(q.z0,q.z1),Math.min(q.z2,q.z3)));
            node.maxX=Math.max(node.maxX,Math.max(Math.max(q.x0,q.x1),Math.max(q.x2,q.x3)));
            node.maxY=Math.max(node.maxY,Math.max(Math.max(q.y0,q.y1),Math.max(q.y2,q.y3)));
            node.maxZ=Math.max(node.maxZ,Math.max(Math.max(q.z0,q.z1),Math.max(q.z2,q.z3)));
        }
        if(end-begin>12) {
            double x=node.maxX-node.minX,y=node.maxY-node.minY,z=node.maxZ-node.minZ;
            int axis=x>=y&&x>=z?0:y>=z?1:2;
            Arrays.sort(quads,begin,end,Comparator.comparingDouble(q->axis==0?q.cx:axis==1?q.cy:q.cz));
            int middle=(begin+end)/2; node.left=build(begin,middle); node.right=build(middle,end);
        }
        return node;
    }
    public Hit pick(double[] origin,double[] direction,double maximum) {
        return pick(origin,direction,maximum,Double.POSITIVE_INFINITY);
    }
    public double maskDistance(double[] origin,double[] direction,double maximum) {
        double result=Double.POSITIVE_INFINITY;
        for(var mask:waterMasks)for(int face=0;face<2;face++){double distance=triangle(mask,face,origin,direction,maximum);if(distance>=0)result=Math.min(result,distance);}
        return result;
    }
    public Hit pick(double[] origin,double[] direction,double maximum,double externalMask) {
        if(origin.length!=3||direction.length!=3||!Double.isFinite(maximum)||maximum<=0) throw new IllegalArgumentException("无效的点选射线");
        for(double value:origin) if(!Double.isFinite(value)) throw new IllegalArgumentException("无效的点选坐标");
        double length=0; for(double value:direction){if(!Double.isFinite(value))throw new IllegalArgumentException("无效的点选方向");length+=value*value;}
        length=Math.sqrt(length); if(!Double.isFinite(length)||length<1e-12) throw new IllegalArgumentException("点选方向不能为空");
        double[] dir={direction[0]/length,direction[1]/length,direction[2]/length};
        double maskDistance=externalMask;
        for(var mask:waterMasks)for(int face=0;face<2;face++){double distance=triangle(mask,face,origin,dir,maximum);if(distance>=0)maskDistance=Math.min(maskDistance,distance);}
        return walk(root,origin,dir,Math.min(maximum,1e7),null,maskDistance);
    }
    private Hit walk(Node node,double[] origin,double[] direction,double maximum,Hit best,double maskDistance) {
        double limit=best==null?maximum:best.distance();
        if(node==null||box(node,origin,direction,limit)==Double.POSITIVE_INFINITY) return best;
        if(node.left!=null) {
            double a=box(node.left,origin,direction,limit),b=box(node.right,origin,direction,limit);
            Node first=a<=b?node.left:node.right,second=a<=b?node.right:node.left;
            best=walk(first,origin,direction,maximum,best,maskDistance); return walk(second,origin,direction,maximum,best,maskDistance);
        }
        for(int i=node.begin;i<node.end;i++) for(int triangle=0;triangle<2;triangle++) {
            var q=quads[i]; double distance=triangle(q,triangle,origin,direction,best==null?maximum:best.distance());
            if(distance>=0&&(q.layer.ordinal()<2||distance<=maskDistance+1e-6)) best=new Hit(q,distance);
        }
        return best;
    }
    private static double box(Node n,double[] o,double[] d,double maximum) {
        double near=0,far=maximum;
        for(int axis=0;axis<3;axis++) {
            double min=axis==0?n.minX:axis==1?n.minY:n.minZ,max=axis==0?n.maxX:axis==1?n.maxY:n.maxZ;
            if(Math.abs(d[axis])<1e-12){if(o[axis]<min-1e-6||o[axis]>max+1e-6)return Double.POSITIVE_INFINITY;continue;}
            double a=(min-o[axis])/d[axis],b=(max-o[axis])/d[axis];
            near=Math.max(near,Math.min(a,b));far=Math.min(far,Math.max(a,b));
            if(far+1e-6<near)return Double.POSITIVE_INFINITY;
        }
        return near;
    }
    private double triangle(SceneMesh.MeshQuad q,int triangle,double[] o,double[] d,double maximum) {
        double bx=triangle==0?q.x1:q.x2,by=triangle==0?q.y1:q.y2,bz=triangle==0?q.z1:q.z2;
        double cx=triangle==0?q.x2:q.x3,cy=triangle==0?q.y2:q.y3,cz=triangle==0?q.z2:q.z3;
        double ex=bx-q.x0,ey=by-q.y0,ez=bz-q.z0,fx=cx-q.x0,fy=cy-q.y0,fz=cz-q.z0;
        double px=d[1]*fz-d[2]*fy,py=d[2]*fx-d[0]*fz,pz=d[0]*fy-d[1]*fx;
        double determinant=ex*px+ey*py+ez*pz;
        if(determinant<=1e-10)return -1; // WebGL preview uses back-face culling.
        double tx=o[0]-q.x0,ty=o[1]-q.y0,tz=o[2]-q.z0;
        double u=(tx*px+ty*py+tz*pz)/determinant;if(u< -1e-6||u>1+1e-6)return -1;
        double ax=ty*ez-tz*ey,ay=tz*ex-tx*ez,az=tx*ey-ty*ex;
        double v=(d[0]*ax+d[1]*ay+d[2]*az)/determinant;if(v< -1e-6||u+v>1+1e-6)return -1;
        double distance=(fx*ax+fy*ay+fz*az)/determinant;if(distance<0||distance>maximum+1e-6)return -1;
        double bu=triangle==0?q.u1:q.u2,bv=triangle==0?q.v1:q.v2,cu=triangle==0?q.u2:q.u3,cv=triangle==0?q.v2:q.v3;
        double textureU=q.u0+u*(bu-q.u0)+v*(cu-q.u0),textureV=q.v0+u*(bv-q.v0)+v*(cv-q.v0);
        int alpha;
        if(q.image!=null) alpha=Image.alpha(q.image.pixel(pixel(textureU,q.image.width),pixel(textureV,q.image.height)));
        else {
            var sprite=atlas.getOrMissingEntry(q.spriteId==null?"minecraft:missingno":q.spriteId).contents;
            alpha=Image.alpha(sprite.framePixel(sprite.frameAtTick(0),pixel(textureU,sprite.frameWidth),pixel(textureV,sprite.frameHeight)));
        }
        if(alpha==0 || q.layer==SceneMesh.Layer.CUTOUT&&alpha<26)return -1;
        return distance;
    }
    private static int pixel(double coordinate,int size){return Math.max(0,Math.min(size-1,(int)Math.floor(coordinate*size)));}
}
