const {app,BrowserWindow,ipcMain}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../..'),out=path.join(__dirname,'large-mesh-output');
const {SceneTransfer,CHUNK_LIMIT}=require(path.join(root,'webapp/scene-transfer'));
const transfer=new SceneTransfer();let maximumRead=0,readCalls=0;
fs.mkdirSync(out,{recursive:true});app.setPath('userData',path.join(out,'electron'));
const files={small:path.join(__dirname,'world-optimization/export/reference-mesh.bin'),large:path.join(out,'crash-mesh.bin')};
ipcMain.handle('mesh-test:open',(event,name)=>{if(!files[name])throw Error('invalid fixture');return transfer.open(files[name],event.sender.id);});
ipcMain.handle('mesh-test:read',(event,p)=>{maximumRead=Math.max(maximumRead,p.length);readCalls++;return transfer.read(event.sender.id,p.id,p.offset,p.length);});
app.whenReady().then(async()=>{
 const win=new BrowserWindow({show:false,width:640,height:480,webPreferences:{preload:path.join(__dirname,'large-mesh-preload.cjs'),contextIsolation:true,sandbox:false}});
 const fail=error=>{fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,error:String(error.stack||error)}));console.error(error);app.exit(1);};
 win.webContents.on('render-process-gone',(_event,details)=>fail(Error('Renderer exited: '+JSON.stringify(details))));
 try {
  await win.loadURL('data:text/html,<div style="width:512px;height:384px;position:relative"><canvas id="scene"></canvas></div>');
  const source=fs.readFileSync(path.join(root,'webapp/renderer/gpu-view.js'),'utf8');
  const result=await win.webContents.executeJavaScript(source+'\n('+regression.toString()+')()');
  result.maximumIpcChunk=maximumRead;result.readCalls=readCalls;
  if(maximumRead>CHUNK_LIMIT)throw Error('Unbounded IPC request');
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
  await transfer.close();win.destroy();app.exit(0);
 }catch(error){fail(error);}
});
async function regression(){
 const check=(ok,text)=>{if(!ok)throw Error(text);};
 const gpu=new GpuView(document.getElementById('scene')),gl=gpu.gl;
 const texture=new ImageData(new Uint8ClampedArray([255,255,255,210]),1,1);
 const readPixels=()=>{const pixels=new Uint8Array(gpu.canvas.width*gpu.canvas.height*4);gl.readPixels(0,0,gpu.canvas.width,gpu.canvas.height,gl.RGBA,gl.UNSIGNED_BYTE,pixels);return pixels;};
 const small=await window.meshTest.open('small');const bytes=new Uint8Array(small.size);
 for(let offset=0;offset<small.size;offset+=4*1024*1024){const n=Math.min(4*1024*1024,small.size-offset);bytes.set(new Uint8Array(await window.meshTest.read(small.id,offset,n)),offset);}
 gpu.uploadMesh(bytes.buffer);gpu.uploadAtlas(texture);
 const cameras=[45,135,225,315].map(rotationDeg=>({orbit:true,rotationDeg,slantDeg:30,zoom:1,background:[.1,.1,.1,1]}));
 const reference=cameras.map(camera=>{gpu.draw(camera);return readPixels();});const min=gpu.min.slice(),max=gpu.max.slice(),count=gpu.quadCount;
 await gpu.uploadMeshStream((offset,n)=>window.meshTest.read(small.id,offset,n),small.size);gpu.uploadAtlas(texture);
 check(gpu.quadCount===count&&JSON.stringify(gpu.min)===JSON.stringify(min)&&JSON.stringify(gpu.max)===JSON.stringify(max),'stream changed counts or bounds');
 cameras.forEach((camera,i)=>{gpu.draw(camera);const pixels=readPixels();check(!pixels.some((value,j)=>value!==reference[i][j]),'stream changed pixels at '+i);});
 const originalExtension=gl.getExtension.bind(gl);gl.getExtension=name=>name==='OES_element_index_uint'?null:originalExtension(name);
 await gpu.uploadMeshStream((offset,n)=>window.meshTest.read(small.id,offset,n),small.size);gpu.uploadAtlas(texture);
 check(!gpu.uintIndices,'16-bit index fixture did not select compatibility mode');
 cameras.forEach((camera,i)=>{gpu.draw(camera);const pixels=readPixels();check(!pixels.some((value,j)=>value!==reference[i][j]),'stream 16-bit compatibility changed pixels at '+i);});
 gl.getExtension=originalExtension;
 gpu.disposeBuffers();
 const large=await window.meshTest.open('large'),start=performance.now();
 const stats=await gpu.uploadMeshStream((offset,n)=>window.meshTest.read(large.id,offset,n),large.size);
 gpu.uploadAtlas(texture);
 check(stats.quads===8218042,'large scene lost faces');
 check(gpu.layers.filter(layer=>layer.ordinal===0).length>100,'opaque layer was not batched');
 gpu.draw({orbit:true,rotationDeg:45,slantDeg:30,zoom:1,background:[.1,.1,.1,1]});
 const first=readPixels();check(first.some((value,i)=>i%4!==3&&value>50),'large model did not render');
 const [x,y,z]=gpu.center;
 for(let i=0;i<3;i++){gpu.draw({eye:[x,y,z+30],target:[x+Math.sin(i*.2),y,z],fovDeg:70});readPixels();}
 check(gl.getError()===gl.NO_ERROR&&!gl.isContextLost(),'large model produced GPU errors');
 return {pass:true,smallPixelsIdentical:true,compatibilityPixelsIdentical:true,comparisonViews:cameras.length*2,largeBytes:large.size,largeQuads:stats.quads,opaqueBatches:gpu.layers.filter(layer=>layer.ordinal===0).length,loadAndDrawMillis:performance.now()-start,flyViews:3,glError:0};
}
