'use strict';

/**
 * Renderer UI.
 *
 * Talks to the Java engine only through the preload bridge. Camera changes stay on the GPU;
 * "导出 PNG" re-renders at the selected resolution and writes the file.
 *
 * The drag/zoom interaction follows quickcraft's preview: 0.015 radians per pixel for rotation
 * (with pitch clamped to ±85 degrees) and an exponential zoom of exp(scroll * 0.12).
 */

const $ = (id) => document.getElementById(id);

const els = {
  materials:$('btnMaterials'), menuMaterials:$('menuMaterials'),
  status: $('engineStatus'),
  dot: document.querySelector('.brand .dot'),
  open: $('btnOpen'),
  fileInfo: $('fileInfo'),
  skinOnline: $('skinOnline'),
  skinPlayer: $('skinPlayer'),
  skinImport: $('skinImport'),
  skinInfo: $('skinInfo'),
  realLighting: $('realLighting'), lightingEnabled:$('lightingEnabled'),
  largeProjection:$('largeProjection'), showCoordinates:$('showCoordinates'), coordinates:$('coordinates'),
  lodPreview: $('lodPreview'),streamInfo: $('streamInfo'),
  geometryBudget:$('geometryBudget'),
  preloadMode:$('preloadMode'),preload:$('btnPreload'),preloadInfo:$('preloadInfo'),
  viewDistance:$('viewDistance'),viewDistanceNumber:$('viewDistanceNumber'),viewDistanceVal:$('viewDistanceVal'),
  previewBackend: $('previewBackend'), previewBackendInfo: $('previewBackendInfo'),
  rotation: $('rotation'),
  rotationVal: $('rotationVal'),
  slant: $('slant'),
  slantVal: $('slantVal'),
  zoom: $('zoom'),
  zoomVal: $('zoomVal'),
  size: $('size'),
  transparent: $('transparent'),
  bg: $('bg'),
  render: $('btnRender'),
  export: $('btnExport'),
  exportGif: $('btnExportGif'),
  preview: $('preview'),
  gl: $('gl'),
  flyBtn: $('btnFly'),
  flyHint: $('flyHint'),
  flyMouseHint: $('flyMouseHint'), crosshair: $('crosshair'),
  placeholder: $('placeholder'),
  viewport: $('viewport'),
  busy: $('busy'),
  busyText: $('busyText'),
  log: $('log'),
  stats: $('stats'),
  bgMode: $('bgMode'),
  sidebar: $('sidebar'),
  sidebarPanel: $('sidebarPanel'),
  toggleSidebar: $('btnToggleSidebar'),
  importBackground: $('btnImportBackground'),
  fullscreen: $('btnFullscreen'),
  welcome: $('welcomeDialog'),
  welcomeClose: $('welcomeClose'),
  welcomeNever: $('welcomeNever'),
  authorLink: $('authorLink'),
  welcomeError: $('welcomeError'),
  inspector: $('blockInspector'), inspectClose: $('inspectClose'), inspectName: $('inspectName'),
  inspectStateTitle: $('inspectStateTitle'),
  inspectId: $('inspectId'), inspectPosition: $('inspectPosition'), inspectProperties: $('inspectProperties'),
  inspectInventory: $('inspectInventory'), inspectInventoryStatus: $('inspectInventoryStatus'), inspectItems: $('inspectItems'),
  inspectItemDetails: $('inspectItemDetails'), inspectItemName: $('inspectItemName'), inspectItemData: $('inspectItemData'),
  inspectDataDetails: $('inspectDataDetails'), inspectData: $('inspectData'),
  packList: $('packList'),
  menuFile: $('menuFile'),
  menuFileDrop: $('menuFileDrop'),
  menuOpenSchematic: $('menuOpenSchematic'),
  menuImportPack: $('menuImportPack'),
  menuExportPng: $('menuExportPng'),
  menuExportGif: $('menuExportGif'),
  menuQuit: $('menuQuit'),
};

const state = {
  file: null,
  info: null,
  lastResult: null,
  previewGeneration: 0,
  rendering: false,
  zoom: 1,
  packs: [],
  /** WebGL viewer; once ready, camera changes never reach the JVM. */
  view: null,
  viewReady: false,
  previewBackend: 'gpu', cpuPreview: false, cpuPreviewReady: false,
  lodPreview: true, largeProjection:false,
  geometryBudgetMB:256,
  preloadMode:'streaming',
  viewDistance:6,
  /** Fly (inside-the-build) mode state. */
  fly: false,
  flyPos: [0, 0, 0],
  flyYaw: 0,
  flyPitch: 0,
  keys: new Set(),
  animHandle: 0,
  flyDirty: false, flyTime: null, flyLookActive: false, flyLockPending: false, flyLockGeneration: 0,
  flyDiscardLockDelta: false,
  background: null,
  fullscreen: false,
  sidebarCollapsed: false,
  inspectGeneration: 0,
  inspectedBlock: null,
};

const PREVIEW_FULL_SIZE = 2048;
/**
 * While dragging, the preview renders small so it keeps up with the cursor; the full-size render
 * follows on release. Back-face culling means this is only about fill rate now — the geometry cost
 * is paid once per camera change regardless of resolution.
 */
const INTERACTIVE_MAX_EDGE = 512;

// --------------------------------------------------------------------- log

function log(message, cls) {
  const line = document.createElement('div');
  if (cls) line.className = cls;
  const t = new Date().toLocaleTimeString('zh-CN', { hour12: false });
  line.textContent = `[${t}] ${message}`;
  els.log.appendChild(line);
  els.log.scrollTop = els.log.scrollHeight;
  while (els.log.childElementCount > 300) els.log.removeChild(els.log.firstChild);
}

function setStatus(text, cls) {
  els.status.textContent = text;
  els.dot.className = 'dot' + (cls ? ' ' + cls : '');
}

function setBusy(on, text) {
  state.rendering = on;
  els.busy.classList.toggle('hidden', !on);
  if (text) els.busyText.textContent = text;
  updateButtons();
}

function updateButtons() {
  const busy=state.rendering||state.scenePreparing;
  const ready = !!state.file && !busy;
  els.render.disabled = !ready;
  els.materials.disabled=els.menuMaterials.disabled=!ready;
  els.export.disabled = !ready;
  els.exportGif.disabled = !ready;
  els.menuExportPng.disabled = !ready;
  els.menuExportGif.disabled = !ready;
  els.skinOnline.disabled = busy||state.scenePreparing;
  els.skinImport.disabled = busy||state.scenePreparing;
  els.skinPlayer.disabled = busy||state.scenePreparing;
  els.realLighting.disabled = busy || !els.lightingEnabled.checked;
  els.lightingEnabled.disabled = busy;
  els.largeProjection.disabled=busy||state.previewBackend==='cpu';
  els.previewBackend.disabled = busy;
  els.lodPreview.disabled = busy || state.previewBackend === 'cpu'||!state.largeProjection;
  els.geometryBudget.disabled=busy||state.previewBackend==='cpu'||!state.largeProjection;
  els.preloadMode.disabled=busy||state.previewBackend==='cpu'||!state.largeProjection;
  els.preload.disabled=els.preloadMode.disabled||!state.viewReady||state.scenePreparing||state.preloadMode==='streaming';
  els.viewDistance.disabled=els.viewDistanceNumber.disabled=busy||state.previewBackend==='cpu';
  els.flyBtn.disabled = state.rendering || !state.viewReady || state.cpuPreview;
  els.menuImportPack.disabled = busy||state.scenePreparing;
  $('sceneCacheLimit').disabled=$('sceneCacheClear').disabled=busy||state.scenePreparing;
  updatePackButtons();
}

// ------------------------------------------------------------------ engine

function renderParams(width, height) {
  const transparent = els.bgMode.value === 'checker' || els.transparent.checked;
  const params = {
    rotation: Number(els.rotation.value),
    slant: Number(els.slant.value),
    width,
    height,
    transparent,
    realLighting: els.realLighting.checked,
    lightingEnabled: els.lightingEnabled.checked,
  };
  if (!transparent) {
    params.backgroundMode = els.bgMode.value;
    params.background = hexToInt(cssColor(els.bgMode.value) || '#22262e');
    params.checkerSize = Math.round(10 * (window.devicePixelRatio || 1));
  }
  return params;
}

function hexToInt(hex) {
  return parseInt(hex.replace('#', ''), 16) & 0xFFFFFF;
}

/**
 * Renders a preview at the viewport's exact pixel size.
 *
 * <p>Rendering at a fixed size and letting the browser scale it destroyed texture quality: a
 * 2048px image shown in an 880px viewport is resampled by the compositor, and bilinear
 * downsampling of 16x16 pixel art produces exactly the soft, muddy look this renderer exists to
 * avoid. Sizing the render to the display area means every texel maps to a screen pixel, so the
 * texture arrives at its native sharpness. Compression is a separate, later step — only the
 * exported file is compressed.
 *
 * @param interactive when true, renders at reduced resolution so the frame returns during a drag
 */
let cpuPreviewRunning = false, cpuPreviewPending = null, lastCpuPreviewKey = null;
function invalidateCpuPreview() {
  state.previewGeneration++;
  if(cpuPreviewPending){cpuPreviewPending.resolve(null);cpuPreviewPending=null;}
  lastCpuPreviewKey=null;
}
function requestPreview(interactive, options = {}) {
  if (!state.file) return Promise.resolve(null);
  if(!state.cpuPreview&&state.previewBackend!=='cpu'){if(state.viewReady)drawView();return Promise.resolve(null);}
  if (state.viewReady && !state.cpuPreview) { drawView(); return Promise.resolve(null); }
  const generation = ++state.previewGeneration;
  const box = viewportPixelSize();
  const params = {...renderParams(box.w,box.h),transparent:true,previewZoom:state.zoom,force:options.force===true};
  delete params.background;
  if(interactive)params.interactive=INTERACTIVE_MAX_EDGE;
  const key=JSON.stringify([state.file,params]);
  if(cpuPreviewPending){cpuPreviewPending.resolve(null);cpuPreviewPending=null;}
  if(key===lastCpuPreviewKey&&!options.force){setBusy(false);return Promise.resolve(null);}
  return new Promise(resolve=>{
    cpuPreviewPending={generation,params,key,interactive,resolve,file:state.file};
    pumpCpuPreviews();
  });
}
async function pumpCpuPreviews() {
  if(cpuPreviewRunning)return;
  cpuPreviewRunning=true;
  while(cpuPreviewPending) {
    const job=cpuPreviewPending;cpuPreviewPending=null;let applied=null;
    if(!job.interactive)setBusy(true,'CPU 渲染预览…');
    try {
      const res=await window.engine.renderPreview(job.params);
      if(job.generation===state.previewGeneration&&job.file===state.file&&state.cpuPreview) {
        els.preview.src='data:image/png;base64,'+res.pngBase64;
        await els.preview.decode();
        if(job.generation===state.previewGeneration&&state.cpuPreview) {
          els.preview.classList.remove('hidden');els.gl.classList.add('hidden');els.placeholder.classList.add('hidden');
          els.preview.style.transform='';els.preview.classList.toggle('upscaled',job.interactive);
          els.stats.textContent=`CPU 预览 ${res.width}×${res.height} · 渲染 ${res.renderMillis} ms`;
          state.cpuPreviewReady=true;lastCpuPreviewKey=job.params.force?null:job.key;applied=res;
        }
      }
    } catch(error) {
      if(job.generation===state.previewGeneration){log('CPU 预览失败：'+error.message,'err');if(!state.cpuPreviewReady)els.placeholder.classList.remove('hidden');}
    } finally {
      if(job.generation===state.previewGeneration)setBusy(false);
      job.resolve(applied);
    }
  }
  cpuPreviewRunning=false;
}

