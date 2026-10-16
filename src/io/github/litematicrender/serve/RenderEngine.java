package io.github.litematicrender.serve;

import io.github.litematicrender.model.ModelBaker;
import io.github.litematicrender.render.Colormaps;
import io.github.litematicrender.render.GifWriter;
import io.github.litematicrender.render.Image;
import io.github.litematicrender.render.MeshExporter;
import io.github.litematicrender.render.RenderDiagnostics;
import io.github.litematicrender.render.RenderSettings;
import io.github.litematicrender.render.SchematicRenderer;
import io.github.litematicrender.render.SpriteAtlas;
import io.github.litematicrender.render.StreamMeshExporter;
import io.github.litematicrender.render.scene.ScenePicker;
import io.github.litematicrender.render.scene.SceneBuilder;
import io.github.litematicrender.render.scene.SceneMesh;
import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.schematic.LitematicaSchematic;
import io.github.litematicrender.world.BlockRegistry;
import io.github.litematicrender.world.Vec3i;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Render engine: loads a resource pack once, then answers render requests.
 *
 * <p>Kept deliberately free of any transport concern — {@link JsonRpcServer} owns stdin/stdout,
 * this class owns the expensive state (resources, atlas, model baker) and the render calls.
 */
public final class RenderEngine implements AutoCloseable {
    private final Path home;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private ResourceManager resources;
    private BlockRegistry registry;
    private Colormaps colormaps;
    private SpriteAtlas atlas;
    private ModelBaker baker;
    private Path loadedPackRoot;
    private List<Path> loadedBuiltins = List.of();
    private List<String> loadedBuiltinNames = List.of();
    private List<Path> loadedUserPacks = List.of();
    private List<String> loadedUserPackNames = List.of();

    /**
     * Built geometry for the open schematic, reused across renders.
     *
     * <p>Building a scene walks every block and bakes ambient occlusion and per-face lighting; it
     * depends on the schematic and resource pack but <em>not</em> on the camera. Measured on a
     * 15k-block schematic: 32 ms to build once, versus 146 000 quads worth of work thrown away on
     * every drag. This is the CPU analogue of what the game does by uploading static geometry to
     * the GPU once and only changing the model-view matrix while dragging — see
     * {@code QuickLitematicaPreviewAccess.drawMesh}, which fits-drags without rebuilding.
     */
    private SceneMesh scene;
    private SceneBuilder sceneBuilder;
    private SceneMesh selectedScene;
    private String selectedSceneKey;
    private SceneMesh pickedScene;
    private io.github.litematicrender.render.scene.ScenePicker picker;
    private BlockInspector inspector;
    private String sceneKey;
    private SceneBuilder streamBuilder;
    private StreamMeshExporter.Index streamIndex;
    private String streamKey;
    private boolean streamRealLighting;
    private boolean sceneRealLighting;
    private boolean sceneLightingEnabled=true,streamLightingEnabled=true;
    /** True when the last render reused {@link #scene} instead of rebuilding it. */
    private boolean sceneFromMesh;

    /** Cached parse of the currently open schematic, keyed by path + mtime + size. */
    private LitematicaSchematic schematic;
    private Path schematicPath;
    private long schematicStamp;
    private String schematicDigest,loadedResourceFingerprint;
    private SceneCache diskCache;
    private String rendererFingerprint;
    private io.github.litematicrender.render.skin.SkinResolver streamSkins;
    private volatile PreviewJob previewJob;
    private static final class PreviewJob {
        final String id=java.util.UUID.randomUUID().toString();
        volatile boolean cancelled,finished;volatile Thread thread;volatile String stage="queued",error="";volatile Map<String,Object> overview,full;volatile Path directory;
        volatile Map<String,Object> live;volatile long committedBytes,viewRevision;volatile int total;volatile PreviewPriority view;
        final List<StreamMeshExporter.Entry> entries=new ArrayList<>();
    }
    @FunctionalInterface private interface BeforeBuild {io.github.litematicrender.render.scene.OverviewMesh run()throws Exception;}

    public RenderEngine(Path home) {
        this.home = home;
    }

