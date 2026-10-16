const {app,BrowserWindow,dialog,shell,nativeImage}=require('electron');
const fs=require('node:fs'),path=require('node:path');
const repo=path.resolve(__dirname,'../..'),root=process.argv[2]||repo;process.argv=process.argv.slice(0,2);
const out=path.join(__dirname,'transparent-output',root===repo?'source':'package');fs.mkdirSync(out,{recursive:true});
process.env.JAVA_TOOL_OPTIONS=(process.env.JAVA_TOOL_OPTIONS||'')+' -Dlitematicrender.skins.offline=true';
app.setPath('userData',path.join(out,'electron'));BrowserWindow.prototype.show=function(){};
app.commandLine.appendSwitch('disable-renderer-backgrounding');
const settingsModule=require(path.join(root,'webapp/ui-settings')),OriginalSettings=settingsModule.UiSettings;settingsModule.UiSettings=class extends OriginalSettings{constructor(){super(out);}};
const settingsFile=path.join(out,'settings/ui.json'),saved=fs.existsSync(settingsFile)?fs.readFileSync(settingsFile):null;
const backgroundFile=path.join(out,'settings/background.img'),savedBackground=fs.existsSync(backgroundFile)?fs.readFileSync(backgroundFile):null;
const restore=()=>{
 if(saved)fs.writeFileSync(settingsFile,saved);else if(fs.existsSync(settingsFile))fs.unlinkSync(settingsFile);
 if(savedBackground)fs.writeFileSync(backgroundFile,savedBackground);else if(fs.existsSync(backgroundFile))fs.unlinkSync(backgroundFile);
};
fs.mkdirSync(path.dirname(settingsFile),{recursive:true});fs.writeFileSync(settingsFile,JSON.stringify({hideWelcome:true,backgroundMode:'checker',previewBackend:'gpu'}));
app.on('will-quit',restore);
const saves=[];dialog.showSaveDialog=async()=>{if(!saves.length)throw Error('Unexpected save dialog');return {canceled:false,filePath:saves.shift()};};
shell.showItemInFolder=()=>{};
const bg=path.join(out,'background.png');fs.writeFileSync(bg,nativeImage.createFromBitmap(Buffer.from([0,0,255,255,255,255,0,255]),{width:2,height:1}).toPNG());
dialog.showOpenDialog=async()=>({canceled:false,filePaths:[bg]});
const {EngineClient}=require(path.join(root,'webapp/engine-client')),original=EngineClient.prototype.call;
let engine;const animations=[];EngineClient.prototype.call=function(method,params){if(!engine)engine=this;return original.call(this,method,params).then(result=>{if(method==='renderAnimation')animations.push({params,result});return result;});};
app.once('browser-window-created',(_event,win)=>win.webContents.once('did-finish-load',async()=>{
 const run=code=>win.webContents.executeJavaScript('(async()=>{'+code+'})()');
 const exportAndWait=action=>run(`await ${action}();if(state.rendering||!els.busy.classList.contains('hidden'))throw Error('Export blocked the preview');const id=[...exportJobs.keys()].at(-1),end=Date.now()+120000;while(!['completed','failed','cancelled'].includes(exportJobs.get(id)?.state)){if(Date.now()>end)throw Error('Background export timeout');await new Promise(r=>setTimeout(r,25));}if(exportJobs.get(id).state!=='completed')throw Error('Background export failed: '+exportJobs.get(id).error);`);
 try{
  const first=await run(`await window.__uiReady;await window.engine.loadResources(null);
   await loadFile(${JSON.stringify(path.join(repo,'tools/verify/fly-output/inspection.litematic'))});
   if(!state.viewReady)throw Error('GPU preview unavailable');
   els.size.add(new Option('192 × 192','192'));els.size.value='192';
   if(!els.transparent.checked||!els.transparent.disabled||!els.viewport.classList.contains('checker'))throw Error('Default grid is not transparently exported');
   return renderParams(192,192);`);
  saves.push(path.join(out,'grid-export.png'));await exportAndWait('exportPng');
  saves.push(path.join(out,'grid-export.gif'));await exportAndWait('exportGif');
  if(animations.length!==1||animations[0].params.delay!==2||animations[0].result.delayCentiseconds!==2||animations[0].result.frames!==480)throw Error('UI GIF must rotate once at 50 FPS with 480 frames over 9.6 seconds');
  if(!(await run("return document.querySelector('#btnExportGif').disabled===false&&document.body.textContent.includes('缩放固定')&&document.body.textContent.includes('9.6 秒');")))throw Error('fixed-scale GIF controls or duration hint missing');
  await run(`els.bgMode.value='white';els.bgMode.dispatchEvent(new Event('change'));
   if(els.transparent.checked||els.transparent.disabled)throw Error('Selecting white did not enable opaque export');`);
  saves.push(path.join(out,'white.png'));await exportAndWait('exportPng');
  await run(`els.transparent.checked=true;els.transparent.dispatchEvent(new Event('change'));`);
  saves.push(path.join(out,'override.png'));await exportAndWait('exportPng');
  await run(`const background=await window.appUi.importBackground();useBackground(background);
   if(els.transparent.checked)throw Error('Imported image stayed transparent');
   await window.engine.render({...renderParams(192,128),out:${JSON.stringify(path.join(out,'image-export.png'))}});
   await window.engine.renderAnimation({...renderParams(96,64),out:${JSON.stringify(path.join(out,'image-export.gif'))},frames:2});
   els.bgMode.value='checker';els.bgMode.dispatchEvent(new Event('change'));
   els.previewBackend.value='cpu';els.previewBackend.dispatchEvent(new Event('change'));
   const end=Date.now()+30000;while(state.rendering||cpuPreviewRunning||cpuPreviewPending){if(Date.now()>end)throw Error('CPU preview timeout');await new Promise(r=>setTimeout(r,20));}
   if(!state.cpuPreviewReady||!els.viewport.classList.contains('checker'))throw Error('CPU checker preview failed');`);
  saves.push(path.join(out,'cpu-grid.png'));await exportAndWait('exportPng');
  if(animations[1].result.delayCentiseconds!==2)throw Error('RPC animation default delay differs from the UI');
  // Bypass Electron's parameter normalization to exercise the Java RPC precedence too.
  await original.call(engine,'render',{width:192,height:192,transparent:true,background:0xFFFFFF,out:path.join(out,'rpc-override.png')});
  await original.call(engine,'render',{width:192,height:192,transparent:false,backgroundMode:'checker',background:0xFFFFFF,out:path.join(out,'rpc-checker.png')});
  for(const name of ['grid-export.png','grid-export.gif','white.png','override.png','image-export.png','image-export.gif','cpu-grid.png','rpc-override.png','rpc-checker.png'])if(!fs.existsSync(path.join(out,name)))throw Error('Missing export: '+name);
  const result={pass:true,version:require(path.join(root,'webapp/package.json')).version,defaultParams:first,actualSaveDialogs:true,gpuAndCpu:true,gridPngGif:true,opaqueBackgrounds:true,explicitTransparent:true,rawRpc:true,rotationDurationMillis:animations[0].result.frames*animations[0].result.delayCentiseconds*10,fixedScaleHint:true};
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));app.quit();
 }catch(error){restore();fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,error:error.stack}));console.error(error);app.exit(1);}
}));
require(path.join(root,'webapp/main.js'));setTimeout(()=>{restore();console.error('Transparency test timed out');app.exit(1);},300000).unref();