/**
 * The viewport's size in device pixels, so a render maps one texel to one screen pixel.
 *
 * <p>The upper bound was removed on request so a large schematic can be debugged at whatever size
 * the viewport actually is. Only a floor remains, so a tiny window still produces something legible
 * rather than a handful of pixels.
 */
function viewportPixelSize() {
  const dpr = window.devicePixelRatio || 1;
  const rect = els.viewport.getBoundingClientRect();
  const w = Math.round(rect.width * dpr);
  const h = Math.round(rect.height * dpr);
  return {
    w: Math.max(256, w),
    h: Math.max(256, h),
  };
}

// ------------------------------------------------------------- WebGL viewer

/**
 * Prepares the WebGL viewer: the engine bakes geometry once, the browser uploads it, and every
 * later camera change is only a matrix update. That is the point of the exercise — the JVM is not
 * involved in a drag at all.
 */
let gpuBuildGeneration=0;
function previewViewPriority(){
  const view=state.view,ready=state.viewReady&&view;
  let eye=ready?view.center.slice():['x','y','z'].map(axis=>(state.info?.size?.[axis]||0)/2);
  if(ready&&state.fly)eye=state.flyPos.slice();
  else if(ready){const dir=view._viewDir(),distance=view.radius*2+32;eye=eye.map((value,i)=>value-dir[i]*distance);}
  const result={eye:eye.map(v=>Math.round(v*4)/4),orbit:!state.fly,distance:state.viewDistance};
  if(ready&&view.lastMatrix)result.planes=Array.from(frustumPlanes(view.lastMatrix));return result;
}
function updateProgressiveStatus(stats){
  if(!stats||state.scenePreparing||state.overview)return;
  setStatus(stats.running?'模型准备完成，正在载入当前视角…':stats.budgetLimited?'已显示当前预算内的细节，可调整视角或提高缓存上限。':'3D 视图已就绪。',stats.running||stats.budgetLimited?'':'ok');
}
async function buildView(options = {}) {
  const generation=++gpuBuildGeneration;
  state.overview=false;state.scenePreparing=false;
  state.livePreview=false;state.firstDetailLoaded=false;
  invalidateCpuPreview();
  if(state.view?.stream)state.view.disposeBuffers();
  if(state.previewBackend==='cpu') {
    if(state.fly)setFly(false);
    state.cpuPreview=true;state.cpuPreviewReady=false;state.viewReady=false;
    if(state.view){state.view.disposeBuffers();if(state.view.texture)state.view.gl.deleteTexture(state.view.texture);state.view.texture=null;}
    els.gl.classList.add('hidden');els.crosshair.classList.add('hidden');updatePreviewBackend();
    await window.engine.releaseStreaming();els.streamInfo.textContent='';
    els.preloadInfo.textContent='预渲染用于 GPU 分块预览。';
    const result=await requestPreview(false,{force:options.force===true});
    if(result)log(`CPU 预览就绪：${result.width}×${result.height}，渲染 ${result.renderMillis} ms。`,'ok');
    return;
  }
  state.cpuPreview=false;state.cpuPreviewReady=false;updatePreviewBackend();
  setBusy(true, state.lodPreview ? '生成分块模型…' : '准备 3D 视图…');
  let streamed = state.largeProjection;
  try {
    if(state.largeProjection) {
      const job=await window.engine.beginPreview({force:options.force===true,realLighting:els.realLighting.checked,lightingEnabled:els.lightingEnabled.checked});
      state.scenePreparing=true;state.previewJobId=job.id;updateButtons();let shown=false,live=false,sequence=0,lastPriority='',lastPriorityAt=0;
      while(generation===gpuBuildGeneration) {
        const query={after:sequence},priority=previewViewPriority(),priorityKey=JSON.stringify(priority);
        if(priorityKey!==lastPriority&&performance.now()-lastPriorityAt>=300){query.view=priority;lastPriority=priorityKey;lastPriorityAt=performance.now();}
        const status=await window.engine.previewStatus(job.id,query);
        if(generation!==gpuBuildGeneration)return;
        if(status.hasFull&&!live&&!shown) {
          const result=await window.engine.previewResult(job.id,false);
          if(generation!==gpuBuildGeneration)return;
          if(state.view?.stream)state.view.disposeBuffers();
          await applyGpuScene(result,{...options,generation,preserveCamera:shown||options.preserveCamera});return;
        }
        if(status.hasOverview&&!shown) {
          const result=await window.engine.previewResult(job.id,true);
          if(generation!==gpuBuildGeneration)return;
          await applyGpuScene(result,{...options,generation});shown=true;
          setBusy(false);els.open.disabled=false;setStatus('总览已显示，正在准备光照与贴图…','');
        }
        if(status.hasLive&&shown&&!live){
          const detail=await window.engine.previewLive(job.id);
          const bitmap=await createImageBitmap(base64ToBlob(detail.atlasDataUrl),{premultiplyAlpha:'none',colorSpaceConversion:'none'});
          if(generation!==gpuBuildGeneration){bitmap.close();return;}
          state.view.uploadAtlas(bitmap);bitmap.close();state.view.stream.beginProgressive(detail.meshTransfer.id,detail.sections);
          if(detail.skins){const s=detail.skins;els.skinInfo.textContent=`皮肤：缓存 ${s.cached} · 新下载 ${s.downloaded} · 本地 ${s.local} · 默认 ${s.fallback}`;}
          state.livePreview=true;state.overview=false;live=true;drawView();
          log('开始逐块补充完整细节，保留尚未替换的总览。');
        }
        if(live){
          state.view.stream.appendProgressive(status.sections,status.committedBytes);sequence=status.next;
          els.stats.textContent=`完整细节逐块载入 · 已生成 ${status.published.toLocaleString()} / ${status.total.toLocaleString()} 区块`;
          setStatus(`正在补充完整细节：${status.published.toLocaleString()} / ${status.total.toLocaleString()} 区块`,'');
          if(status.hasFull&&sequence===status.total){
            const result=await window.engine.previewComplete(job.id);if(generation!==gpuBuildGeneration)return;
            state.view.stream.finishProgressive(result);
            els.stats.textContent=`${result.quads.toLocaleString()} 面 · ${(result.meshBytes/1048576).toFixed(1)} MB · 完整模型已生成`+(result.entities?.saved?` · 实体 ${result.entities.rendered}`:'');
            log(`完整模型已生成：${result.quads.toLocaleString()} 面，准备 ${result.exportMillis} ms；保留已载入区块，继续补充当前视角。`,'ok');
            window.engine.previewDiagnostic(`detail generation complete: ${result.quads} faces, ${result.exportMillis} ms; GPU residents retained`).catch(()=>{});
            drawView();return;
          }
        }
        if(shown&&!live){const labels={indexing:'整理区块',lighting:'计算全局光照',sprites:'准备特殊方块贴图'};setStatus('总览已显示，'+(labels[status.stage]||'正在准备光照与贴图')+'…','');}
        if(status.stage==='failed')throw Error(status.error||'完整场景准备失败');
        if(status.stage==='cancelled')return;
        await new Promise(resolve=>setTimeout(resolve,status.next<status.published?0:150));
      }
      return;
    }
    const res = await window.engine.exportScene({force:options.force===true,realLighting:els.realLighting.checked,lightingEnabled:els.lightingEnabled.checked,streaming:false});
    if(generation!==gpuBuildGeneration)return;
    streamed=!!res.meshTransfer;await applyGpuScene(res,{...options,generation});
  } catch (err) {
    if(generation!==gpuBuildGeneration)return;
    if((state.overview||state.livePreview)&&state.viewReady){log('后续细节准备失败，保留已显示画面：'+err.message,'err');setStatus('后续细节准备失败，可重新渲染重试。','err');return;}
    state.viewReady = false;
    els.crosshair.classList.add('hidden');
    els.gl.classList.add('hidden');
    window.engine.previewDiagnostic('preview failed: '+(err?.stack||err)).catch(()=>{});
    if(streamed) {
      log('大投影载入失败：'+(err?.message||err),'err');
      setStatus('大投影载入失败，请查看日志。','err');
      els.placeholder.classList.remove('hidden');
      return;
    }
    // Surface this prominently: a silent fallback once hid a total WebGL failure behind the CPU
    // path, which looked like "WebGL does not work" rather than a bug worth reading.
    log('3D 视图不可用，回退到 CPU 渲染：' + (err && err.stack ? err.stack : err), 'err');
    setStatus('3D 视图失败：' + (err && err.message ? err.message : err), 'err');
    // Also mirror the failure into the title so it can be read without a screenshot.
    const stack = (err && err.stack ? err.stack : '').split('\n').slice(0, 4).join(' <- ');
    document.title = '投影预览 · 3D 预览不可用';
    state.cpuPreview=true;state.cpuPreviewReady=false;updatePreviewBackend();
    await requestPreview(false);
  } finally {
    if(generation===gpuBuildGeneration){state.scenePreparing=false;setBusy(false);state.previewJobId=null;updateProgressiveStatus(state.view?.stream?.stats());if(state.view?.stream?.progressComplete)refreshSceneCacheInfo().catch(()=>{});if(state.view?.stream&&state.viewReady&&!state.overview&&state.preloadMode!=='streaming')startPreload();}
  }
}


