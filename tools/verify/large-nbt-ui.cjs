const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=process.argv[2]||path.resolve(__dirname,'../..'),mode=process.argv[3]||'on';process.argv=process.argv.slice(0,2);
const staged=path.resolve(root)!==path.resolve(__dirname,'../..');
const out=path.join(__dirname,'large-nbt-output/ui-'+(staged?'package-':'')+mode),runtime=staged?path.join(root,'tools/verify/large-nbt-ui-'+mode):path.join(out,'runtime');fs.mkdirSync(runtime,{recursive:true});app.setPath('userData',path.join(out,'electron'));
process.env.JAVA_TOOL_OPTIONS=`-Dlitematicrender.cache.directory=${runtime.replaceAll('\\','/')}/cache/scenes -Dlitematicrender.skins.offline=true`;
app.commandLine.appendSwitch('disable-backgrounding-occluded-windows');app.commandLine.appendSwitch('disable-renderer-backgrounding');
BrowserWindow.prototype.show=function(){this.setPosition(-20000,-20000,false);this.showInactive();};
const settingsModule=require(path.join(root,'webapp/ui-settings')),Settings=settingsModule.UiSettings;settingsModule.UiSettings=class extends Settings{constructor(){super(runtime);}};
const cleanupModule=require(path.join(root,'webapp/cache-cleanup')),cleanup=cleanupModule.clearRenderingCache;cleanupModule.clearRenderingCache=()=>cleanup(runtime);
let result,testFailed=false;
app.on('will-quit',()=>{
 if(!testFailed&&result){
  try{
   const scenes=fs.existsSync(path.join(runtime,'cache/scenes')),previews=fs.existsSync(path.join(runtime,'cache/previews'));
   if(scenes!==(mode==='off')||previews!==(mode==='off'))throw Error('cache retention after exit does not match setting');
   if(fs.readFileSync(path.join(runtime,'skins/local/keep'),'utf8')!=='local skin')throw Error('local skin was removed');
   if(!fs.existsSync(path.join(runtime,'settings/ui.json')))throw Error('settings were removed');
   result.exitVerified=true;result.cacheAfterExit={scenes,previews};fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log('PASS: exit retention and user-data checks');
  }catch(error){console.error(error);testFailed=true;}
 }
 if(testFailed)app.exit(1);
});
app.once('browser-window-created',(_event,win)=>{win.webContents.setBackgroundThrottling(false);win.webContents.once('did-finish-load',async()=>{
 try{
  result=await win.webContents.executeJavaScript(`(async()=>{
   const wait=ms=>new Promise(r=>setTimeout(r,ms)),check=(v,m)=>{if(!v)throw Error(m+' '+els.log.textContent);};
   await window.__uiReady;if(els.welcome.open)await dismissWelcome(false);await window.engine.loadResources(null);
   const defaults=await window.appUi.settings();check(defaults.clearCacheOnExit===true&&$('clearCacheOnExit').checked,'exit cleanup default disabled');
   $('clearCacheOnExit').click();while($('clearCacheOnExit').disabled)await wait(20);check((await window.appUi.settings()).clearCacheOnExit===false,'cleanup opt-out not saved');
   if(${mode==='on'}){$('clearCacheOnExit').click();while($('clearCacheOnExit').disabled)await wait(20);check((await window.appUi.settings()).clearCacheOnExit===true,'cleanup opt-in not saved');}
   state.largeProjection=true;els.largeProjection.checked=true;state.lodPreview=true;els.lodPreview.checked=true;els.lightingEnabled.checked=true;els.realLighting.checked=true;
   await loadFile(${JSON.stringify(path.join(__dirname,'feature-output/lod-current/dense.litematic'))});await state.view.stream.settled();drawView();check(state.viewReady&&!state.overview,'initial large scene unavailable');
   let openMillis=null,overviewMillis=null;
   if(${mode==='on'}){
    const start=performance.now(),loading=loadFile('D:/litematic-display/故宫.litematic');let timeout=Date.now()+240000;
    while(!state.overview||!state.viewReady){if(Date.now()>timeout)throw Error('city overview timed out '+els.status.textContent);if(!state.rendering&&!state.scenePreparing&&!state.viewReady)throw Error('city open failed '+els.log.textContent);if(state.file==='D:/litematic-display/故宫.litematic'&&openMillis===null)openMillis=performance.now()-start;await wait(100);}
    overviewMillis=performance.now()-start;check(state.info.regionCount===122,'city regions were lost');
    const visibleDeadline=Date.now()+30000;while(!state.view.drawStats?.visibleQuads){if(Date.now()>visibleDeadline)throw Error('city has no visible overview');drawView();await wait(100);}
    check(state.view.gl.getError()===0,'city overview WebGL error');
    window.__cityScreenshotReady=true;const captureDeadline=Date.now()+10000;while(!window.__cityScreenshotSaved){if(Date.now()>captureDeadline)throw Error('city screenshot timed out');await wait(50);}
    await loadFile(${JSON.stringify(path.join(__dirname,'feature-output/water-trapdoors.litematic'))});await loading;await state.view.stream.settled();drawView();check(state.viewReady&&!state.overview&&!state.scenePreparing,'replacement after city failed');
   }
   const cache=await window.engine.sceneCacheStats();check(cache.entries>0,'no cache exists before exit');return {pass:true,mode:${JSON.stringify(mode)},openMillis,overviewMillis,cache,settings:await window.appUi.settings(),log:els.log.textContent};
  })()`);
  fs.writeFileSync(path.join(out,'preview.png'),(await win.webContents.capturePage()).toPNG());fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));
  fs.mkdirSync(path.join(runtime,'cache/previews/exit-test'),{recursive:true});fs.writeFileSync(path.join(runtime,'cache/previews/exit-test/data'),'temporary');
  fs.mkdirSync(path.join(runtime,'skins/local'),{recursive:true});fs.writeFileSync(path.join(runtime,'skins/local/keep'),'local skin');
  if(mode==='on'){
   result.exitJob=await win.webContents.executeJavaScript(`(async()=>{await window.engine.open('D:/litematic-display/故宫.litematic');const job=await window.engine.beginPreview({force:true,realLighting:false,lightingEnabled:false});return await window.engine.previewStatus(job.id);})()`);
   fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));
  }
  console.log(JSON.stringify({...result,settings:undefined,log:undefined}));win.close();
 }catch(error){testFailed=true;console.error(error.stack);fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,error:error.stack},null,2));app.quit();}
});});
const capture=setInterval(async()=>{try{const win=BrowserWindow.getAllWindows()[0];if(win){fs.writeFileSync(path.join(out,'progress.json'),JSON.stringify(await win.webContents.executeJavaScript('({status:els.status.textContent,overview:state.overview,ready:state.viewReady,stats:state.view?.stream?.stats()})')));if(await win.webContents.executeJavaScript('window.__cityScreenshotReady===true')){clearInterval(capture);fs.writeFileSync(path.join(out,'city.png'),(await win.webContents.capturePage()).toPNG());await win.webContents.executeJavaScript('window.__cityScreenshotSaved=true');}}}catch{}},1000);
capture.unref();require(path.join(root,'webapp/main.js'));setTimeout(()=>app.exit(1),360000).unref();
