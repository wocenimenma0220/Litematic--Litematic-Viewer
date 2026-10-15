import io.github.litematicrender.render.*;
import io.github.litematicrender.render.raster.*;
import java.nio.file.*;
import javax.imageio.ImageIO;

public class BackgroundRegression {
    static void check(boolean ok,String message) { if (!ok) throw new AssertionError(message); }
    static boolean quantized(int actual, int expected) {
        return Image.alpha(actual)==255 && Math.abs(Image.red(actual)-Image.red(expected))<=4
            && Math.abs(Image.green(actual)-Image.green(expected))<=4 && Math.abs(Image.blue(actual)-Image.blue(expected))<=4;
    }
    public static void main(String[] args) throws Exception {
        var settings = new RenderSettings(); settings.transparentBackground=false;
        settings.backgroundMode="checker"; settings.checkerSize=10;
        Image target=new Image(40,20); RenderBackground.fill(target,settings);
        check(RenderBackground.isTransparent(settings) && target.pixel(0,0)==0 && target.pixel(10,0)==0,"checker must remain transparent even for legacy opaque settings");
        var parse=io.github.litematicrender.serve.JsonRpcServer.class.getDeclaredMethod("settingsFrom",java.util.Map.class);
        parse.setAccessible(true);
        for(var params:java.util.List.of(java.util.Map.<String,Object>of("transparent",true,"background",0xFFFFFF),
                java.util.Map.<String,Object>of("transparent",false,"backgroundMode","checker","background",0xFFFFFF),
                java.util.Map.<String,Object>of("transparent",true,"backgroundMode","image","backgroundImage","missing.png"))) {
            check(((RenderSettings)parse.invoke(null,params)).transparentBackground,"RPC background overrode transparency: "+params);
        }
        check(!((RenderSettings)parse.invoke(null,java.util.Map.of("background",0xFFFFFF))).transparentBackground,"legacy opaque background changed");
        Image source=new Image(2,1); source.setPixel(0,0,0xFFFF0000); source.setPixel(1,0,0xFF00FFFF);
        settings.backgroundImage=source; settings.backgroundMode="image";
        RenderBackground.fill(target,settings);
        check(target.pixel(0,0)==0xFFFF0000 && target.pixel(39,0)==0xFF00FFFF,"background cover alignment incorrect");
        settings.transparentBackground=true; target=new Image(40,20); RenderBackground.fill(target,settings);
        check(target.pixel(0,0)==0,"transparent export must ignore selected background");
        settings.transparentBackground=false; source.setPixel(0,0,0); source.setPixel(1,0,0); RenderBackground.fill(target,settings);
        check(target.pixel(0,0)==0xFF22262E,"transparent source-background must composite over dark base");
        var smaller=SchematicRenderer.interactiveScale(settings.size(800,600),100);
        check(smaller.backgroundImage==source && smaller.backgroundMode.equals("image"),"preview scaling dropped background settings");
        if (args.length>0) {
            Path output=Path.of(args[0]);
            var png=ImageIO.read(output.resolve("image-export.png").toFile());
            var grid=ImageIO.read(output.resolve("grid-export.png").toFile());
            var gif=ImageIO.read(output.resolve("image-export.gif").toFile());
            check(png.getRGB(0,0)==0xFFFF0000 && png.getRGB(191,0)==0xFF00FFFF,"actual PNG export omitted imported background");
            transparent(grid,"default grid PNG");
            check(quantized(gif.getRGB(0,0),0xFFFF0000) && quantized(gif.getRGB(95,0),0xFF00FFFF),"actual GIF export omitted imported background");
            if(Files.exists(output.resolve("grid-export.gif"))) {
                for(String name:java.util.List.of("cpu-grid.png","override.png","rpc-override.png","rpc-checker.png")) transparent(ImageIO.read(output.resolve(name).toFile()),name);
                var white=ImageIO.read(output.resolve("white.png").toFile());
                check(white.getRGB(0,0)==-1 && white.getRGB(191,191)==-1,"white background no longer opaque");
                var reader=ImageIO.getImageReadersByFormatName("gif").next();
                try(var input=ImageIO.createImageInputStream(output.resolve("grid-export.gif").toFile())) {
                    reader.setInput(input);int frames=reader.getNumImages(true);
                    check(frames>0,"GIF has no frames");
                    for(int i=0;i<frames;i++)transparent(reader.read(i),"grid GIF frame "+i);
                } finally {reader.dispose();}
            }
        }
        System.out.println("PASS: preview-only grid, RPC transparent priority, image cover, PNG/GIF alpha and visible content");
    }
    static void transparent(java.awt.image.BufferedImage image,String name) {
        int width=image.getWidth(),height=image.getHeight(),visible=0,empty=0;
        for(int x:new int[]{0,width-1})for(int y:new int[]{0,height-1})check((image.getRGB(x,y)>>>24)==0,name+" has opaque corner");
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){if((image.getRGB(x,y)>>>24)==0)empty++;else visible++;}
        check(visible>100 && empty>100,name+" is blank or has no transparent area");
    }
}
