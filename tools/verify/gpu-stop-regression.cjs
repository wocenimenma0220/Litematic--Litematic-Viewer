'use strict';
const {app,BrowserWindow}=require('electron');
const fs=require('node:fs'),path=require('node:path');
const out=path.join(__dirname,'drag-stop-output');fs.mkdirSync(out,{recursive:true});
app.setPath('userData',path.join(out,'gpu-electron'));
app.whenReady().then(async()=>{
  const win=new BrowserWindow({show:false,webPreferences:{contextIsolation:true}});
  try{
    await win.loadURL('data:text/html,<canvas id="scene" width="128" height="128"></canvas>');
    const source=fs.readFileSync(path.join(__dirname,'../../webapp/renderer/gpu-view.js'),'utf8');
    const result=await win.webContents.executeJavaScript(source+'\n('+regression.toString()+')()');
    fs.writeFileSync(path.join(out,'gpu-stop.json'),JSON.stringify(result,null,2));
    console.log(JSON.stringify(result));win.destroy();app.exit(0);
  }catch(error){console.error(error.stack);win.destroy();app.exit(1);}
});
async function regression(){
  const check=(ok,message)=>{if(!ok)throw Error(message);};
  const gpu=new GpuView(document.getElementById('scene')),gl=gpu.gl;
  function mesh(count){
    const bytes=new ArrayBuffer(24+count*144),view=new DataView(bytes);let offset=0;
    for(const byte of [76,77,69,83,72,50,0,0])view.setUint8(offset++,byte);
    view.setInt32(8,1);view.setInt32(12,count*4);view.setInt32(16,0);view.setInt32(20,count);
    offset=24;
    for(let q=0;q<count;q++)for(const [x,y]of [[-1,-1],[1,-1],[1,1],[-1,1]])for(const value of [x,y,q,0.5,0.5,1,1,1,1]){view.setFloat32(offset,value);offset+=4;}
    return bytes;
  }
  const wire=mesh(1),replacement=mesh(2);
  for(const blockAt of ['header','vertices']){
    let reached,release;
    const blocked=new Promise(resolve=>{reached=resolve;});
    const uploading=gpu.uploadMeshStream(async(offset,length)=>{
      if(blockAt==='header'&&offset===0||blockAt==='vertices'&&offset>=24){reached();await new Promise(resolve=>{release=resolve;});}
      return wire.slice(offset,offset+length);
    },wire.byteLength).then(()=>null,error=>error);
    await blocked;
    gpu.disposeBuffers();gpu.uploadMesh(replacement);
    const buffers=gpu.buffers.slice(),layers=gpu.layers;
    release();const error=await uploading;
    check(error&&error.message.includes('更新'),'old upload survived cancellation during '+blockAt);
    check(gpu.quadCount===2&&gpu.layers===layers&&buffers.every((buffer,i)=>gpu.buffers[i]===buffer),'old upload changed replacement buffers during '+blockAt);
  }
  await gpu.uploadMeshStream((offset,length)=>Promise.resolve(wire.slice(offset,offset+length)),wire.byteLength);
  gpu.uploadAtlas(new ImageData(new Uint8ClampedArray([255,255,255,255]),1,1));
  gpu.draw({orbit:true,rotationDeg:0,slantDeg:0,zoom:1});
  check(gpu.quadCount===1&&gl.getError()===0,'normal upload failed after cancellation');
  gpu.destroy();
  return {pass:true,checks:['stale header reads cannot clear a replacement','stale vertex reads cannot mutate replacement buffers','subsequent normal stream upload and WebGL draw succeed']};
}
