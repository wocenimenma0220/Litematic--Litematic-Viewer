'use strict';
const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../..'),out=path.join(__dirname,'warm-open-output');fs.mkdirSync(out,{recursive:true});app.setPath('userData',path.join(out,'electron'));
process.env.LITEMATIC_RENDER_HEAP_MB='7936';process.env.JAVA_TOOL_OPTIONS=`-Dlitematicrender.cache.directory=${out.replaceAll('\\','/')}/scenes -Dlitematicrender.resources.cache.directory=${out.replaceAll('\\','/')}/resources -Dlitematicrender.skins.offline=true`;
app.commandLine.appendSwitch('force-device-scale-factor','1');app.commandLine.appendSwitch('disable-renderer-backgrounding');app.commandLine.appendSwitch('disable-backgrounding-occluded-windows');
BrowserWindow.prototype.show=function(){this.setPosition(-20000,-20000,false);this.showInactive();};
const settings=require(path.join(root,'webapp/ui-settings')),Original=settings.UiSettings;fs.mkdirSync(path.join(out,'settings'),{recursive:true});fs.writeFileSync(path.join(out,'settings/ui.json'),JSON.stringify({hideWelcome:true,projectionMode:'streaming',clearCacheOnExit:false,geometryBudgetMB:256,realLighting:true}));settings.UiSettings=class extends Original{constructor(){super(out);}};
const {EngineClient}=require(path.join(root,'webapp/engine-client')),call=EngineClient.prototype.call,calls=[];
EngineClient.prototype.call=function(method,params){const start=performance.now();return call.call(this,method,params).then(result=>{if(method==='loadResources')calls.push({method,millis:performance.now()-start,...result});return result;});};
app.once('browser-window-created',(_event,win)=>{win.setContentSize(1280,900);win.webContents.setBackgroundThrottling(false);win.webContents.once('did-finish-load',async()=>{
 const watch=setInterval(async()=>{try{fs.writeFileSync(path.join(out,'progress.json'),JSON.stringify(await win.webContents.executeJavaScript('({status:els.status.textContent,preparing:state.scenePreparing,stats:state.view?.stream?.stats()})')));}catch{}},2000);
 try{
  const result=await win.webContents.executeJavaScript(`(async()=>{
   const wait=ms=>new Promise(r=>setTimeout(r,ms)),check=(v,m)=>{if(!v)throw Error(m+' '+els.log.textContent);};await window.__uiReady;
   const normal=GpuView.prototype.uploadLodStream;let batchEnabled=true;
   GpuView.prototype.uploadLodStream=function(read,size,options){return normal.call(this,read,size,{...options,readPages:batchEnabled?options.readPages:undefined});};
   await loadFile(${JSON.stringify(path.resolve(root,'../../璃月港.litematic'))});await state.view.stream.settled();drawView();check(state.view.quadCount===7996174,'Full model missing');
   const passes=[];
   for(const enabled of [false,true,false,true]){
    batchEnabled=enabled;state.fly=false;const start=performance.now();await loadFile(state.file);await state.view.stream.settled();drawView();
    const stats=state.view.stream.stats(),millis=performance.now()-start;
    check(els.log.textContent.includes('已复用磁盘场景缓存'),'Cache miss during warm test');check(stats.residentSections===11206,'Missing complete sections');check(!state.view.stream.failed.size,'Failed pages');check(stats.peakBytes<=stats.budgetBytes&&stats.batchPeakBytes<=2*1048576,'Budget exceeded');
    if(enabled)check(stats.batchRequests<200&&stats.pageRequests===0,'Tiny pages were not batched');
    const pixels=new Uint8Array(els.gl.width*els.gl.height*4);state.view.gl.readPixels(0,0,els.gl.width,els.gl.height,state.view.gl.RGBA,state.view.gl.UNSIGNED_BYTE,pixels);let hash=2166136261;for(const value of pixels)hash=Math.imul(hash^value,16777619);
    passes.push({enabled,millis,stats,hash:hash>>>0});
   }
   check(passes.every(p=>p.hash===passes[0].hash),'Batching changed actual preview pixels');
   const stream=state.view.stream,section=stream.manifest.sections.find(s=>s.levels[0].some(p=>p.quads>100));
   state.fly=true;state.viewDistance=2;state.flyPos=[section.center[0],section.center[1]+8,section.center[2]-24];state.flyYaw=Math.PI;state.flyPitch=-Math.atan2(8,24);drawView();await stream.settled();drawView();check(stream.lodCounts[0]>0,'Actual LOD0 geometry missing');
   els.lodPreview.checked=false;els.lodPreview.dispatchEvent(new Event('change'));await wait(100);drawView();await stream.settled();drawView();check(stream.lodCounts[0]>0&&!stream.failed.size,'LOD off failed');check(state.view.gl.getError()===0,'WebGL error');const close=stream.stats();
   state.fly=false;await loadFile(${JSON.stringify(path.resolve(root,'../../1.8w-潜影贝收集.litematic'))});await state.view.stream.settled();drawView();check(state.view.quadCount===648,'Small scene replacement failed');
   return {pass:true,passes,close,smallQuads:state.view.quadCount,log:els.log.textContent};
  })()`);
  clearInterval(watch);fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({...result,calls},null,2));console.log(JSON.stringify({...result,log:undefined,calls}));app.quit();
 }catch(error){clearInterval(watch);fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,error:error.stack,calls},null,2));console.error(error.stack);app.exit(1);}
});});require(path.join(root,'webapp/main.js'));setTimeout(()=>app.exit(1),360000).unref();