    private String materialKey;private Map<String,Object> materialReport;
    public synchronized Map<String,Object> materials()throws Exception {
        ensureResources();if(schematic==null)throw new IllegalStateException("请先打开投影");
        String key=schematicPath+"|"+schematicDigest+"|"+System.identityHashCode(registry);
        if(!key.equals(materialKey)){materialReport=new MaterialList(resources,home).collect(schematic);materialKey=key;}
        return materialReport;
    }
    public synchronized Map<String,Object> exportMaterials(Path output,String scope)throws Exception {
        return exportMaterials(output,scope,false);
    }
    public synchronized Map<String,Object> materials(boolean waterAsIce)throws Exception {return waterAsIce?MaterialList.waterAsIce(materials()):materials();}
    public synchronized Map<String,Object> exportMaterials(Path output,String scope,boolean waterAsIce)throws Exception {
        var report=materials(waterAsIce);var files=new ArrayList<String>();
        if(scope.equals("separate")){
            String name=output.getFileName().toString();int dot=name.lastIndexOf('.');if(dot<0)throw new IllegalArgumentException("请使用 XLSX 或 CSV 扩展名");
            for(String kind:List.of("building","contents")) {
                String suffix=kind.equals("building")?"-建材":"-容器内容物";Path file=output.resolveSibling(name.substring(0,dot)+suffix+name.substring(dot));
                MaterialExporter.write(file,report,kind,schematicPath.getFileName().toString());files.add(file.toAbsolutePath().toString());
            }
        }else{MaterialExporter.write(output,report,scope,schematicPath.getFileName().toString());files.add(output.toAbsolutePath().toString());}
        return Map.of("files",files);
    }
    public Path home() {
        return this.home;
    }
    public synchronized void invalidateScene() { this.scene=null;this.sceneKey=null; }
    public synchronized void releaseStreaming(){clearStream();}
    /** Voxel selection needs no geometry, lighting or entity inspection. */
    public synchronized Map<String,Object> selectBlock(double[] origin,double[] direction,double maximum)throws Exception {
        if(schematic==null)throw new IllegalStateException("请先打开投影");
        double length=0;for(int i=0;i<3;i++){if(!Double.isFinite(origin[i])||Math.abs(origin[i])>30000000||!Double.isFinite(direction[i]))throw new IllegalArgumentException("无效的选择射线");length+=direction[i]*direction[i];}
        length=Math.sqrt(length);if(!Double.isFinite(length)||length<1e-12||!Double.isFinite(maximum)||maximum<=0)throw new IllegalArgumentException("无效的选择距离");maximum=Math.min(maximum,65536);
        int[] cell=new int[3],step=new int[3];double[] next=new double[3],delta=new double[3];
        for(int a=0;a<3;a++){double d=direction[a]/length;cell[a]=(int)Math.floor(origin[a]);step[a]=d>0?1:d<0?-1:0;delta[a]=d==0?Double.POSITIVE_INFINITY:Math.abs(1/d);next[a]=d==0?Double.POSITIVE_INFINITY:((d>0?cell[a]+1:cell[a])-origin[a])/d;}
        double distance=0;
        for(int n=0;n<200000&&distance<=maximum;n++){
            for(var region:schematic.regions().values()){
                var p=region.blockOrigin();int x=cell[0]-p.x(),y=cell[1]-p.y(),z=cell[2]-p.z();
                if(x<0||y<0||z<0||x>=region.blocks.sizeX||y>=region.blocks.sizeY||z>=region.blocks.sizeZ)continue;
                var block=region.blocks.get(x,y,z);if(!block.isAir()&&!List.of("minecraft:barrier","minecraft:light","minecraft:structure_void").contains(block.blockId))return Map.of("hit",true,"position",List.of(cell[0],cell[1],cell[2]));
            }
            int a=next[0]<=next[1]&&next[0]<=next[2]?0:next[1]<=next[2]?1:2;distance=next[a];cell[a]+=step[a];next[a]+=delta[a];
        }
        return Map.of("hit",false);
    }
    public synchronized Map<String,Object> pickBlock(double[] origin,double[] direction,double maximum) throws Exception {
        ensureResources();
        if(this.schematic==null)throw new IllegalStateException("请先打开投影");
        if(this.streamIndex!=null)return pickStream(origin,direction,maximum);
        SceneMesh mesh=sceneMesh();
        if(mesh!=this.pickedScene){
            this.picker=new io.github.litematicrender.render.scene.ScenePicker(mesh,this.atlas);
            this.inspector=new BlockInspector(this.schematic,this.resources);this.pickedScene=mesh;
        }
        return this.inspector.inspect(this.picker.pick(origin,direction,maximum));
    }
    private Map<String,Object> pickStream(double[] origin,double[] direction,double maximum)throws Exception {
        if(streamBuilder==null){
            scene=null;sceneKey=null;
            streamBuilder=new SceneBuilder(registry,baker,colormaps,atlas).realLighting(streamRealLighting).lightingEnabled(streamLightingEnabled);
            if(streamSkins!=null)streamBuilder.skinSnapshot(streamSkins);
            for(var region:schematic.regions().values()){var p=region.blockOrigin();streamBuilder.addRegion(region,new int[]{p.x(),p.y(),p.z()});}
            streamBuilder.prepareStreaming();
        }
        double length=0;for(double value:direction){if(!Double.isFinite(value))throw new IllegalArgumentException("无效的点选方向");length+=value*value;}
        length=Math.sqrt(length);if(!Double.isFinite(length)||length<1e-12||!Double.isFinite(maximum)||maximum<=0)throw new IllegalArgumentException("无效的点选距离");
        double[] dir={direction[0]/length,direction[1]/length,direction[2]/length};
        var candidates=new ArrayList<StreamMeshExporter.Entry>();
        for(var entry:streamIndex.sections())if(rayBox(entry.bounds(),origin,dir,maximum)!=Double.POSITIVE_INFINITY)candidates.add(entry);
        candidates.sort(java.util.Comparator.comparingDouble(e->rayBox(e.bounds(),origin,dir,maximum)));
        double mask=Double.POSITIVE_INFINITY;
        for(var entry:candidates)if(entry.levels().get(0).stream().anyMatch(p->p.layer()==5)) {
            var local=new ScenePicker(streamBuilder.buildSection(entry.position()),atlas);mask=Math.min(mask,local.maskDistance(origin,dir,maximum));
        }
        ScenePicker.Hit best=null;
        for(var entry:candidates) {
            if(best!=null&&rayBox(entry.bounds(),origin,dir,maximum)>best.distance())break;
            var local=new ScenePicker(streamBuilder.buildSection(entry.position()),atlas);
            var hit=local.pick(origin,dir,best==null?maximum:best.distance(),mask);if(hit!=null)best=hit;
        }
        return new BlockInspector(schematic,resources).inspect(best);
    }
    private static double rayBox(float[] bounds,double[] origin,double[] direction,double maximum) {
        double near=0,far=maximum;
        for(int axis=0;axis<3;axis++) {
            if(!Double.isFinite(origin[axis]))throw new IllegalArgumentException("无效的点选坐标");
            if(Math.abs(direction[axis])<1e-12){if(origin[axis]<bounds[axis]||origin[axis]>bounds[axis+3])return Double.POSITIVE_INFINITY;continue;}
            double a=(bounds[axis]-origin[axis])/direction[axis],b=(bounds[axis+3]-origin[axis])/direction[axis];near=Math.max(near,Math.min(a,b));far=Math.min(far,Math.max(a,b));
            if(near>far)return Double.POSITIVE_INFINITY;
        }
        return near;
    }

    public synchronized Map<String,Object> skinSettings() {
        return Map.of("online",io.github.litematicrender.render.skin.SkinResolver.onlineEnabled(this.home));
    }
    public synchronized Map<String,Object> setSkinOnline(boolean online) throws IOException {
        io.github.litematicrender.render.skin.SkinResolver.setOnline(this.home,online);
        this.scene = null; this.sceneKey = null;
        return skinSettings();
    }
    public synchronized Map<String,Object> importSkin(String identity, Path file) throws IOException {
        io.github.litematicrender.render.skin.SkinResolver.importLocal(this.home,identity,file);
        this.scene = null; this.sceneKey = null;
        return Map.of("imported",true);
    }

    /** Default resource pack shipped with the app. */
    public Path defaultPackRoot() {
        return this.home.resolve("assets/vanilla");
    }

