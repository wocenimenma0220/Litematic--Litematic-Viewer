package io.github.litematicrender.schematic;

import io.github.litematicrender.model.*;
import io.github.litematicrender.nbt.*;
import io.github.litematicrender.render.*;
import io.github.litematicrender.render.scene.*;
import io.github.litematicrender.world.*;
import java.nio.file.*;
import java.util.*;

/** Independent selection meshing exposes all six borders and preserves real model shapes. */
public final class SelectionBoundaryRegression {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static List<SceneMesh.MeshQuad> faces(SceneMesh mesh){var result=new ArrayList<SceneMesh.MeshQuad>();for(var layer:List.of(mesh.solid,mesh.cutout,mesh.portal,mesh.fluid,mesh.translucent))result.addAll(layer);return result;}
    static SceneBuilder source(LitematicaSchematic schematic,BlockRegistry registry,ModelBaker baker,Colormaps colors,SpriteAtlas atlas,int[] offset){
        var builder=new SceneBuilder(registry,baker,colors,atlas).lightingEnabled(false);for(var region:schematic.regions().values())builder.addRegion(region,offset);return builder;
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),out=root.resolve("tools/verify/selection-output");Files.createDirectories(out);System.setProperty("litematicrender.home",root.toString());System.setProperty("litematicrender.skins.offline","true");
        try(var resources=CliRenderer.openResources(root.resolve("assets/vanilla"),root)){
            var registry=BlockRegistry.load(resources);var baker=new ModelBaker(resources,registry);var atlas=SpriteAtlas.load(resources,"blocks");var colors=new Colormaps();colors.load(resources);
            var palette=List.of(VisualFixRegression.state("air"),VisualFixRegression.state("stone"));int[] cells=new int[125];Arrays.fill(cells,1);
            Path file=VisualFixRegression.write(out.resolve("solid.litematic"),5,5,5,palette,cells);var schematic=LitematicaSchematic.load(file);schematic.materialize(registry);var builder=source(schematic,registry,baker,colors,atlas,new int[]{0,0,0});
            var full=builder.build();check(full.quadCount==150,"unexpected solid baseline");
            var center=builder.buildSelection(new double[]{2,2,2},new double[]{3,3,3});check(center.quadCount==6,"buried selected block lacks all six faces: "+center.quadCount);
            var render=new RenderSettings(45,30,160,160);render.selectionMin=new double[]{2,2,2};render.selectionMax=new double[]{3,3,3};render.selectionMesh=center;
            var cut=SchematicRenderer.render(null,full,colors,atlas,render);check(Arrays.stream(cut.pixels()).anyMatch(p->p>>>24==255),"CPU export did not render the buried selected surface");cut.writePng(out.resolve("solid-cut.png"));
            for(var q:faces(center))check(q.blockX==2&&q.blockY==2&&q.blockZ==2,"selection emitted outside cells");
            check(builder.buildSelection(new double[]{2,2,2},new double[]{4,3,3}).quadCount==10,"adjacent selected blocks retained an internal face");
            check(builder.buildSelection(new double[]{0,2,0},new double[]{5,3,5}).quadCount==70,"single layer did not expose top and bottom");
            check(builder.build().quadCount==150&&!builder.blockAt(0,0,0).isAir(),"selection scope leaked into original mesh");
            var lit=source(schematic,registry,baker,colors,atlas,new int[]{0,0,0}).lightingEnabled(true).realLighting(true);lit.build();var lightField=SceneBuilder.class.getDeclaredField("light");lightField.setAccessible(true);var originalLight=lightField.get(lit);
            var litCut=lit.buildSelection(new double[]{2,2,2},new double[]{3,3,3});int brightest=0;for(var q:faces(litCut))if(q.vertexColors!=null)for(int c:q.vertexColors)brightest=Math.max(brightest,c&255);
            check(brightest>150,"newly exposed faces retained sealed-world darkness");check(lightField.get(lit)==originalLight,"selection replaced the original world lighting");
            var negative=source(schematic,registry,baker,colors,atlas,new int[]{-10,-4,-12});check(negative.buildSelection(new double[]{-8,-2,-10},new double[]{-7,-1,-9}).quadCount==6,"negative selection lost a border");
            for(String block:List.of("oak_slab","glass","water")){
                var target=block.equals("oak_slab")?VisualFixRegression.state(block,"type","bottom","waterlogged","false"):block.equals("water")?VisualFixRegression.state(block,"level","0"):VisualFixRegression.state(block);
                var states=List.of(VisualFixRegression.state("air"),VisualFixRegression.state("stone"),target);int[] content=cells.clone();content[(2*5+2)*5+2]=2;
                var test=LitematicaSchematic.load(VisualFixRegression.write(out.resolve("selected-"+block+".litematic"),5,5,5,states,content));test.materialize(registry);var b=source(test,registry,baker,colors,atlas,new int[]{0,0,0});b.build();var selected=b.buildSelection(new double[]{2,2,2},new double[]{3,3,3});
                check(selected.quadCount>=6,"selected "+block+" missing exposed geometry");
                if(block.equals("oak_slab"))for(var q:faces(selected))check(Math.max(Math.max(q.y0,q.y1),Math.max(q.y2,q.y3))<=2.50001,"slab was replaced by a cube cap");
                MeshExporter.write(selected,atlas,out.resolve("selected-"+block+".bin"));
            }
            MeshExporter.write(full,atlas,out.resolve("solid.bin"));MeshExporter.write(center,atlas,out.resolve("center.bin"));atlas.stitched.writePng(out.resolve("boundary-atlas.png"));
            System.out.println("PASS: buried block six faces, adjacent face culling, both layer cuts, negative coordinates, restored original world, slab shape, glass and fluid boundaries");
        }
    }
}
