'use strict';
const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const sourceRoot=path.resolve(__dirname,'../..'),root=process.argv[2]||sourceRoot,out=path.join(__dirname,'selection-output',process.argv[2]?'input-package':'input-source');
fs.mkdirSync(out,{recursive:true});app.setPath('userData',path.join(out,'electron'));
process.env.JAVA_TOOL_OPTIONS=(process.env.JAVA_TOOL_OPTIONS||'')+' -Dlitematicrender.skins.offline=true';
process.on('uncaughtException',error=>{console.error(error.stack);fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,error:error.stack}));app.exit(1);});
app.commandLine.appendSwitch('disable-renderer-backgrounding');
BrowserWindow.prototype.show=function(){this.setPosition(-20000,-20000,false);this.showInactive();};
const settingsModule=require(path.join(root,'webapp/ui-settings')),OriginalSettings=settingsModule.UiSettings;
fs.mkdirSync(path.join(out,'settings'),{recursive:true});fs.writeFileSync(path.join(out,'settings/ui.json'),JSON.stringify({hideWelcome:true,projectionMode:'full',clearCacheOnExit:false}));
settingsModule.UiSettings=class extends OriginalSettings{constructor(){super(out);}};
app.once('browser-window-created',(_event,win)=>{
 win.webContents.setBackgroundThrottling(false);
 win.webContents.once('did-finish-load',async()=>{
  const run=code=>win.webContents.executeJavaScript('(async()=>{'+code+'})()'),wait=ms=>new Promise(r=>setTimeout(r,ms)),checks=[];
  const check=(ok,message,details)=>checks.push({pass:!!ok,message,...(details?{details}:{})});
  const key=(type,keyCode,modifiers=[])=>win.webContents.sendInputEvent({type,keyCode,modifiers});
  try{
   await run(`await window.__uiReady;await window.engine.loadResources(null);await loadFile(${JSON.stringify(path.join(sourceRoot,'tools/verify/selection-output/solid.litematic'))});
    window.__inputEvents=[];window.addEventListener('keydown',e=>window.__inputEvents.push({code:e.code,trusted:e.isTrusted,prevented:e.defaultPrevented}));
    window.__lock=value=>{Object.defineProperty(document,'pointerLockElement',{configurable:true,value});document.dispatchEvent(new Event('pointerlockchange'));};
    document.exitPointerLock=()=>window.__lock(null);els.viewport.requestPointerLock=()=>{window.__lock(els.viewport);return Promise.resolve();};
    setFly(true);await Promise.resolve();toggleSelection(true);await runSelectionCommand('//select 1 1 1 1');await runSelectionCommand('//select 2 3 3 3');state.flyYaw=0;state.flyPitch=0;drawView();`);
   check(await run(`els.viewport.dispatchEvent(new KeyboardEvent('keydown',{code:'KeyV',repeat:true,bubbles:true,cancelable:true}));return state.fly&&state.flyLookActive;`),'holding the fly key does not repeatedly toggle modes');
   await run(`if(!state.fly)setFly(true);resumeFlyLook();await Promise.resolve();els.viewport.focus();`);
   key('keyDown','Tab');key('keyUp','Tab');await wait(35);
   const tab=await run(`return {active:document.activeElement.id,fly:state.fly,look:state.flyLookActive};`);
   check(tab.active==='viewport'&&tab.fly&&tab.look,'Tab cannot move focus out of active flight',tab);
   await run(`activateFlyLook();state.flyYaw=0;state.flyPitch=0;state.flyPos=[2,2,4];drawView();`);
   key('keyDown','W');await wait(30);
   const before=await run(`return {position:state.flyPos.slice(),corner:state.selection.corners[state.selection.active].slice()};`);
   const point=await run(`const r=els.viewport.getBoundingClientRect();return {x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2)};`);
   for(let cycle=0;cycle<4;cycle++){
    key('keyDown','Alt',['alt']);
    win.webContents.sendInputEvent({type:'mouseWheel',...point,deltaY:120,deltaX:0,modifiers:['alt'],canScroll:true});
    key('keyUp','Alt');await wait(30);
   }
   const after=await run(`return {focus:document.activeElement.id,fly:state.fly,look:state.flyLookActive,moving:state.keys.has('KeyW'),position:state.flyPos.slice(),corner:state.selection.corners[state.selection.active].slice(),tool:state.selection.enabled,inspector:els.inspector.classList.contains('hidden')};`);
   check(!win.isMenuBarVisible(),'Alt wheel never activates a native menu');
   check(after.fly&&after.look&&after.focus==='viewport'&&after.moving&&after.tool&&after.inspector,'Alt wheel keeps flight, movement, tool and keyboard focus',after);
   check(after.position[2]<before.position[2]&&after.corner[2]!==before.corner[2],'movement and corner adjustment work together');
   key('keyUp','W');key('keyDown','Escape');key('keyUp','Escape');await wait(30);
   await run(`if(!state.fly)setFly(true);pauseFlyLook();window.__requests=0;let complete;
    const pending=new Promise(resolve=>complete=resolve);els.viewport.requestPointerLock=()=>{window.__requests++;return pending;};resumeFlyLook();
    for(let i=0;i<3;i++)els.viewport.dispatchEvent(new PointerEvent('pointerdown',{button:0,bubbles:true,cancelable:true}));
    window.__lock(els.viewport);complete();await Promise.resolve();await Promise.resolve();`);
   check(await run(`return window.__requests===1&&state.flyLookActive;`),'rapid selection clicks share one pending pointer-lock request');
   await run(`els.viewport.requestPointerLock=()=>{window.__lock(els.viewport);return Promise.resolve();};resumeFlyLook();await Promise.resolve();`);
   key('keyDown','/');key('keyUp','/');await wait(30);
   const command=await run(`return {active:document.activeElement.id,look:state.flyLookActive,keys:[...state.keys],resume:commandResume};`);check(command.active==='commandInput'&&!command.look&&!command.keys.length,'command input deliberately releases flight',command);
   key('keyDown','W');key('char','w');key('keyUp','W');await wait(30);
   check(await run(`return state.keys.size===0&&selectionUi.command.value.endsWith('w');`),'typing a movement letter stays in the command input');
   key('keyDown','Escape');key('keyUp','Escape');await wait(30);
   const escaped=await run(`return {active:document.activeElement.id,look:state.flyLookActive,keys:[...state.keys],resume:commandResume};`);check(escaped.look&&escaped.active==='viewport','Escape from the command input returns focus to flight',escaped);
   await run(`state.keys.add('KeyW');shortcutHeldKeys.add('Alt');window.dispatchEvent(new Event('blur'));`);
   check(await run(`return !state.flyLookActive&&state.keys.size===0&&shortcutHeldKeys.size===0;`),'actual window blur still releases input and clears held keys');
   const events=await run(`return window.__inputEvents;`);check(events.some(e=>e.trusted&&e.code==='Tab')&&events.some(e=>e.trusted&&e.code==='AltLeft'),'trusted Chromium keyboard events reached the preview',events.slice(0,10));
   const result={pass:checks.every(c=>c.pass),version:require(path.join(root,'webapp/package.json')).version,checks};fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
   if(result.pass)app.quit();else app.exit(1);
  }catch(error){console.error(error.stack);fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,checks,error:error.stack},null,2));app.exit(1);}
 });
});
setTimeout(()=>app.exit(1),180000).unref();require(path.join(root,'webapp/main.js'));
