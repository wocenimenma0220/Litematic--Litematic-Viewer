package io.github.litematicrender.schematic;

import io.github.litematicrender.model.*;
import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.world.*;
import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

/** Compare lighting against the actual classes shipped in the 1.0.10 ZIP. */
public final class OptionalLightingRegression {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void set(Object object,String key,Object value)throws Exception{var field=object.getClass().getDeclaredField(key);field.setAccessible(true);field.set(object,value);}
    static Method method(Class<?> type,String key,Class<?>...args)throws Exception{var result=type.getDeclaredMethod(key,args);result.setAccessible(true);return result;}
    static void equal(float[] a,float[] b,String name){check(a.length==b.length,name);for(int i=0;i<a.length;i++)check(Float.floatToIntBits(a[i])==Float.floatToIntBits(b[i]),name+" corner "+i+": "+a[i]+" / "+b[i]);}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),reference=Path.of(args[1]).toAbsolutePath(),output=Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(output);System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        try(var loader=new URLClassLoader(new URL[]{reference.toUri().toURL()},OptionalLightingRegression.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                if(name.startsWith("io.github.litematicrender.render.scene.SparseLightVolume")||name.equals("io.github.litematicrender.render.scene.LightVolume")||name.equals("io.github.litematicrender.render.scene.SceneBuilder")||name.startsWith("io.github.litematicrender.render.scene.SceneBuilder$")) {
                    synchronized(getClassLoadingLock(name)){var type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;}
                }
                return super.loadClass(name,resolve);
            }
        };var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)) {
            var registry=BlockRegistry.load(resources);var baker=new ModelBaker(resources,registry);var atlas=SpriteAtlas.load(resources,"blocks");var shapes=new BlockShape(baker,atlas);var colors=new Colormaps();colors.load(resources);
            var legacyLight=loader.loadClass("io.github.litematicrender.render.scene.LightVolume");
            var constructor=legacyLight.getConstructor(int.class,int.class,int.class,BlockState[].class,BlockShape.class);
            Method compute=legacyLight.getMethod("compute"),cell=legacyLight.getMethod("cellLight",int.class,int.class,int.class);
            var brightness=legacyLight.getMethod("brightness",int.class,float[].class);
            int comparisons=0,aoChecks=0,faceChecks=0;
            float[] actual=new float[3],expected=new float[3];
            for(int sky=0;sky<16;sky++)for(int block=0;block<16;block++) {
                int packed=(sky<<8)|block;LightVolume.brightness(packed,actual);brightness.invoke(null,packed,expected);equal(actual,expected,"brightness "+packed);
            }
            int sx=32,sy=32,sz=32;var cells=new BlockState[sx*sy*sz];
            var stone=registry.getOrMissing("minecraft:stone").defaultState;
            var water=registry.getOrMissing("minecraft:water").defaultState;
            var room=new BlockState[9*9*9];Arrays.fill(room,BlockState.air());
            for(int y=1;y<=7;y++)for(int z=1;z<=7;z++)for(int x=1;x<=7;x++)
                if(x==1||x==7||y==1||y==7||z==1||z==7)room[(y*9+z)*9+x]=stone;
            var dark=new LightVolume(9,9,9,room,shapes,true);dark.compute();
            check(dark.cellLight(4,4,4)==0,"sealed room leaked sky or block light");
            var bright=new LightVolume(9,9,9,room,shapes);bright.compute();
            check(LightVolume.skyOf(bright.cellLight(4,4,4))==15,"default bright lighting changed");
            room[(4*9+4)*9+4]=registry.getOrMissing("minecraft:torch").defaultState;
            var torchRoom=new LightVolume(9,9,9,room,shapes,true);torchRoom.compute();
            check(LightVolume.skyOf(torchRoom.cellLight(5,4,4))==0&&LightVolume.blockOf(torchRoom.cellLight(5,4,4))==13,"torch did not light sealed room");
            room[(4*9+4)*9+4]=BlockState.air();room[(7*9+4)*9+4]=registry.getOrMissing("minecraft:glass").defaultState;
            var glassRoom=new LightVolume(9,9,9,room,shapes,true);glassRoom.compute();
            check(LightVolume.skyOf(glassRoom.cellLight(4,4,4))==15,"glass skylight blocked sky");
            LightVolume dense=null;Object old=null;
            for(int sample=0;sample<4;sample++) {
                Arrays.fill(cells,BlockState.air());
                for(int x=6;x<26;x++)for(int z=6;z<26;z++)cells[(20*sz+z)*sx+x]=stone;
                for(int y=3;y<14;y++)cells[(y*sz+7)*sx+7]=water;
                var random=new Random(42+sample);
                for(int i=0;i<1500;i++)cells[random.nextInt(cells.length)]=random.nextBoolean()?stone:water;
                var emitters=List.of("torch","glowstone","redstone_torch","redstone_lamp","furnace","candle","light","fire","soul_fire","campfire");
                for(int i=0;i<emitters.size();i++) {
                    var state=registry.getOrMissing("minecraft:"+emitters.get(i)).defaultState;
                    if(state.getValue("lit")!=null)state=state.with("lit",sample%2==0?"true":"false");
                    if(state.getValue("level")!=null)state=state.with("level","3");
                    cells[((2+i)*sz+2)*sx+2]=state;
                }
                dense=new LightVolume(sx,sy,sz,cells,shapes,true);old=constructor.newInstance(sx,sy,sz,cells,shapes);
                var sparse=new SparseLightVolume(sx,sy,sz,shapes,true);
                for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++){var state=cells[(y*sz+z)*sx+x];if(!state.isAir())sparse.put(x,y,z,state);}
                dense.compute();compute.invoke(old);sparse.compute();
                for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++) {
                    int want=(Integer)cell.invoke(old,x,y,z),got=dense.cellLight(x,y,z);
                    check(got==want,"legacy light differs at "+x+","+y+","+z+": "+got+" / "+want);
                    check(sparse.cellLight(x,y,z)==want,"sparse legacy light differs at "+x+","+y+","+z);comparisons++;
                }
            }
            var oldSparseType=loader.loadClass("io.github.litematicrender.render.scene.SparseLightVolume");
            var oldSparseConstructor=oldSparseType.getConstructor(int.class,int.class,int.class,BlockShape.class);
            var oldPut=oldSparseType.getMethod("put",int.class,int.class,int.class,BlockState.class);
            var oldSparseCompute=oldSparseType.getMethod("compute");var oldSparseCell=oldSparseType.getMethod("cellLight",int.class,int.class,int.class);
            for(int span:List.of(64,2001)) {
                var sparse=new SparseLightVolume(span,128,span,shapes,true);Object previous=oldSparseConstructor.newInstance(span,128,span,shapes);
                for(int x=20;x<42;x++)for(int z=20;z<42;z++){sparse.put(x,100,z,stone);oldPut.invoke(previous,x,100,z,stone);}
                sparse.put(span-1,0,span-1,stone);oldPut.invoke(previous,span-1,0,span-1,stone);
                sparse.compute();oldSparseCompute.invoke(previous);
                var random=new Random(123);
                for(int i=0;i<2000;i++) {
                    int x=i<1000?random.nextInt(64):random.nextInt(span),y=random.nextInt(128),z=i<1000?random.nextInt(64):random.nextInt(span);
                    check(sparse.cellLight(x,y,z)==(Integer)oldSparseCell.invoke(previous,x,y,z),"original sparse differs at "+x+","+y+","+z);comparisons++;
                }
                check(sparse.sectionCount()<100,"sparse lighting filled distant gaps");
            }
            var legacyBuilder=loader.loadClass("io.github.litematicrender.render.scene.SceneBuilder");
            Object oldBuilder=legacyBuilder.getConstructor(BlockRegistry.class,ModelBaker.class,Colormaps.class,SpriteAtlas.class).newInstance(registry,baker,colors,atlas);
            var builder=new SceneBuilder(registry,baker,colors,atlas).realLighting(true);
            var fixture=LitematicaSchematic.load(root.resolve("tools/verify/fly-output/inspection.litematic"));fixture.materialize(registry);
            for(var region:fixture.regions().values()){var origin=region.blockOrigin();int[] offset={origin.x(),origin.y(),origin.z()};builder.addRegion(region,offset);legacyBuilder.getMethod("addRegion",LitematicaSchematic.Region.class,int[].class).invoke(oldBuilder,region,offset);}
            set(builder,"light",dense);set(builder,"lightOrigin",new int[3]);set(oldBuilder,"light",old);set(oldBuilder,"lightOrigin",new int[3]);
            Class<?>[] aoTypes={int.class,int.class,int.class,int.class,Quad.class,float[].class,float[].class,float[].class,BlockState.class};
            Method oldAo=method(legacyBuilder,"ambientOcclusion",aoTypes),newAo=method(SceneBuilder.class,"ambientOcclusion",aoTypes);
            Class<?>[] emitTypes={SceneMesh.class,Quad.class,int.class,int.class,int.class,int.class,BlockState.class,boolean.class};
            Method oldEmit=method(legacyBuilder,"emitQuad",emitTypes),newEmit=method(SceneBuilder.class,"emitQuad",emitTypes);
            for(String id:List.of("stone","stone_slab","oak_stairs","farmland","torch","redstone_wire","glass")) {
                var state=registry.getOrMissing("minecraft:"+id).defaultState;
                for(var variant:baker.selectVariants(state.blockId,state)) {
                    var model=baker.bake(variant);
                    for(int face=-1;face<6;face++)for(var quad:face<0?model.unculled:model.quadsForFace(face)) {
                        for(int x:List.of(0,3,7,12)) {
                            float[] xs={quad.x0+x,quad.x1+x,quad.x2+x,quad.x3+x},ys={quad.y0,quad.y1,quad.y2,quad.y3},zs={quad.z0,quad.z1,quad.z2,quad.z3};
                            if(face>=0){equal((float[])newAo.invoke(builder,x,0,0,face,quad,xs,ys,zs,state),(float[])oldAo.invoke(oldBuilder,x,0,0,face,quad,xs,ys,zs,state),"AO "+id+" face "+face);aoChecks++;}
                            var newMesh=new SceneMesh();var oldMesh=new SceneMesh();
                            newEmit.invoke(builder,newMesh,quad,face,x,0,0,state,true);oldEmit.invoke(oldBuilder,oldMesh,quad,face,x,0,0,state,true);
                            SceneMesh.MeshQuad a=first(newMesh),b=first(oldMesh);equal(new float[]{a.shade,a.tintR,a.tintG,a.tintB},new float[]{b.shade,b.tintR,b.tintG,b.tintB},"face light "+id);if(a.ao!=null)equal(a.ao,b.ao,"emitted AO "+id);faceChecks++;
                        }
                    }
                }
            }
            String report="{\"pass\":true,\"reference\":\"original 1.0.10 ZIP classes\",\"lightCellsCompared\":"+comparisons+",\"brightnessPairs\":256,\"aoChecks\":"+aoChecks+",\"faceChecks\":"+faceChecks+",\"denseAndSparseMatch\":true}";
            Files.writeString(output.resolve("optional-lighting.json"),report);System.out.println(report);
        }
    }
    static SceneMesh.MeshQuad first(SceneMesh mesh){for(var list:List.of(mesh.solid,mesh.cutout,mesh.portal,mesh.fluid,mesh.translucent))if(!list.isEmpty())return list.get(0);throw new AssertionError("no quad emitted");}
}