    /**
     * Packs bundled with the app, loaded on top of vanilla and below anything the user imports.
     *
     * <p>They live in {@code assets/builtin/} as standard zips (or folders) with the usual
     * {@code assets/<namespace>/...} layout. A pack only needs to cover the blocks it changes —
     * anything missing falls through to vanilla.
     */
    /**
     * Builtin packs that exist purely to make the renderer work — static model support (FastChest)
     * and entity textures for the block-entity pass. They are infrastructure rather than look packs,
     * so the UI hides them; the two real built-in look packs stay listed.
     */
    /** Every builtin pack, infrastructure included — this is the resource stack to load. */
    public List<Path> builtinPacks() {
        return io.github.litematicrender.resource.BuiltinPacks.list(this.home);
    }

    /** The builtin packs worth showing in the UI: the look packs, not the plumbing. */
    public List<Path> visibleBuiltinPacks() {
        return builtinPacks().stream()
            .filter(io.github.litematicrender.resource.BuiltinPacks::visible)
            .toList();
    }

    /** Loads (or reloads) the resource stack: vanilla, then built-ins, then the given pack root. */
    /**
     * Loads the resource stack: vanilla, then built-in packs, then user-imported packs.
     *
     * @param packRoot vanilla root, i.e. a directory containing {@code assets/}
     * @param imports  user pack paths (zip or folder) in priority order, highest last
     */
    public synchronized Map<String, Object> loadResources(Path packRoot, List<String> imports) throws Exception {
        long resourceStart=System.nanoTime(),resourceStep=resourceStart;
        Map<String,Object> timings=new LinkedHashMap<>();
        List<Path> builtin = builtinPacks();
        List<Path> user = imports == null ? List.of() : imports.stream().map(Path::of).toList();
        String fingerprint=CacheFingerprint.resources(packRoot,builtin,user);
        timings.put("fingerprintMillis",(System.nanoTime()-resourceStep)/1e6);resourceStep=System.nanoTime();
        boolean same = this.resources != null && packRoot.equals(this.loadedPackRoot)
            && builtin.equals(this.loadedBuiltins) && user.equals(this.loadedUserPacks)&&fingerprint.equals(this.loadedResourceFingerprint);
        if (same) {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("blockKinds", this.registry.blockCount());
            hit.put("sprites", this.atlas.spriteCount());
            hit.put("atlasWidth", this.atlas.stitched.width);
            hit.put("atlasHeight", this.atlas.stitched.height);
            hit.put("builtinPacks", this.loadedBuiltinNames);
            hit.put("importedPacks", this.loadedUserPackNames);
            hit.put("cached", true);
            hit.put("timings",timings);
            return hit;
        }
        closeResources();
        ResourceManager res = new ResourceManager(packRoot);
        // Built-ins sit above vanilla, user packs above built-ins, so an import wins over a built-in.
        List<String> builtinNames = new ArrayList<>();
        for (Path p : builtin) {
            res.importPackUnchecked(p);
        }
        // Report only the look packs; the plumbing packs load silently above.
        for (Path p : visibleBuiltinPacks()) {
            builtinNames.add(p.getFileName().toString());
        }
        List<String> userNames = new ArrayList<>();
        for (Path p : user) {
            res.importPackUnchecked(p);
            userNames.add(p.getFileName().toString());
        }
        timings.put("packMillis",(System.nanoTime()-resourceStep)/1e6);resourceStep=System.nanoTime();
        BlockRegistry reg = BlockRegistry.load(res);
        timings.put("registryMillis",(System.nanoTime()-resourceStep)/1e6);resourceStep=System.nanoTime();
        if(rendererFingerprint==null)rendererFingerprint=CacheFingerprint.tree(home.resolve("bin"));
        String imageKey=CacheFingerprint.parts("resource-images-v1",rendererFingerprint,fingerprint);
        var images=io.github.litematicrender.render.ResourceImageCache.load(home,imageKey,res);
        Colormaps cm=images.colors();SpriteAtlas at=images.atlas();
        timings.put("imageMillis",(System.nanoTime()-resourceStep)/1e6);
        this.resources = res;
        this.registry = reg;
        this.colormaps = cm;
        this.atlas = at;
        this.baker = new ModelBaker(res, reg);
        this.loadedPackRoot = packRoot;
        this.loadedBuiltins = builtin;
        this.loadedBuiltinNames = builtinNames;
        this.loadedUserPacks = user;
        this.loadedUserPackNames = userNames;
        this.loadedResourceFingerprint=fingerprint;
        // A different pack invalidates any materialised schematic.
        this.schematic = null;
        this.schematicPath = null;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("blockKinds", reg.blockCount());
        out.put("sprites", at.spriteCount());
        out.put("atlasWidth", at.stitched.width);
        out.put("atlasHeight", at.stitched.height);
        out.put("builtinPacks", builtinNames);
        out.put("importedPacks", userNames);
        out.put("cached", false);
        out.put("imageCacheHit",images.hit());
        timings.put("totalMillis",(System.nanoTime()-resourceStart)/1e6);out.put("timings",timings);
        return out;
    }

    /** Convenience form with no user imports. */
    public synchronized Map<String, Object> loadResources(Path packRoot) throws Exception {
        return loadResources(packRoot, List.of());
    }

    /** Reads a .litematic and reports its metadata; caches the parse for the next render. */
    public synchronized Map<String, Object> open(Path file) throws Exception {
        ensureResources();
        selectedScene=null;selectedSceneKey=null;
        LitematicaSchematic schem = loadSchematic(file);
        LitematicaSchematic.Metadata md = schem.metadata();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", file.toAbsolutePath().toString());
        out.put("name", md.name);
        out.put("author", md.author);
        out.put("description", md.description);
        out.put("regionCount", md.regionCount);
        out.put("totalBlocks", md.totalBlocks);
        out.put("totalVolume", md.totalVolume);
        Vec3i size = md.enclosingSize;
        out.put("size", Map.of("x", size.x(), "y", size.y(), "z", size.z()));
        out.put("schematicVersion", md.schematicVersion);
        out.put("minecraftDataVersion", md.minecraftDataVersion);
        out.put("hasPreviewImage", md.previewImageData != null && md.previewImageData.length > 0);

        java.util.List<Map<String, Object>> regions = new java.util.ArrayList<>();
        for (var e : schem.regions().entrySet()) {
            var r = e.getValue();
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("name", e.getKey());
            rm.put("size", Map.of("x", r.blocks.sizeX, "y", r.blocks.sizeY, "z", r.blocks.sizeZ));
            rm.put("position", Map.of("x", r.position.x(), "y", r.position.y(), "z", r.position.z()));
            rm.put("paletteSize", r.blocks.palette.length);
            rm.put("blockEntities", r.blockEntities.size());
            regions.add(rm);
        }
        out.put("regions", regions);
        return out;
    }

