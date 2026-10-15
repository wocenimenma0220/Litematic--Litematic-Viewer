const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const repoRoot=path.resolve(__dirname,'../..'),root=process.argv[2]||repoRoot;process.argv=process.argv.slice(0,2);
const out=path.join(__dirname,'lighting-optional/ui');fs.mkdirSync(out,{recursive:true});app.setPath('userData',path.join(out,'electron'));
BrowserWindow.prototype.show=function(){};
const {UiSettings}=require(path.join(root,'webapp/ui-settings'));
const settingsFile=path.join(root,'settings/ui.json');const saved=fs.existsSync(settingsFile)?fs.readFileSync(settingsFile):null;
app.on('will-quit',()=>{if(saved)fs.writeFileSync(settingsFile,saved);else if(fs.existsSync(settingsFile))fs.unlinkSync(settingsFile);});
const {EngineClient}=require(path.join(root,'webapp/engine-client'));const originalCall=EngineClient.prototype.call,calls=[];
EngineClient.prototype.call=function(method,params){return originalCall.call(this,method,params).then(result=>{calls.push({method,mode:params.realLighting,cached:result.sceneCached});return result;});};
const schematic=fs.readdirSync(path.resolve(repoRoot,'../..')).find(name=>name.endsWith('.litematic'));
app.once('browser-window-created',(_event,win)=>win.webContents.once('did-finish-load',async()=>{
 try {
  const result=await win.webContents.executeJavaScript(`(async()=>{
   const check=(ok,text)=>{if(!ok)throw new Error(text);};
   const wait=ms=>new Promise(r=>setTimeout(r,ms));
   await window.__uiReady;if(els.welcome.open)await dismissWelcome(false);await window.engine.loadResources(null);
   await window.appUi.setRealLighting(false);els.realLighting.checked=false;
   await loadFile(${JSON.stringify(path.resolve(repoRoot,'../..',schematic))});check(state.viewReady,'actual view failed');
   els.zoom.value='1.25';state.zoom=1.25;els.rotation.value='135';drawView();
   const before={pos:state.flyPos.slice(),yaw:state.flyYaw,pitch:state.flyPitch,zoom:state.zoom,rotation:els.rotation.value};
   const first=await window.engine.exportScene({realLighting:false});
   const digest=async bytes=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))).map(v=>v.toString(16).padStart(2,'0')).join('');
   const brightHash=await digest(first.meshArrayBuffer);
   els.realLighting.checked=true;els.realLighting.dispatchEvent(new Event('change'));
   check(state.rendering&&els.realLighting.disabled,'switch did not lock during rebuild');
   const deadline=Date.now()+90000;while(state.rendering){if(Date.now()>deadline)throw new Error('switch timeout');await wait(50);}
   check(state.viewReady&&els.realLighting.checked,'switch failed');
   check(JSON.stringify(state.flyPos)===JSON.stringify(before.pos)&&state.flyYaw===before.yaw&&state.flyPitch===before.pitch&&state.zoom===before.zoom&&els.rotation.value===before.rotation,'switch reset camera');
   check((await window.appUi.settings()).realLighting===true,'mode not saved');
   const real=await window.engine.exportScene({realLighting:true});
   check(real.sceneCached===true&&real.realLighting===true,'real scene not cached');
   check(await digest(real.meshArrayBuffer)!==brightHash,'lighting switch did not change baked colors');
   const params=renderParams(128,128);check(params.realLighting===true,'export parameters lost mode');
   const png=await window.engine.render({...params,out:${JSON.stringify(path.join(out,'real.png'))}});check(png.sceneCached===true,'PNG rebuilt in another mode');
   const gif=await window.engine.renderAnimation({...params,out:${JSON.stringify(path.join(out,'real.gif'))},frames:2,delay:5});
   const again=await window.engine.exportScene({realLighting:true});check(again.sceneCached===true&&await digest(again.meshArrayBuffer)===await digest(real.meshArrayBuffer),'GIF changed scene mode');
   await window.engine.pickBlock({origin:[1000,1000,1000],direction:[1,0,0],maximum:10});
   check((await window.engine.exportScene({realLighting:true})).sceneCached===true,'picking reset mode');
   els.realLighting.checked=false;els.realLighting.dispatchEvent(new Event('change'));
   while(state.rendering){if(Date.now()>deadline)throw new Error('restore timeout');await wait(50);}
   const restored=await window.engine.exportScene({realLighting:false});
   check(restored.sceneCached===true&&await digest(restored.meshArrayBuffer)===brightHash,'switch off did not restore exact original mesh');
   els.realLighting.checked=true;els.realLighting.dispatchEvent(new Event('change'));
   while(state.rendering){if(Date.now()>deadline)throw new Error('final switch timeout');await wait(50);}
   els.realLighting.scrollIntoView({block:'center'});await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
   return {pass:true,switchChangesColors:true,switchBackExact:true,preservesCamera:true,pngCached:true,gifFrames:gif.frames,pickingKeepsMode:true,glError:state.view.gl.getError()};
  })()`);
  if(!(await new UiSettings(root).settings()).realLighting)throw new Error('Fresh settings instance lost saved mode');
  if(!calls.some(call=>call.method==='renderAnimation'&&call.mode===true))throw new Error('GIF did not receive realLighting');
  const image=await win.webContents.capturePage();fs.writeFileSync(path.join(out,'switch.png'),image.toPNG());
  result.persistedAcrossSettingsReload=true;result.calls=calls.filter(call=>['exportScene','render','renderAnimation'].includes(call.method));
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));app.quit();
 }catch(error){fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,error:error.stack}));console.error(error.stack);app.exit(1);}
}));
require(path.join(root,'webapp/main.js'));setTimeout(()=>app.exit(1),150000).unref();