async function applyGpuScene(res,options={}) {
  state.overview=res.overview===true;
    // Decode the atlas without fetch: the page's CSP has no `data:` in connect-src, so fetching a
    // data URL is blocked. Building the Blob locally avoids the network path entirely.
    //
    // premultiplyAlpha must be 'none'. The default lets the decoder multiply RGB by alpha, so a
    // white stained-glass texel (255,255,255,163) arrives as (163,163,163,163); the shader then
    // blends that already-darkened colour and glass reads grey in 3D while the CPU path, which
    // reads the PNG's straight-alpha values, stays white. Straight alpha is what the blend
    // function (SRC_ALPHA, ONE_MINUS_SRC_ALPHA) expects.
    const atlasImage = await createImageBitmap(base64ToBlob(res.atlasDataUrl), {
      premultiplyAlpha: 'none',
      colorSpaceConversion: 'none',
    });
    const isCurrent=()=>options.generation==null||options.generation===gpuBuildGeneration;
    if(!isCurrent()){atlasImage.close();throw Error('预览任务已更新');}

    if (!state.view) state.view = new GpuView(els.gl);
    const streamed = !!res.meshTransfer;
    if(streamed)state.viewReady=false;
    const stats = ['LMESH5','LMESH6','LMESH7'].includes(res.meshFormat)
      ? await state.view.uploadLodStream((offset,length)=>window.engine.readSceneChunk(res.meshTransfer.id,offset,length),res.meshTransfer.size,{
          overview:res.overview===true,coarseOverview:res.coarseOverview===true,
          budgetBytes:state.geometryBudgetMB*1048576,
          readPage:page=>window.engine.readScenePage(page.source||res.meshTransfer.id,page),
          isCurrent,
          onChange:stats=>{
            if(!isCurrent()||!state.view?.stream||state.cpuPreview)return;
            els.streamInfo.textContent=(stats.progressive?`已生成 ${stats.publishedSections} / ${stats.totalExpected} 区块 · 当前细节 ${stats.detailResidents} 块 · `:state.overview?'总览 · 完整材质准备中 · ':'')+`已载入 ${stats.residentSections} / ${stats.requestedSections} 区块 · 几何 ${(stats.residentBytes/1048576).toFixed(1)} / ${(stats.budgetBytes/1048576).toFixed(0)} MB`+(stats.budgetLimited?' · 内存预算已满':'');
            $('pageReadInfo').textContent=`读盘 ${(stats.bytesRead/1048576).toFixed(1)} MB · 解压 ${(stats.decodedBytes/1048576).toFixed(1)} MB · 读取临时 ${(stats.transferPeakBytes/1048576).toFixed(1)} / ${(stats.transferLimitBytes/1048576).toFixed(0)} MB`;
            updatePreloadInfo(stats);
            if(stats.progressive&&stats.detailResidents&&!state.firstDetailLoaded){state.firstDetailLoaded=true;log('首批材质区块已载入，正在补充其余区块。','ok');}
            updateProgressiveStatus(stats);
            if(state.viewReady)scheduleStreamView();
          },
          onError:error=>log('分块载入失败：'+error.message,'err'),
        })
      : streamed
      ? await state.view.uploadMeshStream((offset,length)=>window.engine.readSceneChunk(res.meshTransfer.id,offset,length),res.meshTransfer.size,
        (loaded,total)=>{els.busyText.textContent=`载入大投影… ${Math.floor(loaded/Math.max(1,total)*100)}%`;})
      : state.view.uploadMesh(res.meshArrayBuffer);
    state.view.setFrameBounds(res.min,res.max);
    if(!['LMESH5','LMESH6','LMESH7'].includes(res.meshFormat)){els.streamInfo.textContent='完整模型已载入';$('pageReadInfo').textContent='';}
    state.view.uploadAtlas(atlasImage);
    atlasImage.close();
    state.viewReady = true;
    if(!res.overview)setStatus('模型数据已载入，正在准备当前视角…','');
    els.crosshair.classList.toggle('hidden',!state.fly);
    if(state.fly&&!state.animHandle)state.animHandle=requestAnimationFrame(flyTick);

    if (res.skins) {
      const s = res.skins;
      els.skinInfo.textContent = `皮肤：缓存 ${s.cached} · 新下载 ${s.downloaded} · 本地 ${s.local} · 默认 ${s.fallback}`;
    }

    els.gl.classList.remove('hidden');
    els.preview.classList.add('hidden');
    els.placeholder.classList.add('hidden');
    if (!options.preserveCamera) resetFlyToCenter();
    drawView();
    if(!res.overview)updateProgressiveStatus(state.view.stream?.stats());
    if(!res.overview&&state.preloadMode!=='streaming')startPreload();else updatePreloadInfo();
    const mib = (res.meshBytes / 1048576).toFixed(1);
    const v = state.view;
    els.stats.textContent = (res.overview?'总览 · 完整材质准备中 · ':'')+`${stats.quads.toLocaleString()} 面 · ${(stats.totalVertices / 1000).toFixed(0)}k 顶点 · ${mib} MB`+(res.entities?.saved?` · 实体 ${res.entities.rendered}`:'');
    els.stats.title=res.entities?.saved?`已显示 ${res.entities.rendered} 个指定类型实体；其他类型 ${res.entities.unsupported} 个，隐形 ${res.entities.invisible} 个。`:'';
    reportFraming();
    log(`${res.overview?'简化总览已生成':'模型数据已载入'}：${stats.quads.toLocaleString()} 面，${mib} MB，准备 ${res.exportMillis} ms`, 'ok');
    if(res.diskCacheHit)log('已复用磁盘场景缓存，跳过模型重建。','ok');if(res.cacheWarning)log(res.cacheWarning);if(!res.overview)refreshSceneCacheInfo().catch(()=>{});
    window.engine.previewDiagnostic(`preview ready: ${stats.quads} faces; chunked=${streamed}`).catch(()=>{});
}

/** Turns a `data:image/png;base64,...` URL into a Blob, without going through fetch. */
function base64ToBlob(dataUrl) {
  const comma = dataUrl.indexOf(',');
  const meta = dataUrl.slice(0, comma);
  const binary = atob(dataUrl.slice(comma + 1));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  const mime = /:(.*?);/.exec(meta);
  return new Blob([bytes], { type: mime ? mime[1] : 'image/png' });
}

/**
 * Mirrors the framing numbers into the window title.
 *
 * <p>Screenshots proved unreliable for reading these — the header truncates and the interesting
 * half is off screen — so the title carries them where a test harness can read them directly.
 */
function reportFraming() {
  const v = state.view;
  if (!v) return;
  const m = v.lastM;
  if (!m) return;
  // Push the model centre and one corner through the matrix the GPU actually received.
  const c = v.center;
  const proj = (X, Y, Z) => {
    const x = m[0] * X + m[4] * Y + m[8] * Z + m[12];
    const y = m[1] * X + m[5] * Y + m[9] * Z + m[13];
    const w2 = m[3] * X + m[7] * Y + m[11] * Z + m[15];
    return [x / w2, y / w2];
  };
  const pc = proj(c[0], c[1], c[2]);
  const p0 = proj(v.min[0], v.min[1], v.min[2]);
  const p1 = proj(v.max[0], v.max[1], v.max[2]);
  const d = v.diag || {};
  window.__framingInfo = `vpC=${(d.vpClient || []).join('x')} vpR=${(d.vpRect || []).join('x')}`
    + ` | cvC=${(d.cvClient || []).join('x')} cvR=${(d.cvRect || []).join('x')}`
    + ` | buf=${(d.buf || []).join('x')} dpr=${d.dpr}`
    + ` | fit=${(v.fitScale || 0).toFixed(2)}`
    + ` | centre=(${pc[0].toFixed(2)},${pc[1].toFixed(2)})`;
  document.title = state.info ? `${state.info.name} · 投影预览` : '投影预览';
}
/**
 * The viewer backdrop, from the 背景 selector.
 *
 * <p>A bright backdrop exists because translucent blocks — glass and water above all — composite
 * against whatever is behind them. Their textures are authored to be seen against the in-game sky,
 * which is bright; over the dark checkerboard a 46%-alpha white reads as grey, which is correct
 * compositing but useless for judging whether the texture is right. The checkerboard stays an
 * option because it is still the honest way to see which pixels are transparent.
 *
 * @returns an [r,g,b,a] clear colour, or null for transparent (the CSS checkerboard shows through)
 */
function backdropColor() {
  switch (els.bgMode.value) {
    case 'sky':  return [0.55, 0.70, 1.00, 1];   // the in-game sky at noon
    case 'white': return [1, 1, 1, 1];
    case 'dark':  return [0.16, 0.18, 0.21, 1];
    case 'color': {
      const color = hexToInt(els.bg.value);
      return [(color >>> 16 & 255)/255,(color >>> 8 & 255)/255,(color & 255)/255,1];
    }
    default:      return null;                    // transparent
  }
}

let refineTimer = null;
let viewFrame = 0;
let lastViewKey = null;
let lastDrawnView = null;
const INTERACTION_EDGE = 768;
const REFINE_DELAY = 160;

/** Merge slider/wheel input into one draw per display frame. */
function scheduleView() {
  if (viewFrame) return;
  viewFrame = requestAnimationFrame(() => {
    viewFrame = 0;
    drawView(true);
  });
}

/** Completed geometry invalidates the image, without restarting interaction quality changes. */
let streamViewTimer=0,lastStreamDraw=-Infinity;
function scheduleStreamView() {
  if(viewFrame||streamViewTimer)return;
  // Uploads can complete much faster than a large overview can be drawn. Batch automatic
  // refreshes; pointer, wheel and fly camera changes continue to draw immediately.
  const interval=state.scenePreparing?250:100;
  const delay=state.view?.stream?.running?Math.max(0,interval-(performance.now()-lastStreamDraw)):0;
  streamViewTimer=setTimeout(()=>{streamViewTimer=0;if(viewFrame)return;viewFrame=requestAnimationFrame(()=>{viewFrame=0;lastStreamDraw=performance.now();drawView(dragging||state.fly&&state.flyLookActive);});},delay);
}

/** Reuse the displayed frame while idle; restore native resolution after motion ends. */
function drawView(interactive = false) {
  updateCoordinates();
  if(state.cpuPreview) {
    if(!state.cpuPreviewReady)return;
    clearTimeout(refineTimer);
    if(interactive===true)refineTimer=setTimeout(()=>drawView(),REFINE_DELAY);
    requestPreview(interactive===true);
    return;
  }
  if (!state.view || !state.viewReady) return;
  if (viewFrame) { cancelAnimationFrame(viewFrame); viewFrame = 0; }
  clearTimeout(refineTimer);
  const background = backdropColor();
  const view = state.view;
  const cam = state.fly ? flyCamera() : orbitCamera();
  cam.background = background;
  cam.lodEnabled=state.lodPreview;
  if(state.overview){cam.lodEnabled=true;cam.lodLevel=3;}
  if (interactive === true) {
    const box = viewportPixelSize();
    cam.renderScale = Math.min(1, INTERACTION_EDGE / Math.max(box.w, box.h));
    refineTimer = setTimeout(() => drawView(), REFINE_DELAY);
  }
  const key = JSON.stringify([view.revision, cam, els.viewport.clientWidth, els.viewport.clientHeight, window.devicePixelRatio]);
  if (lastDrawnView === view && lastViewKey === key) { state.flyDirty = false; return; }
  view.draw(cam);
  lastDrawnView = view;
  lastViewKey = key;
  state.flyDirty=false;
}

