package io.github.litematicrender.render.scene;

import java.util.*;
import java.util.function.Consumer;
import io.github.litematicrender.render.scene.SceneMesh.MeshQuad;

/** A static plane tree; intersecting polygons are split before camera-dependent traversal. */
public final class TranslucentBsp {
    private static final double EPS=1e-5;
    public static final class Node {
        public final double[] plane;
        public int first,count,front=-1,back=-1;
        Node(double[] plane){this.plane=plane;}
    }
    public final List<MeshQuad> faces=new ArrayList<>();
    public final List<Node> nodes=new ArrayList<>();
    public int fallbackLeaves;
    public int maximumDepth;
    private final int limit;
    private int generated;
    private record Vertex(double x,double y,double z,double u,double v,double ao,double r,double g,double b) {
        Vertex mix(Vertex other,double t){return new Vertex(x+(other.x-x)*t,y+(other.y-y)*t,z+(other.z-z)*t,u+(other.u-u)*t,v+(other.v-v)*t,ao+(other.ao-ao)*t,r+(other.r-r)*t,g+(other.g-g)*t,b+(other.b-b)*t);}
    }
    private TranslucentBsp(int size){limit=(int)Math.min(750000,Math.max(10000,(long)size*3));}
    /** A single quad and its old subdivided grid have the same interpolation. */
    static boolean affine(MeshQuad face) {
        double[] p=plane(face);
        return affineColor(face)&&p!=null&&Math.abs(p[0]*face.x3+p[1]*face.y3+p[2]*face.z3-p[3])<EPS
            &&Math.abs(face.x0+face.x2-face.x1-face.x3)<EPS
            &&Math.abs(face.y0+face.y2-face.y1-face.y3)<EPS
            &&Math.abs(face.z0+face.z2-face.z1-face.z3)<EPS
            &&(face.ao==null||Math.abs(face.ao[0]+face.ao[2]-face.ao[1]-face.ao[3])<1e-6)
            &&Math.abs(face.u0+face.u2-face.u1-face.u3)<1e-6
            &&Math.abs(face.v0+face.v2-face.v1-face.v3)<1e-6;
    }
    private static boolean affineColor(MeshQuad face) {
        if(face.vertexColors==null)return true;
        for(int shift=0;shift<=16;shift+=8)if(((face.vertexColors[0]>>shift)&255)+((face.vertexColors[2]>>shift)&255)!=((face.vertexColors[1]>>shift)&255)+((face.vertexColors[3]>>shift)&255))return false;
        return true;
    }
    public static TranslucentBsp build(List<MeshQuad> input) {
        var tree=new TranslucentBsp(input.size());var planar=new ArrayList<MeshQuad>();
        for(MeshQuad face:input) {
            double[] plane=plane(face);
            if(plane==null)continue;
            double fourth=plane[0]*face.x3+plane[1]*face.y3+plane[2]*face.z3-plane[3];
            boolean affineAo=face.ao==null||Math.abs(face.ao[0]+face.ao[2]-face.ao[1]-face.ao[3])<1e-6;
            boolean affineUv=Math.abs(face.u0+face.u2-face.u1-face.u3)<1e-6&&Math.abs(face.v0+face.v2-face.v1-face.v3)<1e-6;
            if(Math.abs(fourth)>EPS||!affineAo||!affineUv||!affineColor(face)){var vertices=vertices(face);planar.add(polygon(face,List.of(vertices.get(0),vertices.get(1),vertices.get(2))));if(vertices.size()>3)planar.add(polygon(face,List.of(vertices.get(0),vertices.get(2),vertices.get(3))));}
            else planar.add(face);
        }
        tree.generated=planar.size();if(!planar.isEmpty())tree.buildNode(planar,0);return tree;
    }
    private int buildNode(List<MeshQuad> input,int depth) {
        maximumDepth=Math.max(maximumDepth,depth);
        int index=nodes.size();
        double[] selected=null;long best=Long.MAX_VALUE;
        if(depth<144&&generated<limit)for(int sample=0;sample<Math.min(12,input.size());sample++) {
            var anchor=input.get(sample*input.size()/Math.min(12,input.size()));
            double[] candidate=plane(anchor);if(candidate==null||classify(anchor,candidate)!=0)continue;
            int front=0,back=0,split=0;
            for(MeshQuad face:input){int side=classify(face,candidate);if(side==1)front++;else if(side==-1)back++;else if(side==2)split++;}
            long score=Math.abs(front-back)+(long)split*5;
            if(score<best){best=score;selected=candidate;}
        }
        Node node=new Node(selected==null?new double[4]:selected);nodes.add(node);
        if(selected==null){node.first=faces.size();node.count=input.size();faces.addAll(input);fallbackLeaves++;return index;}
        var front=new ArrayList<MeshQuad>();var back=new ArrayList<MeshQuad>();var coplanar=new ArrayList<MeshQuad>();
        for(MeshQuad face:input) {
            int side=classify(face,selected);
            if(side==1)front.add(face);else if(side==-1)back.add(face);else if(side==0)coplanar.add(face);
            else {
                var vertices=vertices(face);
                addPolygons(face,clip(vertices,selected,true),front);
                addPolygons(face,clip(vertices,selected,false),back);
            }
        }
        node.first=faces.size();node.count=coplanar.size();faces.addAll(coplanar);
        if(!front.isEmpty())node.front=buildNode(front,depth+1);
        if(!back.isEmpty())node.back=buildNode(back,depth+1);
        return index;
    }
    private void addPolygons(MeshQuad original,List<Vertex> vertices,List<MeshQuad> output) {
        if(vertices.size()<3)return;
        if(vertices.size()<=4){MeshQuad face=polygon(original,vertices);double[] p=plane(face);if(p!=null){
            if(vertices.size()==4&&Math.abs(p[0]*face.x3+p[1]*face.y3+p[2]*face.z3-p[3])>EPS) {
                addPolygons(original,List.of(vertices.get(0),vertices.get(1),vertices.get(2)),output);
                addPolygons(original,List.of(vertices.get(0),vertices.get(2),vertices.get(3)),output);
            }else{output.add(face);generated++;}
        }}
        else for(int i=1;i<vertices.size()-1;i++){MeshQuad face=polygon(original,List.of(vertices.get(0),vertices.get(i),vertices.get(i+1)));if(plane(face)!=null){output.add(face);generated++;}}
    }
    private static List<Vertex> clip(List<Vertex> input,double[] plane,boolean front) {
        var output=new ArrayList<Vertex>();Vertex previous=input.get(input.size()-1);double before=distance(previous,plane);
        for(Vertex current:input) {
            double after=distance(current,plane);boolean a=front?before>=-EPS:before<=EPS,b=front?after>=-EPS:after<=EPS;
            if(a!=b){double t=before/(before-after);output.add(previous.mix(current,Math.max(0,Math.min(1,t))));}
            if(b)output.add(current);previous=current;before=after;
        }
        for(int i=output.size()-1;i>=0&&output.size()>1;i--){Vertex a=output.get(i),b=output.get((i+1)%output.size());if(Math.abs(a.x-b.x)+Math.abs(a.y-b.y)+Math.abs(a.z-b.z)<EPS)output.remove(i);}
        return output;
    }
    private static double distance(Vertex v,double[] plane){return v.x*plane[0]+v.y*plane[1]+v.z*plane[2]-plane[3];}
    private static int classify(MeshQuad q,double[] p) {
        double a=q.x0*p[0]+q.y0*p[1]+q.z0*p[2]-p[3],b=q.x1*p[0]+q.y1*p[1]+q.z1*p[2]-p[3],c=q.x2*p[0]+q.y2*p[1]+q.z2*p[2]-p[3],d=q.x3*p[0]+q.y3*p[1]+q.z3*p[2]-p[3];
        double min=Math.min(Math.min(a,b),Math.min(c,d)),max=Math.max(Math.max(a,b),Math.max(c,d));
        return min>=-EPS&&max>EPS?1:max<=EPS&&min<-EPS?-1:min>=-EPS&&max<=EPS?0:2;
    }
    private static double[] plane(MeshQuad q) {
        double ax=q.x1-q.x0,ay=q.y1-q.y0,az=q.z1-q.z0,bx=q.x2-q.x0,by=q.y2-q.y0,bz=q.z2-q.z0;
        double nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx,length=Math.sqrt(nx*nx+ny*ny+nz*nz);if(length<1e-10)return null;
        nx/=length;ny/=length;nz/=length;return new double[]{nx,ny,nz,nx*q.x0+ny*q.y0+nz*q.z0};
    }
    private static List<Vertex> vertices(MeshQuad q) {
        float[] ao=q.ao==null?new float[]{1,1,1,1}:q.ao;
        float[] x={q.x0,q.x1,q.x2,q.x3},y={q.y0,q.y1,q.y2,q.y3},z={q.z0,q.z1,q.z2,q.z3},u={q.u0,q.u1,q.u2,q.u3},v={q.v0,q.v1,q.v2,q.v3};
        var result=new ArrayList<Vertex>(4);
        for(int i=0;i<(q.x3==q.x2&&q.y3==q.y2&&q.z3==q.z2?3:4);i++) {
            int color=q.vertexColors==null?0xFFFFFF:q.vertexColors[i];
            result.add(new Vertex(x[i],y[i],z[i],u[i],v[i],ao[i],(color>>16)&255,(color>>8)&255,color&255));
        }
        return result;
    }
    private static MeshQuad polygon(MeshQuad source,List<Vertex> vertices) {
        float[] x=new float[4],y=new float[4],z=new float[4],u=new float[4],v=new float[4],ao=new float[4];
        for(int i=0;i<4;i++){var point=vertices.get(Math.min(i,vertices.size()-1));x[i]=(float)point.x;y[i]=(float)point.y;z[i]=(float)point.z;u[i]=(float)point.u;v[i]=(float)point.v;ao[i]=(float)point.ao;}
        int[] colors=null;if(source.vertexColors!=null){colors=new int[4];for(int i=0;i<4;i++){var point=vertices.get(Math.min(i,vertices.size()-1));colors[i]=(Math.round((float)point.r)<<16)|(Math.round((float)point.g)<<8)|Math.round((float)point.b);}}
        return new MeshQuad(x,y,z,u[0],v[0],u[1],v[1],u[2],v[2],u[3],v[3],source.spriteId,source.rgb,source.image,source.tintR,source.tintG,source.tintB,source.shade,source.ao==null?null:ao,source.layer,source.doubleSided).colors(colors)
            .owner(source.blockX,source.blockY,source.blockZ,source.regionName).entityOwner(source.entityIndex,source.regionName);
    }
    /** Orthographic to-eye vector or perspective eye position. */
    public void visit(double[] eye,boolean orthographic,Consumer<MeshQuad> consumer) {if(!nodes.isEmpty())visit(0,eye,orthographic,consumer);}
    private void visit(int index,double[] eye,boolean ortho,Consumer<MeshQuad> consumer) {
        Node node=nodes.get(index);double[] p=node.plane;double side=p[0]*eye[0]+p[1]*eye[1]+p[2]*eye[2]-(ortho?0:p[3]);
        int far=side>=0?node.back:node.front,near=side>=0?node.front:node.back;
        if(far>=0)visit(far,eye,ortho,consumer);
        var range=faces.subList(node.first,node.first+node.count);
        if(p[0]==0&&p[1]==0&&p[2]==0){var sorted=new ArrayList<>(range);sorted.sort(Comparator.comparingDouble(q->ortho?q.cx*eye[0]+q.cy*eye[1]+q.cz*eye[2]:-((q.cx-eye[0])*(q.cx-eye[0])+(q.cy-eye[1])*(q.cy-eye[1])+(q.cz-eye[2])*(q.cz-eye[2]))));range=sorted;}
        range.forEach(consumer);if(near>=0)visit(near,eye,ortho,consumer);
    }
}
