const {app,BrowserWindow,ipcMain}=require('electron'),fs=require('node:fs'),path=require('node:path'),url=require('node:url');
const root=process.argv[2]||path.resolve(__dirname,'../..'),out=path.join(__dirname,'progressive-output/pixels'),{SceneTransfer}=require(path.join(root,'webapp/scene-transfer')),transfer=new SceneTransfer();
app.setPath('userData',path.join(out,'electron'));app.commandLine.appendSwitch('disable-renderer-backgrounding');
app.whenReady().then(async()=>{const win=new BrowserWindow({width:700,height:520,show:false,webPreferences:{nodeIntegration:true,contextIsolation:false,backgroundThrottling:false}});try{
 const coarse=await transfer.open(path.join(out,'overview.lmesh'),win.webContents.id),fine=await transfer.open(path.join(out,'detail.lmesh'),win.webContents.id,{retain:true});
 ipcMain.handle('chunk',(e,o,n)=>transfer.read(e.sender.id,coarse.id,o,n));ipcMain.handle('page',(e,p)=>transfer.readPage(e.sender.id,p.source||coarse.id,p));
 ipcMain.handle('pages',(e,ps)=>transfer.readPages(e.sender.id,ps[0].source||coarse.id,ps));
 const scripts=['gpu-view.js','lod-stream.js'].map(f=>`<script src="${url.pathToFileURL(path.join(root,'webapp/renderer',f)).href}"></script>`).join(''),file=path.join(out,'comparison.html');fs.writeFileSync(file,`<body style="margin:0"><div style="width:640px;height:384px"><canvas id="v" style="width:100%;height:100%"></canvas></div>${scripts}`);await win.loadFile(file);
 const atlas=fs.readFileSync(path.join(out,'atlas.png')).toString('base64'),overview=fs.readFileSync(path.join(out,'overview.png')).toString('base64'),legacy=fs.readFileSync(path.join(out,'legacy.bin')).toString('base64'),catalog=JSON.parse(fs.readFileSync(path.join(out,'catalog.json')));
 const result=await win.webContents.executeJavaScript(`(async()=>{
  const check=(v,m)=>{if(!v)throw Error(m);},view=new GpuView(document.getElementById('v')),ipc=require('electron').ipcRenderer,errors=[];
  const image=async data=>{const i=new Image();i.src='data:image/png;base64,'+data;await i.decode();view.uploadAtlas(i);};
  const pixels=()=>{const p=new Uint8Array(view.canvas.width*view.canvas.height*4);view.gl.readPixels(0,0,view.canvas.width,view.canvas.height,view.gl.RGBA,view.gl.UNSIGNED_BYTE,p);return p;};
  const camera={orbit:true,rotationDeg:45,slantDeg:30,zoom:1,lodEnabled:false},angles=[[0,30],[45,30],[90,30],[135,30],[225,-20],[315,60]],references=[];
  view.uploadMesh(Uint8Array.from(Buffer.from('${legacy}','base64')).buffer);await image('${atlas}');const bounds={min:view.min.slice(),max:view.max.slice()};
  for(const [rotationDeg,slantDeg]of angles){view.draw({...camera,rotationDeg,slantDeg});references.push(pixels());}
  let release;const gate=new Promise(resolve=>release=resolve);let blockDetail=true;
  await view.uploadLodStream((o,n)=>ipc.invoke('chunk',o,n),${coarse.size},{overview:true,budgetBytes:64*1048576,onError:e=>errors.push(String(e)),readPage:async p=>{if(p.source&&blockDetail)await gate;return ipc.invoke('page',p);},readPages:async ps=>{if(ps[0].source&&blockDetail)await gate;return ipc.invoke('pages',ps);}});
  view.setFrameBounds(bounds.min,bounds.max);await image('${overview}');view.draw({...camera,lodLevel:3});await view.stream.settled();view.draw({...camera,lodLevel:3});
  const stream=view.stream,old=new Map(stream.residents);await image('${atlas}');stream.beginProgressive(${JSON.stringify(fine.id)},${catalog.sections.length});
  stream.appendProgressive(${JSON.stringify(catalog.sections.slice(0,1))},${fine.size});view.draw(camera);await new Promise(r=>setTimeout(r,20));
  for(const [id,resident]of old)check(stream.residents.get(id)===resident,'proxy disappeared while detail read was blocked');
  view.draw(camera);const before=pixels();check(before.some((v,i)=>i%4===3&&v),'proxy was blank during atlas change');blockDetail=false;release();await stream.settled();
  stream.appendProgressive(${JSON.stringify(catalog.sections.slice(1))},${fine.size});stream.finishProgressive({quads:${catalog.quads}});view.draw(camera);await stream.settled();
  const comparisons=[];
  for(let at=0;at<angles.length;at++){const [rotationDeg,slantDeg]=angles[at],cam={...camera,rotationDeg,slantDeg};view.draw(cam);await stream.settled();view.draw(cam);const actual=pixels(),expected=references[at];let severe=0,sum=0;
   for(let i=0;i<actual.length;i+=4){let bad=false;for(let c=0;c<4;c++){const d=Math.abs(actual[i+c]-expected[i+c]);sum+=d;if(d>8)bad=true;}if(bad)severe++;}
   const fraction=severe/(actual.length/4),mean=sum/actual.length;check(fraction<.002&&mean<.1,'progressive pixel mismatch '+JSON.stringify({rotationDeg,slantDeg,fraction,mean}));comparisons.push({rotationDeg,slantDeg,fraction,mean});
  }
  check(!errors.length&&view.gl.getError()===0,'GPU errors '+errors);check(stream===view.stream,'GPU stream replaced');view.draw(camera);return {pass:true,comparisons,stats:stream.stats(),png:view.canvas.toDataURL('image/png')};
 })()`);
 fs.writeFileSync(path.join(out,'comparison.png'),Buffer.from(result.png.split(',')[1],'base64'));delete result.png;fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));await transfer.close();app.quit();
 }catch(error){console.error(error);await transfer.close();app.exit(1);}});setTimeout(()=>app.exit(1),90000).unref();
