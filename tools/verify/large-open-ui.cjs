'use strict';
const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../..'),mode=process.argv[2]||'current',label=process.argv[3]||mode;
const baselineClasses=path.resolve(process.argv[4]||path.join(__dirname,'load-speed-output/baseline-bin'));
if(!['baseline','current'].includes(mode)||!/^[-a-z0-9]+$/.test(label))throw Error('Expected baseline/current and a simple output label');
process.argv=process.argv.slice(0,2);
const out=path.join(__dirname,'load-speed-output','ui-'+label);fs.mkdirSync(out,{recursive:true});
app.setPath('userData',path.join(out,'electron'));
process.env.LITEMATIC_RENDER_HEAP_MB=process.env.LITEMATIC_RENDER_TEST_HEAP_MB||'1536';
process.env.JAVA_TOOL_OPTIONS=`-Dlitematicrender.cache.directory=${out.replaceAll('\\','/')}/scenes -Dlitematicrender.skins.offline=true`;
app.commandLine.appendSwitch('force-device-scale-factor','1');app.commandLine.appendSwitch('disable-renderer-backgrounding');app.commandLine.appendSwitch('disable-backgrounding-occluded-windows');
BrowserWindow.prototype.show=function(){this.setPosition(-20000,-20000,false);this.showInactive();};
const settings=require(path.join(root,'webapp/ui-settings')),Original=settings.UiSettings;
fs.mkdirSync(path.join(out,'settings'),{recursive:true});
fs.writeFileSync(path.join(out,'settings/ui.json'),JSON.stringify({hideWelcome:true,projectionMode:'streaming',clearCacheOnExit:false,geometryBudgetMB:256,realLighting:true,lightingEnabled:true}));
settings.UiSettings=class extends Original{constructor(){super(out);}};
const {EngineClient}=require(path.join(root,'webapp/engine-client')),start=EngineClient.prototype.start,call=EngineClient.prototype.call,calls=[];
EngineClient.prototype.start=function(){if(mode==='baseline')this.opts.classpath=baselineClasses;return start.call(this);};
EngineClient.prototype.call=function(method,params){const begin=performance.now();return call.call(this,method,params).then(result=>{
 if(['loadResources','open','previewResult','previewComplete'].includes(method))calls.push({method,millis:performance.now()-begin,quads:result.quads,cacheHit:result.diskCacheHit});return result;
});};
app.once('browser-window-created',(_event,win)=>{win.setContentSize(1280,900);win.webContents.setBackgroundThrottling(false);win.webContents.once('did-finish-load',async()=>{
 const watch=setInterval(async()=>{try{fs.writeFileSync(path.join(out,'progress.json'),JSON.stringify(await win.webContents.executeJavaScript('({status:els.status.textContent,overview:state.overview,ready:state.viewReady,stats:state.view?.stream?.stats(),draw:state.view?.drawStats})')));}catch{}},2000);
 try {
  const result=await win.webContents.executeJavaScript(`(async()=>{
   const wait=ms=>new Promise(r=>setTimeout(r,ms)),check=(v,m)=>{if(!v)throw Error(m+' '+els.log.textContent);};
   await window.__uiReady;await window.engine.configureSceneCache({limitMB:4096,clear:true});
   const begin=performance.now(),loading=loadFile(${JSON.stringify(path.resolve(root,'../../璃月港.litematic'))});
   let overviewMillis=null,overviewReadyMillis=null,detailMillis=null,inspectPosition=null;const deadline=Date.now()+240000;
   while(state.rendering||state.scenePreparing||!state.viewReady){
    if(Date.now()>deadline)throw Error('Large open timed out');
    if(state.overview&&state.view.drawStats?.visibleQuads&&overviewMillis===null)overviewMillis=performance.now()-begin;
    if(state.overview&&state.viewReady&&overviewReadyMillis===null)overviewReadyMillis=performance.now()-begin;
    const stream=state.view?.stream;
    if(stream?.progressive&&!inspectPosition){
     const section=stream.manifest.sections.find(s=>s.fullReady&&s.levels[0].some(p=>p.quads));
     if(section){inspectPosition=section.position.slice();state.fly=true;state.viewDistance=2;state.flyPos=[section.center[0],section.center[1]+8,section.center[2]-24];state.flyYaw=Math.PI;state.flyPitch=-Math.atan2(8,24);drawView();}
    }
    if((state.firstDetailLoaded||stream?.stats().detailResidents>0)&&detailMillis===null)detailMillis=performance.now()-begin;
    await wait(20);
   }
   await loading;await state.view.stream.settled();drawView();
   const fullMillis=performance.now()-begin,cold=state.view.stream.stats();
   check(overviewReadyMillis!==null,'Missing overview');
   check(state.view.stream.manifest.sections.length===11206,'Missing ownership sections');
   check(cold.peakBytes<=cold.budgetBytes&&cold.transferPeakBytes<=cold.transferLimitBytes,'Geometry budget exceeded');
   check(detailMillis!==null&&cold.lodCounts[0]>0,'Generated geometry never appeared at a close camera');
   check([...state.view.stream.residents.values()].some(r=>r.level===0&&r.pages.some(p=>p.quads&&!p.proxy)),'Only color proxies reached the GPU');
   const closeCold=cold;
   state.fly=false;drawView();await state.view.stream.settled();drawView();
   const warmBegin=performance.now();await loadFile(state.file);await state.view.stream.settled();drawView();const warmMillis=performance.now()-warmBegin;
   check(els.log.textContent.includes('已复用磁盘场景缓存'),'Warm open rebuilt');
   const inspect=async(label,lod)=>{
    const stream=state.view.stream,section=stream.manifest.sections.find(s=>s.position.every((v,i)=>v===inspectPosition[i]));check(section,'Inspection section disappeared');
    els.lodPreview.checked=lod;els.lodPreview.dispatchEvent(new Event('change'));state.fly=true;state.viewDistance=2;state.flyPos=[section.center[0],section.center[1]+8,section.center[2]-24];state.flyYaw=Math.PI;state.flyPitch=-Math.atan2(8,24);
    await wait(100);drawView();await stream.settled();drawView();const stats=stream.stats();
    check(stats.lodCounts[0]>0&&state.view.drawStats.visibleQuads>0,label+' did not show full geometry');check(!stream.failed.size,label+' had failed pages');
    check(stats.peakBytes<=stats.budgetBytes,label+' exceeded memory budget');return stats;
   };
   const closeWarm=await inspect('Warm close view',true),lodDisabled=await inspect('LOD disabled',false);
   check(state.view.gl.getError()===0&&!state.view.gl.isContextLost(),'WebGL error');
   state.fly=false;
   await loadFile(${JSON.stringify(path.resolve(root,'../../1.8w-潜影贝收集.litematic'))});await state.view.stream.settled();drawView();check(state.viewReady,'Small-file replacement failed');
   return {pass:true,overviewMillis,overviewReadyMillis,detailMillis,fullMillis,warmMillis,cold,closeCold,closeWarm,lodDisabled,log:els.log.textContent};
  })()`);
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({...result,calls},null,2));
  clearInterval(watch);console.log(JSON.stringify({...result,log:undefined,calls}));app.quit();
 }catch(error){clearInterval(watch);fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,error:error.stack,calls},null,2));console.error(error.stack);app.exit(1);}
});});
require(path.join(root,'webapp/main.js'));setTimeout(()=>app.exit(1),360000).unref();