/**
 * Orbit camera: the whole build stays framed and the two sliders drive the angles.
 *
 * <p>This carries no eye position on purpose. quickcraft's preview builds its model-view as
 * {@code translate(centre) * rotate * scale * translate(-centre)} under a fixed orthographic box,
 * so the camera direction lives in the rotation and the framing lives in the scale — deriving an
 * eye point as well would apply the fit twice.
 */
function orbitCamera() {
  return {
    orbit: true,
    rotationDeg: Number(els.rotation.value),
    slantDeg: Number(els.slant.value),
    zoom: state.zoom,
  };
}

/**
 * Fly camera for inspecting the interior: a perspective view from inside the build, driven by WASD
 * plus the mouse. Perspective is what makes an interior legible — once you are among the blocks an
 * orthographic projection carries no depth cue at all.
 */
function flyCamera() {
  const yaw = state.flyYaw, pitch = state.flyPitch;
  const dir = [
    -Math.sin(yaw) * Math.cos(pitch),
    Math.sin(pitch),
    -Math.cos(yaw) * Math.cos(pitch),
  ];
  const p = state.flyPos;
  return {
    eye: p,
    target: [p[0] + dir[0], p[1] + dir[1], p[2] + dir[2]],
    up: [0, 1, 0],
    fovDeg: 70,
    viewDistance:state.viewDistance,
    orthoHeight: 1,
  };
}

/** Start at the schematic centre, independent of bounds size or view distance. */
function resetFlyToCenter() {
  if(!state.view)return;
  state.flyPos=Array.from(state.view.center);
  state.flyYaw=0;state.flyPitch=0;
  updateCoordinates();
}
function updateCoordinates() {
  const visible=els.showCoordinates.checked&&state.fly&&state.viewReady&&!state.cpuPreview;
  els.coordinates.classList.toggle('hidden',!visible);
  if(visible)els.coordinates.textContent=state.flyPos.map((v,i)=>['X','Y','Z'][i]+': '+v.toFixed(2)).join('  ');
}

/** Moves the fly camera from the held keys; runs only while that mode is active. */
function flyTick(time) {
  if (!state.fly || !state.viewReady) {
    state.animHandle = 0;
    return;
  }
  const elapsed=state.flyTime===null?1/60:Math.max(0,Math.min(.05,(time-state.flyTime)/1000));
  state.flyTime=time;
  const speed = (state.keys.has('ControlLeft') || state.keys.has('ControlRight') ? 24 : 9) * elapsed;
  const yaw = state.flyYaw;
  const forward = [-Math.sin(yaw), 0, -Math.cos(yaw)];
  const right = [Math.cos(yaw), 0, -Math.sin(yaw)];
  const p = state.flyPos;
  const step = (vec, sign, scale) => {
    p[0] += vec[0] * sign * scale;
    p[1] += vec[1] * sign * scale;
    p[2] += vec[2] * sign * scale;
  };
  if (state.keys.has('KeyW')) step(forward, 1, speed);
  if (state.keys.has('KeyS')) step(forward, -1, speed);
  if (state.keys.has('KeyD')) step(right, 1, speed);
  if (state.keys.has('KeyA')) step(right, -1, speed);
  if (state.keys.has('Space')) p[1] += speed;
  if (state.keys.has('KeyC') || state.keys.has('ShiftLeft') || state.keys.has('ShiftRight')) p[1] -= speed;
  if (state.keys.has('KeyW') || state.keys.has('KeyS') || state.keys.has('KeyA') ||
      state.keys.has('KeyD') || state.keys.has('Space') || state.keys.has('KeyC') ||
      state.keys.has('ShiftLeft') || state.keys.has('ShiftRight')) state.flyDirty = true;
  if(state.flyDirty)drawView(true);
  state.animHandle = requestAnimationFrame(flyTick);
}

// ------------------------------------------------------------------- events

function syncAngleLabels() {
  els.rotationVal.textContent = Math.round(els.rotation.value) + '°';
  els.slantVal.textContent = Math.round(els.slant.value) + '°';
  els.zoomVal.textContent = state.zoom.toFixed(2) + '×';
}

// A slider never floods the engine: wait for it to settle, then render at full size.
let sliderTimer = null;
function onSliderCommit() {
  clearTimeout(sliderTimer);
  sliderTimer = setTimeout(() => drawView(), 40);
}

els.rotation.addEventListener('input', () => { syncAngleLabels(); scheduleView(); });
els.slant.addEventListener('input', () => { syncAngleLabels(); scheduleView(); });
els.rotation.addEventListener('change', onSliderCommit);
els.slant.addEventListener('change', onSliderCommit);

els.zoom.addEventListener('input', () => {
  state.zoom = Number(els.zoom.value);
  syncAngleLabels();
  applyZoomToPreview();
});

/** Zoom is applied to the displayed image, so it stays instant and needs no re-render. */
function applyZoomToPreview() {
  els.preview.style.transform = '';
  scheduleView();
}

// --- drag to rotate, wheel to zoom (quickcraft's coefficients) ---------------

const DRAG_RADIANS_PER_PIXEL = 0.015;
const MOUSE_LOOK_RADIANS_PER_PIXEL = 0.0025;
const MAX_PITCH = 85;
let dragging = false;
let dragX = 0;
let dragY = 0;
let flyPress=null,hoverPoint=null;

els.viewport.addEventListener('pointerdown', (e) => {
  if(e.target.closest('.block-inspector'))return;
  if(state.fly)return;
  // Right button (2) and left button (0) both drag, per the request.
  if (e.button !== 0 && e.button !== 2) return;
  dragging = true;
  dragX = e.clientX;
  dragY = e.clientY;
  els.viewport.setPointerCapture(e.pointerId);
  els.viewport.classList.add('grabbing');
});

els.viewport.addEventListener('mousedown',e=>{
  if(state.fly&&e.button===0&&!e.target.closest('.block-inspector'))flyPress={distance:0,x:e.clientX,y:e.clientY,resume:!state.flyLookActive};
});
// Pointer Lock guarantees mousemove events, including platforms that suppress pointermove.
els.viewport.addEventListener('mousemove', (e) => {
  if(state.fly){
    const locked=document.pointerLockElement===els.viewport;
    if(!state.flyLookActive||!state.viewReady||e.target.closest('.block-inspector'))return;
    let dx=0,dy=0;
    if(!locked)return;
    dx=e.movementX;dy=e.movementY;
    // Reacquiring Pointer Lock can warp the OS cursor back to its anchor. That first
    // nonzero delta is a new input baseline, not a turn; zero-delta focus events
    // must not consume it. Resume normal relative input on the very next event.
    if(state.flyDiscardLockDelta){if(dx||dy)state.flyDiscardLockDelta=false;return;}
    if(flyPress)flyPress.distance+=Math.hypot(dx,dy);
    state.flyYaw-=dx*MOUSE_LOOK_RADIANS_PER_PIXEL;
    state.flyPitch=Math.max(-MAX_PITCH*Math.PI/180,Math.min(MAX_PITCH*Math.PI/180,state.flyPitch-dy*MOUSE_LOOK_RADIANS_PER_PIXEL));
    if(dx||dy)state.flyDirty=true;
    return;
  }
});
els.viewport.addEventListener('pointermove', (e) => {
  if(state.fly)return;
  if (!dragging) return;
  const dx = e.clientX - dragX;
  const dy = e.clientY - dragY;
  dragX = e.clientX;
  dragY = e.clientY;

  // Rotation is in degrees on the slider; convert quickcraft's radians-per-pixel.
  const degPerPixel = DRAG_RADIANS_PER_PIXEL * (180 / Math.PI);
  let rotation = Number(els.rotation.value) + dx * degPerPixel;
  let slant = Number(els.slant.value) + dy * degPerPixel;

  rotation = ((rotation % 360) + 360) % 360;
  slant = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, slant));

  els.rotation.value = String(Math.round(rotation));
  els.slant.value = String(Math.round(slant));
  syncAngleLabels();

  // Coalesce to one request per animation frame. Firing per pointermove event would queue far
  // more renders than the engine can retire, and the display only refreshes this often anyway.
  if (!dragPending) {
    dragPending = true;
    requestAnimationFrame(() => {
      dragPending = false;
      if (dragging) drawView(true);
    });
  }
});

let dragPending = false;

function endDrag(e) {
  if(state.fly)return;
  if (!dragging) return;
  dragging = false;
  els.viewport.classList.remove('grabbing');
  if (e && e.pointerId !== undefined && els.viewport.hasPointerCapture(e.pointerId)) {
    els.viewport.releasePointerCapture(e.pointerId);
  }
  drawView();
}
els.viewport.addEventListener('mouseup',e=>{
  if(state.fly){
    const press=flyPress;flyPress=null;
    if(e.button===0&&press&&Math.max(press.distance,Math.hypot(e.clientX-press.x,e.clientY-press.y))<=4){
      if(press.resume||!els.inspector.classList.contains('hidden')){closeInspector();resumeFlyLook();}
      else inspectCenter();
    }
  }
});

els.viewport.addEventListener('pointerup', endDrag);
els.viewport.addEventListener('pointercancel', endDrag);
els.viewport.addEventListener('pointerleave',()=>{hoverPoint=null;});
els.viewport.addEventListener('contextmenu', (e) => e.preventDefault());

els.viewport.addEventListener(
  'wheel',
  (e) => {
    if(e.target.closest('.block-inspector'))return;
    e.preventDefault();
    // quickcraft: scale *= exp(amount * 0.12). Wheel deltas are large, so normalise by 100.
    state.zoom *= Math.exp(-e.deltaY / 100 * 0.12);
    state.zoom = Math.max(0.05, Math.min(20, state.zoom));
    els.zoom.value = String(state.zoom);
    syncAngleLabels();
    applyZoomToPreview();
  },
  { passive: false }
);

document.querySelectorAll('[data-preset]').forEach((btn) => {
  btn.addEventListener('click', () => {
    const p = btn.dataset.preset;
    const map = {
      iso: [45, 35.264],
      dimetric: [45, 30],
      front: [0, 0],
      top: [0, 89],
      back: [180, 0],
      left: [-90, 0],
    }[p];
    if (!map) return;
    els.rotation.value = String(map[0]);
    els.slant.value = String(map[1]);
    syncAngleLabels();
    requestPreview(false);
  });
});

