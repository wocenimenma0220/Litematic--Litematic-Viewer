const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=process.argv[2]||path.resolve(__dirname,'../..');process.argv=process.argv.slice(0,2);
const out=path.join(__dirname,'inspection-look-output');fs.mkdirSync(out,{recursive:true});
app.setPath('userData',path.join(out,'electron'));BrowserWindow.prototype.show=function(){};
const {EngineClient}=require(path.join(root,'webapp/engine-client'));
const call=EngineClient.prototype.call,sceneCalls=[];
EngineClient.prototype.call=function(method,params){return call.call(this,method,params).then(result=>{if(method==='exportScene')sceneCalls.push(params);return result;});};
app.once('browser-window-created',(_event,win)=>win.webContents.once('did-finish-load',async()=>{
 try {
  const result=await win.webContents.executeJavaScript(`(async()=>{
   const check=(ok,text)=>{if(!ok)throw new Error(text);};
   const wait=ms=>new Promise(r=>setTimeout(r,ms));
   const lock=value=>{Object.defineProperty(document,'pointerLockElement',{configurable:true,value});document.dispatchEvent(new Event('pointerlockchange'));};
   document.exitPointerLock=()=>lock(null);
   els.viewport.requestPointerLock=()=>{lock(els.viewport);return Promise.resolve();};
   const move=(dx,dy)=>{const e=new MouseEvent('mousemove',{bubbles:true,clientX:100,clientY:100});Object.defineProperty(e,'movementX',{value:dx});Object.defineProperty(e,'movementY',{value:dy});els.viewport.dispatchEvent(e);};
   await window.__uiReady;if(els.welcome.open)await dismissWelcome(false);await window.engine.loadResources(null);
   await loadFile(${JSON.stringify(path.resolve(__dirname,'fly-output/inspection.litematic'))});check(state.viewReady,'actual scene failed');
   setFly(true);await wait(0);
   // A Windows cursor warp may be preceded by zero-delta events on lock/focus.
   move(0,0);move(300,-180);
   state.flyPos=[.5,.45,-2];state.flyYaw=Math.PI;state.flyPitch=0;drawView();
   await inspectCenter();check(state.inspectedBlock?.id==='minecraft:chest','real chest was not inspected');
   check(!state.flyLookActive&&document.pointerLockElement===null,'inspection did not release input');
   const position=state.flyPos.slice(),yaw=state.flyYaw,pitch=state.flyPitch;
   const revision=state.view.revision;
   els.inspectClose.click();await wait(0);
   check(state.flyLookActive&&document.pointerLockElement===els.viewport,'close did not resume mouse look');
   move(0,0);move(480,-260);
   check(state.flyYaw===yaw&&state.flyPitch===pitch,'relocking applied cursor recenter delta to the camera');
   move(8,-4);
   check(Math.abs(state.flyYaw-(yaw-8*.0025))<1e-9&&Math.abs(state.flyPitch-(pitch+4*.0025))<1e-9,'normal movement after relock was delayed or altered');
   for(let i=0;i<12;i++)move(2,1);
   check(Math.abs(state.flyYaw-(yaw-32*.0025))<1e-9,'continuous mouse movement lost deltas');
   check(JSON.stringify(state.flyPos)===JSON.stringify(position)&&state.view.revision===revision,'resume changed position or rebuilt the scene');
   // Clicking outside the panel resumes once; it must not reopen inspection.
   showInspection({hit:true,name:'石头',id:'minecraft:stone',position:[0,0,0],region:'test',properties:{}});
   const clickYaw=state.flyYaw,clickPitch=state.flyPitch;
   els.viewport.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,button:0,clientX:80,clientY:80}));
   els.viewport.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,button:0,clientX:80,clientY:80}));await wait(0);
   move(-600,350);check(state.flyYaw===clickYaw&&state.flyPitch===clickPitch,'click-to-resume caused a camera jump');
   move(4,3);check(Math.abs(state.flyYaw-(clickYaw-.01))<1e-9,'click-to-resume ignored normal movement');
   check(els.inspector.classList.contains('hidden'),'resume click reopened panel');
   // Promise completion and duplicate change events must not discard another real movement.
   document.dispatchEvent(new Event('pointerlockchange'));resumeFlyLook();await wait(0);
   const activeYaw=state.flyYaw;move(3,0);check(Math.abs(state.flyYaw-(activeYaw-.0075))<1e-9,'duplicate lock notification reset movement');
   pauseFlyLook();const paused=state.flyYaw;move(200,0);check(state.flyYaw===paused,'unlocked movement rotated camera');
   // Chromium can resolve the request before delivering pointerlockchange.
   els.viewport.requestPointerLock=()=>{Object.defineProperty(document,'pointerLockElement',{configurable:true,value:els.viewport});return Promise.resolve();};
   resumeFlyLook();await wait(0);move(0,0);move(250,-200);
   check(state.flyYaw===paused,'promise-first acquisition applied warp');
   document.dispatchEvent(new Event('pointerlockchange'));move(6,0);
   check(Math.abs(state.flyYaw-(paused-.015))<1e-9,'late lock notification discarded valid movement');
   pauseFlyLook();const legacyYaw=state.flyYaw;
   els.viewport.requestPointerLock=()=>{lock(els.viewport);};resumeFlyLook();
   move(-400,200);check(state.flyYaw===legacyYaw,'event-only acquisition applied warp');
   move(5,0);check(Math.abs(state.flyYaw-(legacyYaw-.0125))<1e-9,'event-only acquisition did not resume input');
   setFly(false);
   return {pass:true,realBlockInspection:true,closeButtonNoJump:true,clickToResumeNoJump:true,normalMouseImmediate:true,continuousDeltasPreserved:true,duplicateLockSafe:true,promiseFirstSafe:true,eventOnlySafe:true,sceneUnchanged:true,glError:state.view.gl.getError()};
  })()`);
  if(sceneCalls.length!==1)throw new Error('Inspect/close rebuilt the scene: '+JSON.stringify(sceneCalls));
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));app.quit();
 }catch(error){fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,error:error.stack}));console.error(error.stack);app.exit(1);}
}));
require(path.join(root,'webapp/main.js'));setTimeout(()=>app.exit(1),120000).unref();
