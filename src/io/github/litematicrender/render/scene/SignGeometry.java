package io.github.litematicrender.render.scene;

import io.github.litematicrender.render.FontRenderer;
import io.github.litematicrender.render.Image;
import io.github.litematicrender.world.BlockState;
import java.util.*;

/** Vanilla sign text planes with ordinary light sampling and full-bright glow-ink outlines. */
public final class SignGeometry {
    private SignGeometry() {}
    public static void emit(SceneMesh mesh, BlockState state, BlockEntityData.Sign sign, FontRenderer font,
                            int x, int y, int z, float[] light, Map<String, Image> sprites) {
        boolean hanging = state.blockId.contains("hanging_sign");
        boolean wall = state.blockId.contains("wall_sign") && !hanging;
        String facing = state.getValue("facing");
        double angle;
        if (facing != null) angle = switch (facing) { case "north" -> 180; case "east" -> 270; case "west" -> 90; default -> 0; };
        else {
            int r;
            try { r = Integer.parseInt(state.getValue("rotation")); } catch (Exception e) { r = 0; }
            angle = r * 22.5;
        }
        for (int side = 0; side < 2; side++) {
            List<String> lines = side == 0 ? sign.frontLines() : sign.backLines();
            if (lines.stream().noneMatch(font::canRenderAny)) continue;
            boolean glow = side == 0 ? sign.glowingFront() : sign.glowingBack();
            int color = side == 0 ? sign.frontColor() : sign.backColor();
            var textPanel=font.signPanel(lines,color,glow,hanging);
            Image panel=textPanel.image();String id=textPanel.spriteId();
            sprites.put(id, panel);
            double a = Math.toRadians(angle), c = Math.cos(a), s = Math.sin(a);
            double baseX = x + 0.5, baseZ = z + 0.5;
            if (wall) { baseX += 0.4375 * s; baseZ -= 0.4375 * c; }
            double centerY = y + (hanging ? 0.305 : wall ? 0.520833333 : 0.833333333);
            double scale = hanging ? 0.0140625 : 0.010416667;
            double distance = hanging ? 0.073 : 0.046666667;
            double sideAngle = a + (side == 0 ? 0 : Math.PI);
            double sc = Math.cos(sideAngle), ss = Math.sin(sideAngle);
            float[] xs = new float[4], ys = new float[4], zs = new float[4];
            int[] hx = {-1,1,1,-1}, hy = {-1,-1,1,1};
            for (int i = 0; i < 4; i++) {
                double dx = hx[i] * panel.width * scale / 4;
                xs[i] = (float)(baseX + dx * sc - distance * ss);
                zs[i] = (float)(baseZ + dx * ss + distance * sc);
                ys[i] = (float)(centerY + hy[i] * panel.height * scale / 4);
            }
            mesh.cutout.add(new SceneMesh.MeshQuad(xs,ys,zs,0,1,1,1,1,0,0,0,
                id,0xFFFFFFFF,panel,glow ? 1 : light[0],glow ? 1 : light[1],glow ? 1 : light[2],1,null,
                SceneMesh.Layer.CUTOUT,false));
        }
    }
}
