package io.github.litematicrender.serve;

import io.github.litematicrender.render.RenderSettings;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Verifies that animation export is a real multi-frame GIF and rotates the camera. */
public final class GifRotationRegression {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),schematic=Path.of(args[1]).toAbsolutePath(),output=Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(output.getParent());System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        try(var engine=new RenderEngine(root)) {
            engine.loadResources(engine.defaultPackRoot());engine.open(schematic);
            var settings=new RenderSettings(17,28,96,96);settings.transparentBackground=true;
            var result=engine.renderAnimation(output,settings,8,4);
            check(((Number)result.get("frames")).intValue()==8,"requested camera frames were reduced to the texture cycle");
            check(((Number)result.get("cameraRevolutions")).intValue()==1,"camera revolution metadata missing");
        }
        byte[] signature=Files.readAllBytes(output);check(signature.length>6&&new String(signature,0,6,java.nio.charset.StandardCharsets.US_ASCII).equals("GIF89a"),"animation is not GIF89a data");
        var reader=ImageIO.getImageReadersByFormatName("gif").next();
        try(var input=ImageIO.createImageInputStream(output.toFile())) {
            reader.setInput(input);check(reader.getNumImages(true)==8,"GIF frame count differs");var first=reader.read(0);boolean changed=false;
            for(int frame=1;frame<8&&!changed;frame++){var next=reader.read(frame);for(int y=0;y<first.getHeight()&&!changed;y+=2)for(int x=0;x<first.getWidth();x+=2)if(first.getRGB(x,y)!=next.getRGB(x,y)){changed=true;break;}}
            check(changed,"all GIF frames are identical; camera did not rotate");
        } finally {reader.dispose();}
        System.out.println("PASS: GIF89a signature, exact frame count and visible 360-degree camera rotation");
    }
}
