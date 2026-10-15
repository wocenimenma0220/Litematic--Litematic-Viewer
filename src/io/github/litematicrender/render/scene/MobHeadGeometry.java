package io.github.litematicrender.render.scene;

import io.github.litematicrender.world.BlockState;
import io.github.litematicrender.render.Image;
import java.util.Map;
import static io.github.litematicrender.render.scene.EntityGeometry.*;

/** Vanilla static skull models. Player profiles deliberately stay in HeadGeometry. */
final class MobHeadGeometry {
    static void emit(EntityGeometry geometry,SceneMesh mesh,BlockState state,int x,int y,int z,float[] light,Map<String,Image> sprites) {
        String kind=state.blockId.substring(state.blockId.indexOf(':')+1).replace("_wall_","_");
        String texture=switch(kind) {
            case "skeleton_skull" -> "skeleton/skeleton";
            case "wither_skeleton_skull" -> "skeleton/wither_skeleton";
            case "zombie_head" -> "zombie/zombie";
            case "creeper_head" -> "creeper/creeper";
            case "dragon_head" -> "enderdragon/dragon";
            case "piglin_head" -> "piglin/piglin";
            default -> throw new IllegalArgumentException("Unsupported skull: "+state.blockId);
        };
        String sprite="litematicrender:mob_head/"+kind;
        Image image=geometry.image("minecraft:entity/"+texture);
        if(image==null)throw new IllegalArgumentException("Missing skull texture: "+texture);
        sprites.putIfAbsent(sprite,image);
        String facing=state.getValue("facing");double angle;double dx=0,dz=0;
        if(facing!=null) {
            angle=switch(facing){case "east"->-90;case "south"->-180;case "west"->-270;default->0;};
            dx=facing.equals("east")?-.25:facing.equals("west")?.25:0;
            dz=facing.equals("south")?-.25:facing.equals("north")?.25:0;
        } else {
            int rotation;try{rotation=Integer.parseInt(state.getValue("rotation"));}catch(Exception ignored){rotation=0;}
            angle=-rotation*22.5;
        }
        Mat world=Mat.identity().translate(x+.5+dx,y+(facing==null?0:.25),z+.5+dz).rotateY(Math.toRadians(angle)).scale(-1,-1,1);
        Part root=new Part();int width=64,height=32;
        switch(kind) {
            case "dragon_head" -> {
                width=height=256;world=world.translate(0,-7.986666/16,0).scale(.75,.75,.75);
                root.box(176,44,-6,-1,-24,12,5,16).box(112,30,-8,-8,-10,16,16,16)
                    .box(0,0,-5,-12,-4,2,4,6,0,true).box(112,0,-5,-3,-22,2,2,4,0,true)
                    .box(0,0,3,-12,-4,2,4,6).box(112,0,3,-3,-22,2,2,4);
                root.child("jaw",0,4,-8,.2,0,0).box(176,65,-6,0,-16,12,4,16);
            }
            case "piglin_head" -> {
                height=64;
                root.box(0,0,-5,-8,-4,10,8,8).box(31,1,-2,-4,-5,4,4,1).box(2,4,2,-2,-5,1,2,1).box(2,0,-3,-2,-5,1,2,1);
                root.child("left_ear",4.5,-6,0,0,0,-.7).box(51,6,0,0,-2,1,5,4);
                root.child("right_ear",-4.5,-6,0,0,0,.7).box(39,6,-1,0,-2,1,5,4);
            }
            case "zombie_head" -> {height=64;root.box(0,0,-4,-8,-4,8,8,8).box(32,0,-4,-8,-4,8,8,8,.25,false);}
            default -> root.box(0,0,-4,-8,-4,8,8,8);
        }
        geometry.parts(mesh,root,world,sprite,width,height,light);
    }
}
