const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../..'),out=path.join(__dirname,'interaction-output/redstone');
app.setPath('userData',path.join(out,'gpu-electron'));
app.whenReady().then(async()=>{
 const win=new BrowserWindow({show:false,webPreferences:{contextIsolation:true}});
 try {
  await win.loadURL('data:text/html,<div style="width:640px;height:360px"><canvas id="view"></canvas></div>');
  const source=fs.readFileSync(path.join(root,'webapp/renderer/gpu-view.js'),'utf8');
  const mesh=fs.readFileSync(path.join(out,'mesh.bin')).toString('base64'),atlas=fs.readFileSync(path.join(out,'atlas.png')).toString('base64');
  const result=await win.webContents.executeJavaScript(source+`\n(${verify.toString()})(${JSON.stringify(mesh)},${JSON.stringify(atlas)})`);
  fs.writeFileSync(path.join(out,'gpu.png'),Buffer.from(result.png.split(',')[1],'base64'));delete result.png;
  fs.writeFileSync(path.join(out,'gpu.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));win.destroy();app.quit();
 }catch(error){console.error(error.stack);app.exit(1);}
});
async function verify(mesh,atlas) {
 const gpu=new GpuView(document.getElementById('view')),gl=gpu.gl;gpu.uploadMesh(Uint8Array.from(atob(mesh),c=>c.charCodeAt(0)).buffer);
 const texture=new Image();await new Promise((resolve,reject)=>{texture.onload=resolve;texture.onerror=reject;texture.src='data:image/png;base64,'+atlas;});gpu.uploadAtlas(texture);
 const read=()=>{const values=new Uint8Array(gpu.canvas.width*gpu.canvas.height*4);gl.readPixels(0,0,gpu.canvas.width,gpu.canvas.height,gl.RGBA,gl.UNSIGNED_BYTE,values);return values;};
 for(const angle of [45,135,225,315]) {
  const cam={orbit:true,rotationDeg:angle,slantDeg:50,zoom:1};gpu.draw(cam);const first=read();
  gpu.draw({...cam,rotationDeg:angle+20});gpu.draw(cam);const returned=read();
  if(first.some((value,i)=>value!==returned[i]))throw new Error('Wire layers changed after camera return at '+angle);
  if(!first.some((value,i)=>i%4===0&&value>150))throw new Error('Powered wire color missing');
 }
 gpu.draw({orbit:true,rotationDeg:45,slantDeg:50,zoom:1});
 if(gl.getError())throw new Error('WebGL failed');
 return {pass:true,views:4,cameraReturnPixelsEqual:true,poweredColorsVisible:true,quads:gpu.quadCount,png:gpu.canvas.toDataURL()};
}
