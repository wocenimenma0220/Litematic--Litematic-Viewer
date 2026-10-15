package io.github.litematicrender.render.scene;

import io.github.litematicrender.model.*;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.render.*;
import io.github.litematicrender.schematic.SchematicEntity;
import io.github.litematicrender.world.*;
import io.github.litematicrender.util.MiniJson;
import java.util.*;

/** Static snapshots of the requested vanilla entities, using the bundled model dimensions and UV nets. */
public final class EntityGeometry {
    private static final Set<String> CARTS=Set.of("minecart","chest_minecart","furnace_minecart","hopper_minecart","tnt_minecart","command_block_minecart","spawner_minecart");
    public static final List<String> WOODS=List.of("oak","spruce","birch","jungle","acacia","dark_oak","mangrove","cherry","pale_oak","bamboo","poplar");
    private final ModelBaker baker;
    private final BlockRegistry registry;
    private final Map<String,Image> generated;
    private final Map<String,Image> images=new HashMap<>();
    private boolean lightingEnabled=true;
    public EntityGeometry lightingEnabled(boolean enabled){this.lightingEnabled=enabled;return this;}
    private SceneMesh.Layer layer=SceneMesh.Layer.CUTOUT;
    private static final Image MASK_IMAGE=maskImage();
    private static Image maskImage(){var image=new Image(1,1);image.setPixel(0,0,-1);return image;}
    public EntityGeometry(ModelBaker baker,BlockRegistry registry,Map<String,Image> generated){this.baker=baker;this.registry=registry;this.generated=generated;}
    public static String kind(SchematicEntity entity){return entity.id().startsWith("minecraft:")?entity.id().substring(10):"";}
    public static String boatWood(SchematicEntity entity) {
        String id=kind(entity),wood;
        if(id.equals("boat")||id.equals("chest_boat"))wood=entity.data().getStringOrDefault("Type",entity.data().getStringOrDefault("type","oak"));
        else {wood=id.replace("_chest_boat","").replace("_chest_raft","").replace("_boat","").replace("_raft","");}
        return WOODS.contains(wood)?wood:"";
    }
    public static boolean boat(SchematicEntity entity){String id=kind(entity);return (id.endsWith("boat")||id.endsWith("raft"))&&!boatWood(entity).isEmpty();}
    public static boolean supported(SchematicEntity entity){String id=kind(entity);return CARTS.contains(id)||Set.of("snow_golem","iron_golem","piglin").contains(id)||boat(entity)||frame(entity);}
    public static boolean frame(SchematicEntity entity){return Set.of("item_frame","glow_item_frame").contains(kind(entity));}
    public static boolean minecart(SchematicEntity entity){return CARTS.contains(kind(entity));}
    public static boolean baby(SchematicEntity entity){return entity.data().getBooleanOrDefault("IsBaby",entity.data().getBooleanOrDefault("is_baby",false));}
    public static int capacity(SchematicEntity entity){String id=kind(entity);return id.equals("chest_minecart")||boat(entity)&&id.contains("chest")?27:id.equals("hopper_minecart")?5:frame(entity)?1:0;}
    public static double attribute(NbtCompound tag,String id,double fallback) {
        var list=tag.getList("attributes").orElseGet(()->tag.getList("Attributes").orElse(null));
        if(list!=null)for(var entry:list.getCompounds()){
            String name=entry.getStringOrDefault("id",entry.getStringOrDefault("Name",""));
            if(name.equals("minecraft:"+id)||name.equals("minecraft:generic."+id)||name.equals("generic."+id))return SchematicEntity.number(entry,"base",SchematicEntity.number(entry,"Base",fallback));
        }
        return fallback;
    }
    public void emit(SceneMesh mesh,SchematicEntity entity,double x,double y,double z,float[] light,boolean submerged) {
        emit(mesh,entity,x,y,z,light,submerged,entity.yaw(),entity.pitch());
    }
    public void emit(SceneMesh mesh,SchematicEntity entity,double x,double y,double z,float[] light,boolean submerged,float cartYaw,float cartPitch) {
        String id=kind(entity);Mat world=Mat.identity().translate(x,y,z);
        if(CARTS.contains(id)){cart(mesh,entity,world,light,cartYaw,cartPitch);return;}
        if(boat(entity)){boat(mesh,entity,world,light,submerged);return;}
        if(frame(entity)){frame(mesh,entity,world,light);return;}
        double scale=Math.max(.0625,Math.min(16,attribute(entity.data(),"scale",1)));
        world=world.scale(scale,scale,scale).rotateY(Math.toRadians(180-entity.yaw())).scale(-1,-1,1).translate(0,-1.501,0);
        if(id.equals("snow_golem"))snow(mesh,entity,world,light);
        else if(id.equals("iron_golem"))iron(mesh,entity,world,light);
        else if(id.equals("piglin"))piglin(mesh,entity,world,light);
    }
    private void boat(SceneMesh mesh,SchematicEntity entity,Mat world,float[] light,boolean submerged) {
        String wood=boatWood(entity);boolean raft=wood.equals("bamboo"),chest=kind(entity).contains("chest");
        String texture="minecraft:entity/"+(chest?"chest_boat/":"boat/")+wood;
        world=world.translate(0,.375,0).rotateY(Math.toRadians(180-entity.yaw())).scale(-1,-1,1).rotateY(Math.PI/2);
        var root=new Part();
        if(raft)root.child("bottom",0,-2.1,1,Math.PI/2,0,0).box(0,0,-14,-11,-4,28,20,4).box(0,0,-14,-9,-8,28,16,4);
        else {
            root.child("bottom",0,3,1,Math.PI/2,0,0).box(0,0,-14,-9,-3,28,16,3);
            root.child("back",-15,4,4,0,Math.PI*1.5,0).box(0,19,-13,-7,-1,18,6,2);
            root.child("front",15,4,0,0,Math.PI/2,0).box(0,27,-8,-7,-1,16,6,2);
            root.child("right",0,4,-9,0,Math.PI,0).box(0,35,-14,-7,-1,28,6,2);
            root.child("left",0,4,9,0,0,0).box(0,43,-14,-7,-1,28,6,2);
        }
        double paddleX=-Math.PI*5/24,paddleY=Math.sin(1)*Math.PI/4;
        root.child("left_paddle",3,raft?-4:-5,9,paddleX,paddleY,Math.PI/16).box(raft?0:62,raft?24:0,-1,0,-5,2,2,18).box(raft?0:62,raft?24:0,-1.001,-3,8,1,6,7);
        root.child("right_paddle",3,raft?-4:-5,-9,paddleX,Math.PI-paddleY,Math.PI/16).box(raft?40:62,raft?24:20,-1,0,-5,2,2,18).box(raft?40:62,raft?24:20,.001,-3,8,1,6,7);
        if(chest){double y=raft?-10.1:-5;
            root.child("chest_bottom",-2,y,-6,0,-Math.PI/2,0).box(0,76,0,0,0,12,8,12);
            root.child("chest_lid",-2,y-4,-6,0,-Math.PI/2,0).box(0,59,0,0,0,12,4,12);
            root.child("chest_lock",-1,y-1,-1,0,-Math.PI/2,0).box(0,59,0,0,0,2,4,1);
        }
        parts(mesh,root,world,texture,128,chest?128:64,light);
        if(!raft&&!submerged){
            String maskTexture="litematicrender:entity/boat_water_mask";generated.put(maskTexture,MASK_IMAGE);
            var mask=new Part();mask.child("water_patch",0,-3,1,Math.PI/2,0,0).box(0,0,-14,-9,-3,28,16,3);
            layer=SceneMesh.Layer.WATER_MASK;parts(mesh,mask,world,maskTexture,128,64,light);layer=SceneMesh.Layer.CUTOUT;
        }
    }
    private void cart(SceneMesh mesh,SchematicEntity entity,Mat world,float[] light,float yaw,float pitch) {
        world=world.translate(0,.375,0).rotateY(Math.toRadians(180-yaw)).rotateZ(Math.toRadians(-pitch));
        var root=new Part();
        root.child("bottom",0,4,0,Math.PI/2,0,0).box(0,10,-10,-8,-1,20,16,2);
        root.child("front",-9,4,0,0,Math.PI*1.5,0).box(0,0,-8,-9,-1,16,8,2);
        root.child("back",9,4,0,0,Math.PI/2,0).box(0,0,-8,-9,-1,16,8,2);
        root.child("left",0,4,-7,0,Math.PI,0).box(0,0,-8,-9,-1,16,8,2);
        root.child("right",0,4,7,0,0,0).box(0,0,-8,-9,-1,16,8,2);
        parts(mesh,root,world.scale(-1,-1,1),"minecraft:entity/minecart/minecart",64,32,light);
        String defaultBlock=switch(kind(entity)){case "chest_minecart"->"chest";case "furnace_minecart"->"furnace";case "hopper_minecart"->"hopper";case "tnt_minecart"->"tnt";case "command_block_minecart"->"command_block";case "spawner_minecart"->"spawner";default->"air";};
        var display=entity.data().getCompound("DisplayState").orElse(null);
        String blockId=display==null?"minecraft:"+defaultBlock:display.getStringOrDefault("Name",display.getStringOrDefault("name","minecraft:air"));
        var state=registry.getOrMissing(blockId).defaultState;
        if(state.isAir())return;
        if(display!=null){var properties=display.getCompound("Properties").orElseGet(()->display.getCompound("properties").orElse(null));if(properties!=null)for(String key:properties.getKeys())state=state.tryWith(key,properties.getStringOrDefault(key,""));}
        else if(defaultBlock.equals("furnace"))state=state.tryWith("lit",SchematicEntity.number(entity.data(),"Fuel",0)>0?"true":"false");
        int offset=(int)SchematicEntity.number(entity.data(),"DisplayOffset",defaultBlock.equals("hopper")?1:6);
        offset=Math.max(-64,Math.min(256,offset));
        var transform=world.scale(.75,.75,.75).translate(-.5,(offset-8)/16.0,.5).rotateY(Math.PI/2);
        block(mesh,state,transform,light);
    }
    private void snow(SceneMesh mesh,SchematicEntity entity,Mat world,float[] light) {
        var root=new Part();
        var head=root.child("head",0,4,0,Math.toRadians(entity.pitch()),0,0).box(0,0,-4,-8,-4,8,8,8,-.5,false);
        root.child("left_arm",5,6,1,0,0,1).box(32,0,-1,0,-1,12,2,2,-.5,false);
        root.child("right_arm",-5,6,-1,0,Math.PI,-1).box(32,0,-1,0,-1,12,2,2,-.5,false);
        root.child("upper_body",0,13,0,0,0,0).box(0,16,-5,-10,-5,10,10,10,-.5,false);
        root.child("lower_body",0,24,0,0,0,0).box(0,36,-6,-12,-6,12,12,12,-.5,false);
        parts(mesh,root,world,"minecraft:entity/snow_golem/snow_golem",64,64,light);
        if(entity.data().getBooleanOrDefault("Pumpkin",true)){
            Mat hat=world.mul(head.transform()).translate(0,-.34375,0).rotateY(Math.PI).scale(.625,-.625,-.625).translate(-.5,-.5,-.5);
            block(mesh,registry.getOrMissing("minecraft:carved_pumpkin").defaultState,hat,light);
        }
    }
    private void iron(SceneMesh mesh,SchematicEntity entity,Mat world,float[] light) {
        var root=new Part();
        root.child("head",0,-7,-2,Math.toRadians(entity.pitch()),0,0).box(0,0,-4,-12,-5.5,8,10,8).box(24,0,-1,-5,-7.5,2,4,2);
        root.child("body",0,-7,0,0,0,0).box(0,40,-9,-2,-6,18,12,11).box(0,70,-4.5,10,-3,9,5,6,.5,false);
        root.child("right_arm",0,-7,0,0,0,0).box(60,21,-13,-2.5,-3,4,30,6);
        root.child("left_arm",0,-7,0,0,0,0).box(60,58,9,-2.5,-3,4,30,6);
        root.child("right_leg",-4,11,0,0,0,0).box(37,0,-3.5,-3,-3,6,16,5);
        root.child("left_leg",5,11,0,0,0,0).box(60,0,-3.5,-3,-3,6,16,5,0,true);
        double max=Math.max(1,attribute(entity.data(),"max_health",100)),fraction=SchematicEntity.number(entity.data(),"Health",max)/max;
        String crack=fraction<.25?"high":fraction<.5?"medium":fraction<.75?"low":"";
        String texture="minecraft:entity/iron_golem/iron_golem";
        if(!crack.isEmpty()){
            var base=image(texture);var overlay=image(texture+"_crackiness_"+crack);
            if(base!=null&&overlay!=null){texture="litematicrender:entity/iron_golem/"+crack;String selected=texture;generated.computeIfAbsent(selected,k->composite(base,overlay,-1));}
        }
        parts(mesh,root,world,texture,128,128,light);
    }
    private void piglin(SceneMesh mesh,SchematicEntity entity,Mat world,float[] light) {
        boolean baby=baby(entity);var root=new Part();double pitch=Math.toRadians(entity.pitch());
        var main=equipment(entity.data(),"mainhand");var off=equipment(entity.data(),"offhand");
        if(entity.data().getBooleanOrDefault("LeftHanded",false)){var swapped=main;main=off;off=swapped;}
        double rightAngle=empty(main)?0:-Math.PI/10,leftAngle=empty(off)?0:-Math.PI/10;
        if(entity.riding()){rightAngle-=Math.PI/5;leftAngle-=Math.PI/5;}
        if(baby){
            root.child("body",0,18,-.5,0,0,0).box(0,13,-3,-3,-1,6,5,3);
            var head=root.child("head",0,15,0,pitch,0,0).box(21,30,-1.5,-3,-4.5,3,3,1).box(0,0,-4.5,-6,-3.5,9,6,7);
            head.child("left_ear",4.2,-4,0,0,0,-Math.PI/36).child("ear",1,1.75,0,0,0,-.6109).box(0,21,-.5,-3,-2,1,6,4);
            head.child("right_ear",-4.2,-4,0,0,0,Math.PI/36).child("ear",-1,1.75,0,0,0,.6109).box(18,13,-.5,-3,-2,1,6,4);
            root.child("left_arm",4,15,0,leftAngle,0,0).box(28,13,-1,0,-1.5,2,5,3);
            root.child("right_arm",-4,15,0,rightAngle,0,0).box(10,30,-1,0,-1.5,2,5,3);
            root.child("right_leg",-1.5,20,0,entity.riding()?-1.4137:0,0,0).box(22,23,-1.5,0,-1.5,3,4,3);
            root.child("left_leg",1.5,20,0,entity.riding()?-1.4137:0,0,0).box(10,23,-1.5,0,-1.5,3,4,3);
        }else{
            root.child("body",0,0,0,0,0,0).box(16,16,-4,0,-2,8,12,4).box(16,32,-4,0,-2,8,12,4,.25,false);
            var head=root.child("head",0,0,0,pitch,0,0).box(0,0,-5,-8,-4,10,8,8).box(31,1,-2,-4,-5,4,4,1).box(2,4,2,-2,-5,1,2,1).box(2,0,-3,-2,-5,1,2,1);
            head.child("left_ear",4.5,-6,0,0,0,-Math.PI/6).box(51,6,0,0,-2,1,5,4);
            head.child("right_ear",-4.5,-6,0,0,0,Math.PI/6).box(39,6,-1,0,-2,1,5,4);
            root.child("left_arm",5,2,0,leftAngle,0,0).box(32,48,-1,-2,-2,4,12,4).box(48,48,-1,-2,-2,4,12,4,.25,false);
            root.child("right_arm",-5,2,0,rightAngle,0,0).box(40,16,-3,-2,-2,4,12,4).box(40,32,-3,-2,-2,4,12,4,.25,false);
            root.child("right_leg",-1.9,12,0,entity.riding()?-1.4137:0,entity.riding()?Math.PI/10:0,entity.riding()?.0785:0).box(0,16,-2,0,-2,4,12,4).box(0,32,-2,0,-2,4,12,4,.25,false);
            root.child("left_leg",1.9,12,0,entity.riding()?-1.4137:0,entity.riding()?-Math.PI/10:0,entity.riding()?-.0785:0).box(16,48,-2,0,-2,4,12,4).box(0,48,-2,0,-2,4,12,4,.25,false);
        }
        parts(mesh,root,world,"minecraft:entity/piglin/piglin"+(baby?"_baby":""),64,64,light);
        held(mesh,main,world.mul(root.children.get("right_arm").transform()),baby,light);
        held(mesh,off,world.mul(root.children.get("left_arm").transform()),baby,light);
        armor(mesh,entity,root,world,light);
    }
    public static NbtCompound equipment(NbtCompound tag,String slot) {
        var equipment=tag.getCompound("equipment").orElse(null);if(equipment!=null)return equipment.getCompound(slot).orElse(null);
        boolean hand=slot.equals("mainhand")||slot.equals("offhand");var list=tag.getList(hand?"HandItems":"ArmorItems").orElse(null);
        int index=switch(slot){case "mainhand","feet"->0;case "offhand","legs"->1;case "chest"->2;default->3;};
        return list==null?null:list.getCompoundAt(index).orElse(null);
    }
    private static boolean empty(NbtCompound item){return item==null||item.getStringOrDefault("id","minecraft:air").equals("minecraft:air")||SchematicEntity.number(item,"count",SchematicEntity.number(item,"Count",1))<=0;}
    private void held(SceneMesh mesh,NbtCompound item,Mat arm,boolean baby,float[] light) {
        Mat m=arm.rotateX(-Math.PI/2).rotateY(Math.PI).translate(-.0625,.125,-.625);
        if(baby)m=arm.translate(0,.25,0).rotateX(-Math.PI/2).rotateY(Math.PI).scale(.5,.5,.5);
        item(mesh,item,m,"thirdperson_righthand",light);
    }
    private void frame(SceneMesh mesh,SchematicEntity entity,Mat world,float[] light){
        NbtCompound tag=entity.data();int facing=(int)SchematicEntity.number(tag,"Facing",SchematicEntity.number(tag,"facing",3));
        String direction=tag.getStringOrDefault("facing","");if(!direction.isEmpty())facing=switch(direction){case "down"->0;case "up"->1;case "north"->2;case "west"->4;case "east"->5;default->3;};
        facing=Math.max(0,Math.min(5,facing));int[][] normals={{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};int[] n=normals[facing];
        double xRot=facing<2?(facing==0?90:-90):0,yRot=switch(facing){case 2->0;case 4->90;case 5->-90;default->180;};
        Mat center=world.translate(n[0]*.46875,n[1]*.46875,n[2]*.46875).rotateX(Math.toRadians(xRot)).rotateY(Math.toRadians(yRot));
        boolean invisible=tag.getBooleanOrDefault("Invisible",false),glow=kind(entity).equals("glow_item_frame");
        if(!invisible){var model=baker.bake("minecraft:block/"+(glow?"glow_item_frame":"item_frame"),0,0,0,false);model(mesh,model,center.translate(-.5,-.5,-.5),light);}
        NbtCompound stack=tag.getCompound("Item").orElseGet(()->tag.getCompound("item").orElse(null));
        int rotation=Math.floorMod((int)SchematicEntity.number(tag,"ItemRotation",SchematicEntity.number(tag,"item_rotation",0)),8);
        item(mesh,stack,center.translate(0,0,invisible?.5:.4375).rotateZ(rotation*Math.PI/4).scale(.5,.5,.5),"fixed",glow?new float[]{1,1,1}:light);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> itemDescription(String id,int depth){
        if(depth>16)return Map.of();
        try{String[] parts=id.split(":",2);var data=baker.resources().getResource(parts[0],"models/"+parts[1]+".json").orElse(null);if(data==null)return Map.of();
            var own=(Map<String,Object>)MiniJson.parse(new String(data,java.nio.charset.StandardCharsets.UTF_8));var result=new LinkedHashMap<String,Object>();
            Object parent=own.get("parent");if(parent instanceof String p){if(!p.contains(":"))p="minecraft:"+p;if(!p.contains("builtin/"))result.putAll(itemDescription(p,depth+1));}
            for(var entry:own.entrySet())if((entry.getKey().equals("textures")||entry.getKey().equals("display"))&&entry.getValue() instanceof Map<?,?> map){var merged=new LinkedHashMap<String,Object>();if(result.get(entry.getKey()) instanceof Map<?,?> inherited)inherited.forEach((k,v)->merged.put(k.toString(),v));map.forEach((k,v)->merged.put(k.toString(),v));result.put(entry.getKey(),merged);}else result.put(entry.getKey(),entry.getValue());
            return result;
        }catch(Exception error){return Map.of();}
    }
    private static double[] vector(Object value,double fallback){double[] result={fallback,fallback,fallback};if(value instanceof List<?> list)for(int i=0;i<Math.min(3,list.size());i++)if(list.get(i) instanceof Number n&&Double.isFinite(n.doubleValue()))result[i]=n.doubleValue();return result;}
    private void item(SceneMesh mesh,NbtCompound stack,Mat matrix,String context,float[] light){
        if(empty(stack))return;String id=stack.getStringOrDefault("id","");if(!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))return;
        String[] name=id.split(":",2);String modelId=name[0]+":item/"+name[1];
        // Current vanilla block items point directly at a block model through items/*.json.
        try{var bytes=baker.resources().getResource(name[0],"items/"+name[1]+".json").orElse(null);if(bytes!=null){var definition=MiniJson.parseObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));if(definition.get("model") instanceof Map<?,?> node&&Set.of("minecraft:model","model").contains(String.valueOf(node.get("type")))&&node.get("model") instanceof String selected)modelId=selected.contains(":")?selected:"minecraft:"+selected;}}catch(Exception ignored){}
        var description=itemDescription(modelId,0);
        if(description.get("display") instanceof Map<?,?> display&&display.get(context) instanceof Map<?,?> transform){double[] t=vector(transform.get("translation"),0),r=vector(transform.get("rotation"),0),s=vector(transform.get("scale"),1);matrix=matrix.translate(t[0]/16,t[1]/16,t[2]/16).rotateX(Math.toRadians(r[0])).rotateY(Math.toRadians(r[1])).rotateZ(Math.toRadians(r[2])).scale(s[0],s[1],s[2]);}
        if(description.containsKey("elements")){model(mesh,baker.bake(modelId,0,0,0,false),matrix.translate(-.5,-.5,-.5),light);return;}
        String texture=name[0]+":item/"+name[1];if(description.get("textures") instanceof Map<?,?> textures){Object layer=textures.get("layer0");for(int i=0;i<16&&layer instanceof String s&&s.startsWith("#");i++)layer=textures.get(s.substring(1));if(layer instanceof String s)texture=s.contains(":")?s:"minecraft:"+s;}
        if(id.equals("minecraft:crossbow"))texture="minecraft:item/crossbow_standby";
        Image pixels=image(texture);if(pixels==null)return;generated.putIfAbsent(texture,pixels);
        float[] u={0,1,1,0},v={0,0,1,1};
        face(mesh,new double[][]{{-.5,.5,-1.0/32},{.5,.5,-1.0/32},{.5,-.5,-1.0/32},{-.5,-.5,-1.0/32}},u,v,matrix,texture,light);
        face(mesh,new double[][]{{.5,.5,1.0/32},{-.5,.5,1.0/32},{-.5,-.5,1.0/32},{.5,-.5,1.0/32}},new float[]{1,0,0,1},v,matrix,texture,light);
        // A pixel outline gives generated items their vanilla thickness without filling transparent holes.
        int width=Math.min(64,pixels.width),height=Math.min(64,pixels.height);int[][] delta={{-1,0},{1,0},{0,-1},{0,1}};
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)if(opaque(pixels,x,y,width,height))for(int side=0;side<4;side++)if(!opaque(pixels,x+delta[side][0],y+delta[side][1],width,height)){
            double x0=(double)x/width-.5,x1=(double)(x+1)/width-.5,y0=.5-(double)y/height,y1=.5-(double)(y+1)/height,a=-1.0/32,b=1.0/32;
            double[][] points=switch(side){case 0->new double[][]{{x0,y0,b},{x0,y0,a},{x0,y1,a},{x0,y1,b}};case 1->new double[][]{{x1,y0,a},{x1,y0,b},{x1,y1,b},{x1,y1,a}};case 2->new double[][]{{x0,y0,b},{x1,y0,b},{x1,y0,a},{x0,y0,a}};default->new double[][]{{x0,y1,a},{x1,y1,a},{x1,y1,b},{x0,y1,b}};};
            float pu=(x+.5f)/width,pv=(y+.5f)/height;face(mesh,points,new float[]{pu,pu,pu,pu},new float[]{pv,pv,pv,pv},matrix,texture,light);
        }
    }
    private static boolean opaque(Image image,int x,int y,int width,int height){return x>=0&&y>=0&&x<width&&y<height&&Image.alpha(image.sampleNearest((x+.5f)/width,(y+.5f)/height))>25;}
    private void model(SceneMesh mesh,ModelBaker.BakedModel model,Mat matrix,float[] light){for(var q:model.all){Image image=image(q.spriteId);if(image!=null)generated.putIfAbsent(q.spriteId,image);face(mesh,new double[][]{{q.x0,q.y0,q.z0},{q.x1,q.y1,q.z1},{q.x2,q.y2,q.z2},{q.x3,q.y3,q.z3}},new float[]{q.u0,q.u1,q.u2,q.u3},new float[]{q.v0,q.v1,q.v2,q.v3},matrix,q.spriteId,light);}}
    private void armor(SceneMesh mesh,SchematicEntity entity,Part model,Mat world,float[] light) {
        boolean baby=baby(entity);
        for(String slot:List.of("head","chest","legs","feet")){
            var item=equipment(entity.data(),slot);if(empty(item))continue;String id=item.getStringOrDefault("id","");
            String material=id.replace("minecraft:","").replace("_helmet","").replace("_chestplate","").replace("_leggings","").replace("_boots","");
            if(!Set.of("golden","iron","diamond","netherite","chainmail","leather","copper","turtle").contains(material))continue;
            String texture="minecraft:entity/equipment/"+(baby?"humanoid_baby/":slot.equals("legs")?"humanoid_leggings/":"humanoid/")+(material.equals("golden")?"gold":material.equals("turtle")?"turtle_scute":material);
            var selected=image(texture);if(selected==null)continue;
            if(material.equals("leather")){var old=item.getCompound("tag").orElse(new NbtCompound()).getCompound("display").orElse(new NbtCompound());var dyed=item.getCompound("components").orElse(new NbtCompound()).get("minecraft:dyed_color").orElse(null);int color=(int)(dyed instanceof NbtCompound tag?SchematicEntity.number(tag,"rgb",0xA06540):SchematicEntity.number(dyed,SchematicEntity.number(old,"color",0xA06540)));String original=texture;texture="litematicrender:entity/armor/leather/"+(baby?"baby/":"adult/")+slot+"/"+color;var overlay=image(original+"_overlay");Image base=selected;generated.computeIfAbsent(texture,k->composite(base,overlay,color));}
            else generated.put(texture,selected);
            if(!baby)texture=trimTexture(item,texture,material,slot.equals("legs"));
            if(baby){parts(mesh,babyArmor(slot,model,entity.pitch()),world,texture,64,64,light);continue;}
            double grow=slot.equals("legs")?.5:1;Part armor=new Part();
            if(slot.equals("head"))armor.child("head",0,0,0,Math.toRadians(entity.pitch()),0,0).box(0,0,-4,-8,-4,8,8,8,grow,false);
            if(slot.equals("chest")||slot.equals("legs"))armor.child("body",0,0,0,0,0,0).box(16,16,-4,0,-2,8,12,4,grow,false);
            if(slot.equals("chest")){for(String side:List.of("right","left")){Part original=model.children.get(side+"_arm");armor.child(side+"_arm",original.x,original.y,original.z,original.rx,original.ry,original.rz).box(40,16,side.equals("right")?-3:-1,-2,-2,4,12,4,grow,side.equals("left"));}}
            if(slot.equals("legs")||slot.equals("feet")){for(String side:List.of("right","left")){Part original=model.children.get(side+"_leg");armor.child(side+"_leg",original.x,original.y,original.z,original.rx,original.ry,original.rz).box(0,16,-2,0,-2,4,12,4,grow,side.equals("left"));}}
            parts(mesh,armor,world,texture,64,32,light);
        }
    }
    private Part babyArmor(String slot,Part model,float pitch){
        Part armor=new Part();double grow=.7;
        if(slot.equals("head"))armor.child("head",0,15,0,Math.toRadians(pitch),0,0).box(0,0,-4.5,-7,-4.5,9,8,8,grow,false);
        if(slot.equals("chest")){
            armor.child("body",0,18,0,0,0,0).box(0,17,-3,-3,-1.5,6,5,3,grow,false);
            for(String side:List.of("right","left")){Part original=model.children.get(side+"_arm");armor.child(side+"_arm",side.equals("right")?-4:4,15,0,original.rx,original.ry,original.rz).box(30,side.equals("right")?25:17,-1,0,-1.53,2,5,3,grow,false);}
        }
        if(slot.equals("legs"))armor.child("waist",0,19,0,0,0,0).box(0,36,-3,-1.2,-1.49,5.9,2,2.9,grow-.1,false);
        if(slot.equals("legs")||slot.equals("feet"))for(String side:List.of("right","left")){
            boolean right=side.equals("right");Part original=model.children.get(side+"_leg"),leg=armor.child(side+"_leg",right?-1.5:1.5,20,.5,original.rx,original.ry,original.rz);
            if(slot.equals("legs"))leg.box(18,right?17:24,right?-1:-2,-.2,-2,3,4,3,grow-.1,false);
            else leg.box(0,right?29:25,right?-1:-2,2.9,-2,3,1,3,grow,right);
        }
        return armor;
    }
    private String trimTexture(NbtCompound item,String baseId,String armorMaterial,boolean leggings){
        var trim=item.getCompound("components").orElse(new NbtCompound()).getCompound("minecraft:trim").orElseGet(()->item.getCompound("tag").orElse(new NbtCompound()).getCompound("Trim").orElse(null));
        if(trim==null)return baseId;
        String pattern=trim.getStringOrDefault("pattern",""),material=trim.getStringOrDefault("material","").replace("minecraft:","");if(!pattern.contains(":"))pattern="minecraft:"+pattern;
        if(!pattern.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||!Set.of("amethyst","copper","diamond","emerald","gold","iron","lapis","netherite","quartz","redstone","resin").contains(material))return baseId;
        String[] id=pattern.split(":",2);String same=armorMaterial.equals("golden")?"gold":armorMaterial;
        String paletteName=material+(material.equals(same)&&Set.of("iron","gold","diamond","netherite","copper").contains(material)?"_darker":"");
        Image base=generated.get(baseId),mask=image(id[0]+":trims/entity/"+(leggings?"humanoid_leggings/":"humanoid/")+id[1]),from=image("minecraft:palettes/trim_base"),to=image("minecraft:palettes/trim/"+paletteName);
        if(base==null||mask==null||from==null||to==null)return baseId;
        String sprite="litematicrender:entity/armor/trim/"+UUID.nameUUIDFromBytes((baseId+pattern+paletteName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        generated.computeIfAbsent(sprite,key->{
            var lookup=new HashMap<Integer,Integer>();for(int i=0;i<Math.min(from.pixels().length,to.pixels().length);i++)lookup.put(from.pixels()[i]&0xffffff,to.pixels()[i]);
            Image overlay=new Image(mask.width,mask.height);for(int i=0;i<mask.pixels().length;i++){int pixel=mask.pixels()[i],color=lookup.getOrDefault(pixel&0xffffff,pixel);overlay.pixels()[i]=Image.argb(Image.alpha(pixel)*Image.alpha(color)/255,Image.red(color),Image.green(color),Image.blue(color));}
            Image result=new Image(base.width,base.height);System.arraycopy(base.pixels(),0,result.pixels(),0,base.pixels().length);SpecialBlockGeometry.over(result,overlay,0xffffff);return result;
        });
        return sprite;
    }
    Image image(String id){return images.computeIfAbsent(id,key->{try{String[] parts=key.split(":",2);var data=baker.resources().getResource(parts[0],"textures/"+parts[1]+".png").orElse(null);return data==null?null:Image.read(data);}catch(Exception error){return null;}});}
    private static Image composite(Image base,Image overlay,int tint){
        var result=new Image(base.width,base.height);
        for(int y=0;y<base.height;y++)for(int x=0;x<base.width;x++){
            int pixel=base.pixel(x,y),r=Image.red(pixel),g=Image.green(pixel),b=Image.blue(pixel);
            if(tint!=-1){r=r*(tint>>16&255)/255;g=g*(tint>>8&255)/255;b=b*(tint&255)/255;}
            if(overlay!=null){int top=overlay.sampleNearest((x+.5f)/base.width,(y+.5f)/base.height),a=Image.alpha(top);r=(Image.red(top)*a+r*(255-a))/255;g=(Image.green(top)*a+g*(255-a))/255;b=(Image.blue(top)*a+b*(255-a))/255;}
            result.setPixel(x,y,Image.argb(Image.alpha(pixel),r,g,b));
        }
        return result;
    }
    private void block(SceneMesh mesh,BlockState state,Mat matrix,float[] light){
        for(var variant:baker.selectVariants(state.blockId,state))for(var q:baker.bake(variant).all){
            var positions=new double[][]{{q.x0,q.y0,q.z0},{q.x1,q.y1,q.z1},{q.x2,q.y2,q.z2},{q.x3,q.y3,q.z3}};
            face(mesh,positions,new float[]{q.u0,q.u1,q.u2,q.u3},new float[]{q.v0,q.v1,q.v2,q.v3},matrix,q.spriteId,light);
        }
    }
    void parts(SceneMesh mesh,Part part,Mat matrix,String texture,int width,int height,float[] light){
        matrix=matrix.mul(part.transform());
        for(var cube:part.boxes)cube(mesh,cube,matrix,texture,width,height,light);
        for(var child:part.children.values())parts(mesh,child,matrix,texture,width,height,light);
    }
    private void cube(SceneMesh mesh,Box box,Mat matrix,String texture,int width,int height,float[] light){
        double x=box.x-box.grow,y=box.y-box.grow,z=box.z-box.grow,xx=box.x+box.w+box.grow,yy=box.y+box.h+box.grow,zz=box.z+box.d+box.grow;
        if(box.mirror){double old=x;x=xx;xx=old;}
        double[][] points={{x,y,z},{xx,y,z},{xx,yy,z},{x,yy,z},{x,y,zz},{xx,y,zz},{xx,yy,zz},{x,yy,zz}};
        int[][] faces={{5,4,0,1},{2,3,7,6},{0,4,7,3},{1,0,3,2},{5,1,2,6},{4,5,6,7}};
        double u=box.u,v=box.v,d=box.d,w=box.w,h=box.h;
        double[][] uv={{u+d,v,u+d+w,v+d},{u+d+w,v+d,u+d+w+w,v},{u,v+d,u+d,v+d+h},{u+d,v+d,u+d+w,v+d+h},{u+d+w,v+d,u+d+w+d,v+d+h},{u+d+w+d,v+d,u+d+w+d+w,v+d+h}};
        for(int f=0;f<6;f++){
            var positions=new double[4][3];var us=new float[4];var vs=new float[4];
            for(int i=0;i<4;i++){int corner=box.mirror?3-i:i;var p=points[faces[f][corner]];for(int axis=0;axis<3;axis++)positions[i][axis]=p[axis]/16;us[i]=(float)(uv[f][corner==0||corner==3?2:0]/width);vs[i]=(float)(uv[f][corner<2?1:3]/height);}
            face(mesh,positions,us,vs,matrix,texture,light);
        }
    }
    void face(SceneMesh mesh,double[][] positions,float[] u,float[] v,Mat matrix,String texture,float[] light){
        float[] x=new float[4],y=new float[4],z=new float[4];for(int i=0;i<4;i++){double[] p=matrix.point(positions[i][0],positions[i][1],positions[i][2]);x[i]=(float)p[0];y[i]=(float)p[1];z[i]=(float)p[2];}
        double ax=x[1]-x[0],ay=y[1]-y[0],az=z[1]-z[0],bx=x[2]-x[0],by=y[2]-y[0],bz=z[2]-z[0];
        double nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx,length=Math.abs(nx)+Math.abs(ny)+Math.abs(nz);if(length<1e-10)return;
        float shade=(float)((Math.abs(nx)*.6+Math.abs(nz)*.8+Math.abs(ny)*(ny>0?1:.5))/length);
        if(!lightingEnabled)shade=1;
        (layer==SceneMesh.Layer.WATER_MASK?mesh.waterMask:mesh.cutout).add(new SceneMesh.MeshQuad(x,y,z,u[0],v[0],u[1],v[1],u[2],v[2],u[3],v[3],texture,-1,generated.get(texture),light[0],light[1],light[2],shade,null,layer,false));
    }
    private record Box(double u,double v,double x,double y,double z,double w,double h,double d,double grow,boolean mirror){}
    static final class Part {
        double x,y,z,rx,ry,rz;final List<Box> boxes=new ArrayList<>();final Map<String,Part> children=new LinkedHashMap<>();
        Part child(String name,double x,double y,double z,double rx,double ry,double rz){var child=new Part();child.x=x;child.y=y;child.z=z;child.rx=rx;child.ry=ry;child.rz=rz;children.put(name,child);return child;}
        Part box(double u,double v,double x,double y,double z,double w,double h,double d){return box(u,v,x,y,z,w,h,d,0,false);}
        Part box(double u,double v,double x,double y,double z,double w,double h,double d,double grow,boolean mirror){boxes.add(new Box(u,v,x,y,z,w,h,d,grow,mirror));return this;}
        Mat transform(){return Mat.identity().translate(x/16,y/16,z/16).rotateZ(rz).rotateY(ry).rotateX(rx);}
    }
    static final class Mat {
        final double[] values;Mat(double[] values){this.values=values;}
        static Mat identity(){return new Mat(new double[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1});}
        Mat mul(Mat other){double[] result=new double[16];for(int row=0;row<4;row++)for(int col=0;col<4;col++)for(int k=0;k<4;k++)result[row*4+col]+=values[row*4+k]*other.values[k*4+col];return new Mat(result);}
        Mat translate(double x,double y,double z){var m=identity();m.values[3]=x;m.values[7]=y;m.values[11]=z;return mul(m);}
        Mat scale(double x,double y,double z){var m=identity();m.values[0]=x;m.values[5]=y;m.values[10]=z;return mul(m);}
        Mat rotateX(double angle){double c=Math.cos(angle),s=Math.sin(angle);return mul(new Mat(new double[]{1,0,0,0,0,c,-s,0,0,s,c,0,0,0,0,1}));}
        Mat rotateY(double angle){double c=Math.cos(angle),s=Math.sin(angle);return mul(new Mat(new double[]{c,0,s,0,0,1,0,0,-s,0,c,0,0,0,0,1}));}
        Mat rotateZ(double angle){double c=Math.cos(angle),s=Math.sin(angle);return mul(new Mat(new double[]{c,-s,0,0,s,c,0,0,0,0,1,0,0,0,0,1}));}
        double[] point(double x,double y,double z){return new double[]{values[0]*x+values[1]*y+values[2]*z+values[3],values[4]*x+values[5]*y+values[6]*z+values[7],values[8]*x+values[9]*y+values[10]*z+values[11]};}
    }
}