    /** Renders the open schematic to a PNG. */
    public synchronized Map<String, Object> render(Path outPng, RenderSettings settings, boolean reportDiagnostics)
            throws Exception {
        ensureResources();
        if (this.schematic == null) {
            throw new IllegalStateException("no schematic open; call open first");
        }
        this.cancelled.set(false);
        RenderDiagnostics.reset();
        try {
        long t0 = System.currentTimeMillis();
        SceneMesh mesh = sceneMesh(settings.realLighting,settings.lightingEnabled);
        settings.selectionMesh=selectionScene(settings);
        // "interactive" trades resolution for latency: see SchematicRenderer.interactiveScale.
        RenderSettings effective = settings.interactiveMaxEdge > 0
            ? SchematicRenderer.interactiveScale(settings, settings.interactiveMaxEdge)
            : settings;
        Image image = SchematicRenderer.render(this.schematic, mesh, this.colormaps,
            this.atlas, effective);
        long t1 = System.currentTimeMillis();
        image.writePng(outPng);
        long t2 = System.currentTimeMillis();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", outPng.toAbsolutePath().toString());
        out.put("width", image.width);
        out.put("height", image.height);
        out.put("renderMillis", t1 - t0);
        out.put("sceneCached", this.sceneFromMesh);
        out.put("encodeMillis", t2 - t1);
        if (reportDiagnostics) {
            Map<String, Long> diag = RenderDiagnostics.snapshot();
            if (!diag.isEmpty()) out.put("unresolved", diag);
        }
        return out;
        }finally{if(streamIndex!=null){scene=null;sceneKey=null;pickedScene=null;picker=null;}}
    }

    /**
     * The scene for the open schematic, rebuilt only when something it depends on changes.
     *
     * <p>The key covers everything that feeds the bake: which file is open, and which resource
     * packs are stacked. Anything camera-related (rotation, slant, size) is deliberately absent —
     * that is the whole point, since those are exactly what a drag changes.
     */
    private SceneMesh sceneMesh() { return sceneMesh(this.sceneRealLighting,this.sceneLightingEnabled); }

    private SceneMesh sceneMesh(boolean realLighting) { return sceneMesh(realLighting,true); }

    private SceneMesh sceneMesh(boolean realLighting,boolean lightingEnabled) {
        SceneBuilder preparedBuilder=streamBuilder!=null&&streamRealLighting==realLighting&&streamLightingEnabled==lightingEnabled?streamBuilder:null;
        this.sceneLightingEnabled=lightingEnabled;
        this.sceneRealLighting=realLighting;
        String key = this.schematicPath + "|" + this.loadedBuiltinNames + "|" + this.loadedUserPackNames
            + "|" + System.identityHashCode(this.baker) + "|" + realLighting + "|" + lightingEnabled;
        if (this.scene != null && key.equals(this.sceneKey)) {
            this.sceneFromMesh = true;
            return this.scene;
        }
        SceneBuilder builder = preparedBuilder!=null?preparedBuilder:new SceneBuilder(this.registry, this.baker, this.colormaps, this.atlas).realLighting(realLighting).lightingEnabled(lightingEnabled);
        if(preparedBuilder==null&&streamIndex!=null&&streamSkins!=null)builder.skinSnapshot(streamSkins);
        this.pickedScene=null;this.picker=null;this.inspector=null;
        if(preparedBuilder==null)for (var e : this.schematic.regions().entrySet()) {
            var r = e.getValue();
            var origin=r.blockOrigin();builder.addRegion(r, new int[]{origin.x(),origin.y(),origin.z()});
        }
        this.scene = builder.build();
        this.sceneBuilder=builder;
        this.sceneKey = key;
        this.sceneFromMesh = false;
        return this.scene;
    }

    private SceneMesh selectionScene(RenderSettings settings){
        if(settings.selectionMin==null){selectedScene=null;selectedSceneKey=null;return null;}
        if(streamIndex!=null&&streamBuilder==null&&streamRealLighting==settings.realLighting&&streamLightingEnabled==settings.lightingEnabled){
            streamBuilder=new SceneBuilder(registry,baker,colormaps,atlas).realLighting(streamRealLighting).lightingEnabled(streamLightingEnabled);
            if(streamSkins!=null)streamBuilder.skinSnapshot(streamSkins);
            for(var region:schematic.regions().values()){var p=region.blockOrigin();streamBuilder.addRegion(region,new int[]{p.x(),p.y(),p.z()});}
            streamBuilder.prepareStreaming();
        }
        SceneBuilder source=streamBuilder!=null&&streamRealLighting==settings.realLighting&&streamLightingEnabled==settings.lightingEnabled?streamBuilder:null;
        if(source==null){sceneMesh(settings.realLighting,settings.lightingEnabled);source=sceneBuilder;}
        String key=schematicDigest+"|"+System.identityHashCode(source)+"|"+java.util.Arrays.toString(settings.selectionMin)+"|"+java.util.Arrays.toString(settings.selectionMax);
        if(!key.equals(selectedSceneKey)){selectedScene=null;selectedScene=source.buildSelection(settings.selectionMin,settings.selectionMax);selectedSceneKey=key;}
        return selectedScene;
    }
    public synchronized Map<String,Object> exportSelectionScene(Path atlasOut,Path meshOut,RenderSettings settings)throws Exception{
        ensureResources();if(schematic==null)throw new IllegalStateException("请先打开投影");if(settings.selectionMin==null)throw new IllegalArgumentException("请选择渲染范围");
        long start=System.currentTimeMillis();SceneMesh mesh=selectionScene(settings);atlas.rebakeAtTick(0);atlas.stitched.writePng(atlasOut);int vertices=MeshExporter.write(mesh,atlas,meshOut);
        return Map.of("atlas",atlasOut.toAbsolutePath().toString(),"mesh",meshOut.toAbsolutePath().toString(),"meshFormat","LMESH4","quads",mesh.quadCount,"vertices",vertices,"exportMillis",System.currentTimeMillis()-start);
    }

