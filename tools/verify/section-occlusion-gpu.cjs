const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../..'),out=path.join(__dirname,'world-optimization');
app.setPath('userData',path.join(out,'occlusion-electron'));
app.whenReady().then(async()=>{
 const win=new BrowserWindow({show:false,webPreferences:{contextIsolation:true}});
 try {
  await win.loadURL('data:text/html,<div style="width:384px;height:256px"><canvas id="view"></canvas></div>');
  await win.webContents.executeJavaScript(fs.readFileSync(path.join(root,'webapp/renderer/gpu-view.js'),'utf8')+'\nglobalThis.gpu=new GpuView(document.getElementById("view"));');
  const report={pass:true,fixtures:[]};
  for(const mode of [0,1,2,3,'reference']) {
   const mesh=fs.readFileSync(path.join(out,mode==='reference'?'optimized.bin':`rooms/rooms-${mode}.bin`)).toString('base64');
   const atlas=fs.readFileSync(path.join(out,mode==='reference'?'atlas.png':`rooms/rooms-${mode}.png`)).toString('base64');
   const result=await win.webContents.executeJavaScript(`(${check.toString()})(${JSON.stringify(mesh)},${JSON.stringify(atlas)},${JSON.stringify(mode)})`);
   if(result.error){for(const [name,png] of Object.entries(result.images))fs.writeFileSync(path.join(out,name+'.png'),Buffer.from(png.split(',')[1],'base64'));throw new Error(result.error);}
   report.fixtures.push(result);
  }
  fs.writeFileSync(path.join(out,'occlusion-gpu.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));win.destroy();app.quit();
 }catch(error){console.error(error.stack);fs.writeFileSync(path.join(out,'occlusion-gpu.json'),JSON.stringify({pass:false,error:error.stack},null,2));app.exit(1);}
});
async function check(mesh,atlas,mode) {
 const view=globalThis.gpu,gl=view.gl;view.uploadMesh(Uint8Array.from(atob(mesh),c=>c.charCodeAt(0)).buffer);
 const image=new Image();await new Promise((resolve,reject)=>{image.onload=resolve;image.onerror=reject;image.src='data:image/png;base64,'+atlas;});view.uploadAtlas(image);
 if(!view.sectionGraph)throw new Error('Missing section graph');
 const pixels=()=>{const result=new Uint8Array(view.canvas.width*view.canvas.height*4);gl.readPixels(0,0,view.canvas.width,view.canvas.height,gl.RGBA,gl.UNSIGNED_BYTE,result);return result;};
 let minimumRatio=1,views=0;const details=[];
 const cameras=[];
 if(mode==='reference') {
  for(const offset of [[0,0,0],[8,0,0],[-8,0,0]])for(let i=0;i<8;i++) {
   const angle=i*Math.PI/4,eye=view.center.map((v,j)=>v+offset[j]);
   cameras.push({eye,target:[eye[0]+Math.sin(angle),eye[1]-.15,eye[2]+Math.cos(angle)],fovDeg:70});
  }
 }else {
  for(const eye of [[8,10,10],[56,10,10],[8,20,20],[0.5,0.5,0.5],[-10,10,10]])for(const direction of [[1,0,0],[-1,0,0],[1,0,.5],[1,.5,0]])
   cameras.push({eye,target:eye.map((v,i)=>v+direction[i]),fovDeg:70});
 }
 if(mode===3)for(const camera of cameras){camera.eye=camera.eye.map((v,i)=>v-[48,16,16][i]);camera.target=camera.target.map((v,i)=>v-[48,16,16][i]);}
 for(const camera of cameras) {
  view.draw({...camera,occlusion:false});const full=pixels(),before={...view.drawStats};
  view.draw(camera);const culled=pixels(),after={...view.drawStats};
  let changed=0,maxError=0,over3=0;for(let i=0;i<full.length;i++)if(full[i]!==culled[i]){changed++;const d=Math.abs(full[i]-culled[i]);maxError=Math.max(maxError,d);if(d>3)over3++;}
  if(changed) {
   const png=data=>{const canvas=document.createElement('canvas');canvas.width=view.canvas.width;canvas.height=view.canvas.height;canvas.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(data),canvas.width,canvas.height),0,0);return canvas.toDataURL();};
   return {error:'Occlusion changed '+changed+' channels, max '+maxError+', over3 '+over3+', sample '+JSON.stringify(Array.from({length:full.length/4},(_,i)=>i).filter(i=>full[i*4]!==culled[i*4]||full[i*4+3]!==culled[i*4+3]).slice(0,5).map(i=>({xy:[i%view.canvas.width,Math.floor(i/view.canvas.width)],a:Array.from(full.slice(i*4,i*4+4)),b:Array.from(culled.slice(i*4,i*4+4))})))+' in '+mode+' at '+JSON.stringify(camera),images:{full:png(full),culled:png(culled)}};
  }
  if(before.visibleQuads)minimumRatio=Math.min(minimumRatio,after.visibleQuads/before.visibleQuads);views++;
  if(details.length<4)details.push({before:before.visibleQuads,after:after.visibleQuads});
 }
 if(mode===0&&minimumRatio>.9)throw new Error('Closed-room geometry was not excluded');
 // Orbit remains complete and old meshes remain readable in the other GPU regressions.
 const orbit={orbit:true,rotationDeg:45,slantDeg:30,zoom:1};view.draw({...orbit,occlusion:false});const a=pixels();view.draw(orbit);const b=pixels();
 if(a.some((v,i)=>v!==b[i])||gl.getError())throw new Error('Orbit/GL regression');
 const camera=cameras[0];view.draw(camera);const indexed=pixels();
 const extension=gl.getExtension.bind(gl);gl.getExtension=name=>name==='OES_element_index_uint'?null:extension(name);
 view.uploadMesh(Uint8Array.from(atob(mesh),c=>c.charCodeAt(0)).buffer);view.draw(camera);const fallback=pixels();gl.getExtension=extension;
 if(indexed.some((v,i)=>v!==fallback[i])||gl.getError())throw new Error('Occlusion/no-uint fallback changed pixels');
 return {mode,views,pixelsEqual:true,noUintFallback:true,minimumVisibleRatio:minimumRatio,details};
}
