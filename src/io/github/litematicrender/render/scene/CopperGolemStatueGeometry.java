package io.github.litematicrender.render.scene;

import io.github.litematicrender.world.BlockState;
import java.util.Map;
import static io.github.litematicrender.render.scene.EntityGeometry.*;

/** Minecraft 26.3 CopperGolemModel pose layers and CopperGolemStatueBlockRenderer transforms. */
final class CopperGolemStatueGeometry {
    private static final Map<String,Part> POSES=Map.of(
        "standing",standing(),"running",running(),"sitting",sitting(),"star",star());

    static String texture(String blockId) {
        String name=blockId.substring(10).replace("waxed_","");
        String suffix=name.startsWith("exposed_")?"_exposed":name.startsWith("weathered_")?"_weathered":name.startsWith("oxidized_")?"_oxidized":"";
        return "minecraft:entity/copper_golem/copper_golem"+suffix;
    }

    static void emit(EntityGeometry geometry,SceneMesh mesh,BlockState state,int x,int y,int z,String texture,float[] light) {
        Part root=POSES.getOrDefault(String.valueOf(state.getValue("copper_golem_pose")),POSES.get("standing"));
        Mat world=Mat.identity().translate(x+.5,y,z+.5).rotateY(Math.toRadians(180-SpecialBlockGeometry.yaw(state.getValue("facing"))));
        geometry.parts(mesh,root,world,texture,64,64,light);
    }

    private static Part root() {
        // The statue overrides the living model's root y=24 and rotates it by PI around Z.
        Part root=new Part();root.rz=Math.PI;return root;
    }

    private static Part head(Part body,double grow) {
        return body.child("head",0,-6,0,0,0,0)
            .box(0,0,-4,-5,-5,8,5,10,grow,false)
            .box(56,0,-1,-2,-6,2,3,2)
            .box(37,8,-1,-9,-1,2,4,2,-.015,false)
            .box(37,0,-2,-13,-2,4,4,4,-.015,false);
    }

    private static Part standing() {
        Part root=root(),body=root.child("body",0,-5,0,0,0,0).box(0,15,-4,-6,-3,8,6,6);
        head(body,.015);
        body.child("right_arm",-4,-6,0,0,0,0).box(36,16,-3,-1,-2,3,10,4);
        body.child("left_arm",4,-6,0,0,0,0).box(50,16,0,-1,-2,3,10,4);
        root.child("right_leg",0,-5,0,0,0,0).box(0,27,-4,0,-2,4,5,4);
        root.child("left_leg",0,-5,0,0,0,0).box(16,27,0,0,-2,4,5,4);
        return root;
    }

    private static Part running() {
        Part root=root(),body=root.child("body",-1.064,-5,0,0,0,0);
        body.child("body_r1",1.1,.1,.7,.1204,-.0064,-.0779).box(0,15,-4.02,-6.116,-3.5,8,6,6);
        Part head=body.child("head",.7,-5.6,-1.8,0,0,0);
        head.box(0,0,-4,-5.1,-5,8,5,10).box(56,0,-1.02,-2.1,-6,2,3,2)
            .box(37,8,-1.02,-9.1,-1,2,4,2,-.015,false).box(37,0,-2,-13.1,-2,4,4,4,-.015,false);
        body.child("right_arm",-4,-6,0,0,0,0).child("right_arm_r1",.7,-.248,-1.62,1.0036,0,0).box(36,16,-3.052,-1.11,-2.036,3,10,4);
        body.child("left_arm",4,-6,0,0,0,0).child("left_arm_r1",.732,0,0,-.8715,-.0535,-.0449).box(50,16,.032,-1.1,-2,3,10,4);
        root.child("right_leg",-3.064,-5,0,0,0,0).child("right_leg_r1",1.048,0,-.9,-.8727,0,0).box(0,27,-1.856,-.1,-1.09,4,5,4);
        root.child("left_leg",.936,-5,0,0,0,0).child("left_leg_r1",1,0,0,.7854,0,0).box(16,27,-2.088,-.1,-2,4,5,4);
        return root;
    }

    private static Part sitting() {
        Part root=root(),body=root.child("body",0,-3,2.325,0,0,0)
            .box(3,19,-3,-4,-4.525,6,1,6).box(0,15,-4,-3,-3.525,8,6,6);
        body.child("body_r1",0,-1,-4.325,0,0,-3.1416).box(3,18,-4,-3,-2.2,8,6,3);
        body.child("head",0,-6,-.2,0,0,0)
            .box(37,8,-1,-7,-3.3,2,4,2,-.015,false).box(37,0,-2,-11,-4.3,4,4,4,-.015,false)
            .box(0,0,-4,-3,-7.325,8,5,10).box(56,0,-1,0,-8.325,2,3,2);
        body.child("right_arm",-4,-5.6,-1.8,.4363,0,0).child("right_arm_r1",0,.0893,.1198,-1.0472,0,0).box(36,16,-3.075,-.9733,-1.9966,3,10,4);
        body.child("left_arm",4,-5.6,-1.7,.4363,0,0).child("left_arm_r1",0,-.0015,-.0808,-1.0472,0,0).box(50,16,.075,-1.0443,-1.8997,3,10,4);
        root.child("right_leg",-2.1,-2.1,-2.075,0,0,0).child("right_leg_r1",.05,-1.9,1.075,-1.5708,0,0).box(0,27,-2,.975,0,4,5,4);
        root.child("left_leg",2,-2,-2.075,0,0,0).child("left_leg_r1",.05,-2,1.075,-1.5708,0,0).box(16,27,-2,.975,0,4,5,4);
        return root;
    }

    private static Part star() {
        Part root=root(),body=root.child("body",0,-5,0,0,0,0).box(0,15,-4,-6,-3,8,6,6);
        head(body,0);
        body.child("right_arm",-4,-6,0,0,0,0).child("right_arm_r1",1,1,0,0,0,1.9199).box(36,16,-1.5,-5,-2,3,10,4);
        body.child("left_arm",4,-6,0,0,0,0).child("left_arm_r1",-1,1,0,0,0,-1.9199).box(50,16,-1.5,-5,-2,3,10,4);
        root.child("right_leg",-3,-5,0,0,0,0).child("right_leg_r1",.35,2,.01,0,0,.2618).box(0,27,-2,-2.5,-2,4,5,4);
        root.child("left_leg",1,-5,0,0,0,0).child("left_leg_r1",1.65,2,0,0,0,-.2618).box(16,27,-2,-2.5,-2,4,5,4);
        return root;
    }
}