els.transparent.addEventListener('change', () => {
  els.bg.disabled = els.transparent.checked;
  drawView();
});
els.bg.addEventListener('change', () => {
  els.bgMode.value = 'color';
  els.bgMode.dispatchEvent(new Event('change'));
});
/** Paints the viewport's CSS backdrop to match the selected mode. */
function applyBackdrop(resetExport = false) {
  const mode = els.bgMode.value;
  // The checkerboard visualizes empty pixels; it is never part of an export.
  if (resetExport || mode === 'checker') els.transparent.checked = mode === 'checker';
  els.transparent.disabled = mode === 'checker';
  els.bg.disabled = els.transparent.checked;
  els.viewport.style.background = '';
  els.viewport.classList.toggle('checker', mode === 'checker');
  els.viewport.classList.toggle('image-background', mode === 'image');
  els.viewport.style.backgroundColor = cssColor(mode);
  els.viewport.style.backgroundImage = mode === 'image' && state.background
    ? `url("${state.background.dataUrl}")` : '';
}

els.bgMode.addEventListener('change', async () => {
  applyBackdrop(true);
  drawView();
  try { await window.appUi.setBackgroundMode(els.bgMode.value, els.bg.value); }
  catch (error) { log('背景设置失败：' + error.message, 'err'); }
});

/** The CSS colour matching each backdrop option, so the DOM and the framebuffer agree. */
function cssColor(mode) {
  switch (mode) {
    case 'sky':   return '#8cb3ff';
    case 'white': return '#ffffff';
    case 'dark':  return '#292e36';
    case 'color': return els.bg.value;
    default:      return '';
  }
}

applyBackdrop(true);

function useBackground(background) {
  if (background.color) els.bg.value = background.color;
  state.background = background.dataUrl ? background : null;
  els.bgMode.querySelector('option[value="image"]').disabled = !state.background;
  els.bgMode.value = background.mode;
  els.importBackground.title = state.background ? `${background.name} · 点击更换，10 MB 以内` : 'PNG / JPG，10 MB 以内';
  applyBackdrop(true); drawView();
}
els.importBackground.addEventListener('click', async () => {
  els.importBackground.disabled = true;
  try {
    const background = await window.appUi.importBackground();
    if (background) { useBackground(background); log('已导入背景：' + background.name, 'ok'); }
  } catch (error) { log('背景导入失败：' + error.message, 'err'); }
  finally { els.importBackground.disabled = false; }
});
function updateFullscreen(fullscreen) {
  state.fullscreen = fullscreen;
  els.fullscreen.textContent = fullscreen ? '退出全屏 (F11)' : '全屏 (F11)';
  els.fullscreen.setAttribute('aria-pressed', String(fullscreen));
  drawView();
}
async function setFullscreen(enabled) {
  try { updateFullscreen((await window.appUi.fullscreen(enabled)).fullscreen); }
  catch (error) { log('全屏切换失败：' + error.message, 'err'); }
}
els.fullscreen.addEventListener('click', () => setFullscreen(!state.fullscreen));
window.appUi.onFullscreen(updateFullscreen);

function applySidebarCollapsed(collapsed) {
  state.sidebarCollapsed = collapsed;
  els.sidebarPanel.classList.toggle('collapsed', collapsed);
  els.sidebar.inert = collapsed;
  const label = collapsed ? '展开左侧操作面板' : '收起左侧操作面板';
  els.toggleSidebar.textContent = collapsed ? '›' : '‹';
  els.toggleSidebar.title = label;
  els.toggleSidebar.setAttribute('aria-label', label);
  els.toggleSidebar.setAttribute('aria-expanded', String(!collapsed));
  requestAnimationFrame(drawView);
}
els.toggleSidebar.addEventListener('click', async () => {
  applySidebarCollapsed(!state.sidebarCollapsed);
  try { await window.appUi.setSidebarCollapsed(state.sidebarCollapsed); }
  catch (error) { log('面板设置保存失败：' + error.message, 'err'); }
});

async function dismissWelcome(forever) {
  try { await window.appUi.dismissWelcome(forever); els.welcome.close(); }
  catch (error) {
    els.welcomeError.textContent = '无法保存选择：' + error.message;
    els.welcomeError.classList.remove('hidden');
  }
}
els.welcomeClose.addEventListener('click', () => dismissWelcome(false));
els.welcomeNever.addEventListener('click', () => dismissWelcome(true));
els.authorLink.addEventListener('click', async event => {
  event.preventDefault();
  try { await window.appUi.openAuthorLink(); }
  catch (error) { log('打开网页失败：' + error.message, 'err'); }
});
window.__uiReady = (async () => {
  try {
    const settings = await window.appUi.settings();
    state.previewBackend=settings.previewBackend==='cpu'?'cpu':'gpu';
    state.largeProjection=settings.largeProjection===true;els.largeProjection.checked=state.largeProjection;
    els.lightingEnabled.checked=settings.lightingEnabled!==false;
    els.showCoordinates.checked=settings.showCoordinates===true;
    $('clearCacheOnExit').checked=settings.clearCacheOnExit!==false;
    state.lodPreview=settings.lodPreview!==false;els.lodPreview.checked=state.lodPreview;
    state.geometryBudgetMB=settings.geometryBudgetMB??256;els.geometryBudget.value=String(state.geometryBudgetMB);
    state.preloadMode=settings.preloadMode??'streaming';els.preloadMode.value=state.preloadMode;
    applyViewDistance(settings.viewDistance??6,false);
    els.previewBackend.value=state.previewBackend;updatePreviewBackend();
    els.realLighting.checked = settings.realLighting === true;
    useBackground(settings.background);
    applySidebarCollapsed(settings.sidebarCollapsed);
    if (settings.showWelcome) els.welcome.showModal();
  } catch (error) { log('界面设置加载失败：' + error.message, 'err'); }
})();

// --- WASD fly mode ----------------------------------------------------------

/**
 * Fly mode swaps the orbit camera for a perspective one inside the build. Movement is driven by a
 * requestAnimationFrame loop rather than by key events, so a held key moves continuously instead of
 * once per repeat, and the frame rate follows the display.
 */
function updateFlyMouseHint() {
  els.flyMouseHint.classList.toggle('hidden',!state.fly);
  els.flyMouseHint.textContent=state.flyLockPending?'正在锁定鼠标…':state.flyLookActive?'鼠标已锁定；Esc 释放鼠标并暂停视角。':'鼠标已释放，视角暂停。点击预览区重新锁定；V 退出漫游。';
  document.body.classList.toggle('mouse-look',state.fly&&state.flyLookActive);
}
function pauseFlyLook(release=true) {
  state.flyLockGeneration++;state.flyLockPending=false;
  state.flyLookActive=false;state.flyDiscardLockDelta=false;state.keys.clear();hoverPoint=null;flyPress=null;
  if(release&&document.pointerLockElement===els.viewport)document.exitPointerLock();
  updateFlyMouseHint();
}
function activateFlyLook() {
  // Both pointerlockchange and the request promise can report the same acquisition.
  // Only the transition to active arms the baseline reset.
  if(!state.flyLookActive)state.flyDiscardLockDelta=true;
  state.flyLookActive=true;state.flyLockPending=false;hoverPoint=null;
  els.viewport.focus({preventScroll:true});updateFlyMouseHint();
}
function resumeFlyLook() {
  if(!state.fly)return;
  if(document.pointerLockElement===els.viewport){activateFlyLook();return;}
  const generation=++state.flyLockGeneration;
  state.flyLookActive=false;state.flyLockPending=true;hoverPoint=null;updateFlyMouseHint();
  const failed=()=>{
    if(generation!==state.flyLockGeneration||!state.fly||document.pointerLockElement===els.viewport)return;
    pauseFlyLook(false);
    log('鼠标锁定失败。请点击预览区重新锁定；Esc 或切换窗口会释放鼠标。', 'err');
  };
  if(!els.viewport.requestPointerLock){failed();return;}
  try {
    const request=els.viewport.requestPointerLock();
    if(request&&typeof request.then==='function')request.then(()=>{
      if(generation!==state.flyLockGeneration||!state.fly)return;
      if(document.pointerLockElement!==els.viewport){failed();return;}
      activateFlyLook();
    }).catch(failed);
  } catch(_error){failed();}
}
document.addEventListener('pointerlockerror',()=>{
  if(state.fly&&state.flyLockPending){
    pauseFlyLook(false);
    log('鼠标未能锁定，请点击预览区重试。', 'err');
  }
});
document.addEventListener('pointerlockchange',()=>{
  if(document.pointerLockElement===els.viewport){
    if(!state.fly||(!state.flyLookActive&&!state.flyLockPending))document.exitPointerLock();
    else activateFlyLook();
  } else if(state.fly)pauseFlyLook(false);
});
function setFly(on) {
  if(on&&(!state.viewReady||state.cpuPreview))return;
  dragging=false;flyPress=null;hoverPoint=null;els.viewport.classList.remove('grabbing');
  state.fly = on;
  state.keys.clear();state.flyTime=null;state.flyDirty=true;
  els.crosshair.classList.toggle('hidden',!on||!state.viewReady);
  if (on) {
    resetFlyToCenter();
    document.body.classList.add('flying');
    els.flyBtn.textContent = '退出漫游 (V)';
    els.flyHint.classList.remove('hidden');
    resumeFlyLook();
    if (!state.animHandle) state.animHandle = requestAnimationFrame(flyTick);
  } else {
    pauseFlyLook();
    closeInspector();
    document.body.classList.remove('flying');
    els.flyBtn.textContent = '进入漫游 (V)';
    els.flyHint.classList.add('hidden');
    if (state.animHandle) {
      cancelAnimationFrame(state.animHandle);
      state.animHandle = 0;
    }
  }
  drawView();
}

els.flyBtn.addEventListener('click', () => setFly(!state.fly));

window.addEventListener('keydown', (e) => {
  if (e.code === 'F11') { e.preventDefault(); setFullscreen(!state.fullscreen); return; }
  if (els.welcome.open) return;
  if (e.code === 'Escape' && state.fullscreen) { e.preventDefault(); setFullscreen(false); return; }
  if(e.code==='Escape'&&state.fly){closeInspector();pauseFlyLook();e.preventDefault();return;}
  // Never swallow typing in a form control.
  if (e.target && /^(INPUT|SELECT|TEXTAREA)$/.test(e.target.tagName)) return;
  if (e.code === 'KeyV') {
    setFly(!state.fly);
    e.preventDefault();
    return;
  }
  if(e.target.closest && e.target.closest('.block-inspector'))return;
  if (!state.fly||!state.flyLookActive) return;
  if (['KeyW', 'KeyA', 'KeyS', 'KeyD', 'Space', 'KeyC', 'ShiftLeft', 'ShiftRight', 'ControlLeft', 'ControlRight'].includes(e.code)) {
    state.keys.add(e.code);
    e.preventDefault();          // Space would otherwise scroll the panel
    if (!state.animHandle) state.animHandle = requestAnimationFrame(flyTick);
  }
});

