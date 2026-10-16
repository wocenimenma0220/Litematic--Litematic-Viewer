import io.github.litematicrender.render.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Decode the real GIF: faint pixels must survive and alpha coverage must stay stable between frames. */
public class GifTransparencyRegression {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]).resolve("tools/verify/selection-output");Files.createDirectories(out);
        int[] alphas={0,1,26,64,127,128,254,255};
        Image image=new Image(16*alphas.length,16);
        for(int y=0;y<16;y++)for(int x=0;x<image.width;x++)image.pixels()[y*image.width+x]=(alphas[x/16]<<24)|((x/16%2==0)?0xe08030:0x3060d0);
        var progress=new ArrayList<String>();Path file=out.resolve("fractional-alpha.gif");
        GifWriter.writeFrames(file,2,2,tick->image,(stage,completed,total)->progress.add(stage+":"+completed+"/"+total));
        check(progress.equals(List.of("rendering:1/2","rendering:2/2","encoding:1/2","encoding:2/2")),"progress was incomplete: "+progress);
        try(var stream=ImageIO.createImageInputStream(file.toFile())){
            var reader=ImageIO.getImageReadersByFormatName("gif").next();reader.setInput(stream);
            try{
                check(reader.getNumImages(true)==2,"missing frames");var first=reader.read(0);var second=reader.read(1);
                for(int tile=0;tile<alphas.length;tile++){
                    int visible=0;
                    for(int y=0;y<16;y++)for(int x=tile*16;x<(tile+1)*16;x++){
                        int pixel=first.getRGB(x,y);if(pixel>>>24!=0){visible++;int expected=image.pixels()[y*image.width+x];for(int shift:new int[]{0,8,16})check(Math.abs((pixel>>shift&255)-(expected>>shift&255))<=8,"faint colour missing from palette");}
                        check(pixel==second.getRGB(x,y),"alpha pattern flickers between identical frames");
                    }
                    check(Math.abs(visible-Math.round(alphas[tile]*256f/255))<=1,"alpha "+alphas[tile]+" coverage "+visible);
                    if(alphas[tile]==26)check(visible==26,"10% surroundings disappeared");
                }
            }finally{reader.dispose();}
        }
        Path direct=out.resolve("fractional-direct.gif");GifWriter.write(direct,List.of(image),2);
        var decoded=ImageIO.read(direct.toFile());int faint=0;for(int y=0;y<16;y++)for(int x=32;x<48;x++)if(decoded.getRGB(x,y)>>>24!=0)faint++;
        check(faint==26,"direct GIF path dropped partial alpha");
        System.out.println("PASS: decoded GIF retains 10% coverage, faint colours, stable alpha, opaque/empty pixels and render/encoding progress");
    }
}
