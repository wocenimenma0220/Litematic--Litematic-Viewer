const {app,BrowserWindow,dialog,nativeImage}=require('electron'),fs=require('node:fs'),path=require('node:path');
const repo=path.resolve(__dirname,'../..'),root=process.argv[2]||repo,smoke=process.argv.includes('--smoke');process.argv=process.argv.slice(0,2);
const out=path.join(__dirname,'pack-management-output',root===repo?'source':'package');fs.mkdirSync(out,{recursive:true});
const progress=phase=>fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,running:true,phase,time:new Date().toISOString()}));
progress('startup');
app.setPath('userData',path.join(out,'electron'));BrowserWindow.prototype.show=function(){};
const files=['settings/ui.json','settings/removed-builtins.json','renders/packs.json'].map(p=>path.join(root,p));
const saved=files.map(file=>fs.existsSync(file)?fs.readFileSync(file):null);
const restore=()=>files.forEach((file,i)=>{if(saved[i])fs.writeFileSync(file,saved[i]);else if(fs.existsSync(file))fs.unlinkSync(file);});
for(const file of files)fs.mkdirSync(path.dirname(file),{recursive:true});
fs.writeFileSync(files[0],JSON.stringify({hideWelcome:true,previewBackend:'gpu',backgroundMode:'checker'}));
fs.writeFileSync(files[1],'[]');fs.writeFileSync(files[2],'[]');app.on('will-quit',restore);
const selected=[];dialog.showOpenDialog=async()=>({canceled:false,filePaths:[selected.shift()]});
const packs=['red','blue'].map((name,i)=>{
 const directory=path.join(out,name),textures=path.join(directory,'assets/minecraft/textures/block');fs.mkdirSync(textures,{recursive:true});
 const png=nativeImage.createFromBitmap(Buffer.from(i?[255,0,0,255]:[0,0,255,255]),{width:1,height:1}).toPNG();
 for(const texture of ['piston_top_sticky','piston_top'])fs.writeFileSync(path.join(textures,texture+'.png'),png);
 return directory;
});
app.once('browser-window-created',(_event,win)=>win.webContents.once('did-finish-load',async()=>{
 const run=code=>win.webContents.executeJavaScript('(async()=>{'+code+'})()');
 const settle=`const deadline=Date.now()+45000;while(state.rendering||cpuPreviewRunning||cpuPreviewPending){if(Date.now()>deadline)throw Error('pack change timeout');await new Promise(r=>setTimeout(r,20));}`;
 try{
  const names=await run(`await window.__uiReady;
   const deadline=Date.now()+45000;while(!els.packList.querySelector('[data-pack="vanilla"]')){if(Date.now()>deadline)throw Error('pack startup timeout: '+els.log.textContent);await new Promise(r=>setTimeout(r,20));}
   if(els.packList.querySelector('[data-pack="vanilla"] .packname').textContent!=='mc默认材质包')throw Error('vanilla pack name missing');
   if(els.packList.querySelectorAll('[data-act="remove-builtin"]').length!==2)throw Error('two bundled packs must be removable');
   els.viewport.style.width=els.viewport.style.minWidth=els.viewport.style.maxWidth='640px';els.viewport.style.height=els.viewport.style.minHeight=els.viewport.style.maxHeight='384px';els.viewport.style.flex='0 0 auto';
   await loadFile(${JSON.stringify(path.join(__dirname,'state-model-output/pistons.litematic'))});
   els.rotation.value='73';els.slant.value='22';state.zoom=1.2;drawView();return window.__builtinPacks.slice();`);
  for(let i=0;i<(smoke?1:2);i++) {
   progress('remove builtin '+i);
   if(i===1)await run(`els.previewBackend.value='cpu';els.previewBackend.dispatchEvent(new Event('change'));${settle}`);
   await run(`els.packList.querySelector('[data-act="remove-builtin"]').click();${settle}
    if(window.__builtinPacks.length!==${1-i}||els.packList.querySelectorAll('[data-act="remove-builtin"]').length!==${1-i})throw Error('deleted builtin still listed');
    if(els.rotation.value!=='73'||els.slant.value!=='22'||state.zoom!==1.2)throw Error('pack change reset camera');
    if(!state.viewReady&&!state.cpuPreviewReady)throw Error('pack removal destroyed preview');
    await window.engine.renderPreview({...renderParams(128,128),transparent:true});`);
  }
  if(JSON.parse(fs.readFileSync(files[1],'utf8')).length!==(smoke?1:2))throw Error('removed builtin preferences not saved');
  const {EngineClient}=require(path.join(root,'webapp/engine-client')),{resolveRuntime}=require(path.join(root,'webapp/runtime'));
  progress('fresh engine');
  const fresh=new EngineClient({...resolveRuntime(root),home:root});
  try{await fresh.start();const resources=await fresh.call('loadResources',{});if(resources.builtinPacks.length!==(smoke?1:0))throw Error('fresh engine reloaded deleted builtins');}finally{fresh.stop();}
  if(smoke){
   await run(`els.packList.scrollIntoView({block:'center'});await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));`);
   fs.writeFileSync(path.join(out,'pack-list.png'),(await win.webContents.capturePage()).toPNG());
   const result={pass:true,smoke:true,defaultName:'mc默认材质包',gpuRebuild:true,cameraPreserved:true,freshEnginePersistsRemoval:true};
   fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));app.quit();return;
  }
  const images=[];
  for(const pack of packs){progress('import '+path.basename(pack));selected.push(pack);images.push(await run(`await importPack();${settle}return els.preview.src;`));}
  if(images[0]===images[1])throw Error('imported pack priority did not change actual CPU pixels');
  progress('priority');const redAgain=await run(`await movePack(${JSON.stringify(packs[1])},'up');${settle}return els.preview.src;`);
  if(redAgain!==images[0])throw Error('priority order not applied');
  progress('remove imported');const blueAgain=await run(`await removePack(${JSON.stringify(packs[0])});${settle}return els.preview.src;`);
  if(blueAgain!==images[1])throw Error('user pack removal not applied');
  progress('reload');const loaded=new Promise(resolve=>win.webContents.once('did-finish-load',resolve));win.webContents.reload();await loaded;
  await run(`await window.__uiReady;const deadline=Date.now()+45000;while(!els.packList.querySelector('[data-pack="vanilla"]')){if(Date.now()>deadline)throw Error('pack reload timeout');await new Promise(r=>setTimeout(r,20));}
   if(window.__builtinPacks.length!==0||state.packs.length!==1||els.packList.querySelectorAll('.pack').length!==2)throw Error('pack list not restored on startup');`);
  for(const name of names){progress('restore '+name);selected.push(path.join(root,'assets/builtin',name));await run('await importPack();');}
  await run(`if(window.__builtinPacks.length!==2||state.packs.length!==1)throw Error('bundled pack reimport duplicated or failed');
   try{await window.packs.removeBuiltin('../vanilla');throw Error('invalid pack accepted');}catch(error){if(error.message==='invalid pack accepted')throw error;}
   els.packList.scrollIntoView({block:'center'});await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));`);
  fs.writeFileSync(path.join(out,'pack-list.png'),(await win.webContents.capturePage()).toPNG());
  const result={pass:true,defaultName:'mc默认材质包',removed:names,gpuAndCpuRebuild:true,cameraPreserved:true,freshEnginePersistsRemoval:true,importedPackPixelsChange:true,priorityPixelsExact:true,startupRestoresList:true,builtinReimportRestores:true};
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));app.quit();
 }catch(error){restore();fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,error:error.stack}));console.error(error);app.exit(1);}
}));
require(path.join(root,'webapp/main.js'));setTimeout(()=>{restore();console.error('Pack management regression timed out');app.exit(1);},390000).unref();
