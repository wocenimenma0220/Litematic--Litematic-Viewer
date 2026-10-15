const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const repo=path.resolve(__dirname,'../..'),root=process.argv[2]||repo;process.argv=process.argv.slice(0,2);
const out=path.join(__dirname,'preview-backend-output');fs.mkdirSync(out,{recursive:true});app.setPath('userData',path.join(out,'cpu-large-electron'));
BrowserWindow.prototype.show=function(){};
const settingsFile=path.join(root,'settings/ui.json'),saved=fs.existsSync(settingsFile)?fs.readFileSync(settingsFile):null;
const restore=()=>{if(saved)fs.writeFileSync(settingsFile,saved);else if(fs.existsSync(settingsFile))fs.unlinkSync(settingsFile);};app.on('will-quit',restore);
const {EngineClient}=require(path.join(root,'webapp/engine-client')),original=EngineClient.prototype.call;let gpuRequests=0;
EngineClient.prototype.call=function(method,params){if(method==='exportScene')gpuRequests++;return original.call(this,method,params);};
app.once('browser-window-created',(_event,win)=>win.webContents.once('did-finish-load',async()=>{
 try {
  const result=await win.webContents.executeJavaScript(`(async()=>{
   await window.__uiReady;if(els.welcome.open)await dismissWelcome(false);await window.engine.loadResources(null);
   els.previewBackend.value='cpu';els.previewBackend.dispatchEvent(new Event('change'));
   while(state.rendering)await new Promise(r=>setTimeout(r,20));
   els.viewport.style.width=els.viewport.style.minWidth=els.viewport.style.maxWidth='640px';
   els.viewport.style.height=els.viewport.style.minHeight=els.viewport.style.maxHeight='384px';els.viewport.style.flex='0 0 auto';
   const start=performance.now();await loadFile(${JSON.stringify(path.resolve(repo,'../../璃月港.litematic'))});
   if(!state.cpuPreviewReady||state.viewReady||state.view)throw Error('Large CPU preview failed: '+els.log.textContent);
   if(els.preview.naturalWidth<500||els.preview.classList.contains('hidden'))throw Error('Large CPU image missing');
   const image=document.createElement('canvas');image.width=els.preview.naturalWidth;image.height=els.preview.naturalHeight;
   const ctx=image.getContext('2d');ctx.drawImage(els.preview,0,0);if(!ctx.getImageData(0,0,image.width,image.height).data.some((v,i)=>i%4===3&&v))throw Error('Large CPU image empty');
   await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
   return {pass:true,largeBlocks:state.info.totalBlocks,width:els.preview.naturalWidth,height:els.preview.naturalHeight,cpuOnly:true,loadMillis:performance.now()-start};
  })()`);
  if(gpuRequests)throw Error('CPU preview exported GPU mesh');result.gpuExports=gpuRequests;
  fs.writeFileSync(path.join(out,'cpu-large.json'),JSON.stringify(result,null,2));fs.writeFileSync(path.join(out,'cpu-large.png'),(await win.webContents.capturePage()).toPNG());
  console.log(JSON.stringify(result));app.quit();
 }catch(error){restore();fs.writeFileSync(path.join(out,'cpu-large.json'),JSON.stringify({pass:false,error:error.stack}));console.error(error);app.exit(1);}
}));
require(path.join(root,'webapp/main.js'));setTimeout(()=>{restore();app.exit(1);},165000).unref();
