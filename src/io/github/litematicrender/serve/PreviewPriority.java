package io.github.litematicrender.serve;

import io.github.litematicrender.render.scene.SectionVisibility;
import io.github.litematicrender.util.MiniJson;
import java.util.*;

/** Immutable camera snapshot; reprioritizing pending work does not acquire the render monitor. */
final class PreviewPriority {
    final double[] eye,planes;final boolean orbit;final int distance;
    PreviewPriority(Map<String,Object> values){
        eye=vector(values.get("eye"),3);Object clip=values.get("planes");planes=clip==null?new double[0]:vector(clip,24);
        orbit=MiniJson.asBool(values.get("orbit"),true);distance=Math.max(1,Math.min(128,MiniJson.asInt(values.get("distance"),6)));
    }
    private static double[] vector(Object value,int length){
        var array=MiniJson.asArray(value);if(array==null||array.size()!=length)throw new IllegalArgumentException("预览视角数据无效");double[] result=new double[length];
        for(int i=0;i<length;i++){if(!(array.get(i) instanceof Number n)||!Double.isFinite(n.doubleValue())||Math.abs(n.doubleValue())>1e10)throw new IllegalArgumentException("预览视角数据无效");result[i]=n.doubleValue();}return result;
    }
    double score(SectionVisibility.Position p){
        double x=p.x()*16.0+8,y=p.y()*16.0+8,z=p.z()*16.0+8,dx=x-eye[0],dy=y-eye[1],dz=z-eye[2],score=dx*dx+dy*dy+dz*dz;
        if(!orbit&&(Math.abs(dx)>(distance+1)*16||Math.abs(dz)>(distance+1)*16))score+=1e16;
        for(int at=0;at<planes.length;at+=4){double a=planes[at],b=planes[at+1],c=planes[at+2];if(a*x+b*y+c*z+planes[at+3]+8*(Math.abs(a)+Math.abs(b)+Math.abs(c))<0){score+=1e17;break;}}return score;
    }
    void sort(List<SectionVisibility.Position> pending){var scores=new HashMap<SectionVisibility.Position,Double>(pending.size());for(var p:pending)scores.put(p,score(p));pending.sort(Comparator.comparingDouble(scores::get));}
}
