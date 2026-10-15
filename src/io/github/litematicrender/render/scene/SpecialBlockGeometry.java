package io.github.litematicrender.render.scene;

import io.github.litematicrender.nbt.*;
import io.github.litematicrender.render.Image;
import io.github.litematicrender.schematic.SchematicEntity;
import io.github.litematicrender.world.BlockState;
import io.github.litematicrender.world.BlockRegistry;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.github.litematicrender.render.scene.EntityGeometry.*;

/** Static block-entity snapshots. Textures are composed before the streaming atlas is frozen. */
final class SpecialBlockGeometry {
    interface Blocks { BlockState at(int x,int y,int z); }
    private final EntityGeometry geometry;
    private final Map<String,Image> generated;
    private final Blocks blocks;
    private final java.util.function.Predicate<BlockState> opaque;
    private final int top;
    private static final String[] DYES={"white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black"};
    private static final int[] COLORS={16383998,16351261,13061821,3847130,16701501,8439583,15961002,4673362,10329495,1481884,8991416,3949738,8606770,6192150,11546150,1908001};
    private static final Map<String,String> LEGACY_PATTERNS=new HashMap<>();
    static {
        String[] pairs={"b","base","bs","stripe_bottom","ts","stripe_top","ls","stripe_left","rs","stripe_right","cs","stripe_center","ms","stripe_middle","drs","stripe_downright","dls","stripe_downleft","ss","small_stripes","cr","cross","sc","straight_cross","bl","square_bottom_left","br","square_bottom_right","tl","square_top_left","tr","square_top_right","bt","triangle_bottom","tt","triangle_top","bts","triangles_bottom","tts","triangles_top","ld","diagonal_left","rd","diagonal_up_right","lud","diagonal_up_left","rud","diagonal_right","mc","circle","mr","rhombus","vh","half_vertical","hh","half_horizontal","vhr","half_vertical_right","hhb","half_horizontal_bottom","bo","border","cbo","curly_border","gra","gradient","gru","gradient_up","bri","bricks","glb","globe","cre","creeper","sku","skull","flo","flower","moj","mojang","pig","piglin"};
        for(int i=0;i<pairs.length;i+=2)LEGACY_PATTERNS.put(pairs[i],pairs[i+1]);
    }
    SpecialBlockGeometry(EntityGeometry geometry,Map<String,Image> generated,Blocks blocks,java.util.function.Predicate<BlockState> opaque,int top){this.geometry=geometry;this.generated=generated;this.blocks=blocks;this.opaque=opaque;this.top=top;}
    static boolean supports(String id){return banner(id)||id.equals("minecraft:lectern")||id.equals("minecraft:conduit")||id.equals("minecraft:beacon")||BlockRegistry.isCopperGolemStatue(id);}
    static boolean banner(String id){return id.startsWith("minecraft:")&&(id.endsWith("_banner")||id.endsWith("_wall_banner"));}
    static int dye(String name){for(int i=0;i<DYES.length;i++)if(DYES[i].equals(name))return COLORS[i];return COLORS[0];}
    private static int dye(NbtElement value){if(value instanceof NbtScalars.NbtString s)return dye(s.value);return COLORS[Math.max(0,Math.min(15,(int)SchematicEntity.number(value,0)))];}
    void emit(SceneMesh mesh,BlockState state,NbtCompound tag,int x,int y,int z,float[] light){
        if(banner(state.blockId))banner(mesh,state,tag,x,y,z,light);
        else if(state.blockId.equals("minecraft:lectern")&&"true".equals(state.getValue("has_book")))book(mesh,state,x,y,z,light);
        else if(state.blockId.equals("minecraft:conduit"))conduit(mesh,x,y,z,light);
        else if(state.blockId.equals("minecraft:beacon"))beacon(mesh,x,y,z);
        else if(BlockRegistry.isCopperGolemStatue(state.blockId))CopperGolemStatueGeometry.emit(geometry,mesh,state,x,y,z,texture(CopperGolemStatueGeometry.texture(state.blockId)),light);
    }
    private String texture(String id){Image image=geometry.image(id);if(image!=null)generated.putIfAbsent(id,image);return id;}
    private void banner(SceneMesh mesh,BlockState state,NbtCompound tag,int x,int y,int z,float[] light){
        boolean wall=state.blockId.endsWith("_wall_banner");
        String color=state.blockId.substring(10).replace("_wall_banner","").replace("_banner","");
        NbtList patterns=tag==null?null:tag.getList("patterns").orElseGet(()->tag.getList("Patterns").orElseGet(()->tag.getCompound("components").orElse(new NbtCompound()).getList("minecraft:banner_patterns").orElse(null)));
        var layers=new ArrayList<String>();var tints=new ArrayList<Integer>();layers.add("base");tints.add(dye(color));
        if(patterns!=null)for(var layer:patterns.getCompounds()){
            if(layers.size()>16)break;
            String pattern=layer.getStringOrDefault("pattern",layer.getStringOrDefault("Pattern",""));
            pattern=LEGACY_PATTERNS.getOrDefault(pattern,pattern);if(!pattern.contains(":"))pattern="minecraft:"+pattern;
            if(!pattern.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))continue;
            layers.add(pattern);tints.add(dye(layer.get("color").orElseGet(()->layer.get("Color").orElse(null))));
        }
        String sprite="litematicrender:banner/"+UUID.nameUUIDFromBytes((layers.toString()+tints).getBytes(StandardCharsets.UTF_8));
        generated.computeIfAbsent(sprite,key->{
            Image base=geometry.image("minecraft:entity/banner/banner_base");if(base==null)return new Image(64,64);
            Image result=new Image(base.width,base.height);System.arraycopy(base.pixels(),0,result.pixels(),0,base.pixels().length);
            for(int i=0;i<layers.size();i++){
                String[] id=(layers.get(i).contains(":")?layers.get(i):"minecraft:"+layers.get(i)).split(":",2);
                Image overlay=geometry.image(id[0]+":entity/banner/"+id[1]);if(overlay!=null)over(result,overlay,tints.get(i));
            }
            return result;
        });
        double angle=wall?yaw(state.getValue("facing")):number(state.getValue("rotation"))*22.5;
        Mat world=Mat.identity().translate(x+.5,y,z+.5).rotateY(Math.toRadians(-angle)).scale(2.0/3,-2.0/3,-2.0/3);
        Part stand=new Part();if(!wall)stand.box(44,0,-1,-42,-1,2,42,2);
        stand.box(0,42,-10,wall?-20.5:-44,wall?9.5:-1,20,2,2);
        geometry.parts(mesh,stand,world,texture("minecraft:entity/banner/banner_base"),64,64,light);
        Part cloth=new Part();cloth.child("flag",0,wall?-20.5:-44,wall?10.5:0,-.0025*Math.PI,0,0).box(0,0,-10,0,-2,20,40,1);
        geometry.parts(mesh,cloth,world,sprite,64,64,light);
    }
    static void over(Image base,Image overlay,int tint){
        for(int y=0;y<base.height;y++)for(int x=0;x<base.width;x++){
            int top=overlay.sampleNearest((x+.5f)/base.width,(y+.5f)/base.height),a=Image.alpha(top);if(a==0)continue;
            int under=base.pixel(x,y),ua=Image.alpha(under),out=a+ua*(255-a)/255;int[] rgb=new int[3];
            for(int i=0;i<3;i++){int shift=16-i*8,c=(top>>shift&255)*(tint>>shift&255)/255;rgb[i]=(c*a+(under>>shift&255)*ua*(255-a)/255)/Math.max(1,out);}
            base.setPixel(x,y,Image.argb(out,rgb[0],rgb[1],rgb[2]));
        }
    }
    private void book(SceneMesh mesh,BlockState state,int x,int y,int z,float[] light){
        double open=1.5,offset=Math.sin(open);Part root=new Part();
        root.child("left_lid",0,0,-1,0,Math.PI+open,0).box(0,0,-6,-5,-.005,6,10,.005);
        root.child("right_lid",0,0,1,0,-open,0).box(16,0,0,-5,-.005,6,10,.005);
        root.child("seam",0,0,0,0,Math.PI/2,0).box(12,0,-1,-5,0,2,10,.005);
        root.child("left_pages",offset,0,0,0,open,0).box(0,10,0,-4,-.99,5,8,1);
        root.child("right_pages",offset,0,0,0,-open,0).box(12,10,0,-4,-.01,5,8,1);
        root.child("flip_page1",offset,0,0,0,open*.8,0).box(24,10,0,-4,0,5,8,.005);
        root.child("flip_page2",offset,0,0,0,-open*.8,0).box(24,10,0,-4,0,5,8,.005);
        Mat world=Mat.identity().translate(x+.5,y+1.0625,z+.5).rotateY(Math.toRadians(-yaw(state.getValue("facing"))-90)).rotateZ(Math.toRadians(67.5)).translate(0,-.125,0);
        geometry.parts(mesh,root,world,texture("minecraft:entity/enchantment/enchanting_table_book"),64,32,light);
    }
    private void conduit(SceneMesh mesh,int x,int y,int z,float[] light){
        int frame=0;boolean water=true;
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
            BlockState s=blocks.at(x+dx,y+dy,z+dz);if(s==null||!(s.blockId.equals("minecraft:water")||"true".equals(s.getValue("waterlogged"))))water=false;
        }
        if(water)for(int dx=-2;dx<=2;dx++)for(int dy=-2;dy<=2;dy++)for(int dz=-2;dz<=2;dz++){
            int ax=Math.abs(dx),ay=Math.abs(dy),az=Math.abs(dz);
            if((ax>1||ay>1||az>1)&&(dx==0&&(ay==2||az==2)||dy==0&&(ax==2||az==2)||dz==0&&(ax==2||ay==2))){BlockState s=blocks.at(x+dx,y+dy,z+dz);if(s!=null&&Set.of("minecraft:prismarine","minecraft:prismarine_bricks","minecraft:dark_prismarine","minecraft:sea_lantern").contains(s.blockId))frame++;}
        }
        Mat center=Mat.identity().translate(x+.5,y+.5,z+.5);Part root=new Part();
        if(frame<16){root.box(0,0,-3,-3,-3,6,6,6);geometry.parts(mesh,root,center,texture("minecraft:entity/conduit/base"),32,16,light);return;}
        root.box(0,0,-4,-4,-4,8,8,8);geometry.parts(mesh,root,center.translate(0,-.05,0),texture("minecraft:entity/conduit/cage"),32,16,light);
        Part wind=new Part();wind.box(0,0,-8,-8,-8,16,16,16);String windTexture="litematicrender:conduit/wind";
        generated.computeIfAbsent(windTexture,key->{Image source=geometry.image("minecraft:entity/conduit/wind");if(source==null)return new Image(64,32);int frameHeight=Math.min(source.height,source.width/2);Image frameImage=new Image(source.width,frameHeight);System.arraycopy(source.pixels(),0,frameImage.pixels(),0,frameImage.pixels().length);return frameImage;});
        geometry.parts(mesh,wind,center,windTexture,64,32,light);geometry.parts(mesh,wind,center.scale(.875,.875,.875).rotateX(Math.PI).rotateZ(Math.PI),windTexture,64,32,light);
        Part eye=new Part();eye.box(0,0,-4,-4,0,8,8,0,.01,false);geometry.parts(mesh,eye,center.translate(0,-.05,-.02).scale(2.0/3,2.0/3,2.0/3),texture("minecraft:entity/conduit/"+(frame>=42?"open_eye":"closed_eye")),16,16,new float[]{1,1,1});
    }
    static double yaw(String facing){return switch(String.valueOf(facing)){case "west"->90;case "north"->180;case "east"->270;default->0;};}
    private void beacon(SceneMesh mesh,int x,int y,int z){
        Set<String> base=Set.of("minecraft:iron_block","minecraft:gold_block","minecraft:diamond_block","minecraft:emerald_block","minecraft:netherite_block");
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){BlockState s=blocks.at(x+dx,y-1,z+dz);if(s==null||!base.contains(s.blockId))return;}
        var starts=new ArrayList<Integer>();var colors=new ArrayList<Integer>();starts.add(y);colors.add(0xffffff);boolean stained=false;
        for(int yy=y+1;yy<top;yy++){
            BlockState s=blocks.at(x,yy,z);if(s==null||s.isAir())continue;
            String id=s.blockId;boolean glass=id.endsWith("_stained_glass")||id.endsWith("_stained_glass_pane");
            if(glass){int color=dye(id.substring(10).replace("_stained_glass_pane","").replace("_stained_glass","")),last=colors.get(colors.size()-1);
                if(!stained||color!=last){if(stained)color=average(last,color);starts.add(yy);colors.add(color);}stained=true;
            }else if(!id.equals("minecraft:bedrock")&&opaque.test(s))return;
        }
        for(int i=0;i<starts.size();i++){
            int start=starts.get(i),end=i+1<starts.size()?starts.get(i+1):start+2048,color=colors.get(i);
            for(int yy=start;yy<end;yy+=16){int endY=Math.min(end,yy+16);beam(mesh,x,z,yy,endY,color,false);beam(mesh,x,z,yy,endY,color,true);}
        }
    }
    private static int average(int a,int b){return (((a>>16&255)+(b>>16&255))/2)<<16|(((a>>8&255)+(b>>8&255))/2)<<8|((a&255)+(b&255))/2;}
    private void beam(SceneMesh mesh,int x,int z,int bottom,int upper,int color,boolean glow){
        String sprite="litematicrender:beacon/"+Integer.toHexString(color)+(glow?"/glow":"/core");
        Image texture=generated.computeIfAbsent(sprite,key->{
            Image source=geometry.image("minecraft:entity/beacon/beacon_beam");if(source==null){source=new Image(1,1);source.setPixel(0,0,-1);}
            int width=source.width,height=source.height*16;Image tile=new Image(width,height);
            for(int yy=0;yy<height;yy++)for(int xx=0;xx<width;xx++){int p=source.pixel(xx,yy%source.height);tile.setPixel(xx,yy,Image.argb(glow?32:Image.alpha(p),Image.red(p)*(color>>16&255)/255,Image.green(p)*(color>>8&255)/255,Image.blue(p)*(color&255)/255));}return tile;
        });
        float radius=glow?.25f:(float)(.2/Math.sqrt(2)),cx=x+.5f,cz=z+.5f;
        float[][] points={{cx-radius,cz-radius},{cx+radius,cz-radius},{cx+radius,cz+radius},{cx-radius,cz+radius}};
        SceneMesh.Layer layer=glow?SceneMesh.Layer.TRANSLUCENT:SceneMesh.Layer.SOLID;
        for(int side=0;side<4;side++){float[] a=points[side],b=points[(side+1)%4];var q=new SceneMesh.MeshQuad(new float[]{a[0],a[0],b[0],b[0]},new float[]{upper,bottom,bottom,upper},new float[]{a[1],a[1],b[1],b[1]},0,0,0,(upper-bottom)/16f,1,(upper-bottom)/16f,1,0,sprite,-1,texture,1,1,1,1,null,layer,true);(glow?mesh.translucent:mesh.solid).add(q);}
    }
    private static int number(String value){try{return Integer.parseInt(value);}catch(Exception e){return 0;}}
}