    /** Renders without writing a file; used for fast previews of a crop or a thumbnail. */
    public synchronized long renderStamp(Path outPng, RenderSettings settings) throws Exception {
        return ((Number) render(outPng, settings, false).get("renderMillis")).longValue();
    }

    /**
     * Exports the scene for the WebGL viewer: the atlas as a texture, and the mesh as a vertex
     * buffer.
     *
     * <p>Everything camera-independent is resolved here — models, lighting, ambient occlusion,
     * layer assignment, translucent subdivision — so the viewer only ever re-projects vertices.
     * That is the division of labour the game uses, and the reason quickcraft's preview drags
     * smoothly: it draws from resident buffers and changes nothing but the model-view matrix.
     */
    public synchronized Map<String, Object> exportScene(Path atlasOut, Path meshOut) throws Exception {
        return exportScene(atlasOut,meshOut,false);
    }

    public synchronized Map<String, Object> exportScene(Path atlasOut, Path meshOut, boolean force) throws Exception {
        return exportScene(atlasOut,meshOut,force,false);
    }

    public synchronized Map<String, Object> exportScene(Path atlasOut, Path meshOut, boolean force, boolean realLighting) throws Exception {
        return exportScene(atlasOut,meshOut,force,realLighting,true);
    }
    public synchronized Map<String,Object> exportScene(Path atlasOut,Path meshOut,boolean force,boolean realLighting,boolean lightingEnabled)throws Exception {
        clearStream();
        ensureResources();
        if (this.schematic == null) {
            throw new IllegalStateException("no schematic open; call open first");
        }
        if(force){this.scene=null;this.sceneKey=null;}
        long buildStart=System.currentTimeMillis();
        SceneMesh mesh = sceneMesh(realLighting,lightingEnabled);
        long t0 = System.currentTimeMillis();
        this.atlas.stitched.writePng(atlasOut);
        int vertices = MeshExporter.write(mesh, this.atlas, meshOut);
        long t1 = System.currentTimeMillis();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("atlas", atlasOut.toAbsolutePath().toString());
        out.put("mesh", meshOut.toAbsolutePath().toString());
        out.put("atlasWidth", this.atlas.stitched.width);
        out.put("atlasHeight", this.atlas.stitched.height);
        out.put("vertices", vertices);
        out.put("skins",mesh.skinReport);
        out.put("entities",mesh.entityReport);
        out.put("quads", vertices / 4);
        out.put("exportMillis", t1 - t0);
        out.put("sceneBuildMillis",t0-buildStart);
        out.put("sceneCached",this.sceneFromMesh);
        out.put("realLighting",realLighting);
        out.put("meshBytes", java.nio.file.Files.size(meshOut));
        float[] b = sceneBounds(mesh);
        out.put("min", List.of(b[0], b[1], b[2]));
        out.put("max", List.of(b[3], b[4], b[5]));
        return out;
    }

