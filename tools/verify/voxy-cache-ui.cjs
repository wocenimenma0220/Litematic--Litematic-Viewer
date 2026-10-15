const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=process.argv[2]||path.resolve(__dirname,'../..');process.argv=process.argv.slice(0,2);
const out=path.join(__dirname,'cache-output/ui');fs.mkdirSync(out,{recursive:true});app.setPath('userData',path.join(out,'electron'));
process.env.JAVA_TOOL_OPTIONS=`-Dlitematicrender.cache.directory=${root.replaceAll('\\','/')}/tools/verify/preview-test-scenes -Dlitematicrender.skins.offline=true`;
app.commandLine.appendSwitch('disable-backgrounding-occluded-windows');app.commandLine.appendSwitch('disable-renderer-backgrounding');
BrowserWindow.prototype.show=function(){this.setPosition(-20000,-20000,false);this.showInactive();};
const settingsModule=require(path.join(root,'webapp/ui-settings')),OriginalSettings=settingsModule.UiSettings;
settingsModule.UiSettings=class extends OriginalSettings{constructor(){super(out);}};
const {EngineClient}=require(path.join(root,'webapp/engine-client')),original=EngineClient.prototype.call,calls=[];
EngineClient.prototype.call=function(method,params){return original.call(this,method,params).then(result=>{if(['beginPreview','previewResult','exportCachedScene'].includes(method))calls.push({method,overview:result.overview,hit:result.diskCacheHit,builds:result.meshBuildCount,bytes:result.meshBytes});return result;});};
app.once('browser-window-created',(_event,win)=>{win.webContents.setBackgroundThrottling(false);win.webContents.once('did-finish-load',async()=>{
 try{
  const result=await win.webContents.executeJavaScript(`(async()=>{
   const check=(v,m)=>{if(!v)throw Error(m+' '+els.log.textContent);},wait=ms=>new Promise(r=>setTimeout(r,ms));
   await window.__uiReady;if(els.welcome.open)await dismissWelcome(false);await window.engine.loadResources(null);
   state.largeProjection=true;els.largeProjection.checked=true;state.lodPreview=false;els.lodPreview.checked=false;state.geometryBudgetMB=64;els.geometryBudget.value='64';
   await window.engine.configureSceneCache({limitMB:256,clear:true});
   let first=performance.now();const loading=loadFile(${JSON.stringify(path.join(__dirname,'feature-output/lod-current/dense.litematic'))});
   let overview=false,coarseCamera=null,firstVisible=null;
   const end=Date.now()+120000;while(state.rendering||state.scenePreparing||!state.viewReady){
    if(Date.now()>end)throw Error('preview timeout '+JSON.stringify({rendering:state.rendering,preparing:state.scenePreparing,ready:state.viewReady,log:els.log.textContent}));
    if(state.overview&&state.viewReady){overview=true;check(els.export.disabled&&els.materials.disabled,'background build controls enabled');check(!els.flyBtn.disabled,'overview cannot roam');
      check([...els.packList.querySelectorAll('button[data-act]')].every(b=>b.disabled),'pack mutation enabled during background build');
      check(state.view.stream.loads<=state.view.stream.manifest.sections.length,'same overview uploaded twice');
      if(!coarseCamera){state.flyPos=state.view.center.map((v,i)=>v+(i===0?2:0));coarseCamera=state.flyPos.slice();}
      if(state.view.stream.residents.size&&firstVisible===null)firstVisible=performance.now()-first;
    }await wait(20);
   }await loading;check(state.viewReady&&!state.overview,'final scene unavailable');check(overview,'coarse stage never displayed');check(els.status.textContent.includes('已就绪'),'completed scene still says preparing');
   check(state.flyPos.every((v,i)=>v===coarseCamera[i]),'upgrade reset camera');await state.view.stream.settled();drawView();
   const full=state.view.stream.stats();check(!full.budgetLimited&&full.decodedBytes>full.bytesRead&&full.cpuPageCacheBytes===0,'compressed page or budget statistics incorrect '+JSON.stringify(full));
   check(full.peakBytes<=full.budgetBytes&&full.transferPeakBytes<=full.transferLimitBytes,'budget exceeded');check(state.view.gl.getError()===0,'WebGL error');
   const coldMillis=performance.now()-first;first=performance.now();await buildView();await state.view.stream.settled();drawView();const warmMillis=performance.now()-first;
   check(!state.overview&&state.viewReady,'warm preview not ready');check(els.log.textContent.includes('已复用磁盘场景缓存'),'warm hit missing');
   // Cancel an actual cold task, then open a different input. The old id cannot be adopted.
   const old=await window.engine.beginPreview({force:true,realLighting:true,lightingEnabled:true});await wait(30);await window.engine.cancelPreview();let stale=false;try{await window.engine.previewResult(old.id,false);}catch(e){stale=true;}check(stale,'cancelled result adopted');
   await loadFile(${JSON.stringify(path.join(__dirname,'feature-output/water-trapdoors.litematic'))});await state.view.stream.settled();drawView();check(state.viewReady&&!state.scenePreparing,'replacement scene not ready');
   check(!els.materials.disabled&&!els.export.disabled,'controls not restored');
   const cache=await window.engine.sceneCacheStats();return {pass:true,overview,firstVisible,coldMillis,warmMillis,full,cache,log:els.log.textContent};
  })()`);
  fs.writeFileSync(path.join(out,'preview.png'),(await win.webContents.capturePage()).toPNG());fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({...result,calls},null,2));console.log(JSON.stringify({...result,log:undefined,calls}));app.quit();
 }catch(error){const debug=await win.webContents.executeJavaScript('({log:els.log.textContent,rendering:state.rendering,preparing:state.scenePreparing,ready:state.viewReady})').catch(()=>null);fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,error:error.stack,calls,debug},null,2));console.error(error.stack,debug);app.exit(1);}
});});require(path.join(root,'webapp/main.js'));setTimeout(()=>app.exit(1),240000).unref();
