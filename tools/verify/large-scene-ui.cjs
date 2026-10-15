const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const repo=path.resolve(__dirname,'../..'),root=process.argv[2]||repo;process.argv=process.argv.slice(0,2);
const out=path.join(__dirname,'large-mesh-output');fs.mkdirSync(out,{recursive:true});
app.setPath('userData',path.join(out,'app-electron'));BrowserWindow.prototype.show=function(){};
app.once('browser-window-created',(_event,win)=>{
 win.webContents.on('render-process-gone',(_event,details)=>{fs.writeFileSync(path.join(out,'app-result.json'),JSON.stringify({pass:false,renderer:details}));app.exit(1);});
 win.webContents.once('did-finish-load',async()=>{
  try {
   const result=await win.webContents.executeJavaScript(`(async()=>{
    await window.__uiReady;if(els.welcome.open)await dismissWelcome(false);await window.engine.loadResources(null);
    const start=performance.now();await loadFile(${JSON.stringify(path.resolve(repo,'../../璃月港.litematic'))});
    if(!state.viewReady)throw Error('Full app failed: '+els.log.textContent);
    if(state.view.quadCount<8000000)throw Error('Large scene lost faces');
    const modes=[];
    setFly(true);pauseFlyLook();
    const p=state.view.center;state.flyPos=[p[0],p[1],p[2]+30];state.flyPitch=0;
    for(let i=0;i<4;i++){state.flyYaw=i*.4;drawView(true);modes.push({...state.view.drawStats});}
    setFly(false);
    if(state.view.gl.getError()!==0||state.view.gl.isContextLost())throw Error('Large app GPU failure');
    const gl=state.view.gl,pixels=new Uint8Array(els.gl.width*els.gl.height*4);
    gl.readPixels(0,0,els.gl.width,els.gl.height,gl.RGBA,gl.UNSIGNED_BYTE,pixels);
    if(!pixels.some((value,i)=>i%4===3&&value>0))throw Error('Large app canvas is empty');
    await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
    return {pass:true,quads:state.view.quadCount,opaqueBatches:state.view.layers.filter(l=>l.ordinal===0).length,openMillis:performance.now()-start,flyViews:modes.length,actualAtlasPixels:true,glError:0,log:els.log.textContent.slice(-1800)};
   })()`);
   fs.writeFileSync(path.join(out,'app-result.json'),JSON.stringify(result,null,2));
   fs.writeFileSync(path.join(out,'large-preview.png'),(await win.webContents.capturePage()).toPNG());
   console.log(JSON.stringify(result));app.quit();
  }catch(error){fs.writeFileSync(path.join(out,'app-result.json'),JSON.stringify({pass:false,error:error.stack}));console.error(error);app.exit(1);}
 });
});
require(path.join(root,'webapp/main.js'));setTimeout(()=>app.exit(1),165000).unref();
