const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../..'),out=path.join(root,'tools/verify/optimization-output/advanced');
app.setPath('userData',path.join(out,'gpu-electron'));
app.whenReady().then(async()=>{
  const win=new BrowserWindow({show:false,webPreferences:{contextIsolation:true}});
  try {
    await win.loadURL('data:text/html,<div style="width:128px;height:128px"><canvas id="scene" style="width:128px;height:128px"></canvas></div>');
    const source=fs.readFileSync(path.join(root,'webapp/renderer/gpu-view.js'),'utf8');
    const mesh=fs.readFileSync(path.join(out,'crossing-mesh.bin')).toString('base64'),atlas=fs.readFileSync(path.join(out,'crossing-atlas.png')).toString('base64');
    const result=await win.webContents.executeJavaScript(source+`\n(${test.toString()})(${JSON.stringify(mesh)},${JSON.stringify(atlas)})`);
    fs.writeFileSync(path.join(out,'bsp-gpu.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
    win.destroy();app.exit(result.pass?0:1);
  }catch(error){console.error(error.stack);win.destroy();app.exit(1);}
});
async function test(mesh,atlas){
  try {
    const gpu=new GpuView(document.getElementById('scene')),gl=gpu.gl;
    const bytes=Uint8Array.from(atob(mesh),char=>char.charCodeAt(0));gpu.uploadMesh(bytes.buffer);
    const texture=new Image();await new Promise((resolve,reject)=>{texture.onload=resolve;texture.onerror=reject;texture.src='data:image/png;base64,'+atlas;});gpu.uploadAtlas(texture);
    const layer=gpu.layers.find(layer=>layer.centroids);if(!layer.bspTree)throw new Error('BSP metadata not loaded');
    gpu.draw({orbit:true,rotationDeg:0,slantDeg:0,zoom:1,culling:false});
    const read=column=>{const pixel=new Uint8Array(4);gl.readPixels(Math.floor(column*gpu.canvas.width/128),Math.floor(gpu.canvas.height/2),1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixel);return pixel;};
    const left=read(45),right=read(83);
    if(left[2]<=left[0]||right[0]<=right[2])throw new Error('intersecting planes do not have correct local colors: '+[left,right]);
    const before=layer.bspSorts;gpu.draw({orbit:true,rotationDeg:5,slantDeg:0,zoom:1,culling:false});if(layer.bspSorts!==before)throw new Error('BSP reordered without crossing a partition');
    gpu.draw({eye:[-.8,0,.4],target:[-.8,0,-.6],fovDeg:80,culling:false});const first=layer.bspSorts;
    gpu.draw({eye:[.8,0,.4],target:[.8,0,-.6],fovDeg:80,culling:false});if(layer.bspSorts<=first)throw new Error('translation across a plane did not reorder');
    const extension=gl.getExtension.bind(gl);gl.getExtension=name=>name==='OES_element_index_uint'?null:extension(name);
    gpu.uploadMesh(bytes.buffer);gpu.draw({orbit:true,rotationDeg:0,slantDeg:0,zoom:1,culling:false});const fallback=read(45);
    if(fallback.some((value,i)=>value!==left[i])||gl.getError()!==gl.NO_ERROR)throw new Error('BSP fallback changed pixels');
    return {pass:true,left:[...left],right:[...right],crossingCorrect:true,partitionCache:true,translationTrigger:true,noUintFallback:true,nodes:layer.bspTree.length};
  }catch(error){return {pass:false,error:error.stack};}
}
