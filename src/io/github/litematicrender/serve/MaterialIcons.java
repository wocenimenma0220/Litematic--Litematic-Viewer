package io.github.litematicrender.serve;

import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.util.MiniJson;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Small representative faces/cubes from vanilla resources; independent of scene generation. */
final class MaterialIcons {
    private final ResourceManager resources;
    private final Map<String,String> cache=new HashMap<>();
    private final Map<String,Map<String,String>> modelTextures=new HashMap<>();
    MaterialIcons(ResourceManager resources){this.resources=resources;}
    String icon(String id){return cache.computeIfAbsent(id,this::create);}
    private byte[] read(String id,String directory,String suffix) {
        if(id==null)return null;id=id.contains(":")?id:"minecraft:"+id;
        if(id.contains("..")||!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))return null;
        String[] parts=id.split(":",2);
        // The original art stays recognizable even when a schematic resource pack replaces models.
        return parts[0].equals("minecraft")?resources.vanillaPack().read(parts[0],directory+parts[1]+suffix):resources.getResource(parts[0],directory+parts[1]+suffix).orElse(null);
    }
    private Map<String,Object> json(String id,String directory) {
        try{byte[] bytes=read(id,directory,".json");return bytes==null?Map.of():MiniJson.parseObject(new String(bytes,StandardCharsets.UTF_8));}catch(Exception ignored){return Map.of();}
    }
    private BufferedImage texture(String id) {
        try{byte[] bytes=read(id,"textures/",".png");if(bytes==null)return null;var image=ImageIO.read(new ByteArrayInputStream(bytes));if(image==null)return null;return image.getSubimage(0,0,image.getWidth(),Math.min(image.getWidth(),image.getHeight()));}catch(Exception ignored){return null;}
    }
    private BufferedImage crop(String id,int x,int y,int w,int h,int atlasWidth) {
        try{byte[] bytes=read(id,"textures/",".png");if(bytes==null)return null;var image=ImageIO.read(new ByteArrayInputStream(bytes));double scale=image.getWidth()/(double)atlasWidth;return image.getSubimage((int)(x*scale),(int)(y*scale),Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));}catch(Exception ignored){return null;}
    }
    private Map<String,String> textures(String model,Set<String> visited) {
        if(modelTextures.containsKey(model))return modelTextures.get(model);
        if(visited.size()>32||!visited.add(model))return Map.of();
        var data=json(model,"models/");var result=new LinkedHashMap<String,String>();
        if(data.get("parent") instanceof String parent)result.putAll(textures(parent,visited));
        if(data.get("textures") instanceof Map<?,?> slots)for(var entry:slots.entrySet()) {
            Object value=entry.getValue();if(value instanceof Map<?,?> ref)value=ref.get("texture");
            if(value instanceof String path)result.put(String.valueOf(entry.getKey()),path);
        }
        modelTextures.put(model,result);return result;
    }
    private BufferedImage slot(Map<String,String> slots,String... keys) {
        for(String key:keys){String path=slots.get(key);var seen=new HashSet<String>();while(path!=null&&path.startsWith("#")&&seen.add(path))path=slots.get(path.substring(1));if(path!=null&&!path.startsWith("#")){var image=texture(path);if(image!=null)return image;}}
        return null;
    }
    private void models(Object value,Set<String> output,int depth) {
        if(depth>20||!(value instanceof Map<?,?> data))return;
        if(data.get("model") instanceof String path)output.add(path);
        for(String key:List.of("fallback","on_false","on_true","model"))models(data.get(key),output,depth+1);
        for(String key:List.of("models","cases","entries","multipart"))if(data.get(key) instanceof List<?> list)for(Object entry:list)models(entry,output,depth+1);
        if(data.get("base") instanceof String path)output.add(path);
        if(data.get("variants") instanceof Map<?,?> variants)for(Object entry:variants.values()){if(entry instanceof List<?> list)for(Object variant:list)models(variant,output,depth+1);else models(entry,output,depth+1);}
        models(data.get("apply"),output,depth+1);
    }
    private BufferedImage special(String path) {
        String skull=switch(path){case "skeleton_skull"->"skeleton/skeleton";case "wither_skeleton_skull"->"skeleton/wither_skeleton";case "zombie_head"->"zombie/zombie";case "creeper_head"->"creeper/creeper";case "piglin_head"->"piglin/piglin";case "player_head"->"player/wide/steve";default->null;};
        if(path.equals("piglin_head")) {
            var head=crop("minecraft:entity/"+skull,8,8,10,8,64);if(head==null)return null;var face=new BufferedImage(10,8,BufferedImage.TYPE_INT_ARGB);var g=face.createGraphics();draw(g,head,0,0,10,8);draw(g,crop("minecraft:entity/"+skull,32,2,4,4,64),3,4,4,4);draw(g,crop("minecraft:entity/"+skull,3,5,1,2,64),7,6,1,2);draw(g,crop("minecraft:entity/"+skull,3,1,1,2,64),2,6,1,2);g.dispose();return face;
        }
        if(skull!=null)return crop("minecraft:entity/"+skull,8,8,8,8,64);
        if(path.equals("dragon_head"))return crop("minecraft:entity/enderdragon/dragon",128,46,16,16,256);
        if(Set.of("chest","trapped_chest","ender_chest").contains(path)) {
            String atlas="minecraft:entity/chest/"+(path.equals("ender_chest")?"ender":path.equals("trapped_chest")?"trapped":"normal");
            var lid=crop(atlas,14,14,14,5,64);var base=crop(atlas,14,33,14,10,64);if(lid==null||base==null)return null;
            var face=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);var g=face.createGraphics();g.drawImage(lid,1,0,14,5,null);g.drawImage(base,1,5,14,10,null);g.setColor(new Color(205,205,205));g.fillRect(7,4,2,4);g.dispose();return face;
        }
        if(path.equals("shulker_box")||path.endsWith("_shulker_box")) {
            String atlas="minecraft:entity/shulker/"+(path.equals("shulker_box")?"shulker":"shulker_"+path.replace("_shulker_box",""));
            return crop(atlas,16,0,16,16,64);
        }
        if(path.equals("shield"))return crop("minecraft:entity/shield/shield_base_nopattern",1,1,12,22,64);
        if(path.equals("conduit"))return crop("minecraft:entity/conduit/base",6,6,6,6,32);
        if(path.equals("decorated_pot"))return texture("minecraft:entity/decorated_pot/decorated_pot_side");
        if(path.endsWith("_bed")) {
            String color=path.substring(0,path.length()-4);var head=texture("minecraft:block/"+color+"_bed_head_up");var foot=texture("minecraft:block/"+color+"_bed_foot_up");
            if(head!=null&&foot!=null){var image=new BufferedImage(16,32,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();draw(g,head,0,0,16,16);draw(g,foot,0,16,16,16);g.dispose();return image;}
        }
        if(path.endsWith("_banner")) {
            String color=path.substring(0,path.length()-7);String[] colors={"white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black"};int[] rgb={16383998,16351261,13061821,3847130,16701501,8439583,15961002,4673362,10329495,1481884,8991416,3949738,8606770,6192150,11546150,1908001};
            var cloth=crop("minecraft:entity/banner/base",1,1,20,40,64);int index=Arrays.asList(colors).indexOf(color);
            if(cloth!=null&&index>=0){int dye=rgb[index];var colored=new BufferedImage(cloth.getWidth(),cloth.getHeight(),BufferedImage.TYPE_INT_ARGB);for(int y=0;y<cloth.getHeight();y++)for(int x=0;x<cloth.getWidth();x++){int pixel=cloth.getRGB(x,y);colored.setRGB(x,y,(pixel&0xff000000)|(((pixel>>16)&255)*((dye>>16)&255)/255<<16)|(((pixel>>8)&255)*((dye>>8)&255)/255<<8)|(pixel&255)*(dye&255)/255);}return colored;}
        }
        return null;
    }
    private static void draw(Graphics2D g,BufferedImage image,int x,int y,int w,int h){if(image!=null)g.drawImage(image,x,y,w,h,null);}
    private static BufferedImage flat(BufferedImage image) {
        var result=new BufferedImage(40,40,BufferedImage.TYPE_INT_ARGB);var g=result.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        double scale=Math.min(34d/image.getWidth(),34d/image.getHeight());int w=(int)Math.round(image.getWidth()*scale),h=(int)Math.round(image.getHeight()*scale);draw(g,image,(40-w)/2,(40-h)/2,w,h);g.dispose();return result;
    }
    private static void face(Graphics2D g,BufferedImage image,int x,int y,int ax,int ay,int bx,int by,int shade) {
        var polygon=new Polygon(new int[]{x,x+ax,x+ax+bx,x+bx},new int[]{y,y+ay,y+ay+by,y+by},4);Shape clip=g.getClip();g.clip(polygon);
        g.drawImage(image,new AffineTransform(ax/(double)image.getWidth(),ay/(double)image.getWidth(),bx/(double)image.getHeight(),by/(double)image.getHeight(),x,y),null);
        if(shade>0){g.setColor(new Color(0,0,0,shade));g.fillPolygon(polygon);}g.setClip(clip);
    }
    private static BufferedImage cube(BufferedImage top,BufferedImage side,BufferedImage front) {
        var result=new BufferedImage(40,40,BufferedImage.TYPE_INT_ARGB);var g=result.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        face(g,top,20,2,18,9,-18,9,0);face(g,front,2,11,18,9,0,18,38);face(g,side,20,20,18,-9,0,18,18);g.dispose();return result;
    }
    private String create(String id) {
        try {
            String[] parts=(id.contains(":")?id:"minecraft:"+id).split(":",2);String path=parts[1],ns=parts[0]+":";
            BufferedImage image=parts[0].equals("minecraft")?special(path):null;
            if(image!=null)image=flat(image);
            else {
                var direct=texture(ns+"item/"+path);
                if(direct!=null)image=flat(direct);
                var candidates=new LinkedHashSet<String>();models(json(id,"items/").get("model"),candidates,0);candidates.add(ns+"item/"+path);candidates.add(ns+"block/"+path);models(json(id,"blockstates/"),candidates,0);
                for(String model:candidates) {
                    if(image!=null)break;var slots=textures(model,new HashSet<>());
                    var front=slot(slots,"layer0","all","front","north","side","up","top","end","particle");if(front==null)continue;
                    var top=slot(slots,"up","top","end","all");var side=slot(slots,"east","side","all");
                    image=model.contains(":block/")&&top!=null&&side!=null?cube(top,side,front):flat(front);
                }
                if(image==null){var block=texture(ns+"block/"+path);if(block!=null)image=flat(block);}
            }
            if(image==null)return "";var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return "data:image/png;base64,"+Base64.getEncoder().encodeToString(output.toByteArray());
        }catch(Exception ignored){return "";}
    }
}
