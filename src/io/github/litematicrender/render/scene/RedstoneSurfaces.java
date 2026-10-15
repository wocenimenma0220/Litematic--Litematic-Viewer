package io.github.litematicrender.render.scene;

import io.github.litematicrender.model.Quad;
import io.github.litematicrender.render.*;
import io.github.litematicrender.world.BlockState;
import java.util.*;

/** Bake coincident wire layers into one surface, before camera-dependent sorting. */
final class RedstoneSurfaces {
    private record Key(int face,int plane,int shade,int emission) {}
    private record Source(Quad q,SpriteContents sprite,float[][] points,int tint) {}
    private RedstoneSurfaces() {}
    static List<Quad> compose(List<Quad> input,BlockState state,SpriteAtlas atlas,Map<String,Image> generated) {
        // Animated custom wires keep their original sprites so GIF ticks remain live.
        if(input.stream().anyMatch(q->atlas.getOrMissingEntry(q.spriteId).contents.isAnimated()))return input;
        var groups=new LinkedHashMap<Key,List<Quad>>();var result=new ArrayList<Quad>();
        for(var q:input) {
            float[][] p=points(q);int normal=q.face/2==0?1:q.face/2==1?2:0;
            var sprite=atlas.getOrMissingEntry(q.spriteId).contents;
            boolean rectangle=q.face>=0&&!sprite.isAnimated();
            for(int i=0;i<4;i++)rectangle&=Math.abs(p[i][normal]-p[0][normal])<1e-7;
            for(int axis=0;axis<3;axis++)rectangle&=Math.abs(p[0][axis]+p[2][axis]-p[1][axis]-p[3][axis])<1e-7;
            int a=normal==0?2:0,b=normal==1?2:1;
            rectangle&=(p[1][a]==p[0][a]||p[1][b]==p[0][b])&&(p[3][a]==p[0][a]||p[3][b]==p[0][b]);
            if(!rectangle){result.add(q);continue;}
            var key=new Key(q.face,Float.floatToIntBits(p[0][normal]),q.shadeFaceOverride,q.lightEmission);
            groups.computeIfAbsent(key,ignored->new ArrayList<>()).add(q);
        }
        int index=0;
        for(var group:groups.values()) {
            if(group.size()==1){result.addAll(group);continue;}
            Quad merged=merge(group,state,atlas,generated,index++);
            if(merged==null)result.addAll(group);else result.add(merged);
        }
        return result;
    }
    private static Quad merge(List<Quad> group,BlockState state,SpriteAtlas atlas,Map<String,Image> generated,int index) {
        Quad first=group.get(0);int normal=first.face/2==0?1:first.face/2==1?2:0,a=normal==0?2:0,b=normal==1?2:1;
        float lowA=Float.POSITIVE_INFINITY,lowB=lowA,highA=Float.NEGATIVE_INFINITY,highB=highA;
        double densityA=0,densityB=0;
        for(var q:group) {
            float[][] p=points(q);for(var v:p){lowA=Math.min(lowA,v[a]);lowB=Math.min(lowB,v[b]);highA=Math.max(highA,v[a]);highB=Math.max(highB,v[b]);}
            var sprite=atlas.getOrMissingEntry(q.spriteId).contents;
            double ax=p[1][a]-p[0][a],ay=p[1][b]-p[0][b],bx=p[3][a]-p[0][a],by=p[3][b]-p[0][b],det=ax*by-ay*bx;
            if(Math.abs(det)<1e-12)return null;
            densityA=Math.max(densityA,(Math.abs((q.u1-q.u0)*by-(q.u3-q.u0)*ay)*sprite.frameWidth+Math.abs((q.v1-q.v0)*by-(q.v3-q.v0)*ay)*sprite.frameHeight)/Math.abs(det));
            densityB=Math.max(densityB,(Math.abs((q.u3-q.u0)*ax-(q.u1-q.u0)*bx)*sprite.frameWidth+Math.abs((q.v3-q.v0)*ax-(q.v1-q.v0)*bx)*sprite.frameHeight)/Math.abs(det));
        }
        int width=(int)Math.ceil((highA-lowA)*densityA-1e-6),height=(int)Math.ceil((highB-lowB)*densityB-1e-6);
        if(width<=0||height<=0||(long)width*height>1_048_576)return null;
        var sources=new ArrayList<Source>();
        for(var q:group){int tint=BlockTints.colorFor(state,q.spriteId,q.tintIndex);sources.add(new Source(q,atlas.getOrMissingEntry(q.spriteId).contents,points(q),tint<0?0xffffff:tint));}
        var image=new Image(width,height);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            double wa=lowA+(x+.5)*(highA-lowA)/width,wb=lowB+(y+.5)*(highB-lowB)/height,alpha=0,r=0,g=0,blue=0;
            for(var source:sources) {
                Quad q=source.q;float[][] p=source.points;double ax=p[1][a]-p[0][a],ay=p[1][b]-p[0][b],bx=p[3][a]-p[0][a],by=p[3][b]-p[0][b],det=ax*by-ay*bx;
                double u=((wa-p[0][a])*by-(wb-p[0][b])*bx)/det,v=((wb-p[0][b])*ax-(wa-p[0][a])*ay)/det;
                if(u<0||u>=1||v<0||v>=1)continue;
                var sprite=source.sprite;
                double tu=q.u0+(q.u1-q.u0)*u+(q.u3-q.u0)*v,tv=q.v0+(q.v1-q.v0)*u+(q.v3-q.v0)*v;
                int pixel=sprite.framePixel(0,Math.max(0,Math.min(sprite.frameWidth-1,(int)Math.floor(tu*sprite.frameWidth))),Math.max(0,Math.min(sprite.frameHeight-1,(int)Math.floor(tv*sprite.frameHeight))));
                int tint=source.tint;
                double opacity=Image.alpha(pixel)/255.0,remain=1-opacity;
                r=Image.red(pixel)*((tint>>16)&255)/255.0*opacity+r*remain;
                g=Image.green(pixel)*((tint>>8)&255)/255.0*opacity+g*remain;
                blue=Image.blue(pixel)*(tint&255)/255.0*opacity+blue*remain;alpha=opacity+alpha*remain;
            }
            if(alpha>0)image.setPixel(x,y,((int)Math.round(alpha*255)<<24)|((int)Math.round(r/alpha)<<16)|((int)Math.round(g/alpha)<<8)|(int)Math.round(blue/alpha));
        }
        String id="litematicrender:redstone/"+state.propertyString().replaceAll("[^a-z0-9_]+","_")+"_"+index;
        generated.put(id,image);
        float[][] p=points(first);float minA=Float.POSITIVE_INFINITY,minB=minA,maxA=Float.NEGATIVE_INFINITY,maxB=maxA;
        for(var v:p){minA=Math.min(minA,v[a]);minB=Math.min(minB,v[b]);maxA=Math.max(maxA,v[a]);maxB=Math.max(maxB,v[b]);}
        float[] xs=new float[4],ys=new float[4],zs=new float[4],us=new float[4],vs=new float[4];
        for(int i=0;i<4;i++) {
            p[i][a]=p[i][a]==minA?lowA:highA;p[i][b]=p[i][b]==minB?lowB:highB;
            xs[i]=p[i][0];ys[i]=p[i][1];zs[i]=p[i][2];us[i]=(p[i][a]-lowA)/(highA-lowA);vs[i]=(p[i][b]-lowB)/(highB-lowB);
        }
        return new Quad(xs,ys,zs,us,vs,first.face,id,-1,first.shadeFaceOverride,first.lightEmission,true);
    }
    private static float[][] points(Quad q){return new float[][]{{q.x0,q.y0,q.z0},{q.x1,q.y1,q.z1},{q.x2,q.y2,q.z2},{q.x3,q.y3,q.z3}};}
}
