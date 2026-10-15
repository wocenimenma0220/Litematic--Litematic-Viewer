const {app,BrowserWindow,ipcMain}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../..'),out=path.join(__dirname,'lod-large-output');
const {SceneTransfer}=require(path.join(root,'webapp/scene-transfer'));const transfer=new SceneTransfer();
app.setPath('userData',path.join(out,'electron'));process.argv=process.argv.slice(0,2);
const save=value=>fs.writeFileSync(path.join(out,'gpu-result.json'),JSON.stringify(value,null,2));
app.whenReady().then(async()=>{
 const win=new BrowserWindow({width:1200,height:800,show:false,webPreferences:{nodeIntegration:true,contextIsolation:false,backgroundThrottling:false}});
 const info=await transfer.open(path.join(out,'scene.lmesh'),win.webContents.id);
 ipcMain.handle('lod:read',(event,offset,length)=>transfer.read(event.sender.id,info.id,offset,length));
 const scripts=['gpu-view.js','lod-stream.js'].map(file=>`<script src="${require('node:url').pathToFileURL(path.join(root,'webapp/renderer',file)).href}"></script>`).join('');
 const html=path.join(out,'gpu.html');fs.writeFileSync(html,`<html><body style="margin:0;background:#ddd"><canvas id="view" style="width:1152px;height:720px"></canvas>${scripts}</body></html>`);
 try{
  await win.loadFile(html);save({pass:false,running:true,phase:'overview'});
  const atlas=fs.readFileSync(path.join(out,'atlas.png')).toString('base64');
  const result=await win.webContents.executeJavaScript(`(async()=>{
   const view=new GpuView(document.getElementById('view')),errors=[];const start=performance.now();let firstChunkMillis=null;
   const read=(offset,length)=>require('electron').ipcRenderer.invoke('lod:read',offset,length);
   await view.uploadLodStream(read,${info.size},{onError:e=>errors.push(String(e)),onChange:()=>{if(firstChunkMillis===null&&view.stream.residents.size)firstChunkMillis=performance.now()-start;}});
   const image=new Image();image.src='data:image/png;base64,${atlas}';await image.decode();view.uploadAtlas(image);
   let camera={orbit:true,rotationDeg:45,slantDeg:35,zoom:1};view.draw(camera);await view.stream.settled();view.draw(camera);
   const overview={...view.stream.stats(),settledMillis:performance.now()-start,firstChunkMillis};
   if(!overview.lodCounts.slice(1).some(Boolean))throw Error('Large overview never used LOD');
   if(overview.budgetLimited||overview.residentSections!==view.stream.manifest.sections.length)throw Error('Large overview dropped sections instead of covering the model');
   if(overview.bytesRead>=${info.size}*.75)throw Error('Large overview loaded too much of the file');
   const moves=[];
   const samples=[.2,.8,.4].map(r=>view.stream.manifest.sections[Math.min(view.stream.manifest.sections.length-1,Math.floor(view.stream.manifest.sections.length*r))]);
   for(const [index,section] of samples.entries()){
    const c=section.center;camera={eye:[c[0],c[1]+8,c[2]-24],target:c,fovDeg:70,viewDistance:index===1?128:6};
    view.draw(camera);view.stream.plan(camera,frustumPlanes(view.lastMatrix),true);await view.stream.settled();view.draw(camera);
    const expected=view.stream.manifest.sections.filter(s=>inRenderDistance(s,camera)).length;
    if(view.stream.residents.size!==expected||[...view.stream.residents.values()].some(r=>!inRenderDistance(r.section,camera)))throw Error('Large render distance did not constrain geometry residency');
    moves.push({...view.stream.stats(),viewDistance:camera.viewDistance});
   }
   if(!moves.some(s=>s.lodCounts[0])||!moves.at(-1).evictions)throw Error('Large fly view did not refine and evict geometry');
   if(view.stream.peakBytes>view.stream.budgetBytes||errors.length||view.gl.getError()||view.gl.isContextLost())throw Error('Large streaming failure: '+errors);
   camera={orbit:true,rotationDeg:45,slantDeg:35,zoom:1};view.draw(camera);view.stream.plan(camera,frustumPlanes(view.lastMatrix),true);await view.stream.settled();view.draw(camera);
   const result={pass:true,wireBytes:${info.size},sections:view.stream.manifest.sections.length,quads:view.stream.manifest.quads,overview,moves,final:view.stream.stats(),errors};window.testView=view;return result;
  })()`);
  fs.writeFileSync(path.join(out,'overview.png'),(await win.webContents.capturePage()).toPNG());save(result);console.log(JSON.stringify(result));
  await win.webContents.executeJavaScript('window.testView.disposeBuffers()');await transfer.close();app.quit();
 }catch(error){save({pass:false,error:error.stack});console.error(error);await transfer.close();app.exit(1);}
});setTimeout(()=>{save({pass:false,error:'Large GPU timeout'});app.exit(1);},480000).unref();
