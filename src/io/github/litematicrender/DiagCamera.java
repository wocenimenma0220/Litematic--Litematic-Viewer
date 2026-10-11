package io.github.litematicrender;

import io.github.litematicrender.render.raster.Camera;

/**
 * Proves or disproves the view matrix: for an isometric-style render the three world axes must
 * project to screen directions that are consistent with a real rotation (orthonormal, right-handed,
 * with +Y going <em>up</em> the screen).
 *
 * <p>A rotation matrix is orthonormal with determinant +1; anything else cannot be produced by a
 * camera and shows up as a sheared or mirrored picture.
 */
public final class DiagCamera {
    public static void main(String[] args) {
        float[][] cases = {
            {45f, 30f}, {45f, 35.264f}, {0f, 0f}, {90f, 0f}, {135f, 30f},
            {180f, 0f}, {270f, 45f}, {0f, 89f}, {30f, -20f},
        };
        int bad = 0;
        for (float[] c : cases) {
            Camera cam = new Camera();
            cam.rotationDeg = c[0];
            cam.slantDeg = c[1];
            cam.centerX = cam.centerY = cam.centerZ = 0;
            cam.update();

            // Recover the matrix by probing the basis vectors.
            double[] o = new double[3];
            cam.toView(1, 0, 0, o); double[] rx = {o[0], o[1], o[2]};
            cam.toView(0, 1, 0, o); double[] ry = {o[0], o[1], o[2]};
            cam.toView(0, 0, 1, o); double[] rz = {o[0], o[1], o[2]};

            double det = rx[0] * (ry[1] * rz[2] - ry[2] * rz[1])
                       - ry[0] * (rx[1] * rz[2] - rx[2] * rz[1])
                       + rz[0] * (rx[1] * ry[2] - rx[2] * ry[1]);
            double lenX = Math.sqrt(rx[0] * rx[0] + rx[1] * rx[1] + rx[2] * rx[2]);
            double lenY = Math.sqrt(ry[0] * ry[0] + ry[1] * ry[1] + ry[2] * ry[2]);
            double lenZ = Math.sqrt(rz[0] * rz[0] + rz[1] * rz[1] + rz[2] * rz[2]);
            double dotXY = rx[0] * ry[0] + rx[1] * ry[1] + rx[2] * ry[2];
            double dotXZ = rx[0] * rz[0] + rx[1] * rz[1] + rx[2] * rz[2];
            double dotYZ = ry[0] * rz[0] + ry[1] * rz[1] + ry[2] * rz[2];

            boolean orthonormal = Math.abs(lenX - 1) < 1e-6 && Math.abs(lenY - 1) < 1e-6
                && Math.abs(lenZ - 1) < 1e-6 && Math.abs(dotXY) < 1e-6
                && Math.abs(dotXZ) < 1e-6 && Math.abs(dotYZ) < 1e-6;
            boolean rightHanded = Math.abs(det - 1) < 1e-6;
            // Screen y is flipped when drawing (sy = cy - viewY), so a point above the centre
            // must have a positive view Y.
            boolean upIsUp = ry[1] > 0;

            String verdict = (orthonormal && rightHanded && upIsUp) ? "ok" : "BAD";
            if (!verdict.equals("ok")) bad++;
            System.out.printf("rot=%6.1f slant=%7.3f  len=(%.4f,%.4f,%.4f) det=%+.4f upIsUp=%s  %s%n",
                c[0], c[1], lenX, lenY, lenZ, det, upIsUp, verdict);
            if (!upIsUp) {
                System.out.printf("      -> +Y world maps to view Y %.4f (screen moves %s)%n",
                    ry[1], ry[1] > 0 ? "up" : "DOWN");
            }
        }
        System.out.println();
        System.out.println(bad == 0 ? "camera matrix OK" : "camera matrix FAILURES: " + bad);

        // Screen-space assertions through the exact mapping the rasterizer uses:
        //   sx = w/2 + offX + viewX*zoom,  sy = h/2 + offY - viewY*zoom
        // These are the properties a viewer actually perceives, so they are checked directly.
        System.out.println();
        System.out.println("== screen mapping (w=h=1000, zoom=100, no offset) ==");
        Camera cam = new Camera();
        cam.rotationDeg = 45f;
        cam.slantDeg = 30f;
        cam.centerX = cam.centerY = cam.centerZ = 0;
        cam.zoom = 100f;
        cam.update();

        int screenBad = 0;
        // 1) a block above the centre must land higher on screen (smaller sy)
        double syAbove = sy(cam, 0, 10, 0);
        double syCentre = sy(cam, 0, 0, 0);
        screenBad += expect("higher Y -> smaller screen Y (up)", syAbove < syCentre,
            String.format("sy(y=10)=%.1f  sy(y=0)=%.1f", syAbove, syCentre));

        // 2) +X must go right, +Z must go down-and-right (the standard isometric reading)
        double sxPlusX = sx(cam, 10, 0, 0);
        screenBad += expect("+X -> right", sxPlusX > sx(cam, 0, 0, 0),
            String.format("sx(+X)=%.1f", sxPlusX));
        double syPlusZ = sy(cam, 0, 0, 10);
        screenBad += expect("+Z -> downward (toward viewer-bottom)", syPlusZ > syCentre,
            String.format("sy(+Z)=%.1f  sy(0)=%.1f", syPlusZ, syCentre));

        // 3) depth: the point nearer the camera must have the larger view depth, because the
        //    rasterizer keeps a pixel when dVal >= stored.
        double dAbove = cam.viewDepth(0, 10, 0);
        double dCentre = cam.viewDepth(0, 0, 0);
        screenBad += expect("viewer is above: +Y is nearer (larger view depth)", dAbove > dCentre,
            String.format("depth(y=10)=%.3f  depth(y=0)=%.3f", dAbove, dCentre));

        // 4) the camera must look down at the model, so the top face is visible: with slant > 0
        //    the +Y axis must point toward the viewer (positive view Z).
        System.out.println();
        System.out.println(screenBad == 0
            ? "screen mapping OK"
            : "screen mapping FAILURES: " + screenBad);
        if (bad + screenBad > 0) System.exit(1);
    }

    private static double sx(Camera c, double x, double y, double z) {
        double[] o = new double[3];
        c.toView(x, y, z, o);
        return 500 + o[0] * c.zoom;
    }

    private static double sy(Camera c, double x, double y, double z) {
        double[] o = new double[3];
        c.toView(x, y, z, o);
        return 500 - o[1] * c.zoom;
    }

    private static int expect(String what, boolean ok, String detail) {
        System.out.printf("  %s %-52s %s%n", ok ? "ok  " : "FAIL", what, detail);
        return ok ? 0 : 1;
    }
}
