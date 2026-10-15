const {app,BrowserWindow,dialog}=require('electron'),fs=require('node:fs'),path=require('node:path');
const repo=path.resolve(__dirname,'../..'),root=process.argv[2]||repo;process.argv=process.argv.slice(0,2);
const fixtures=path.join(__dirname,'lod-output'),out=path.join(fixtures,root===repo?'ui-source':'ui-package');fs.mkdirSync(out,{recursive:true});
app.setPath('userData',path.join(out,'electron'));
// Exercise Chromium's real animation frames without placing a window on the user's screen.
app.commandLine.appendSwitch('disable-backgrounding-occluded-windows');
app.commandLine.appendSwitch('disable-renderer-backgrounding');
BrowserWindow.prototype.show=function(){this.setPosition(-20000,-20000,false);this.showInactive();};
const dialogs=[];dialog.showOpenDialog=async(_window,options)=>{dialogs.push(options);return {canceled:true,filePaths:[]};};
const prefs=path.join(root,'settings/ui.json'),saved=fs.existsSync(prefs)?fs.readFileSync(prefs):null;
const restore=()=>{if(saved)fs.writeFileSync(prefs,saved);else if(fs.existsSync(prefs))fs.unlinkSync(prefs);};
fs.mkdirSync(path.dirname(prefs),{recursive:true});fs.writeFileSync(prefs,JSON.stringify({hideWelcome:true,previewBackend:'gpu',lodPreview:true,realLighting:true,backgroundMode:'checker'}));app.on('will-quit',restore);
const progress=phase=>fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,running:true,phase}));progress('startup');
const {EngineClient}=require(path.join(root,'webapp/engine-client')),originalCall=EngineClient.prototype.call,sceneExports=[];
EngineClient.prototype.call=function(method,params){return originalCall.call(this,method,params).then(result=>{if(method==='exportScene')sceneExports.push(result);return result;});};
app.once('browser-window-created',(_event,win)=>{
 win.webContents.setBackgroundThrottling(false);
 win.webContents.once('did-finish-load',async()=>{
  const run=code=>win.webContents.executeJavaScript('(async()=>{'+code+'})()');
  try{
   const setup=await run(`await window.__uiReady;await window.engine.loadResources(null);
    if(!els.lodPreview.checked||!els.realLighting.checked)throw Error('LOD or smooth lighting not enabled');
    if(state.viewDistance!==6||els.viewDistance.value!=='6'||els.viewDistanceNumber.value!=='6'||els.viewDistance.max!=='128')throw Error('default six-chunk view distance or 128-chunk control missing');
    for(const invalid of [0,129,2.5,'6']){let rejected=false;try{await window.appUi.setViewDistance(invalid);}catch(error){rejected=true;}if(!rejected)throw Error('invalid view distance accepted');}
    if(state.geometryBudgetMB!==256||els.geometryBudget.value!=='256'||els.geometryBudget.max!=='16384')throw Error('custom geometry budget control missing');
    if(state.preloadMode!=='streaming'||els.preloadMode.options.length!==3)throw Error('three preparation modes missing');
    for(const invalid of [0,31,16385,256.5,'256']){let rejected=false;try{await window.appUi.setGeometryBudget(invalid);}catch(error){rejected=true;}if(!rejected)throw Error('invalid geometry budget accepted');}
    els.geometryBudget.value='32';await saveGeometryBudget();
    els.viewport.style.width=els.viewport.style.minWidth=els.viewport.style.maxWidth='640px';els.viewport.style.height=els.viewport.style.minHeight=els.viewport.style.maxHeight='384px';els.viewport.style.flex='0 0 auto';
    await loadFile(${JSON.stringify(path.join(fixtures,'small.litematic'))});
    if(!state.viewReady||!state.view.stream)throw Error('actual application did not select streamed LMESH: '+els.log.textContent);
    await state.view.stream.settled();await new Promise(r=>setTimeout(r,350));
    if(!state.view.drawStats.visibleQuads||JSON.parse(lastViewKey)[0]!==state.view.revision)throw Error('completed chunks never redrew automatically: '+JSON.stringify({stats:state.view.drawStats,revision:state.view.revision,key:lastViewKey,viewFrame,visibility:document.visibilityState}));
    await window.shellApi.openPack();await window.shellApi.openPack('folder');
    return state.view.stream.stats();`);
   if(dialogs.length!==2||dialogs[0].properties.join()!=='openFile'||dialogs[1].properties.join()!=='openDirectory'||dialogs[0].filters[0].extensions[0]!=='zip')throw Error('ZIP and folder dialogs did not select the correct modes');
   progress('pixel comparison');
   const comparison=await run(`const parent=document.createElement('div');parent.style.cssText='position:absolute;left:-10000px;width:640px;height:384px';document.body.appendChild(parent);
    const canvas=document.createElement('canvas');parent.appendChild(canvas);const old=new GpuView(canvas);
    // Compare the same resource stack, including fresh-install versus user-removed built-ins.
    // The tiny LOD fixture is already fully resident before replacing its backing export file.
    const legacy=await window.engine.exportScene({realLighting:true,streaming:false});
    old.uploadMesh(legacy.meshArrayBuffer);
    const image=await createImageBitmap(base64ToBlob(legacy.atlasDataUrl),{premultiplyAlpha:'none',colorSpaceConversion:'none'});old.uploadAtlas(image);image.close();
    const pixels=view=>{const gl=view.gl,result=new Uint8Array(view.canvas.width*view.canvas.height*4);gl.readPixels(0,0,view.canvas.width,view.canvas.height,gl.RGBA,gl.UNSIGNED_BYTE,result);return result;};
    const comparisons=[];
    for(const [yaw,pitch] of [[0,20],[45,30],[90,50],[135,30],[240,20],[315,50]]) {
     const camera={orbit:true,rotationDeg:yaw,slantDeg:pitch,zoom:1};state.view.draw(camera);state.view.stream.plan(camera,frustumPlanes(state.view.lastMatrix),true);await state.view.stream.settled();state.view.draw(camera);old.draw(camera);
     const actual=pixels(state.view),expected=pixels(old);let severe=0,difference=0;
     for(let i=0;i<actual.length;i+=4){let bad=false;for(let c=0;c<4;c++){const d=Math.abs(actual[i+c]-expected[i+c]);difference+=d;bad||=d>8;}if(bad)severe++;}
     const fraction=severe/(actual.length/4),mean=difference/actual.length;if(fraction>.012||mean>1.5)throw Error('LOD0 changed pixels: '+JSON.stringify({yaw,pitch,fraction,mean}));comparisons.push({yaw,pitch,fraction,mean});
    }
    old.disposeBuffers();old.gl.deleteTexture(old.texture);parent.remove();return comparisons;`);
   progress('controls and exports');
   const controls=await run(`const wait=async()=>{const end=Date.now()+45000;while(state.rendering||cpuPreviewRunning||cpuPreviewPending){if(Date.now()>end)throw Error('mode switch timeout');await new Promise(r=>setTimeout(r,20));}};
    els.lodPreview.checked=false;els.lodPreview.dispatchEvent(new Event('change'));await wait();if(state.view.stream||!state.viewReady||!els.geometryBudget.disabled)throw Error('complete mesh toggle failed');
    els.lodPreview.checked=true;els.lodPreview.dispatchEvent(new Event('change'));await wait();await state.view.stream.settled();
    if(!(await window.appUi.settings()).lodPreview||state.view.stream.budgetBytes!==32*1048576||els.geometryBudget.disabled)throw Error('LOD or geometry budget selection not saved');
    await window.engine.render({...renderParams(128,128),out:${JSON.stringify(path.join(out,'full-resolution.png'))}});
    const hit=await window.engine.pickBlock({origin:[.05,3,.05],direction:[0,-1,0],maximum:10});if(!hit.hit||hit.id!=='minecraft:stone')throw Error('exact streamed picking failed after export');
    els.previewBackend.value='cpu';els.previewBackend.dispatchEvent(new Event('change'));await wait();if(!state.cpuPreviewReady||state.view.stream||!els.lodPreview.disabled||!els.viewDistance.disabled||!els.viewDistanceNumber.disabled||!els.geometryBudget.disabled)throw Error('CPU mode retained streamed GPU resources or fly distance controls');
    els.previewBackend.value='gpu';els.previewBackend.dispatchEvent(new Event('change'));await wait();await state.view.stream.settled();return {toggle:true,persisted:true,exportAndPick:true,cpuRelease:true};`);
   progress('budget and movement');
   const movement=await run(`await loadFile(${JSON.stringify(path.join(fixtures,'dense.litematic'))});const stream=state.view.stream;await stream.settled();await new Promise(r=>setTimeout(r,350));
    const initial=stream.stats();if(!initial.lodCounts.slice(1).some(Boolean)||initial.bytesRead>=20*1024*1024/2)throw Error('overview loaded full-detail data');
    for(let i=0;i<12;i++){drawView(true);await new Promise(r=>setTimeout(r,15));dragging=true;endDrag();await new Promise(r=>setTimeout(r,15));}
    await stream.settled();await new Promise(r=>setTimeout(r,350));
    if(stream.loads!==initial.loads||stream.lodChanges!==initial.lodChanges)throw Error('clicks or temporary draw resolution restarted LOD loading');
    els.geometryBudget.value='2048';await saveGeometryBudget();await stream.settled();
    if(state.view.stream!==stream||stream.budgetBytes!==2048*1048576||(await window.appUi.settings()).geometryBudgetMB!==2048)throw Error('custom budget rebuilt scene or failed to persist');
    els.geometryBudget.value='32';await saveGeometryBudget();await stream.settled();
    if(stream.peakBytes>32*1048576||state.view.stream!==stream)throw Error('lowered budget did not release geometry');
    dragging=true;for(let i=0;i<20;i++){els.rotation.value=String(45+i);drawView(true);if(state.view.drawStats.visibleChunks!==20)throw Error('dragging lost the model overview');await new Promise(r=>setTimeout(r,16));}dragging=false;drawView();await stream.settled();await new Promise(r=>setTimeout(r,350));
    state.fly=true;state.flyPos=[160,8,-16];state.flyYaw=Math.PI;state.flyPitch=0;drawView();
    const changeRange=async(distance,expected,number=false)=>{
      const input=number?els.viewDistanceNumber:els.viewDistance;input.value=String(distance);input.dispatchEvent(new Event('input'));input.dispatchEvent(new Event('change'));
      const deadline=Date.now()+10000;
      while(stream.wanted.size!==expected||(await window.appUi.settings()).viewDistance!==distance){if(Date.now()>deadline)throw Error('view distance did not update/persist');await new Promise(r=>setTimeout(r,20));}
      await stream.settled();await new Promise(r=>setTimeout(r,100));
      if(stream.residents.size!==expected||state.view.stream!==stream||els.viewDistance.value!==String(distance)||els.viewDistanceNumber.value!==String(distance))throw Error('view distance rebuilt the scene or retained distant blocks');
    };
    await changeRange(1,3,true);const limited=stream.stats();
    if([...stream.residents.values()].some(r=>r.section.position[0]<9||r.section.position[0]>11))throw Error('distant section remained after reducing range');
    await changeRange(128,20);const expanded=stream.stats();await changeRange(6,13);
    state.fly=false;drawView();await stream.settled();await new Promise(r=>setTimeout(r,350));
    if(stream.residents.size!==20)throw Error('view distance cropped the orbit overview');
    const savedRadius=state.view.radius;state.view.radius=10000;resetFlyToCenter();const spawn=state.flyPos.slice();state.view.radius=savedRadius;
    if(Math.floor(spawn[2]/16)-Math.floor((state.view.max[2]-.001)/16)>state.viewDistance)throw Error('fly spawn was beyond the default render range');
    state.fly=true;state.flyPos=[8,8,-20];state.flyYaw=Math.PI;state.flyPitch=0;drawView();await stream.settled();await new Promise(r=>setTimeout(r,350));
    for(let i=0;i<8;i++){await inspectCenter();closeInspector();drawView(true);await new Promise(r=>setTimeout(r,25));}
    await stream.settled();await new Promise(r=>setTimeout(r,350));if(!state.fly||!state.viewReady||!state.view.drawStats.visibleChunks)throw Error('repeated fly selection made the preview disappear');
    state.fly=false;drawView();await stream.settled();await new Promise(r=>setTimeout(r,350));stream.onChange=()=>{};
    const pressureCamera={orbit:true,rotationDeg:45,slantDeg:30,zoom:1,lodLevel:0};
    state.view.draw(pressureCamera);stream.plan(pressureCamera,frustumPlanes(state.view.lastMatrix),true);await stream.settled();
    const pressure=stream.stats();if(!pressure.lodCounts[0]||!pressure.lodCounts.slice(1).some(Boolean)||pressure.residentSections!==20)throw Error('budget admission did not preserve coarse coverage while capping detail');
    const move=async x=>{const camera={eye:[x,8,-20],target:[x,8,8],fovDeg:45};state.view.draw(camera);stream.plan(camera,frustumPlanes(state.view.lastMatrix),true);await stream.settled();state.view.draw(camera);return stream.stats();};
    const near=await move(8);if(!near.lodCounts[0])throw Error('near camera never loaded full detail');
    const far=await move(312);if(far.evictions<1||far.loads<=near.loads)throw Error('camera movement did not replace old geometry');
    const first={eye:[8,8,-20],target:[8,8,8],fovDeg:45},last={eye:[312,8,-20],target:[312,8,8],fovDeg:45};
    state.view.draw(first);stream.plan(first,frustumPlanes(state.view.lastMatrix),true);state.view.draw(last);stream.plan(last,frustumPlanes(state.view.lastMatrix),true);await stream.settled();state.view.draw(last);
    if(stream.peakBytes>stream.budgetBytes||stream.residentBytes+stream.loadingBytes>stream.budgetBytes)throw Error('geometry exceeded memory budget');
    if([...stream.residents.values()].some(r=>!stream.wanted.has(r.id)))throw Error('cancelled request installed stale geometry');
    if([...stream.residents.values()].some(r=>r.pages.some(p=>p.layer!==4&&p.data)))throw Error('opaque CPU vertex arrays retained');
    if(state.view.gl.getError()!==0||state.view.gl.isContextLost())throw Error('streaming WebGL failure');
    return {initial,limited,expanded,pressure,near,far,final:stream.stats(),automaticRepaint:true,stableClicks:12,continuousDrag:true,flySelections:8,viewDistance:true,spawnInRange:true};`);
   if(!sceneExports.some(e=>e.meshFormat==='LMESH7'&&e.fullMeshResident===false))throw Error('engine did not use bounded LMESH7 scene generation');
   progress('background preparation and zoom cache');
   const preloading=await run(`const stream=state.view.stream;els.geometryBudget.value='2048';await saveGeometryBudget();state.fly=true;state.flyPos=[160,8,8];applyViewDistance(1);drawView();await stream.settled();
    const mode=async value=>{els.preloadMode.value=value;els.preloadMode.dispatchEvent(new Event('change'));const end=Date.now()+5000;while((await window.appUi.settings()).preloadMode!==value){if(Date.now()>end)throw Error('preload mode did not persist');await new Promise(r=>setTimeout(r,10));}await new Promise(r=>setTimeout(r,25));};
    await mode('distance');await stream.settled();if(stream.preloadJob.sections.length!==3||stream.preloadJob.prepared.size!==3||stream.preloadJob.status!=='complete')throw Error('current-distance preparation wrong');
    const read=stream.read;stream.read=async(...args)=>{await new Promise(r=>setTimeout(r,8));return read(...args);};
    await mode('full');let frames=0;while(stream.preloadJob.status==='running'&&frames<12){if(state.rendering)throw Error('background preparation blocked preview');state.flyYaw+=.01;drawView();await new Promise(r=>requestAnimationFrame(r));frames++;}
    await stream.settled();stream.read=read;if(stream.preloadJob.prepared.size!==20||stream.preloadJob.status!=='complete'||frames<1)throw Error('full preparation failed or did not overlap preview');
    const camera={orbit:true,rotationDeg:45,slantDeg:30,zoom:1,culling:false};for(const level of [0,1,2,3]){stream.plan({...camera,lodLevel:level},null,true);await stream.settled();}
    const loads=stream.loads;for(const level of [0,3,1,2,0,3]){stream.plan({...camera,lodLevel:level},null,true);await stream.settled();}if(stream.loads!==loads)throw Error('zoom roundtrip reread cached detail');
    state.fly=false;for(const zoom of [12,.2,6,1,.05,1]){els.zoom.value=String(zoom);els.zoom.dispatchEvent(new Event('input'));els.zoom.dispatchEvent(new Event('change'));await new Promise(r=>setTimeout(r,200));await stream.settled();}if(stream.loads!==loads)throw Error('actual zoom controls reloaded prepared geometry');
    await mode('streaming');if(stream.preloadJob||!els.preload.disabled)throw Error('streaming mode kept a background job');state.fly=false;state.flyYaw=0;applyViewDistance(6);drawView();return {distanceChunks:3,fullChunks:20,concurrentPreviewFrames:frames,cachedZoomRoundTrips:true};`);
   progress('special appearance comparison');
   const appearances=[];
   for(const name of ['banners','books-conduits','beacons','frames','armor']){
    const pixels=await run(`await loadFile(${JSON.stringify(path.join(fixtures,'appearances'))}+'/'+${JSON.stringify(name)}+'.litematic');await state.view.stream.settled();
     const parent=document.createElement('div');parent.style.cssText='position:absolute;left:-10000px;width:640px;height:384px';document.body.appendChild(parent);const canvas=document.createElement('canvas');parent.appendChild(canvas);const reference=new GpuView(canvas);
     const legacy=await window.engine.exportScene({realLighting:true,streaming:false});reference.uploadMesh(legacy.meshArrayBuffer);reference.setFrameBounds(legacy.min,legacy.max);const image=await createImageBitmap(base64ToBlob(legacy.atlasDataUrl),{premultiplyAlpha:'none',colorSpaceConversion:'none'});reference.uploadAtlas(image);image.close();
     const pixels=view=>{const gl=view.gl,result=new Uint8Array(view.canvas.width*view.canvas.height*4);gl.readPixels(0,0,view.canvas.width,view.canvas.height,gl.RGBA,gl.UNSIGNED_BYTE,result);return result;};let maximum=0;
     for(const yaw of [35,120]){const camera={orbit:true,rotationDeg:yaw,slantDeg:30,zoom:1};state.view.draw(camera);await state.view.stream.settled();state.view.draw(camera);reference.draw(camera);const actual=pixels(state.view),expected=pixels(reference);let severe=0;for(let i=0;i<actual.length;i+=4){let bad=false;for(let c=0;c<4;c++)bad||=Math.abs(actual[i+c]-expected[i+c])>8;if(bad)severe++;}maximum=Math.max(maximum,severe/(actual.length/4));}
     if(maximum>.02||state.view.gl.getError())throw Error('special appearance differs between LOD and full GPU preview: '+maximum);const screenshot=els.gl.toDataURL();reference.disposeBuffers();reference.gl.deleteTexture(reference.texture);parent.remove();return {maximum,screenshot};`);
    fs.writeFileSync(path.join(out,name+'-gpu.png'),Buffer.from(pixels.screenshot.split(',')[1],'base64'));appearances.push({name,maximum: pixels.maximum});
   }
   const result={pass:true,setup,comparison,controls,movement,preloading,appearances,streamedExports:sceneExports.filter(e=>e.meshFormat==='LMESH7').length};
   fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));app.quit();
  }catch(error){restore();fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,error:error.stack}));console.error(error);app.exit(1);}
 });
});
require(path.join(root,'webapp/main.js'));setTimeout(()=>{restore();console.error('LOD UI timeout');app.exit(1);},390000).unref();
