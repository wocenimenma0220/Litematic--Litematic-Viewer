const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=process.argv[2]||path.resolve(__dirname,'../..'),input=process.argv[3]||'D:/litematic-display/32铜人.litematic',out=path.join(__dirname,'copper-statue-output'),runtime=path.join(root,'tools/verify/copper-statue-runtime');process.argv=process.argv.slice(0,2);
fs.mkdirSync(out,{recursive:true});app.setPath('userData',path.join(out,'electron'));
process.env.JAVA_TOOL_OPTIONS=`-Dlitematicrender.cache.directory=${runtime.replaceAll('\\','/')}/cache/scenes -Dlitematicrender.skins.offline=true`;
const settings=require(path.join(root,'webapp/ui-settings')),Settings=settings.UiSettings;settings.UiSettings=class extends Settings{constructor(){super(runtime);}};
const cleaner=require(path.join(root,'webapp/cache-cleanup')),clean=cleaner.clearRenderingCache;cleaner.clearRenderingCache=()=>clean(runtime);
app.commandLine.appendSwitch('disable-renderer-backgrounding');BrowserWindow.prototype.show=function(){};
const {EngineClient}=require(path.join(root,'webapp/engine-client')),call=EngineClient.prototype.call,calls=[];
EngineClient.prototype.call=function(method,params){return call.call(this,method,params).then(result=>{if(['exportScene','previewResult','previewLive'].includes(method))calls.push({method,overview:result.overview,quads:result.quads,hit:result.diskCacheHit});return result;});};
let failed=false;app.on('will-quit',()=>{if(failed)app.exit(1);});
app.once('browser-window-created',(_event,win)=>{win.webContents.setBackgroundThrottling(false);win.webContents.once('did-finish-load',async()=>{
 try{
  const result=await win.webContents.executeJavaScript(`(async()=>{
   const check=(value,message)=>{if(!value)throw Error(message+' '+els.log.textContent);};await window.__uiReady;if(els.welcome.open)await dismissWelcome(false);await window.engine.loadResources(null);
   state.largeProjection=false;els.largeProjection.checked=false;state.lodPreview=false;els.lodPreview.checked=false;state.geometryBudgetMB=128;els.geometryBudget.value='128';state.preloadMode='streaming';els.preloadMode.value='streaming';
   els.lightingEnabled.checked=true;els.realLighting.checked=true;await window.engine.configureSceneCache({limitMB:4096,clear:true});
   await loadFile(${JSON.stringify(input)});drawView();check(state.viewReady&&!state.scenePreparing&&state.view.quadCount===1824,'ordinary statue model missing');check(state.view.gl.getError()===0,'ordinary GPU error');
   const ordinary=state.view.canvas.toDataURL('image/png');
   state.largeProjection=true;els.largeProjection.checked=true;await buildView({force:true});await state.view.stream.settled();drawView();
   const stats=state.view.stream.stats();check(stats.progressComplete&&stats.detailResidents>0&&stats.lodCounts[0]>0,'streamed full detail missing');check(state.view.gl.getError()===0,'streaming GPU error');
   const streamed=state.view.canvas.toDataURL('image/png');await buildView({preserveCamera:true});await state.view.stream.settled();drawView();check(els.log.textContent.includes('已复用磁盘场景缓存'),'statue cache did not reopen');check(state.view.quadCount===1824,'cached scene face count wrong');
   return {pass:true,stats,ordinary,streamed,log:els.log.textContent};
  })()`);
  assert(calls.some(c=>c.method==='exportScene'&&c.quads===1824));assert(calls.some(c=>c.method==='previewResult'&&!c.overview&&c.quads===1824));assert(calls.some(c=>c.hit===true));
  for(const name of ['ordinary','streamed']){fs.writeFileSync(path.join(out,name+'-gpu.png'),Buffer.from(result[name].split(',')[1],'base64'));delete result[name];}
  fs.writeFileSync(path.join(out,'ui-result.json'),JSON.stringify({...result,calls},null,2));console.log(JSON.stringify({...result,log:undefined,calls}));win.close();
 }catch(error){failed=true;console.error(error.stack);fs.writeFileSync(path.join(out,'ui-result.json'),JSON.stringify({pass:false,error:error.stack,calls},null,2));win.close();}
});});require(path.join(root,'webapp/main.js'));setTimeout(()=>{failed=true;app.quit();},180000).unref();