window.addEventListener('keyup', (e) => state.keys.delete(e.code));
// A window that loses focus never delivers the keyup, which would leave the camera drifting.
window.addEventListener('blur', () => {state.keys.clear();if(state.fly)pauseFlyLook();});

// --- viewport resize --------------------------------------------------------

/**
 * The canvas is sized from its CSS box on every frame, so it follows the window automatically. The
 * observer only exists to repaint immediately on resize rather than waiting for the next input.
 */
if (typeof ResizeObserver !== 'undefined') {
  new ResizeObserver(() => drawView()).observe(els.viewport);
}

// ------------------------------------------------------------ File menu

function toggleFileMenu(force) {
  const show = force === undefined ? els.menuFileDrop.classList.contains('hidden') : force;
  els.menuFileDrop.classList.toggle('hidden', !show);
}

els.menuFile.addEventListener('click', (e) => {
  e.stopPropagation();
  toggleFileMenu();
});

document.addEventListener('click', () => toggleFileMenu(false));
els.menuFileDrop.addEventListener('click', (e) => e.stopPropagation());

els.menuOpenSchematic.addEventListener('click', () => {
  toggleFileMenu(false);
  openSchematic();
});
els.menuImportPack.addEventListener('click', async () => {
  toggleFileMenu(false);
  await importPack();
});
$('menuImportPackFolder').addEventListener('click',async()=>{toggleFileMenu(false);await importPack('folder');});
els.menuExportPng.addEventListener('click', () => { toggleFileMenu(false); exportPng(); });
els.menuExportGif.addEventListener('click', () => { toggleFileMenu(false); exportGif(); });
els.menuQuit.addEventListener('click', () => {
  window.close();
});

async function openSchematic() {
  const file = await window.shellApi.openSchematic();
  if (!file) return;
  await loadFile(file);
}

let fileOpenGeneration=0;
async function loadFile(file) {
  const opening=++fileOpenGeneration;
  ++gpuBuildGeneration;state.scenePreparing=false;state.overview=false;state.viewReady=false;
  if(state.view?.stream)state.view.disposeBuffers();
  closeInspector();
  els.open.disabled = true;
  setBusy(true, '读取投影…');
  // Frame each new build from a known state; a leftover zoom would open the file mid-detail.
  state.zoom = 1;
  els.zoom.value = '1';
  syncAngleLabels();
  try {
    const info = await window.engine.open(file);
    if(opening!==fileOpenGeneration)return;
    state.file = file;
    state.info = info;
    showFileInfo(info);
    log(`已打开 ${info.name}（${info.regions.length} 个区域，${info.totalBlocks} 方块）`, 'ok');
    if (info.unresolved) log('存在无法解析的资源：' + JSON.stringify(info.unresolved), 'err');
    setBusy(false);
    await buildView();
  } catch (err) {
    if(opening!==fileOpenGeneration)return;
    log('打开失败：' + err.message, 'err');
    setBusy(false);
  } finally {
    if(opening===fileOpenGeneration){els.open.disabled = false;updateButtons();}
  }
}

function closeInspector() {
  state.inspectGeneration++;state.inspectedBlock=null;els.inspector.classList.add('hidden');
}
els.inspectClose.addEventListener('click',()=>{closeInspector();resumeFlyLook();});
function dataText(value) { const text=JSON.stringify(value,null,2)||'';return text.length>24000?text.slice(0,24000)+'\n…':text; }
function showInspection(result) {
  pauseFlyLook();
  state.inspectedBlock=result;
  els.inspectName.textContent=result.name;els.inspectId.textContent=result.id;
  els.inspectStateTitle.textContent=result.kind==='entity'?'实体状态':'方块状态';
  els.inspectPosition.textContent=`${result.kind==='entity'?'实体 · ':''}投影坐标 ${result.position.map(v=>Number.isInteger(v)?v:Number(v.toFixed(3))).join(', ')} · 区域 ${result.region}`;
  const labels={facing:'朝向',waterlogged:'含水',type:'形态',part:'部位',powered:'充能',power:'红石强度',lit:'点亮',open:'打开',attachment:'安装方式',level:'等级',rotation:'旋转',occupied:'占用',cracked:'开裂',axis:'轴向',yaw:'水平朝向',pitch:'俯仰角',riding:'乘坐中',wood:'木材',baby:'幼年',pumpkin:'南瓜头',health:'生命值'};
  const values={north:'北',south:'南',west:'西',east:'东',up:'上',down:'下',true:'是',false:'否',single:'单个',left:'左半',right:'右半',head:'床头',foot:'床尾'};
  els.inspectProperties.replaceChildren();
  for(const [key,value] of Object.entries(result.properties||{})){
    const dt=document.createElement('dt'),dd=document.createElement('dd');
    dt.textContent=labels[key]||key;dt.title=key;dd.textContent=values[value]?`${value}（${values[value]}）`:String(value);
    els.inspectProperties.append(dt,dd);
  }
  if(!Object.keys(result.properties||{}).length){const dd=document.createElement('dd');dd.textContent='无额外状态';els.inspectProperties.append(dd);}
  const inventory=result.container;els.inspectInventory.classList.toggle('hidden',!inventory);els.inspectItems.replaceChildren();
  els.inspectItemDetails.classList.add('hidden');els.inspectItemDetails.open=false;
  if(inventory){
    const status={saved:'显示投影已保存的内容物。',empty:'已保存为空容器。',missing:'投影未保存内容物，无法确定是否为空。',loot:'内容物尚未生成，投影只保存了战利品表。',personal:'末影箱内容属于玩家数据，方块投影不包含个人仓库。',partial:inventory.partnerMissing?'另一半不在投影中；只显示现有半箱保存的物品。':'只保存了部分内容物数据；显示已保存的物品。'};
    els.inspectInventoryStatus.textContent=`${inventory.doubleChest?'双箱 · ':''}${inventory.slots?inventory.slots+' 格 · ':''}${status[inventory.status]||''}`;
    for(const item of inventory.items){
      const button=document.createElement('button');button.type='button';button.className='inventory-item';button.title=item.id;
      const slots=item.displaySlots||[item.displaySlot];
      const slot=document.createElement('span');slot.className='item-slot';slot.textContent=item.stacks>1?item.stacks+' 格':'#'+(item.displaySlot+1);
      button.title=item.id+' · 槽位 '+slots.map(s=>'#'+(s+1)).join(', ');
      const image=document.createElement(item.icon?'img':'span');image.className='item-icon';
      if(item.icon){image.src=item.icon;image.alt='';}else image.textContent='◇';
      const name=document.createElement('span');name.className='item-name';name.textContent=item.name;
      const count=document.createElement('span');count.textContent='×'+item.count;
      button.append(slot,image,name,count);button.addEventListener('click',()=>{
        els.inspectItemName.textContent=item.name+' · '+item.id+' · ×'+item.count;
        els.inspectItemData.textContent=dataText(item.stacks>1?{数量合计:item.count,来源槽位:slots.map(s=>s+1),单组保存的数据:item.data}:item.data);els.inspectItemDetails.classList.remove('hidden');els.inspectItemDetails.open=true;
      });els.inspectItems.append(button);
    }
  }
  els.inspectDataDetails.classList.toggle('hidden',!result.data);els.inspectDataDetails.open=false;
  els.inspectData.textContent=result.data?dataText(result.data):'';
  els.inspector.classList.remove('hidden');
}
async function inspectAt(clientX,clientY) {
  if(!state.fly||!state.viewReady||state.scenePreparing||state.overview)return;
  drawView();const ray=state.view.rayFromScreen(clientX,clientY);if(!ray)return;
  const generation=++state.inspectGeneration;
  try {
    const result=await window.engine.pickBlock(ray);
    if(generation!==state.inspectGeneration||!state.fly)return;
    if(result.hit)showInspection(result);else closeInspector();
  }catch(error){if(generation===state.inspectGeneration)log('查看状态失败：'+error.message,'err');}
}
function inspectCenter() {
  const box=els.gl.getBoundingClientRect();
  return inspectAt(box.left+box.width/2,box.top+box.height/2);
}

els.open.addEventListener('click', openSchematic);
async function rebuildPreview() {
  if (!state.file || state.rendering || state.scenePreparing) return;
  closeInspector();
  if (state.fly) pauseFlyLook();
  log('开始重新渲染…');
  await buildView({ force: true, preserveCamera: true });
  if (state.viewReady||state.cpuPreviewReady) log('重新渲染完成，已保留当前视角。', 'ok');
}
els.render.addEventListener('click', rebuildPreview);
els.export.addEventListener('click', exportPng);
els.exportGif.addEventListener('click', exportGif);

function updatePreviewBackend() {
  els.previewBackendInfo.textContent=state.previewBackend==='cpu'
    ?'CPU 支持旋转和缩放；漫游与方块点选请切换 GPU。'
    :state.cpuPreview?'GPU 不可用，当前使用 CPU 预览。':'GPU 支持实时旋转、缩放和漫游。';
  updateButtons();
}
els.previewBackend.addEventListener('change',async()=>{
  const previous=state.previewBackend,backend=els.previewBackend.value;
  setBusy(true,'切换预览渲染方式…');
  try { await window.appUi.setPreviewBackend(backend); }
  catch(error){els.previewBackend.value=previous;log('保存渲染方式失败：'+error.message,'err');setBusy(false);return;}
  if(state.fly)setFly(false);
  state.previewBackend=backend;updatePreviewBackend();
  log('预览已切换为 '+backend.toUpperCase()+'。');
  if(state.file)await buildView({preserveCamera:true});else setBusy(false);
});

els.lightingEnabled.addEventListener('change',async()=>{
  const enabled=els.lightingEnabled.checked;setBusy(true,'更新光照…');
  try{await window.appUi.setLightingEnabled(enabled);if(state.file)await buildView({preserveCamera:true});}
  catch(error){els.lightingEnabled.checked=!enabled;log(error.message,'err');}
  finally{setBusy(false);}
});
els.largeProjection.addEventListener('change',async()=>{
  const enabled=els.largeProjection.checked;setBusy(true,'更新模型…');
  try{await window.appUi.setLargeProjection(enabled);state.largeProjection=enabled;if(state.file)await buildView({preserveCamera:true});}
  catch(error){els.largeProjection.checked=state.largeProjection;log(error.message,'err');}
  finally{setBusy(false);}
});
els.showCoordinates.addEventListener('change',async()=>{
  const enabled=els.showCoordinates.checked;
  try{await window.appUi.setShowCoordinates(enabled);}catch(error){els.showCoordinates.checked=!enabled;log(error.message,'err');}
  updateCoordinates();
});
els.realLighting.addEventListener('change', async () => {
  const enabled = els.realLighting.checked;
  setBusy(true, '更新光照…');
  try {
    await window.appUi.setRealLighting(enabled);
  } catch (error) {
    els.realLighting.checked = !enabled;
    log('保存光照设置失败：' + error.message, 'err');
    setBusy(false);
    return;
  }
  log(enabled ? '已开启平滑光照，正在更新预览。' : '已恢复明亮光照，正在更新预览。');
  if (state.file) await buildView({ preserveCamera: true });
  else setBusy(false);
});

