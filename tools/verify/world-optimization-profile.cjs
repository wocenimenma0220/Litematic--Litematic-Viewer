const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../..'),out=path.join(__dirname,'world-optimization');
app.setPath('userData',path.join(out,'frame-electron'));
app.whenReady().then(async()=>{
 const win=new BrowserWindow({show:false,width:1360,height:900,webPreferences:{contextIsolation:true}});
 try {
  await win.loadURL('data:text/html,<div style="width:960px;height:640px"><canvas id="view"></canvas></div>');
  const reports={};
  for(const variant of ['baseline','optimized']) {
   const source=fs.readFileSync(path.join(root,variant==='baseline'?'tools/verify/world-optimization/baseline/gpu-view.js':'webapp/renderer/gpu-view.js'),'utf8');
   await win.webContents.executeJavaScript('(()=>{'+source+';globalThis.ProfileGpu=GpuView;globalThis.profileView=new GpuView(document.getElementById("view"));})()');
   let bytes=fs.readFileSync(path.join(out,variant+'.bin'));
   if(variant==='baseline') {
    if(bytes.readInt32BE(bytes.length-4)!==0)throw new Error('Baseline unexpectedly has visibility metadata');
    bytes=Buffer.from(bytes.subarray(0,bytes.length-4));bytes[5]=51;
   }
   const mesh=bytes.toString('base64');
   const atlas=fs.readFileSync(path.join(out,'atlas.png')).toString('base64');
   reports[variant]=await win.webContents.executeJavaScript(`(${profile.toString()})(${JSON.stringify(mesh)},${JSON.stringify(atlas)})`);
  }
  fs.writeFileSync(path.join(out,'frames.json'),JSON.stringify(reports,null,2));console.log(JSON.stringify(reports));
  win.destroy();app.quit();
 }catch(error){console.error(error.stack);app.exit(1);}
});
async function profile(mesh,atlas) {
 const view=globalThis.profileView,gl=view.gl;
 const bytes=Uint8Array.from(atob(mesh),c=>c.charCodeAt(0)).buffer;const start=performance.now();view.uploadMesh(bytes);const uploadMillis=performance.now()-start;
 const image=new Image();await new Promise((resolve,reject)=>{image.onload=resolve;image.onerror=reject;image.src='data:image/png;base64,'+atlas;});view.uploadAtlas(image);
 let sortMillis=0,cullMillis=0,uploadBytes=0,sortCalls=0;
 const sort=globalThis.ProfileGpu.prototype._sortTranslucent,visibility=globalThis.ProfileGpu.prototype._updateVisibility,bufferData=gl.bufferData.bind(gl);
 view._sortTranslucent=function(...args){const t=performance.now(),before=args[0].bspSorts||0;const result=sort.apply(this,args);sortMillis+=performance.now()-t;if((args[0].bspSorts||0)>before)sortCalls++;return result;};
 view._updateVisibility=function(...args){const t=performance.now();const result=visibility.apply(this,args);cullMillis+=performance.now()-t;return result;};
 gl.bufferData=(target,data,usage)=>{uploadBytes+=data?.byteLength||0;return bufferData(target,data,usage);};
 const percentile=(array,p)=>array.slice().sort((a,b)=>a-b)[Math.min(array.length-1,Math.floor(array.length*p))];
 const frame=(cam)=>{const a=performance.now();view.draw(cam);const b=performance.now();const pixel=new Uint8Array(4);gl.readPixels(Math.floor(view.canvas.width/2),Math.floor(view.canvas.height/2),1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixel);return {submit:b-a,complete:performance.now()-a,pixel:[...pixel],...view.drawStats};};
 const results={};
 for(const mode of ['orbit','fly','fly-fixed']) {
  const frames=[];sortMillis=0;cullMillis=0;uploadBytes=0;sortCalls=0;
  for(let i=0;i<36;i++) {
   const angle=mode==='fly-fixed'?0:i*.018;
   const cam=mode==='orbit'?{orbit:true,rotationDeg:45+i,slantDeg:30,zoom:1}:{eye:view.center.slice(),target:[view.center[0]+Math.sin(angle),view.center[1]-.15,view.center[2]+Math.cos(angle)],fovDeg:70};
   cam.renderScale=mode==='orbit'?1:.4;
   const result=frame(cam);if(i>=6)frames.push(result);
   await new Promise(resolve=>setTimeout(resolve,0));
   if(i===5){sortMillis=0;cullMillis=0;uploadBytes=0;sortCalls=0;}
  }
  results[mode]={sampledFrames:frames.length,submitMean:frames.reduce((n,f)=>n+f.submit,0)/frames.length,completeMean:frames.reduce((n,f)=>n+f.complete,0)/frames.length,completeP95:percentile(frames.map(f=>f.complete),.95),sortMean:sortMillis/30,cullMean:cullMillis/30,indexUploadMiB:uploadBytes/30/1048576,sortCalls,visibleMin:Math.min(...frames.map(f=>f.visibleQuads)),visibleMax:Math.max(...frames.map(f=>f.visibleQuads)),centerPixel:frames[0].pixel};
 }
 gl.bufferData=bufferData;delete view._sortTranslucent;delete view._updateVisibility;
 const debug=gl.getExtension('WEBGL_debug_renderer_info');
 if(gl.getError())throw new Error('WebGL failed');
 return {quads:view.quadCount,transparentQuads:view.layers.find(l=>l.centroids)?.quads,bspNodes:view.layers.find(l=>l.centroids)?.bspTree?.length,uploadMillis,renderer:debug?gl.getParameter(debug.UNMASKED_RENDERER_WEBGL):gl.getParameter(gl.RENDERER),results};
}
