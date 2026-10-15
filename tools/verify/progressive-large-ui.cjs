const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=process.argv[2]||path.resolve(__dirname,'../..');process.argv=process.argv.slice(0,2);
const out=path.join(__dirname,'cache-large-output/ui');fs.mkdirSync(out,{recursive:true});app.setPath('userData',path.join(out,'electron'));
fs.rmSync(path.join(out,'coarse.png'),{force:true});
process.env.JAVA_TOOL_OPTIONS=`-Dlitematicrender.cache.directory=${root.replaceAll('\\','/')}/tools/verify/cache-large-output/ui-scenes -Dlitematicrender.skins.offline=true`;
app.commandLine.appendSwitch('disable-backgrounding-occluded-windows');app.commandLine.appendSwitch('disable-renderer-backgrounding');
BrowserWindow.prototype.show=function(){this.setPosition(-20000,-20000,false);this.showInactive();};
const sm=require(path.join(root,'webapp/ui-settings')),Settings=sm.UiSettings;sm.UiSettings=class extends Settings{constructor(){super(out);}};
const {EngineClient}=require(path.join(root,'webapp/engine-client')),original=EngineClient.prototype.call,calls=[];
EngineClient.prototype.call=function(method,params){return original.call(this,method,params).then(result=>{if(method==='previewResult'){calls.push({overview:result.overview,hit:result.diskCacheHit,builds:result.meshBuildCount,bytes:result.meshBytes,mesh:result.mesh,sections:result.sections,quads:result.quads});}return result;});};
app.once('browser-window-created',(_event,win)=>{win.webContents.setBackgroundThrottling(false);win.webContents.once('did-finish-load',async()=>{
 const capture=setInterval(async()=>{try{const state=await win.webContents.executeJavaScript('({overview:state.overview,ready:state.viewReady,rendering:state.rendering,preparing:state.scenePreparing,stats:state.view?.stream?.stats()})');fs.writeFileSync(path.join(out,'progress.json'),JSON.stringify(state));if(state.overview&&state.ready&&!fs.existsSync(path.join(out,'coarse.png')))fs.writeFileSync(path.join(out,'coarse.png'),(await win.webContents.capturePage()).toPNG());}catch{}},2000);
 try{const result=await win.webContents.executeJavaScript(`(async()=>{
  const wait=ms=>new Promise(r=>setTimeout(r,ms)),check=(v,m)=>{if(!v)throw Error(m+' '+els.log.textContent);};
  await window.__uiReady;if(els.welcome.open)await dismissWelcome(false);await window.engine.loadResources(null);
  state.largeProjection=true;els.largeProjection.checked=true;state.lodPreview=true;els.lodPreview.checked=true;state.geometryBudgetMB=256;els.geometryBudget.value='256';els.realLighting.checked=true;els.lightingEnabled.checked=true;
  await window.engine.configureSceneCache({limitMB:4096,clear:true});
  const start=performance.now();let firstVisible=null,overview=false;const loading=loadFile('D:/litematic-display/璃月港.litematic');const end=Date.now()+180000;
  while(state.rendering||state.scenePreparing||!state.viewReady){if(Date.now()>end)throw Error('large load timeout '+els.log.textContent);if(state.overview&&state.viewReady){overview=true;if(state.view.drawStats?.visibleQuads&&firstVisible===null)firstVisible=performance.now()-start;}await wait(20);}
  await loading;check(overview&&firstVisible!==null,'coarse view never drew');await state.view.stream.settled();drawView();const coldMillis=performance.now()-start,cold=state.view.stream.stats();
  check(!cold.budgetLimited&&cold.peakBytes<=cold.budgetBytes&&cold.transferPeakBytes<=cold.transferLimitBytes,'large budget violation');
  const warmStart=performance.now();await buildView();await state.view.stream.settled();drawView();const warmMillis=performance.now()-warmStart,warm=state.view.stream.stats();check(els.log.textContent.includes('已复用磁盘场景缓存'),'large warm not cached');
  const stream=state.view.stream,positions=[.2,.8,.2],moves=[];
  state.fly=true;state.viewDistance=6;state.flyYaw=Math.PI;state.flyPitch=-Math.atan2(8,24);
  for(const f of positions){const section=stream.manifest.sections[Math.floor((stream.manifest.sections.length-1)*f)],c=section.center;state.flyPos=[c[0],c[1]+8,c[2]-24];drawView();stream.plan(flyCamera(),frustumPlanes(state.view.lastMatrix),true);await stream.settled();drawView();await stream.settled();moves.push(stream.stats());}
  check(moves.at(-1).cacheHits>moves[0].cacheHits,'A-B-A did not reuse geometry');check(moves.every(m=>m.peakBytes<=m.budgetBytes),'roaming budget exceeded');
  state.fly=false;drawView();await stream.settled();drawView();check(state.view.gl.getError()===0,'large WebGL error');
  return {pass:true,overview,firstVisible,coldMillis,warmMillis,cold,warm,moves,log:els.log.textContent};
 })()`);clearInterval(capture);fs.writeFileSync(path.join(out,'full.png'),(await win.webContents.capturePage()).toPNG());fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({...result,calls},null,2));console.log(JSON.stringify({...result,log:undefined,calls}));app.quit();
 }catch(error){clearInterval(capture);fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,error:error.stack,calls},null,2));console.error(error.stack);app.exit(1);}
});});require(path.join(root,'webapp/main.js'));setTimeout(()=>app.exit(1),300000).unref();