els.lodPreview.addEventListener('change',async()=>{
  const enabled=els.lodPreview.checked;setBusy(true,'更新预览细节…');
  try{await window.appUi.setLodPreview(enabled);state.lodPreview=enabled;const stream=state.view?.stream;if(stream){stream.planKey=null;stream.lastPlan=-Infinity;}if(state.viewReady)drawView();}
  catch(error){els.lodPreview.checked=state.lodPreview;log('更新预览失败：'+error.message,'err');}
  finally{setBusy(false);}
});

function applyViewDistance(value,redraw=true) {
  const parsed=Number(value),distance=String(value??'').trim()!==''&&Number.isFinite(parsed)?Math.max(1,Math.min(128,Math.round(parsed))):state.viewDistance;
  state.viewDistance=distance;els.viewDistance.value=els.viewDistanceNumber.value=String(distance);
  els.viewDistanceVal.textContent=distance+' 区块';
  if(redraw&&state.viewReady)scheduleView();
}
async function saveGeometryBudget() {
  const value=els.geometryBudget.value.trim(),parsed=Number(value);
  const megabytes=value&&Number.isFinite(parsed)?Math.max(32,Math.min(16384,Math.round(parsed))):state.geometryBudgetMB;
  els.geometryBudget.value=String(megabytes);
  try {
    await window.appUi.setGeometryBudget(megabytes);
    state.geometryBudgetMB=megabytes;
    await state.view?.stream?.setBudgetBytes(megabytes*1048576);
    if(state.viewReady)scheduleView();
  } catch(error) {log('更新几何缓存失败：'+error.message,'err');}
}
function updatePreloadInfo(stats=state.view?.stream?.stats()) {
  const job=stats?.preload;
  const labels={running:'后台预渲染',complete:'预渲染就绪',paused:'预渲染已暂停',limited:'缓存上限已到，可提高上限后继续',error:'预渲染中断，请查看日志后重试'};
  els.preloadInfo.textContent=job?`${labels[job.status]||'预渲染'} · ${job.prepared} / ${job.total} 区块`:state.scenePreparing?'完整区块正在生成，已生成部分会随视角载入。':'随视角按需加载，缓存中的细节可重复使用。';
  els.preload.textContent=job?.status==='running'?'暂停预渲染':job?.status==='limited'||job?.status==='paused'?'继续预渲染':'开始预渲染';
}
function startPreload() {
  const stream=state.view?.stream;if(!stream||state.scenePreparing||state.preloadMode==='streaming')return;
  stream.preload(state.preloadMode,{eye:state.fly?state.flyPos:state.view.center,viewDistance:state.viewDistance});
  updatePreloadInfo();
}
els.preloadMode.addEventListener('change',async()=>{
  try{await window.appUi.setPreloadMode(els.preloadMode.value);state.preloadMode=els.preloadMode.value;state.view?.stream?.stopPreload();if(state.preloadMode!=='streaming')startPreload();else{if(state.view?.stream)state.view.stream.preloadJob=null;updatePreloadInfo();}updateButtons();}
  catch(error){els.preloadMode.value=state.preloadMode;log('保存预渲染方式失败：'+error.message,'err');}
});
els.preload.addEventListener('click',()=>{if(state.view?.stream?.preloadJob?.status==='running')state.view.stream.stopPreload();else startPreload();updatePreloadInfo();});
els.geometryBudget.addEventListener('change',saveGeometryBudget);
els.geometryBudget.addEventListener('keydown',event=>{if(event.key==='Enter')els.geometryBudget.blur();});
async function saveViewDistance(value) {
  applyViewDistance(value);
  try{await window.appUi.setViewDistance(state.viewDistance);}
  catch(error){log('保存视距失败：'+error.message,'err');}
}
els.viewDistance.addEventListener('input',()=>applyViewDistance(els.viewDistance.value));
els.viewDistance.addEventListener('change',()=>saveViewDistance(els.viewDistance.value));
els.viewDistanceNumber.addEventListener('input',()=>{if(els.viewDistanceNumber.validity.valid&&els.viewDistanceNumber.value!=='')applyViewDistance(els.viewDistanceNumber.value);});
els.viewDistanceNumber.addEventListener('change',()=>saveViewDistance(els.viewDistanceNumber.value));

els.skinOnline.addEventListener('change', async () => {
  const online = els.skinOnline.checked;
  els.skinOnline.disabled = true;
  try {
    await window.skins.setOnline(online);
    log(online ? '已开启皮肤联网获取，优先使用缓存。' : '已切换为离线皮肤模式，仅使用缓存和本地 PNG。', 'ok');
    if (state.file && !state.rendering) await buildView({ preserveCamera: true });
  } catch (error) {
    els.skinOnline.checked = !online;
    log('皮肤设置失败：' + error.message, 'err');
  } finally { els.skinOnline.disabled = false; }
});

els.skinImport.addEventListener('click', async () => {
  const identity = els.skinPlayer.value.trim();
  if (!identity) { els.skinPlayer.focus(); log('请先填写皮肤对应的玩家名或 UUID。', 'err'); return; }
  els.skinImport.disabled = true;
  try {
    const result = await window.skins.importLocal(identity);
    if (result.imported) {
      log('已导入 ' + identity + ' 的本地皮肤。', 'ok');
      if (state.file && !state.rendering) await buildView({ preserveCamera: true });
    }
  } catch (error) { log('皮肤导入失败：' + error.message, 'err'); }
  finally { els.skinImport.disabled = false; }
});

async function exportPng() {
  if (!state.file || state.rendering || state.scenePreparing) return;
  const size = Number(els.size.value);
  const suggested = ((state.info && state.info.name) || 'render') + `_${size}.png`;
  const out = await window.shellApi.savePng(suggested);
  if (!out) return;
  setBusy(true, `渲染 ${size}×${size}…`);
  try {
    const res = await window.engine.render({ ...renderParams(size, size), out, diagnostics: true });
    state.lastResult = res;
    els.stats.textContent = `${res.width}×${res.height} · 渲染 ${res.renderMillis} ms · 编码 ${res.encodeMillis} ms`;
    log(`已导出：${res.path}`, 'ok');
    if (res.unresolved) {
      const keys = Object.keys(res.unresolved);
      log(`注意：有 ${keys.length} 类资源未能解析（${keys.slice(0, 3).join('; ')}${keys.length > 3 ? ' …' : ''}）`, 'err');
    }
    window.shellApi.reveal(res.path);
  } catch (err) {
    log('导出失败：' + err.message, 'err');
  } finally {
    setBusy(false);
  }
}

async function exportGif() {
  if (!state.file || state.rendering || state.scenePreparing) return;
  const suggested = ((state.info && state.info.name) || 'animation') + '.gif';
  const out = await window.shellApi.saveGif(suggested);
  if (!out) return;
  const size = Number(els.size.value);
  setBusy(true, '渲染旋转动画…');
  try {
    const res = await window.engine.renderAnimation({
      ...renderParams(size, size),
      out,
      frames: 64,
      delay: 5,
    });
    log(`已导出动画：${res.path}（${res.frames} 帧）`, 'ok');
    window.shellApi.reveal(res.path);
  } catch (err) {
    log('动画导出失败：' + err.message, 'err');
  } finally {
    setBusy(false);
  }
}

function showFileInfo(info) {
  const size = info.size || {};
  const rows = [
    ['名称', info.name],
    ['作者', info.author],
    ['尺寸', `${size.x} × ${size.y} × ${size.z}`],
    ['区域', String(info.regionCount)],
    ['方块数', String(info.totalBlocks)],
  ];
  els.fileInfo.innerHTML = rows
    .map(([k, v]) => `<div class="row"><span>${escapeHtml(k)}</span><span>${escapeHtml(String(v))}</span></div>`)
    .join('');
  els.fileInfo.classList.remove('hidden');
}

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])
  );
}

// -------------------------------------------------------- resource packs

function applyResourceList(res) {
  window.__builtinPacks = res.builtinPacks || [];
  renderPackList();
}

async function reloadPackResources() {
  invalidateCpuPreview();
  const res = await window.engine.loadResources(null);
  applyResourceList(res);
  if (state.file) {
    await window.engine.open(state.file);
    await buildView({ preserveCamera: true });
  }
  return res;
}

function updatePackButtons() {
  els.packList.querySelectorAll('button[data-act]').forEach(b => {
    b.disabled = state.rendering || state.scenePreparing || b.dataset.unavailable === 'true';
  });
}

async function importPack(kind) {
  const p = await window.shellApi.openPack(kind);
  if (!p) return;
  setBusy(true, '应用材质包…');
  try {
    state.packs = await window.packs.import(p);
    const res = await reloadPackResources();
    log(`已导入材质包：${p}`, 'ok');
    log(`贴图 ${res.sprites} 张`, 'ok');
  } catch (err) {
    log('导入材质包失败：' + err.message, 'err');
  } finally {
    setBusy(false);
  }
}

async function removePack(p) {
  setBusy(true, '应用材质包…');
  try {
    state.packs = await window.packs.remove(p);
    await reloadPackResources();
    log(`已移除材质包：${p}`, 'ok');
  } catch (err) {
    log('移除失败：' + err.message, 'err');
  } finally {
    setBusy(false);
  }
}

async function removeBuiltinPack(name) {
  if (state.rendering) return;
  setBusy(true, '应用材质包…');
  try {
    await window.packs.removeBuiltin(name);
    await reloadPackResources();
    log(`已删除材质包：${name}`, 'ok');
  } catch (err) {
    log('删除失败：' + err.message, 'err');
  } finally {
    setBusy(false);
  }
}

async function movePack(p, direction) {
  setBusy(true, '应用材质包…');
  try {
    state.packs = await window.packs.move(p, direction);
    await reloadPackResources();
  } catch (err) {
    log('调整优先级失败：' + err.message, 'err');
  } finally {
    setBusy(false);
  }
}

