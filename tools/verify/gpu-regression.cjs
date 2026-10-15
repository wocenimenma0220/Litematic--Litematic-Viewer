const { app, BrowserWindow } = require('electron');
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '../..');
const resultPath = path.join(root, 'tools/verify/output/gpu-result.json');
fs.mkdirSync(path.dirname(resultPath), { recursive: true });
app.setPath('userData',path.join(root,'tools/verify/fly-output/gpu-electron'));
fs.writeFileSync(resultPath, JSON.stringify({ started: true }));
app.whenReady().then(async () => {
  const win = new BrowserWindow({ show: false, webPreferences: { contextIsolation: true } });
  try {
    await win.loadURL('data:text/html,<canvas id="scene" width="128" height="128" style="width:128px;height:128px"></canvas>');
    const source = fs.readFileSync(path.join(root, 'webapp/renderer/gpu-view.js'), 'utf8');
    const result = await win.webContents.executeJavaScript(source + '\n(' + regression.toString() + ')()');
    console.log(JSON.stringify(result));
    fs.writeFileSync(resultPath, JSON.stringify(result, null, 2));
    if (!result.pass) throw new Error(result.error);
    win.destroy(); app.exit(0);
  } catch (e) {
    fs.writeFileSync(resultPath, JSON.stringify({ pass: false, error: e.stack }, null, 2));
    console.error(e.stack); win.destroy(); app.exit(1);
  }
});
function regression() {
  try {
    const gpu = new GpuView(document.getElementById('scene'));
    const gl = gpu.gl;
    // Two overlapping red/blue planes from different blending layers.
    const parts = [];
    function quad(z, u, reversed = false) {
      let corners = [[-2,-2,z], [2,-2,z], [2,2,z], [-2,2,z]];
      if (reversed) corners = [corners[0], corners[3], corners[2], corners[1]];
      return [0,1,2,0,2,3].flatMap(i => [...corners[i], u, 0.5, 1, 1, 1, 1]);
    }
    parts.push({ ordinal: 3, data: [...quad(-0.25, 0.25), ...quad(-0.25, 0.25, true)] });
    parts.push({ ordinal: 4, data: [...quad(0.25, 0.75), ...quad(0.25, 0.75, true)] });
    const bytes = new ArrayBuffer(16 + parts.reduce((n, p) => n + 8 + p.data.length * 4, 0));
    const view = new DataView(bytes); let offset = 0;
    for (const b of [76,77,69,83,72,49,0,0]) view.setUint8(offset++, b);
    view.setInt32(offset, parts.length); offset += 4;
    view.setInt32(offset, 24); offset += 4;
    for (const p of parts) {
      view.setInt32(offset, p.ordinal); offset += 4;
      view.setInt32(offset, 2); offset += 4;
      for (const f of p.data) { view.setFloat32(offset, f); offset += 4; }
    }
    gpu.uploadMesh(bytes);
    if (gpu.layers.length !== 1) throw new Error('blended materials were not merged');
    gpu.texture = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, gpu.texture);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 2, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE,
      new Uint8Array([255,0,0,128, 0,0,255,128]));
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    const pixels = [];
    for (const angle of [0, 180]) {
      gpu.draw({ orbit: true, rotationDeg: angle, slantDeg: 0, zoom: 1 });
      const pixel = new Uint8Array(4);
      gl.readPixels(gpu.canvas.width / 2, gpu.canvas.height / 2, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
      pixels.push([...pixel]);
    }
    if (!(pixels[0][2] > pixels[0][0] && pixels[1][0] > pixels[1][2])) {
      throw new Error('transparent colour order did not reverse with the camera: ' + JSON.stringify(pixels));
    }
    for (let rotation = 0; rotation < 360; rotation += 22.5) {
      for (const slant of [-60, 0, 30, 75]) {
        gpu.draw({ orbit: true, rotationDeg: rotation, slantDeg: slant, zoom: 1 });
        const dir = gpu._viewDir(), m = gpu.lastMatrix;
        const length = Math.hypot(m[2], m[6], m[10]);
        for (let i = 0; i < 3; i++) {
          if (Math.abs(dir[i] - m[2 + i * 4] / length) > 1e-6) throw new Error('orbit sorting direction disagrees with projection');
        }
        const layer = gpu.layers[0];
        for (let i = 1; i < layer.order.length; i++) {
          if (layer.depths[layer.order[i - 1]] < layer.depths[layer.order[i]] - 1e-8) throw new Error('quad ordering failed');
        }
      }
    }
    gpu.draw({ orbit: false, eye: [0,0,-5], target: [0,0,0], fovDeg: 60 });
    const layer = gpu.layers[0];
    // Exercise the no-uint-index path used by older graphics drivers.
    layer.indexBuffer = null; layer.indices = null;
    layer.sortedData = new Float32Array(layer.data.length); layer.lastDirection = null;
    gpu.draw({ orbit: true, rotationDeg: 0, slantDeg: 0, zoom: 1 });
    const fallback = new Uint8Array(4);
    gl.readPixels(gpu.canvas.width / 2, gpu.canvas.height / 2, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, fallback);
    if (fallback[2] <= fallback[0]) throw new Error('fallback transparent order failed');
    if (gl.getError() !== gl.NO_ERROR) throw new Error('WebGL error');
    const empty = new ArrayBuffer(56), emptyView = new DataView(empty);
    [76,77,69,83,72,49,0,0].forEach((b,i) => emptyView.setUint8(i,b));
    emptyView.setInt32(8,5); emptyView.setInt32(12,0);
    for (let i = 0; i < 5; i++) { emptyView.setInt32(16+i*8,i); emptyView.setInt32(20+i*8,0); }
    gpu.uploadMesh(empty);
    gpu.draw({ orbit: true, rotationDeg: 0, slantDeg: 0, zoom: 1 });
    if (gl.getError() !== gl.NO_ERROR) throw new Error('Empty material layers caused a WebGL error');
    const maskedParts=[{ordinal:0,data:quad(-.5,.75)},{ordinal:3,data:quad(0,.25)},{ordinal:5,data:quad(.25,.25)}];
    const maskBytes=new ArrayBuffer(16+maskedParts.reduce((n,p)=>n+8+p.data.length*4,0));const maskView=new DataView(maskBytes);let at=0;
    [76,77,69,83,72,49,0,0].forEach(b=>maskView.setUint8(at++,b));maskView.setInt32(at,3);at+=4;maskView.setInt32(at,18);at+=4;
    for(const part of maskedParts){maskView.setInt32(at,part.ordinal);at+=4;maskView.setInt32(at,1);at+=4;for(const value of part.data){maskView.setFloat32(at,value);at+=4;}}
    gpu.uploadMesh(maskBytes);gpu.draw({orbit:true,rotationDeg:0,slantDeg:0,zoom:1});const masked=new Uint8Array(4);gl.readPixels(gpu.canvas.width/2,gpu.canvas.height/2,1,1,gl.RGBA,gl.UNSIGNED_BYTE,masked);
    if(masked[0]!==0||masked[2]===0)throw new Error('boat mask painted color or failed to hide water: '+masked);
    if(gl.getParameter(gl.COLOR_WRITEMASK).some(value=>!value)||gl.getError()!==gl.NO_ERROR)throw new Error('boat mask leaked GL color-write state');
    function meshBytes(parts,compact=true) {
      const input=parts.map(part=>({ordinal:part.ordinal,data:compact?part.data.filter((_value,i)=>[0,1,2,5].includes(Math.floor(i/9)%6)):part.data}));
      const bytes=new ArrayBuffer(16+input.reduce((n,p)=>n+8+p.data.length*4,0)),v=new DataView(bytes);let offset=0;
      [76,77,69,83,72,compact?50:49,0,0].forEach(b=>v.setUint8(offset++,b));v.setInt32(offset,input.length);offset+=4;
      v.setInt32(offset,input.reduce((n,p)=>n+p.data.length/9,0));offset+=4;
      for(const part of input){v.setInt32(offset,part.ordinal);offset+=4;v.setInt32(offset,part.data.length/(compact?36:54));offset+=4;for(const value of part.data){v.setFloat32(offset,value);offset+=4;}}
      return bytes;
    }
    const testParts=[{ordinal:0,data:quad(0,.25)},{ordinal:3,data:quad(.25,.75)},
      {ordinal:4,data:quad(0,.75).map((value,i)=>i%9===0?value+80:value)}];
    const camera={eye:[0,0,5],target:[0,0,0],fovDeg:60};
    const read=()=>{const pixels=new Uint8Array(gpu.canvas.width*gpu.canvas.height*4);gl.readPixels(0,0,gpu.canvas.width,gpu.canvas.height,gl.RGBA,gl.UNSIGNED_BYTE,pixels);return pixels;};
    gpu.uploadMesh(meshBytes(testParts,false));gpu.draw({...camera,culling:false});const legacy=read();
    gpu.uploadMesh(meshBytes(testParts,true));gpu.draw({...camera,culling:false});const compact=read();
    if(legacy.some((value,i)=>value!==compact[i]))throw new Error('4-vertex mesh differs from legacy triangles');
    gpu.draw(camera);const culled=read(),stats={...gpu.drawStats};
    if(compact.some((value,i)=>value!==culled[i])||stats.visibleQuads>=stats.totalQuads)throw new Error('frustum culling changed pixels or did not exclude offscreen geometry');
    // Translate while preserving direction: the visibility change must invalidate transparent order.
    gpu.draw({eye:[80,0,5],target:[80,0,0],fovDeg:60});const translated=read();
    gpu.draw({eye:[80,0,5],target:[80,0,0],fovDeg:60,culling:false});const translatedFull=read();
    if(translated.some((value,i)=>value!==translatedFull[i]))throw new Error('translation did not refresh transparent visibility');
    // Force the driver without 32-bit indices; exercise a second 16-bit vertex-address batch.
    const extension=gl.getExtension.bind(gl);gl.getExtension=name=>name==='OES_element_index_uint'?null:extension(name);
    const many=[];for(let q=0;q<17000;q++)many.push(...quad(0,.25));
    gpu.uploadMesh(meshBytes([{ordinal:0,data:many}],true));gpu.draw({orbit:true,rotationDeg:0,slantDeg:0,zoom:1});
    const noUint=read();if(!noUint.some(value=>value)||gpu.drawStats.drawCalls<2||gl.getError()!==gl.NO_ERROR)throw new Error('16-bit opaque index batches failed');
    gpu.uploadMesh(meshBytes(testParts,true));gpu.draw(camera);const noUintBlend=read();
    if(culled.some((value,i)=>value!==noUintBlend[i])||gl.getError()!==gl.NO_ERROR)throw new Error('no-uint transparent fallback changed pixels');
    gl.getExtension=extension;
    // A tinted face with varying AO must bake each corner independently. Interpolation
    // of baked colors equals interpolated shade times the face's constant tint.
    const shades=[.2,.4,.8,.6],tint=[.8,.6,.4];
    const lit=quad(0,.25).map((value,i)=>{
      const corner=[0,1,2,0,2,3][Math.floor(i/9)],component=i%9;
      return component===5?shades[corner]:component>=6?tint[component-6]:value;
    });
    gpu.uploadMesh(meshBytes([{ordinal:4,data:lit}],true));
    const baked=gpu.layers[0].data;
    for(let corner=0;corner<4;corner++)for(let axis=0;axis<3;axis++) {
      if(Math.abs(baked[corner*9+6+axis]-shades[corner]*tint[axis])>1e-7)throw new Error('AO/tint bake changed vertex color');
    }
    gl.bindTexture(gl.TEXTURE_2D,gpu.texture);
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,1,1,0,gl.RGBA,gl.UNSIGNED_BYTE,new Uint8Array([255,255,255,255]));
    const litCamera={orbit:true,rotationDeg:0,slantDeg:0,zoom:1};
    gpu.draw(litCamera);const fullLit=read(),fullMatrix=Array.from(gpu.lastMatrix);
    const centerPixel=new Uint8Array(4);gl.readPixels(gpu.canvas.width/2,gpu.canvas.height/2,1,1,gl.RGBA,gl.UNSIGNED_BYTE,centerPixel);
    for(let axis=0;axis<3;axis++)if(Math.abs(centerPixel[axis]-.5*tint[axis]*255)>3)throw new Error('baked AO interpolation changed: '+centerPixel);
    const fullWidth=gpu.canvas.width,fullHeight=gpu.canvas.height;
    gpu.draw({...litCamera,renderScale:.5});
    if(gpu.canvas.width!==Math.round(fullWidth*.5)||gpu.canvas.height!==Math.round(fullHeight*.5)||fullMatrix.some((v,i)=>v!==gpu.lastMatrix[i]))throw new Error('quality scale changed orbit composition');
    gpu.draw(litCamera);const refined=read();
    if(fullLit.some((v,i)=>v!==refined[i]))throw new Error('native quality did not restore exact pixels');
    gpu.draw(camera);const rayBefore=gpu.rayFromScreen(64,64),perspectiveMatrix=Array.from(gpu.lastMatrix);
    gpu.draw({...camera,renderScale:.5});const rayAfter=gpu.rayFromScreen(64,64);
    if(perspectiveMatrix.some((v,i)=>v!==gpu.lastMatrix[i])||JSON.stringify(rayBefore)!==JSON.stringify(rayAfter))throw new Error('quality scale changed picking ray');
    if(gl.getError()!==gl.NO_ERROR)throw new Error('quality/baking WebGL error');
    return { pass: true, views: 66, pixels, fallback: [...fallback],boatWaterMask:[...masked],compactPixelsEqual:true,cullingPixelsEqual:true,translationPixelsEqual:true,noUintBatches:true,bakedLightPixel:[...centerPixel],qualityRestoresPixels:true,qualityPreservesRay:true,culling:stats, renderer: gl.getParameter(gl.RENDERER) };
  } catch (e) { return { pass: false, error: e.stack }; }
}
