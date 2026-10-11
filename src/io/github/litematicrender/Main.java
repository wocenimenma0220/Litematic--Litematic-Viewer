package io.github.litematicrender;

import io.github.litematicrender.render.RenderSettings;
import io.github.litematicrender.render.RenderTest;

import java.nio.file.Path;

/**
 * Application entry point.
 *
 * <p>The GUI lives in the Electron/web shell; this Java side is the render engine and is driven
 * either from the command line (for scripting and testing) or over the line-based JSON protocol
 * used by the shell.
 *
 * <pre>
 *   java ... io.github.litematicrender.Main --render &lt;file.litematic&gt; [out.png] [width] [height]
 *   java ... io.github.litematicrender.Main --anim   &lt;file.litematic&gt; [out.gif] [size] [frames]
 *   java ... io.github.litematicrender.Main --serve
 * </pre>
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        Path base = Path.of(System.getProperty("litematicrender.home", ".")).toAbsolutePath();
        Path vanilla = base.resolve("assets/vanilla");

        if (args.length > 0 && args[0].equals("--render")) {
            String[] rest = new String[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            RenderTest.main(buildCliArgs(rest, vanilla));
            return;
        }

        if (args.length > 0 && args[0].equals("--anim")) {
            if (args.length < 2) {
                System.err.println("usage: --anim <file.litematic> [out.gif] [size] [frames]");
                System.exit(2);
            }
            Path schem = Path.of(args[1]);
            Path out = Path.of(args.length > 2 ? args[2] : "animation.gif");
            int size = args.length > 3 ? Integer.parseInt(args[3]) : 512;
            int frames = args.length > 4 ? Integer.parseInt(args[4]) : 64;
            RenderSettings settings = new RenderSettings(45f, 30f, size, size);
            RenderTest.renderAnimation(schem, vanilla, out, settings, frames, 5, true);
            return;
        }

        if (args.length > 0 && args[0].equals("--serve")) {
            io.github.litematicrender.serve.JsonRpcServer.main(new String[0]);
            return;
        }

        System.err.println("usage: --render <file.litematic> [out.png] [width] [height]");
        System.err.println("       --anim   <file.litematic> [out.gif] [size] [frames]");
        System.err.println("       --serve");
        System.exit(2);
    }

    private static String[] buildCliArgs(String[] rest, Path vanilla) {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(rest.length > 0 ? rest[0] : "");
        out.add(vanilla.toString());
        out.add(rest.length > 1 ? rest[1] : "render.png");
        if (rest.length > 2) out.add(rest[2]);
        if (rest.length > 3) out.add(rest[3]);
        return out.toArray(String[]::new);
    }
}