    /** GPU preview path: retain the compressed world and lighting, never the complete face graph. */
    public synchronized Map<String,Object> exportStreamScene(Path atlasOut,Path meshOut,boolean force,boolean realLighting)throws Exception {
        return exportStreamScene(atlasOut,meshOut,force,realLighting,true);
    }
    public synchronized Map<String,Object> exportStreamScene(Path atlasOut,Path meshOut,boolean force,boolean realLighting,boolean lightingEnabled)throws Exception {
        return exportStreamScene(atlasOut,meshOut,force,realLighting,lightingEnabled,null,null,null);
    }
    private Map<String,Object> exportStreamScene(Path atlasOut,Path meshOut,boolean force,boolean realLighting,boolean lightingEnabled,io.github.litematicrender.render.skin.SkinResolver snapshot,io.github.litematicrender.render.scene.OverviewMesh overview,StreamMeshExporter.Progress progress)throws Exception {
        ensureResources();if(schematic==null)throw new IllegalStateException("请先打开投影");
        String key=schematicPath+"|"+schematicStamp+"|"+System.identityHashCode(baker)+"|"+realLighting+"|"+lightingEnabled+"|"+meshOut.toAbsolutePath().normalize();
        boolean cached=!force&&key.equals(streamKey)&&streamIndex!=null&&Files.isRegularFile(meshOut);
        long begin=System.currentTimeMillis();
        if(!cached) {
            scene=null;sceneKey=null;sceneBuilder=null;selectedScene=null;selectedSceneKey=null;picker=null;pickedScene=null;clearStream();
            var builder=new SceneBuilder(registry,baker,colormaps,atlas).realLighting(realLighting).lightingEnabled(lightingEnabled).overview(overview);
            if(snapshot!=null)builder.skinSnapshot(snapshot);
            if(progress!=null)builder.preparationProgress(progress::stage);
            for(var region:schematic.regions().values()){var origin=region.blockOrigin();builder.addRegion(region,new int[]{origin.x(),origin.y(),origin.z()});}
            var index=StreamMeshExporter.write(builder,atlas,meshOut,progress);
            streamBuilder=builder;streamIndex=index;streamKey=key;streamRealLighting=realLighting;sceneRealLighting=realLighting;streamLightingEnabled=lightingEnabled;sceneLightingEnabled=lightingEnabled;streamSkins=snapshot;
        }
        if(progress==null)atlas.stitched.writePng(atlasOut);var index=streamIndex;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("atlas",atlasOut.toAbsolutePath().toString());result.put("mesh",meshOut.toAbsolutePath().toString());result.put("meshFormat","LMESH7");
        result.put("atlasWidth",atlas.stitched.width);result.put("atlasHeight",atlas.stitched.height);result.put("quads",index.quads());result.put("vertices",index.quads()*4);
        result.put("skins",index.skins());result.put("entities",index.entities());result.put("sceneCached",cached);result.put("realLighting",realLighting);
        result.put("exportMillis",System.currentTimeMillis()-begin);result.put("sceneBuildMillis",System.currentTimeMillis()-begin);result.put("meshBytes",index.bytes());
        result.put("sections",index.sections().size());result.put("lodLevels",4);result.put("vertexBytes",20);result.put("maximumSectionQuads",index.maximumSectionQuads());result.put("fullMeshResident",false);result.put("coarseOverview",overview!=null);
        var b=index.bounds();result.put("min",List.of(b[0],b[1],b[2]));result.put("max",List.of(b[3],b[4],b[5]));return result;
    }
    private void clearStream(){streamBuilder=null;streamIndex=null;streamKey=null;streamSkins=null;if(diskCache!=null)diskCache.protect(null);}
    private SceneCache diskCache()throws java.io.IOException {if(diskCache==null){diskCache=new SceneCache(home);rendererFingerprint=CacheFingerprint.tree(home.resolve("bin"));}return diskCache;}
    private io.github.litematicrender.render.skin.SkinResolver preparedSkins() {
        var snapshot=new io.github.litematicrender.render.skin.SkinResolver(home);var heads=new java.util.ArrayList<io.github.litematicrender.nbt.NbtCompound>();
        for(var region:schematic.regions().values()) {
            if(java.util.Arrays.stream(region.blocks.palette).noneMatch(s->s!=null&&(s.blockId.equals("minecraft:player_head")||s.blockId.equals("minecraft:player_wall_head"))))continue;
            region.blocks.forEachNonAir((x,y,z,state)->{if(state.blockId.equals("minecraft:player_head")||state.blockId.equals("minecraft:player_wall_head"))heads.add(region.blockEntities.get(LitematicaSchematic.pack(x,y,z)));});
        }
        snapshot.prepare(heads);return snapshot;
    }
    @SuppressWarnings("unchecked")
    public synchronized Map<String,Object> exportCachedStreamScene(boolean force,boolean realLighting,boolean lightingEnabled)throws Exception {
        return exportCachedStreamScene(force,realLighting,lightingEnabled,null,null);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> exportCachedStreamScene(boolean force,boolean realLighting,boolean lightingEnabled,BeforeBuild beforeBuild,PreviewJob liveJob)throws Exception {
        ensureResources();if(schematic==null)throw new IllegalStateException("请先打开投影");long begin=System.currentTimeMillis();var cache=diskCache();var snapshot=preparedSkins();
        String key=CacheFingerprint.parts("scene-cache-lmesh6-v1",rendererFingerprint,schematicDigest,loadedResourceFingerprint,Boolean.toString(realLighting),Boolean.toString(lightingEnabled),snapshot.contentFingerprint());
        clearStream();SceneCache.Entry entry=force?null:cache.load(key);
        if(entry!=null) {
            try {
                var metadata=entry.result();var index=io.github.litematicrender.render.StreamMeshIndex.read(Path.of((String)metadata.get("mesh")),snapshot.summary(),(Map<String,Object>)metadata.get("entities"));
                streamIndex=index;streamSkins=snapshot;streamRealLighting=realLighting;sceneRealLighting=realLighting;streamLightingEnabled=lightingEnabled;sceneLightingEnabled=lightingEnabled;scene=null;sceneKey=null;picker=null;pickedScene=null;cache.protect(entry.directory());
                metadata.put("skins",snapshot.summary());metadata.put("sceneBuildMillis",0);metadata.put("meshBuildCount",0);metadata.put("exportMillis",System.currentTimeMillis()-begin);metadata.put("cacheValidationMillis",System.currentTimeMillis()-begin);metadata.put("cacheStored",true);return metadata;
            }catch(Exception damaged){cache.discard(entry.directory());entry=null;}
        }
        var overview=beforeBuild!=null?beforeBuild.run():new io.github.litematicrender.render.scene.OverviewMesh(schematic,baker,atlas);
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
        Path work=cache.begin();boolean success=false;
        try {
            Path atlasFile=liveJob==null?work.resolve("atlas.png"):liveJob.directory.resolve("detail-atlas.png"),meshFile=liveJob==null?work.resolve("mesh.lmesh"):liveJob.directory.resolve("detail.lmesh");
            StreamMeshExporter.Progress progress=liveJob==null?null:new StreamMeshExporter.Progress(){
                public void stage(String stage){liveJob.stage=stage;}
                public void prepared(List<io.github.litematicrender.render.scene.SectionVisibility.Position> positions,long committed)throws IOException{
                    if(liveJob.cancelled)throw new java.io.InterruptedIOException("预览已取消");atlas.stitched.writePng(atlasFile);liveJob.total=positions.size();liveJob.committedBytes=committed;
                    var b=overview.bounds();liveJob.live=Map.of("atlas",atlasFile.toString(),"mesh",meshFile.toString(),"meshFormat","LMESH7","committedBytes",committed,"sections",positions.size(),"min",List.of(b[0],b[1],b[2]),"max",List.of(b[3],b[4],b[5]),"skins",snapshot.summary());liveJob.stage="details-building";
                }
                public void section(StreamMeshExporter.Entry entry,long committed,int completed)throws IOException{
                    if(liveJob.cancelled)throw new java.io.InterruptedIOException("预览已取消");synchronized(liveJob){liveJob.entries.add(entry);liveJob.committedBytes=committed;}
                }
                public long viewRevision(){return liveJob.viewRevision;}
                public void prioritize(List<io.github.litematicrender.render.scene.SectionVisibility.Position> pending){var view=liveJob.view;if(view!=null)view.sort(pending);}
            };
            var metadata=exportStreamScene(atlasFile,meshFile,true,realLighting,lightingEnabled,snapshot,overview,progress);
            if(liveJob!=null){linkOrCopy(atlasFile,work.resolve("atlas.png"));linkOrCopy(meshFile,work.resolve("mesh.lmesh"));}
            if(!CacheFingerprint.file(schematicPath).equals(schematicDigest)||!CacheFingerprint.resources(loadedPackRoot,loadedBuiltins,loadedUserPacks).equals(loadedResourceFingerprint))throw new java.io.IOException("投影或材质包在准备期间发生变化，请重新打开。");
            metadata.put("meshBuildCount",1);entry=cache.publish(key,work,metadata);success=true;
            var result=entry.result();result.put("meshBuildCount",1);result.put("diskCacheHit",false);result.put("exportMillis",System.currentTimeMillis()-begin);return result;
        }finally{if(!success)cache.discard(work);}
    }
    private static void linkOrCopy(Path source,Path target)throws IOException{try{Files.createLink(target,source);}catch(IOException|UnsupportedOperationException failure){Files.copy(source,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}}
    public synchronized Map<String,Object> sceneCacheStats()throws java.io.IOException{var stats=new LinkedHashMap<String,Object>(diskCache().stats());Path root=home.resolve("cache/previews");long bytes=0;if(Files.isDirectory(root))try(var files=Files.walk(root)){for(Path file:files.filter(Files::isRegularFile).toList())bytes+=Files.size(file);}stats.put("overviewTemporaryBytes",bytes);return stats;}
    public synchronized Map<String,Object> configureSceneCache(long megabytes,boolean clear)throws java.io.IOException{return diskCache().configure(megabytes,clear);}

    /** Status/cancel deliberately do not acquire the render monitor held by the background build. */
    public Map<String,Object> beginPreview(boolean force,boolean realLighting,boolean lightingEnabled) {
        cancelPreview();var job=new PreviewJob();previewJob=job;
        job.thread=Thread.ofPlatform().name("preview-"+job.id).daemon(true).unstarted(()->{
            try {synchronized(this){
                if(job.cancelled)throw new java.util.concurrent.CancellationException();
                var result=exportCachedStreamScene(force,realLighting,lightingEnabled,()->{
                    if(job.cancelled)throw new java.util.concurrent.CancellationException();job.stage="overview-building";long started=System.currentTimeMillis();
                    Path root=home.resolve("cache/previews");Files.createDirectories(root);if(!root.toRealPath().startsWith(home.toRealPath()))throw new IOException("临时总览路径不在软件目录内");prunePreviews(root);job.directory=Files.createTempDirectory(root,"preview-");
                    var overview=new io.github.litematicrender.render.scene.OverviewMesh(schematic,baker,atlas);var colorAtlas=SpriteAtlas.overview();
                    Path mesh=job.directory.resolve("mesh.lmesh"),png=job.directory.resolve("atlas.png");var index=StreamMeshExporter.write(overview,colorAtlas,mesh);colorAtlas.stitched.writePng(png);
                    if(job.cancelled)throw new java.util.concurrent.CancellationException();var b=overview.bounds();var metadata=new LinkedHashMap<String,Object>();
                    metadata.put("atlas",png.toString());metadata.put("mesh",mesh.toString());metadata.put("meshFormat","LMESH7");metadata.put("meshBytes",index.bytes());metadata.put("quads",index.quads());metadata.put("sections",index.sections().size());metadata.put("overview",true);metadata.put("overviewStep",overview.step());metadata.put("exportMillis",System.currentTimeMillis()-started);metadata.put("min",List.of(b[0],b[1],b[2]));metadata.put("max",List.of(b[3],b[4],b[5]));
                    job.overview=Map.copyOf(metadata);job.stage="full-building";return overview;
                },job);
                if(job.cancelled)throw new java.util.concurrent.CancellationException();job.full=Map.copyOf(result);job.stage="ready";
            }}catch(Throwable error){if(job.cancelled||Thread.currentThread().isInterrupted()){job.cancelled=true;job.stage="cancelled";}else{job.error=error.getMessage()==null?error.toString():error.getMessage();job.stage="failed";}}
            finally{job.finished=true;if(job.cancelled)discardPreview(job);}
        });job.thread.start();return Map.of("id",job.id);
    }
    private PreviewJob preview(String id){var job=previewJob;if(job==null||!job.id.equals(id)||job.cancelled)throw new IllegalStateException("预览任务已更新");return job;}
    public Map<String,Object> previewStatus(String id){return previewStatus(id,-1,null);}
    public Map<String,Object> previewStatus(String id,int after,Map<String,Object> view){
        var job=preview(id);if(view!=null){job.view=new PreviewPriority(view);job.viewRevision++;}
        var result=new LinkedHashMap<String,Object>();result.put("id",id);result.put("stage",job.stage);result.put("hasOverview",job.overview!=null);result.put("hasFull",job.full!=null);result.put("hasLive",job.live!=null);result.put("error",job.error);result.put("total",job.total);
        List<StreamMeshExporter.Entry> updates;int next,published;
        synchronized(job){published=job.entries.size();if(after>published||after< -1)throw new IllegalArgumentException("预览区块游标无效");next=after<0?0:Math.min(published,after+256);updates=after<0?List.of():List.copyOf(job.entries.subList(after,next));result.put("committedBytes",job.committedBytes);}
        result.put("published",published);result.put("next",next);result.put("sections",updates.stream().map(StreamMeshExporter::describe).toList());return result;
    }
    public Map<String,Object> previewLive(String id){var job=preview(id);if(job.live==null)throw new IllegalStateException("完整区块尚未开始发布");var result=new LinkedHashMap<>(job.live);result.put("committedBytes",job.committedBytes);return result;}
    public Map<String,Object> previewResult(String id,boolean overview){var job=preview(id);var result=overview?job.overview:job.full;if(result==null)throw new IllegalStateException("此档预览尚未就绪");return result;}
    public Map<String,Object> cancelPreview(){var job=previewJob;if(job!=null){job.cancelled=true;var thread=job.thread;if(thread!=null)thread.interrupt();if(job.finished)discardPreview(job);}return Map.of("cancelled",true);}
    private void discardPreview(PreviewJob job){Path path=job.directory;if(path==null)return;Path root=home.resolve("cache/previews").toAbsolutePath().normalize();path=path.toAbsolutePath().normalize();if(!root.equals(path.getParent())||!path.getFileName().toString().startsWith("preview-"))return;try{List<Path> paths;try(var walk=Files.walk(path)){paths=walk.sorted(java.util.Comparator.reverseOrder()).toList();}for(Path p:paths)if(!p.toRealPath().startsWith(root.toRealPath()))return;for(Path p:paths)Files.deleteIfExists(p);}catch(IOException ignored){}}
    private void prunePreviews(Path root)throws IOException{try(var files=Files.list(root)){for(Path directory:files.filter(p->p.getFileName().toString().startsWith("preview-")).toList())if(System.currentTimeMillis()-Files.getLastModifiedTime(directory).toMillis()>24L*60*60*1000){var expired=new PreviewJob();expired.directory=directory;discardPreview(expired);}}}

    /** Axis-aligned bounds of every quad: minX, minY, minZ, maxX, maxY, maxZ. */
    private static float[] sceneBounds(SceneMesh mesh) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (List<SceneMesh.MeshQuad> layer : List.of(mesh.solid, mesh.cutout,
                mesh.portalSorted, mesh.fluidSorted, mesh.translucentSorted)) {
            for (SceneMesh.MeshQuad q : layer) {
                if(q.decorativeEffect())continue;
                minX = Math.min(minX, Math.min(Math.min(q.x0, q.x1), Math.min(q.x2, q.x3)));
                maxX = Math.max(maxX, Math.max(Math.max(q.x0, q.x1), Math.max(q.x2, q.x3)));
                minY = Math.min(minY, Math.min(Math.min(q.y0, q.y1), Math.min(q.y2, q.y3)));
                maxY = Math.max(maxY, Math.max(Math.max(q.y0, q.y1), Math.max(q.y2, q.y3)));
                minZ = Math.min(minZ, Math.min(Math.min(q.z0, q.z1), Math.min(q.z2, q.z3)));
                maxZ = Math.max(maxZ, Math.max(Math.max(q.z0, q.z1), Math.max(q.z2, q.z3)));
            }
        }
        return new float[]{minX, minY, minZ, maxX, maxY, maxZ};
    }


    /**
     * Renders one full camera revolution while advancing animated texture ticks.
     */
    public synchronized Map<String, Object> renderAnimation(Path outGif, RenderSettings settings,
                                                            int ticks, int delayCs) throws Exception {
        return renderAnimation(outGif,settings,ticks,delayCs,(stage,completed,total)->{});
    }

    public synchronized Map<String,Object> renderAnimation(Path outGif,RenderSettings settings,int ticks,int delayCs,GifWriter.Progress progress)throws Exception {
        ensureResources();
        if (this.schematic == null) {
            throw new IllegalStateException("no schematic open; call open first");
        }
        RenderDiagnostics.reset();
        try {
        long t0 = System.currentTimeMillis();
        progress.update("building",0,Math.max(2,Math.min(RenderSettings.MAX_ANIMATION_FRAMES,ticks)));
        SceneMesh mesh=sceneMesh(settings.realLighting,settings.lightingEnabled);
        settings.selectionMesh=selectionScene(settings);
        long buildMillis=System.currentTimeMillis()-t0;
        int frameCount=Math.max(2,Math.min(RenderSettings.MAX_ANIMATION_FRAMES,ticks));
        var camera=SchematicRenderer.fitRotationCamera(mesh,settings);
        var cycle=this.atlas.animationCycle(mesh,frameCount);
        long[] renderingNanos={0};
        GifWriter.writeFrames(outGif,frameCount,delayCs,i -> {
            long renderStart=System.nanoTime();
            RenderSettings s = new RenderSettings(settings.rotationDeg+i*360f/frameCount, settings.slantDeg,
                settings.width, settings.height);
            s.transparentBackground = settings.transparentBackground;
            s.backgroundColor = settings.backgroundColor;
            s.backgroundMode = settings.backgroundMode;
            s.backgroundImage = settings.backgroundImage;
            s.checkerSize = settings.checkerSize;
            s.ambientShadeScale = settings.ambientShadeScale;
            s.lightingEnabled = settings.lightingEnabled;
            s.realLighting = settings.realLighting;
            s.selectionMin=settings.selectionMin;s.selectionMax=settings.selectionMax;
            s.selectionMesh=settings.selectionMesh;
            s.animationTick = settings.animationTick + (int)(i * (long)delayCs / 5);
            camera.rotationDeg=s.rotationDeg;
            camera.update();
            Image frame=SchematicRenderer.renderWithCamera(mesh,this.atlas,s,camera);
            renderingNanos[0]+=System.nanoTime()-renderStart;
            return frame;
        },progress);
        long t2 = System.currentTimeMillis();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", outGif.toAbsolutePath().toString());
        out.put("width", settings.width);
        out.put("height", settings.height);
        out.put("frames", frameCount);
        out.put("animationPeriodTicks", cycle.complete()?cycle.frames():0);
        out.put("cameraRevolutions", 1);
        out.put("delayCentiseconds", delayCs);
        out.put("sceneBuildMillis",buildMillis);
        out.put("renderMillis", buildMillis+renderingNanos[0]/1_000_000);
        out.put("encodeMillis", Math.max(0,t2-t0-buildMillis-renderingNanos[0]/1_000_000));
        out.put("bytes", java.nio.file.Files.size(outGif));
        Map<String, Long> diag = RenderDiagnostics.snapshot();
        if (!diag.isEmpty()) out.put("unresolved", diag);
        return out;
        }finally{if(streamIndex!=null){scene=null;sceneKey=null;pickedScene=null;picker=null;}}
    }

    public void cancel() {
        this.cancelled.set(true);
    }

    public boolean isCancelled() {
        return this.cancelled.get();
    }

    // ------------------------------------------------------------------

    private void ensureResources() throws Exception {
        if (this.resources == null) {
            loadResources(defaultPackRoot());
        }
    }

    private LitematicaSchematic loadSchematic(Path file) throws Exception {
        String digest=CacheFingerprint.file(file);
        long stamp = java.nio.file.Files.getLastModifiedTime(file).toMillis()
            ^ java.nio.file.Files.size(file);
        if (this.schematic != null && file.equals(this.schematicPath) && digest.equals(this.schematicDigest)) {
            return this.schematic;
        }
        LitematicaSchematic schem = LitematicaSchematic.load(file);
        clearStream();
        scene=null;sceneKey=null;sceneBuilder=null;selectedScene=null;selectedSceneKey=null;picker=null;pickedScene=null;
        schem.materialize(this.registry);
        this.schematic = schem;
        this.schematicPath = file;
        this.schematicStamp = stamp;
        this.schematicDigest=digest;
        return schem;
    }

    private void closeResources() {
        clearStream();scene=null;sceneKey=null;sceneBuilder=null;selectedScene=null;selectedSceneKey=null;
        if (this.resources != null) {
            this.resources.close();
            this.resources = null;
        }
        this.registry = null;
        this.colormaps = null;
        this.atlas = null;
        this.baker = null;
        this.pickedScene=null;this.picker=null;this.inspector=null;
        this.loadedPackRoot = null;
    }

    @Override
    public void close() {
        cancelPreview();
        synchronized(this){closeResources();}
    }
}