function renderPackList() {
  if (!els.packList) return;
  const items = [`
    <li class="pack base" data-pack="vanilla">
      <span class="packname">mc默认材质包</span>
      <span class="tag">默认</span>
    </li>`];
  (window.__builtinPacks || []).forEach((name) => {
    items.push(`
      <li class="pack builtin">
        <span class="packname">${escapeHtml(name)}</span>
        <span class="tag">内置</span>
        <span class="packbtns">
          <button class="mini danger" data-act="remove-builtin" data-p="${escapeHtml(name)}" title="删除材质包" aria-label="删除 ${escapeHtml(name)}">×</button>
        </span>
      </li>`);
  });
  state.packs.forEach((p, i) => {
    const name = p.split(/[\\/]/).pop();
    items.push(`
      <li class="pack">
        <span class="packname" title="${escapeHtml(p)}">${escapeHtml(name)}</span>
        <span class="packbtns">
          <button class="mini" data-act="up" data-p="${escapeHtml(p)}" data-unavailable="${i === 0}">↑</button>
          <button class="mini" data-act="down" data-p="${escapeHtml(p)}" data-unavailable="${i === state.packs.length - 1}">↓</button>
          <button class="mini danger" data-act="remove" data-p="${escapeHtml(p)}">×</button>
        </span>
      </li>`);
  });
  els.packList.innerHTML = items.join('');

  els.packList.querySelectorAll('button[data-act]').forEach((b) => {
    b.addEventListener('click', async () => {
      const p = b.dataset.p;
      if (b.dataset.act === 'up') await movePack(p, 'up');
      if (b.dataset.act === 'down') await movePack(p, 'down');
      if (b.dataset.act === 'remove') await removePack(p);
      if (b.dataset.act === 'remove-builtin') await removeBuiltinPack(p);
    });
  });
  updatePackButtons();
}

// ------------------------------------------------------- wake-up

window.engine.onEvent((evt) => {
  if (evt.event === 'stderr' && evt.message && evt.message.trim()) {
    const text = evt.message.trim();
    if (/unresolved|error|exception|failed/i.test(text)) log(text, 'err');
  }
});

// A schematic passed on the command line opens once the engine is up.
window.engine.onOpenFile((file) => {
  log(`命令行指定投影：${file}`);
  pendingOpen = file;
});

let pendingOpen = null;

window.engine.onExit((info) => {
  if (info.error) {
    setStatus('渲染引擎启动失败：' + info.error, 'err');
    log('引擎启动失败：' + info.error + '\n请确认 Java 运行时可用（runtime/ 或 JAVA_HOME）。', 'err');
  } else {
    setStatus('渲染引擎已退出', 'err');
    log(`引擎退出（code=${info.code}）`, 'err');
    if (info.stderrTail && info.stderrTail.length) {
      log(info.stderrTail.slice(-8).join('\n'), 'err');
    }
  }
  updateButtons();
});

(async function init() {
  els.bg.disabled = els.transparent.checked;
  syncAngleLabels();
  try {
    await window.__uiReady;
    const st = await window.engine.status();
    if (!st.running) {
      setStatus('渲染引擎未运行', 'err');
      return;
    }
    setStatus(`引擎就绪 · Java: ${st.javaSource}`, 'ok');
    const skinSettings = await window.skins.settings();
    els.skinOnline.checked = skinSettings.online;
    log(`Java 运行时：${st.java}（${st.javaSource}）`);
    const t0 = performance.now();
    state.packs = await window.packs.list();
    const res = await window.engine.loadResources(null);
    applyResourceList(res);
    if (res.builtinPacks && res.builtinPacks.length) {
      log(`内置材质包：${res.builtinPacks.join('，')}`, 'ok');
    }
    log(`资源包已加载（${Math.round(performance.now() - t0)} ms，贴图 ${res.sprites} 张）`, 'ok');
    if (pendingOpen) {
      await loadFile(pendingOpen);
      pendingOpen = null;
    }
  } catch (err) {
    setStatus('引擎初始化失败', 'err');
    log('初始化失败：' + err.message, 'err');
  }
})();

async function refreshSceneCacheInfo(stats) {
  stats=stats||await window.engine.sceneCacheStats();$('sceneCacheLimit').value=String(stats.limitMB);
  $('sceneCacheInfo').textContent=stats.entries+' 个场景 · '+(stats.bytes/1048576).toFixed(1)+' MB'+(stats.temporaryBytes?' · 本次临时 '+(stats.temporaryBytes/1048576).toFixed(1)+' MB':'')+(stats.overviewTemporaryBytes?' · 总览临时 '+(stats.overviewTemporaryBytes/1048576).toFixed(1)+' MB':'');
}
async function configureSceneCache(clear=false) {
  const limitMB=Number($('sceneCacheLimit').value);$('sceneCacheLimit').disabled=$('sceneCacheClear').disabled=true;
  try{await refreshSceneCacheInfo(await window.engine.configureSceneCache({limitMB,clear}));if(clear)log('已清理未使用的场景缓存。','ok');}
  catch(error){log('缓存设置失败：'+error.message,'err');await refreshSceneCacheInfo().catch(()=>{});}
  finally{updateButtons();}
}
$('sceneCacheLimit').addEventListener('change',()=>configureSceneCache(false));$('sceneCacheClear').addEventListener('click',()=>configureSceneCache(true));
$('clearCacheOnExit').addEventListener('change',async()=>{
  const input=$('clearCacheOnExit'),enabled=input.checked;input.disabled=true;
  try{await window.appUi.setClearCacheOnExit(enabled);}catch(error){input.checked=!enabled;log('保存退出清理设置失败：'+error.message,'err');}
  finally{input.disabled=false;}
});
window.__uiReady?.then(()=>refreshSceneCacheInfo().catch(()=>{}));

// Material lists use the parsed schematic and never request scene geometry.
let materialReport=null,materialWaterAsIce=false,materialSourceFile=null;
function drawMaterials() {
  const query=$('materialSearch').value.trim().toLowerCase();
  for(const [key,id] of [['building','buildingMaterials'],['contents','containerMaterials']]) {
    const panel=$(id);panel.replaceChildren();const table=document.createElement('table'),head=document.createElement('thead'),header=document.createElement('tr');
    for(const label of ['','物品名','数量','需盒']){const th=document.createElement('th');th.textContent=label;header.append(th);}head.append(header);table.append(head);
    const body=document.createElement('tbody');const rows=materialReport?.[key]||[];
    for(const row of rows.filter(r=>!query||(r.name+' '+r.id).toLowerCase().includes(query))) {
      const tr=document.createElement('tr'),icon=document.createElement('td'),name=document.createElement('td'),count=document.createElement('td'),boxes=document.createElement('td');tr.dataset.itemId=row.id;icon.className='material-icon';
      if(row.icon){const img=document.createElement('img');img.src=row.icon;img.alt='';img.width=img.height=40;img.loading='lazy';icon.append(img);}else{const missing=document.createElement('span');missing.className='material-icon-empty';missing.textContent='?';missing.title='暂无对应贴图';icon.append(missing);}
      name.textContent=row.name;const detail=document.createElement('small');detail.textContent=row.id;name.append(detail);count.textContent=Number(row.count).toLocaleString('zh-CN');
      const stacks=document.createElement('small');stacks.textContent=row.maxStack?(row.stacks+' 组 '+row.remainder+' 个'):'堆叠上限未知';count.append(stacks);boxes.textContent=row.boxes===undefined?'':row.boxes+' 盒';tr.append(icon,name,count,boxes);body.append(tr);
    }
    table.append(body);panel.append(table);if(!body.children.length){const empty=document.createElement('p');empty.className='muted small';empty.textContent=query?'没有匹配的物品':'没有已知材料';panel.append(empty);}
  }
  if(materialReport){$('materialSummary').textContent='建材 '+materialReport.building.length+' 种 · 容器内容物 '+materialReport.contents.length+' 种；潜影盒 27 格，达到半盒才显示需盒数。';$('materialWarnings').textContent=materialReport.warnings.join(' ');$('materialConvertWater').textContent=materialWaterAsIce?'恢复水桶统计':'水桶换算为冰';$('materialConvertWater').disabled=!materialReport.waterBucketCount;$('materialConversion').hidden=!materialWaterAsIce;$('materialConversion').textContent='已将建材中的 '+Number(materialReport.waterBucketCount).toLocaleString('zh-CN')+' 桶水按 1:1 换算为普通冰，并合并已有冰；导出采用当前结果。容器内物品保持原样。';}
}
async function showMaterials() {
  if(!state.file||state.rendering||state.scenePreparing)return;
  if(materialSourceFile!==state.file){materialSourceFile=state.file;materialWaterAsIce=false;}
  $('materialStatus').textContent='正在统计材料…';$('materialExport').disabled=true;$('materialConvertWater').disabled=true;$('materialDialog').showModal();
  try{materialReport=await window.engine.materials({waterAsIce:materialWaterAsIce});drawMaterials();$('materialStatus').textContent='';$('materialExport').disabled=false;}
  catch(error){$('materialStatus').textContent='统计失败：'+error.message;}
}
els.materials.addEventListener('click',showMaterials);els.menuMaterials.addEventListener('click',()=>{toggleFileMenu(false);showMaterials();});
$('materialClose').addEventListener('click',()=>$('materialDialog').close());$('materialSearch').addEventListener('input',drawMaterials);
$('materialConvertWater').addEventListener('click',async()=>{
  $('materialConvertWater').disabled=true;$('materialExport').disabled=true;$('materialStatus').textContent='正在换算…';
  try{materialReport=await window.engine.materials({waterAsIce:!materialWaterAsIce});materialWaterAsIce=materialReport.waterAsIce;drawMaterials();$('materialStatus').textContent='';}
  catch(error){$('materialStatus').textContent='换算失败：'+error.message;}
  finally{$('materialConvertWater').disabled=!materialReport?.waterBucketCount;$('materialExport').disabled=!materialReport;}
});
$('materialExport').addEventListener('click',async()=>{
  const format=$('materialFormat').value,scope=$('materialScope').value;
  $('materialExport').disabled=true;$('materialStatus').textContent='请选择保存位置…';
  try {
    const file=await window.shellApi.saveMaterials({suggested:((state.info?.name&&state.info.name!=='?'?state.info.name:state.file.split(/[\\/]/).pop().replace(/\.litematic$/i,'')).replace(/[<>:"\\/|?*\x00-\x1f]/g,'_').slice(0,120))+'-材料清单.'+format,format,scope});
    if(!file){$('materialStatus').textContent='已取消导出';return;}
    $('materialStatus').textContent='正在导出…';
    const result=await window.engine.exportMaterials({out:file,scope,waterAsIce:materialWaterAsIce});$('materialStatus').textContent='已导出：'+result.files.join('；');log('材料表已导出：'+result.files.join('；'));
  }catch(error){$('materialStatus').textContent='导出失败：'+error.message;}finally{$('materialExport').disabled=false;}
});
