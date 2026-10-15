package io.github.litematicrender.serve;
import io.github.litematicrender.resource.ResourceManager;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;

public final class MaterialIconPreview {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();
        String[] ids={"stone","frosted_ice","ice","water_bucket","oak_log","grass_block","skeleton_skull","wither_skeleton_skull","zombie_head","creeper_head","dragon_head","piglin_head","chest","trapped_chest","ender_chest","shulker_box","red_banner","blue_banner","white_bed","red_bed","shield","conduit","decorated_pot","furnace","cactus","observer","stonecutter","scaffolding","potion","diamond_sword"};
        try(var resources=new ResourceManager(root.resolve("assets/vanilla"))){var icons=new MaterialIcons(resources);var sheet=new BufferedImage(1080,620,BufferedImage.TYPE_INT_RGB);var g=sheet.createGraphics();g.setColor(new Color(32,36,44));g.fillRect(0,0,1080,620);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);g.setFont(new Font("Consolas",Font.PLAIN,12));
            for(int i=0;i<ids.length;i++){String data=icons.icon("minecraft:"+ids[i]);if(data.isEmpty())throw new AssertionError("Missing icon: "+ids[i]);var image=ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(data.substring(data.indexOf(',')+1))));boolean opaque=false;for(int y=0;y<40;y++)for(int x=0;x<40;x++)opaque|=(image.getRGB(x,y)>>>24)>0;if(!opaque)throw new AssertionError("Empty icon: "+ids[i]);int x=i%6*180,y=i/6*124;g.drawImage(image,x+50,y+4,80,80,null);g.setColor(new Color(230,233,239));g.drawString(ids[i],x+5,y+106);}
            if(icons.icon("minecraft:red_banner").equals(icons.icon("minecraft:blue_banner")))throw new AssertionError("Banner colors collapsed");
            g.dispose();ImageIO.write(sheet,"png",root.resolve("tools/verify/material-output/icons-preview.png").toFile());
        }
        System.out.println("PASS: representative vanilla icons decoded, nonempty, contact sheet saved");
    }
}
