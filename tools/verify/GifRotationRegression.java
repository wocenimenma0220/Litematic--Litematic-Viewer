package io.github.litematicrender.serve;

import io.github.litematicrender.nbt.*;
import io.github.litematicrender.nbt.io.NbtFileUtils;
import io.github.litematicrender.render.*;
import io.github.litematicrender.render.raster.Camera;
import io.github.litematicrender.render.scene.SceneMesh;
import java.awt.AlphaComposite;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.w3c.dom.Node;

/** Checks fixed-center framing and a GIF player's composited frames, including loop boundaries. */
public final class GifRotationRegression {
    private static final double[] MIN={-30,7,-12}, MAX={-18,11,-10};
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static NbtCompound xyz(int x,int y,int z){return new NbtCompound().putInt("x",x).putInt("y",y).putInt("z",z);}
    private static Path fixture(Path file)throws Exception {
        long[] words=new long[3];Arrays.fill(words,0x5555555555555555L);
        var palette=new NbtList();for(String name:List.of("air","stone"))palette.add(new NbtCompound().putString("Name","minecraft:"+name));
        var size=xyz(12,4,2);
        var region=new NbtCompound().put("Position",xyz(-30,7,-12)).put("Size",size).put("BlockStatePalette",palette).put("BlockStates",new NbtScalars.NbtLongArray(words));
        NbtFileUtils.writeCompressed(file,new NbtCompound().putInt("Version",6).putInt("MinecraftDataVersion",0).put("Metadata",new NbtCompound().putString("Name","Fixed-center orbit").put("EnclosingSize",size)).put("Regions",new NbtCompound().put("prism",region)));
        return file;
    }
    private static SceneMesh boundsMesh() {
        var mesh=new SceneMesh();
        for(double z:new double[]{MIN[2],MAX[2]})mesh.solid.add(new SceneMesh.MeshQuad(
            new float[]{-30,-18,-18,-30},new float[]{7,7,11,11},new float[]{(float)z,(float)z,(float)z,(float)z},
            0,0,1,0,1,1,0,1,null,0xffffff,1,1,1,1,null,SceneMesh.Layer.SOLID));
        return mesh;
    }
    private static void cameraGeometry() {
        for(float pitch:new float[]{0,28,-30,89}) {
            var settings=new RenderSettings(17,pitch,160,128);Camera camera=SchematicRenderer.fitRotationCamera(boundsMesh(),settings);
            float zoom=camera.zoom;double[] point=new double[3];
            for(int frame=0;frame<360;frame++) {
                camera.rotationDeg=17+frame;camera.update();camera.toView(-24,9,-11,point);
                check(Math.abs(point[0])+Math.abs(point[1])+Math.abs(point[2])<1e-10,"orbit pivot moved away from model center");
                check(camera.zoom==zoom&&camera.offsetX==0&&camera.offsetY==0,"orbit scale or pan changed");
                for(int corner=0;corner<8;corner++) {
                    camera.toView((corner&1)==0?MIN[0]:MAX[0],(corner&2)==0?MIN[1]:MAX[1],(corner&4)==0?MIN[2]:MAX[2],point);
                    check(Math.abs(point[0]*zoom)<=160*.46+.001&&Math.abs(point[1]*zoom)<=128*.46+.001,"fixed orbit clipped a model corner");
                }
                camera.toView(-24,11,-11,point);double top=point[1];camera.toView(-24,7,-11,point);
                check(Math.abs((top-point[1])*zoom-4*Math.cos(Math.toRadians(pitch))*zoom)<1e-8,"vertical axis changed projected size");
            }
        }
        var empty=SchematicRenderer.fitRotationCamera(new SceneMesh(),new RenderSettings());
        check(Float.isFinite(empty.zoom)&&empty.centerX==0,"empty scene has an invalid camera");
    }
    private static Node node(Node root,String name) {
        if(root.getNodeName().equals(name))return root;
        for(Node child=root.getFirstChild();child!=null;child=child.getNextSibling()){Node found=node(child,name);if(found!=null)return found;}return null;
    }
    private static String attribute(Node node,String name){return node.getAttributes().getNamedItem(name).getNodeValue();}
    private static void inspect(Path file,int frameCount,int delay,RenderSettings settings,boolean prism)throws Exception {
        byte[] signature=Files.readAllBytes(file);check(signature.length>6&&new String(signature,0,6,java.nio.charset.StandardCharsets.US_ASCII).equals("GIF89a"),"animation is not GIF89a data");
        var reader=ImageIO.getImageReadersByFormatName("gif").next();
        try(var input=ImageIO.createImageInputStream(file.toFile())) {
            reader.setInput(input);check(reader.getNumImages(true)==frameCount,"GIF frame count differs");
            var frames=new ArrayList<BufferedImage>();var camera=SchematicRenderer.fitRotationCamera(boundsMesh(),settings);boolean changed=false;int departing=0;
            for(int frame=0;frame<frameCount;frame++) {
                Node control=node(reader.getImageMetadata(frame).getAsTree("javax_imageio_gif_image_1.0"),"GraphicControlExtension");
                check(attribute(control,"disposalMethod").equals("restoreToBackgroundColor"),"old transparent pixels are retained between frames");
                check(Integer.parseInt(attribute(control,"delayTime"))==delay,"GIF speed is not the requested delay");
                var image=reader.read(frame);frames.add(image);
                if(frame>0)for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
                    int before=frames.get(frame-1).getRGB(x,y),after=image.getRGB(x,y);changed|=before!=after;
                    if((before>>>24)!=0&&(after>>>24)==0)departing++;
                }
                if(prism) {
                    camera.rotationDeg=settings.rotationDeg+frame*360f/frameCount;camera.update();double[] point=new double[3];double[] expected={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
                    for(int corner=0;corner<8;corner++) {
                        camera.toView((corner&1)==0?MIN[0]:MAX[0],(corner&2)==0?MIN[1]:MAX[1],(corner&4)==0?MIN[2]:MAX[2],point);
                        double x=image.getWidth()/2.0+point[0]*camera.zoom,y=image.getHeight()/2.0-point[1]*camera.zoom;
                        expected[0]=Math.min(expected[0],x);expected[1]=Math.min(expected[1],y);expected[2]=Math.max(expected[2],x);expected[3]=Math.max(expected[3],y);
                    }
                    int[] actual={image.getWidth(),image.getHeight(),-1,-1};
                    for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)>>>24)!=0){actual[0]=Math.min(actual[0],x);actual[1]=Math.min(actual[1],y);actual[2]=Math.max(actual[2],x);actual[3]=Math.max(actual[3],y);}
                    for(int axis=0;axis<4;axis++)check(Math.abs(actual[axis]-expected[axis])<2,"export refitted or shifted camera at frame "+frame+": "+Arrays.toString(actual)+" vs "+Arrays.toString(expected));
                }
            }
            check(changed&&departing>100,"rotation does not exercise pixels vacated by the model");
            var canvas=new BufferedImage(settings.width,settings.height,BufferedImage.TYPE_INT_ARGB);var graphics=canvas.createGraphics();
            // Simulate a player for two complete loops, clearing each disposed full-frame image.
            try {
                for(int step=0;step<frameCount*2;step++) {
                    graphics.setComposite(AlphaComposite.Clear);graphics.fillRect(0,0,settings.width,settings.height);
                    graphics.setComposite(AlphaComposite.SrcOver);var image=frames.get(step%frameCount);graphics.drawImage(image,0,0,null);
                    for(int y=0;y<settings.height;y++)for(int x=0;x<settings.width;x++)check(canvas.getRGB(x,y)==image.getRGB(x,y),"composited playback retained a ghost pixel");
                }
            } finally {graphics.dispose();}
            if(prism) {
                var strip=new BufferedImage(settings.width*4,settings.height,BufferedImage.TYPE_INT_ARGB);var g=strip.createGraphics();
                for(int i=0;i<4;i++)g.drawImage(frames.get(i*frameCount/4),i*settings.width,0,null);g.dispose();ImageIO.write(strip,"png",file.resolveSibling("orbit-frames.png").toFile());
            }
        } finally {reader.dispose();}
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),schematic=Path.of(args[1]).toAbsolutePath(),output=Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(output.getParent());System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");cameraGeometry();
        var settings=new RenderSettings(17,28,160,128);settings.transparentBackground=true;
        try(var engine=new RenderEngine(root)) {
            engine.loadResources(engine.defaultPackRoot());engine.open(fixture(output.resolveSibling("orbit-fixture.litematic")));
            var result=engine.renderAnimation(output,settings,RenderSettings.DEFAULT_ANIMATION_FRAMES,RenderSettings.DEFAULT_ANIMATION_DELAY_CS);
            check(((Number)result.get("frames")).intValue()==480&&((Number)result.get("cameraRevolutions")).intValue()==1,"480-frame camera revolution was capped or lost");
            check(((Number)result.get("delayCentiseconds")).intValue()==2,"50 FPS playback metadata missing");
            inspect(output,480,2,settings,true);
            engine.open(schematic);Path mixed=output.resolveSibling("mixed-rotation.gif");engine.renderAnimation(mixed,settings,8,2);inspect(mixed,8,2,settings,false);
        }
        System.out.println("PASS: centered full-circle orbit, constant scale, all-angle framing, GIF89a, 480 frames at 50 FPS over 9.6 seconds and ghost-free transparent playback across two loops");
    }
}
