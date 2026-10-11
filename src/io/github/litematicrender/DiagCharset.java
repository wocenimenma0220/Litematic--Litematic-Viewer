package io.github.litematicrender;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Prints how the JVM decodes non-ASCII filenames, to diagnose pack names that come out as
 * replacement characters.
 */
public final class DiagCharset {
    public static void main(String[] args) throws Exception {
        System.out.println("sun.jnu.encoding=" + System.getProperty("sun.jnu.encoding")
            + "   file.encoding=" + System.getProperty("file.encoding")
            + "   native.encoding=" + System.getProperty("native.encoding"));
        Path dir = Path.of(args[0]);
        for (Path p : Files.newDirectoryStream(dir)) {
            String n = p.getFileName().toString();
            System.out.println("name:  " + n);
            System.out.println("  utf8 bytes: " + java.util.Arrays.toString(
                n.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            System.out.println("  exists(Path.of): " + Files.exists(dir.resolve(n)));
        }
    }
}
