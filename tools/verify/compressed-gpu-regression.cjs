const {app,BrowserWindow,ipcMain}=require('electron'),fs=require('node:fs'),path=require('node:path'),url=require('node:url');
const root=path.resolve(__dirname,'../..'),out=path.join(__dirname,'cache-geometry-output'),{SceneTransfer}=require('../../webapp/scene-transfer'),transfer=new SceneTransfer();
app.setPath('userData',path.join(out,'electron'));app.commandLine.appendSwitch('disable-renderer-backgrounding');
app.whenReady().then(async()=>{const win=new BrowserWindow({width:700,height:520,show:false,webPreferences:{nodeIntegration:true,contextIsolation:false,backgroundThrottling:false}});try{
 const cap=await transfer.open(path.join(out,'small.lmesh'),win.webContents.id);ipcMain.handle('chunk',(e,o,n)=>transfer.read(e.sender.id,cap.id,o,n));ipcMain.handle('page',(e,p)=>transfer.readPage(e.sender.id,cap.id,p));
 const scripts=['gpu-view.js','lod-stream.js'].map(f=>`<script src="${url.pathToFileURL(path.join(root,'webapp/renderer',f)).href}"></script>`).join('');const file=path.join(out,'comparison.html');fs.writeFileSync(file,`<body style="margin:0;overflow:hidden"><div style="width:640px;height:384px;overflow:hidden"><canvas id="v" style="width:100%;height:100%"></canvas></div>${scripts}`);await win.loadFile(file);
 const atlas=fs.readFileSync(path.join(out,'atlas.png')).toString('base64'),legacy=fs.readFileSync(path.join(out,'legacy.bin')).toString('base64');
 const result=await win.webContents.executeJavaScript(`(async()=>{
  const view=new GpuView(document.getElementById('v')),errors=[],ipc=require('electron').ipcRenderer;
  const image=new Image();image.src='data:image/png;base64,${atlas}';await image.decode();view.uploadAtlas(image);
  const read=()=>{const pixels=new Uint8Array(view.canvas.width*view.canvas.height*4);view.gl.readPixels(0,0,view.canvas.width,view.canvas.height,view.gl.RGBA,view.gl.UNSIGNED_BYTE,pixels);return pixels;};
  const angles=[[0,30],[45,30],[90,30],[135,30],[225,-20],[315,60]],reference=[];
  view.uploadMesh(Uint8Array.from(Buffer.from('${legacy}','base64')).buffer);
  const before={min:view.min,max:view.max,center:view.center,radius:view.radius};
  for(const [yaw,pitch]of angles){view.draw({orbit:true,rotationDeg:yaw,slantDeg:pitch,zoom:1});reference.push(read());if(yaw===0)require('fs').writeFileSync(${JSON.stringify(path.join(out,'reference-view.png'))},Buffer.from(view.canvas.toDataURL().split(',')[1],'base64'));}
  await view.uploadLodStream((o,n)=>ipc.invoke('chunk',o,n),${cap.size},{readPage:p=>ipc.invoke('page',p),onError:e=>errors.push(String(e)),budgetBytes:64*1048576});
  const comparisons=[];
  for(let k=0;k<angles.length;k++){const [yaw,pitch]=angles[k],camera={orbit:true,rotationDeg:yaw,slantDeg:pitch,zoom:1,lodEnabled:false};view.draw(camera);view.stream.plan(camera,frustumPlanes(view.lastMatrix),true);await view.stream.settled();view.draw(camera);const actual=read(),expected=reference[k];if(k===0)require('fs').writeFileSync(${JSON.stringify(path.join(out,'actual-view.png'))},Buffer.from(view.canvas.toDataURL().split(',')[1],'base64'));let severe=0,sum=0;for(let i=0;i<actual.length;i+=4){let bad=false;for(let c=0;c<4;c++){const d=Math.abs(actual[i+c]-expected[i+c]);sum+=d;if(d>8)bad=true;}if(bad)severe++;}const fraction=severe/(actual.length/4),mean=sum/actual.length;if(fraction>.012||mean>1.5)throw Error('pixel mismatch '+JSON.stringify({yaw,pitch,fraction,mean,before,after:{min:view.min,max:view.max,center:view.center,radius:view.radius},stats:view.stream.stats()}));comparisons.push({yaw,pitch,fraction,mean});}
  if(errors.length||view.gl.getError())throw Error('GPU failure '+errors);return {pass:true,comparisons,stats:view.stream.stats()};
 })()`);fs.writeFileSync(path.join(out,'gpu-result.json'),JSON.stringify(result,null,2));fs.writeFileSync(path.join(out,'gpu.png'),(await win.webContents.capturePage()).toPNG());console.log(JSON.stringify(result));await transfer.close();app.quit();
 }catch(error){console.error(error);await transfer.close();app.exit(1);}});setTimeout(()=>app.exit(1),90000).unref();
