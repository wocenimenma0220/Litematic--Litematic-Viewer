const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=process.argv[2]||path.resolve(__dirname,'../..'),large=process.argv[3]==='large',out=path.join(__dirname,'progressive-output',large?'large-ui':'ui');process.argv=process.argv.slice(0,2);
fs.mkdirSync(out,{recursive:true});app.setPath('userData',path.join(out,'electron'));
const runtime=path.join(root,'tools/verify/progressive-ui-runtime-'+(large?'large':'small'));process.env.JAVA_TOOL_OPTIONS=`-Dlitematicrender.cache.directory=${runtime.replaceAll('\\','/')}/cache/scenes -Dlitematicrender.skins.offline=true`;
const sm=require(path.join(root,'webapp/ui-settings')),Settings=sm.UiSettings;sm.UiSettings=class extends Settings{constructor(){super(runtime);}};
const cleanupModule=require(path.join(root,'webapp/cache-cleanup')),cleanup=cleanupModule.clearRenderingCache;cleanupModule.clearRenderingCache=()=>cleanup(runtime);
app.commandLine.appendSwitch('disable-backgrounding-occluded-windows');app.commandLine.appendSwitch('disable-renderer-backgrounding');BrowserWindow.prototype.show=function(){this.setPosition(-20000,-20000,false);this.showInactive();};
const {EngineClient}=require(path.join(root,'webapp/engine-client')),call=EngineClient.prototype.call,calls=[];EngineClient.prototype.call=function(method,params){return call.call(this,method,params).then(result=>{if(['beginPreview','previewResult','previewLive','exportCachedScene'].includes(method))calls.push({method,overview:result.overview,hit:result.diskCacheHit});if(method==='previewStatus'&&result.sections?.length)calls.push({method,published:result.published,total:result.total,positions:result.sections.slice(0,4).map(s=>s.position)});return result;});};
let failed=false;app.on('will-quit',()=>{if(failed)app.exit(1);});
app.once('browser-window-created',(_event,win)=>{win.webContents.setBackgroundThrottling(false);win.webContents.once('did-finish-load',async()=>{
 const interval=setInterval(async()=>{try{fs.writeFileSync(path.join(out,'progress.json'),JSON.stringify(await win.webContents.executeJavaScript('({status:els.status.textContent,preparing:state.scenePreparing,overview:state.overview,live:state.livePreview,stats:state.view?.stream?.stats(),log:els.log.textContent})')));}catch{}},1000);
 try{
  const result=await win.webContents.executeJavaScript(`(async()=>{
   const wait=ms=>new Promise(r=>setTimeout(r,ms)),check=(v,m)=>{if(!v)throw Error(m+' '+els.log.textContent);};await window.__uiReady;if(els.welcome.open)await dismissWelcome(false);await window.engine.loadResources(null);
   state.largeProjection=true;els.largeProjection.checked=true;state.lodPreview=${large};els.lodPreview.checked=${large};state.geometryBudgetMB=${large?256:64};els.geometryBudget.value=String(state.geometryBudgetMB);els.lightingEnabled.checked=true;els.realLighting.checked=true;
   await window.engine.configureSceneCache({limitMB:4096,clear:true});
   const start=performance.now(),loading=loadFile(${JSON.stringify(large?'D:/litematic-display/璃月港.litematic':path.join(__dirname,'progressive-output/checker.litematic'))});let overview=null,stream=null,firstDetail=null,firstStats=null,toggled=false;const deadline=Date.now()+240000;
   while(state.rendering||state.scenePreparing||!state.viewReady){
    if(Date.now()>deadline)throw Error('progressive timeout');
    if(state.overview&&state.viewReady&&!overview){overview=performance.now()-start;stream=state.view.stream;}
    const stats=state.view?.stream?.stats();
    if(stats?.progressive&&stats.detailResidents&&firstDetail===null){firstDetail=performance.now()-start;firstStats=stats;check(state.scenePreparing,'first detail arrived after whole build');check(state.view.stream===stream,'overview stream was discarded');check(stats.publishedSections<stats.totalExpected,'all sections completed before first detail');}
    if(firstDetail!==null&&!toggled){const generation=gpuBuildGeneration,job=state.previewJobId;els.lodPreview.click();await wait(150);check(gpuBuildGeneration===generation&&state.previewJobId===job&&state.view.stream===stream,'LOD toggle restarted generation');els.lodPreview.click();await wait(150);toggled=true;}
    await wait(20);
   }
   await loading;check(firstDetail!==null,'no early detail appeared');check(state.view.stream===stream,'completion replaced GPU stream');check(stream.progressComplete,'final progressive metadata missing');await stream.settled();drawView();
   const complete=performance.now()-start,stats=stream.stats();check(stats.peakBytes<=stats.budgetBytes,'GPU budget exceeded');check(state.view.gl.getError()===0,'WebGL error');
   const old=state.view.stream,begin=gpuBuildGeneration;els.lodPreview.click();await wait(200);els.lodPreview.click();await wait(200);check(state.view.stream===old&&gpuBuildGeneration===begin,'idle LOD toggle rebuilt stream');
   return {pass:true,overview,firstDetail,complete,firstStats,stats,log:els.log.textContent};
  })()`);
  fs.writeFileSync(path.join(out,'preview.png'),(await win.webContents.capturePage()).toPNG());fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({...result,calls},null,2));console.log(JSON.stringify({...result,log:undefined,calls:undefined}));clearInterval(interval);win.close();
 }catch(error){failed=true;clearInterval(interval);const debug=await win.webContents.executeJavaScript('({status:els.status.textContent,log:els.log.textContent,stats:state.view?.stream?.stats()})').catch(()=>null);fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,error:error.stack,debug,calls},null,2));console.error(error.stack,debug);app.quit();}
});});require(path.join(root,'webapp/main.js'));setTimeout(()=>{failed=true;app.quit();},300000).unref();
