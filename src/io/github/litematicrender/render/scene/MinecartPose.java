package io.github.litematicrender.render.scene;

import io.github.litematicrender.schematic.SchematicEntity;
import io.github.litematicrender.world.BlockState;
import java.util.Map;
import java.util.Set;

/** Static counterpart of vanilla OldMinecartBehavior.getPos/getPosOffs and oldRender. */
public final class MinecartPose {
    private MinecartPose() {}
    @FunctionalInterface public interface Blocks { BlockState at(int x,int y,int z); }
    public record Pose(double x,double y,double z,float yaw,float pitch) {}
    private record Track(int x,int y,int z,int[][] exits) {}
    private static final Set<String> RAILS=Set.of("minecraft:rail","minecraft:powered_rail","minecraft:detector_rail","minecraft:activator_rail");
    private static final Map<String,int[][]> EXITS=Map.of(
        "north_south",new int[][]{{0,0,-1},{0,0,1}},
        "east_west",new int[][]{{-1,0,0},{1,0,0}},
        "ascending_east",new int[][]{{-1,-1,0},{1,0,0}},
        "ascending_west",new int[][]{{-1,0,0},{1,-1,0}},
        "ascending_north",new int[][]{{0,0,-1},{0,-1,1}},
        "ascending_south",new int[][]{{0,-1,-1},{0,0,1}},
        "south_east",new int[][]{{0,0,1},{1,0,0}},
        "south_west",new int[][]{{0,0,1},{-1,0,0}},
        "north_west",new int[][]{{0,0,-1},{-1,0,0}},
        "north_east",new int[][]{{0,0,-1},{1,0,0}});

    public static Pose resolve(SchematicEntity entity,double x,double y,double z,Blocks blocks) {
        Track track=track(x,y,z,blocks);
        if(track==null)return new Pose(x,y,z,entity.yaw(),entity.pitch());
        double[] center=position(track,x,z),front=offset(track,x,z,.3,blocks),back=offset(track,x,z,-.3,blocks);
        if(front==null)front=center;
        if(back==null)back=center;
        double dx=back[0]-front[0],dy=back[1]-front[1],dz=back[2]-front[2],length=Math.sqrt(dx*dx+dy*dy+dz*dz);
        float yaw=entity.yaw(),pitch=entity.pitch();
        if(length>1e-9) {
            yaw=(float)Math.toDegrees(Math.atan2(dz,dx));
            pitch=(float)(Math.atan(dy/length)*73);
        }
        return new Pose(center[0],(front[1]+back[1])*.5,center[2],yaw,pitch);
    }
    private static boolean rail(BlockState state){return state!=null&&RAILS.contains(state.blockId);}
    private static Track track(double x,double y,double z,Blocks blocks) {
        int bx=(int)Math.floor(x),by=(int)Math.floor(y),bz=(int)Math.floor(z);
        if(rail(blocks.at(bx,by-1,bz)))by--;
        var state=blocks.at(bx,by,bz);
        if(!rail(state))return null;
        var exits=EXITS.get(state.getValue("shape"));
        return exits==null?null:new Track(bx,by,bz,exits);
    }
    private static double[] position(Track track,double x,double z) {
        int[] a=track.exits[0],b=track.exits[1];
        double x0=track.x+.5+a[0]*.5,y0=track.y+.0625+a[1]*.5,z0=track.z+.5+a[2]*.5;
        double dx=(b[0]-a[0])*.5,dy=b[1]-a[1],dz=(b[2]-a[2])*.5;
        double t=dx==0?z-track.z:dz==0?x-track.x:((x-x0)*dx+(z-z0)*dz)*2;
        return new double[]{x0+dx*t,y0+dy*t+(dy<0?1:dy>0?.5:0),z0+dz*t};
    }
    private static double[] offset(Track track,double x,double z,double distance,Blocks blocks) {
        int[] a=track.exits[0],b=track.exits[1];
        double dx=b[0]-a[0],dz=b[2]-a[2],length=Math.sqrt(dx*dx+dz*dz);
        x+=dx/length*distance;z+=dz/length*distance;
        double y=track.y+(a[1]!=b[1]?1:0);
        int bx=(int)Math.floor(x)-track.x,bz=(int)Math.floor(z)-track.z;
        if(a[1]!=0&&bx==a[0]&&bz==a[2])y+=a[1];
        else if(b[1]!=0&&bx==b[0]&&bz==b[2])y+=b[1];
        var next=track(x,y,z,blocks);
        return next==null?null:position(next,x,z);
    }
}
