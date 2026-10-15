package io.github.litematicrender.render.scene;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.model.Quad;
import io.github.litematicrender.world.BlockState;
import io.github.litematicrender.render.skin.SkinResolver;

/** Player skins on the vanilla head/hat model, preserving rotation, with Steve as the fallback. */
public final class HeadGeometry {
    private HeadGeometry() {}
    public static void emit(SceneMesh mesh, ModelBaker baker, BlockState state, int x, int y, int z, float[] light, SkinResolver.Skin skin) {
        emit(mesh,baker,state,x,y,z,light,skin,true);
    }
    public static void emit(SceneMesh mesh,ModelBaker baker,BlockState state,int x,int y,int z,float[] light,SkinResolver.Skin skin,boolean lightingEnabled) {
        var model = baker.bake(new ModelBaker.Variant("minecraft:block/litematicrender_steve_head", 0, 0, 0, false));
        String facing = state.getValue("facing");
        float angle, offsetX = 0, offsetY = 0, offsetZ = 0;
        if (facing != null) {
            angle = switch (facing) { case "north" -> 0; case "west" -> -90; case "east" -> 90; default -> 180; };
            offsetX = facing.equals("east") ? -0.25f : facing.equals("west") ? 0.25f : 0;
            offsetZ = facing.equals("south") ? -0.25f : facing.equals("north") ? 0.25f : 0;
            offsetY = 0.25f;
        } else {
            int rotation;
            try { rotation = Integer.parseInt(state.getValue("rotation")); } catch (Exception e) { rotation = 0; }
            angle = rotation * 22.5f - 180;
        }
        double radians = Math.toRadians(angle), cos = Math.cos(radians), sin = Math.sin(radians);
        for (Quad q : model.all) {
            float[] xs = {q.x0,q.x1,q.x2,q.x3}, ys = {q.y0,q.y1,q.y2,q.y3}, zs = {q.z0,q.z1,q.z2,q.z3};
            for (int i = 0; i < 4; i++) {
                double dx = xs[i] - 0.5, dz = zs[i] - 0.5;
                xs[i] = x + 0.5f + offsetX + (float)(dx * cos - dz * sin);
                zs[i] = z + 0.5f + offsetZ + (float)(dx * sin + dz * cos);
                ys[i] += y + offsetY;
            }
            float ax = xs[1]-xs[0], ay = ys[1]-ys[0], az = zs[1]-zs[0];
            float bx = xs[2]-xs[0], by = ys[2]-ys[0], bz = zs[2]-zs[0];
            float nx = ay*bz-az*by, ny = az*bx-ax*bz, nz = ax*by-ay*bx;
            float shade = Math.abs(ny) > 0.001f ? (ny > 0 ? 1 : 0.5f)
                : (Math.abs(nx)*0.6f + Math.abs(nz)*0.8f) / Math.max(0.0001f, Math.abs(nx)+Math.abs(nz));
            if(!lightingEnabled)shade=1;
            boolean overlay = Math.min(Math.min(q.u0,q.u1),Math.min(q.u2,q.u3)) >= 0.5f;
            SceneMesh.Layer layer = skin != null && overlay && skin.translucentOverlay()
                ? SceneMesh.Layer.TRANSLUCENT : SceneMesh.Layer.CUTOUT;
            SceneMesh.MeshQuad head = new SceneMesh.MeshQuad(xs, ys, zs,
                q.u0,q.v0,q.u1,q.v1,q.u2,q.v2,q.u3,q.v3,
                skin == null ? q.spriteId : skin.spriteId(),0xFFFFFFFF,skin == null ? null : skin.image(),
                light[0],light[1],light[2],shade,null,layer,false);
            if (layer == SceneMesh.Layer.TRANSLUCENT) mesh.translucent.add(head); else mesh.cutout.add(head);
        }
    }
}
