const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const repo=path.resolve(__dirname,'../..'),root=process.argv[2]||repo;process.argv=process.argv.slice(0,2);
const fixtures=path.join(__dirname,'state-model-output'),out=path.join(fixtures,root===repo?'ui-source':'ui-package');fs.mkdirSync(out,{recursive:true});
app.setPath('userData',path.join(out,'electron'));BrowserWindow.prototype.show=function(){};
const settingsFile=path.join(root,'settings/ui.json'),saved=fs.existsSync(settingsFile)?fs.readFileSync(settingsFile):null;
const restore=()=>{if(saved)fs.writeFileSync(settingsFile,saved);else if(fs.existsSync(settingsFile))fs.unlinkSync(settingsFile);};
fs.mkdirSync(path.dirname(settingsFile),{recursive:true});fs.writeFileSync(settingsFile,JSON.stringify({hideWelcome:true,previewBackend:'gpu',backgroundMode:'checker'}));app.on('will-quit',restore);
app.once('browser-window-created',(_event,win)=>win.webContents.once('did-finish-load',async()=>{
 const run=code=>win.webContents.executeJavaScript('(async()=>{'+code+'})()');
 try{
  await run(`await window.__uiReady;await window.engine.loadResources(null);`);
  const checks=[];
  for(const name of ['pistons','heads-on-stone','rail-minecarts']) {
   const result=await run(`await loadFile(${JSON.stringify(path.join(fixtures,name+'.litematic'))});
    if(!state.viewReady)throw Error('GPU scene unavailable: '+els.log.textContent);
    state.view.draw({orbit:true,rotationDeg:45,slantDeg:30,zoom:1});
    const gl=state.view.gl,pixels=new Uint8Array(els.gl.width*els.gl.height*4);gl.readPixels(0,0,els.gl.width,els.gl.height,gl.RGBA,gl.UNSIGNED_BYTE,pixels);
    if(gl.getError()!==0||!pixels.some((v,i)=>i%4===3&&v>0))throw Error('GPU frame empty or invalid');
    return {quads:state.view.quadCount,screenshot:els.gl.toDataURL('image/png')};`);
   fs.writeFileSync(path.join(out,name+'.png'),Buffer.from(result.screenshot.split(',')[1],'base64'));delete result.screenshot;checks.push({name,...result});
   if(name==='heads-on-stone')await run(`state.view.draw({eye:[2.05,3,0],target:[2.05,1,2.05],fovDeg:35});
    const gl=state.view.gl,pixel=new Uint8Array(4);gl.readPixels(Math.floor(els.gl.width/2),Math.floor(els.gl.height/2),1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixel);
    if(pixel[3]!==255)throw Error('Stone top under skull is missing in GPU view');
    const hit=await window.engine.pickBlock({origin:[2.05,3,0],direction:[0,-2,2.05],maximum:10});
    if(!hit.hit||hit.position.join(',')!=='2,0,2')throw Error('Skull support face could not be picked: '+JSON.stringify(hit));`);
  }
  // The real CPU preview must display the same newly tilted minecart scene.
  await run(`els.previewBackend.value='cpu';els.previewBackend.dispatchEvent(new Event('change'));
   const end=Date.now()+30000;while(state.rendering||cpuPreviewRunning||cpuPreviewPending){if(Date.now()>end)throw Error('CPU preview timeout');await new Promise(r=>setTimeout(r,20));}
   if(!state.cpuPreviewReady)throw Error('CPU minecart preview failed');`);
  const result={pass:true,checks,gpuStoneSupportVisibleAndPickable:true,cpuMinecartPreview:true};
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));app.quit();
 }catch(error){restore();fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,error:error.stack}));console.error(error);app.exit(1);}
}));
require(path.join(root,'webapp/main.js'));setTimeout(()=>{restore();console.error('State model UI timeout');app.exit(1);},165000).unref();
