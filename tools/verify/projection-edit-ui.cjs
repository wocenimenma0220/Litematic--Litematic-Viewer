
'use strict';
const {app,BrowserWindow,dialog}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../..'),out=path.join(__dirname,'projection-edit-output');fs.mkdirSync(out,{recursive:true});app.setPath('userData',path.join(out,'electron'));
BrowserWindow.prototype.show=function(){this.setPosition(-20000,-20000,false);this.showInactive();};app.commandLine.appendSwitch('disable-renderer-backgrounding');
const settings=require('../../webapp/ui-settings'),Original=settings.UiSettings;fs.mkdirSync(path.join(out,'settings'),{recursive:true});fs.writeFileSync(path.join(out,'settings/ui.json'),JSON.stringify({hideWelcome:true,projectionMode:'full',clearCacheOnExit:false}));settings.UiSettings=class extends Original{constructor(){super(out);}};
const original=fs.readFileSync(path.join(out,'original.litematic'));dialog.showSaveDialog=async()=>({canceled:false,filePath:path.join(out,'ui-edited.litematic')});
app.once('browser-window-created',(_event,win)=>{win.webContents.setBackgroundThrottling(false);win.webContents.setAudioMuted(true);win.webContents.once('did-finish-load',async()=>{
 try{
 const result=await win.webContents.executeJavaScript(`(async()=>{
  const check=(ok,text)=>{if(!ok)throw Error(text);},wait=ms=>new Promise(r=>setTimeout(r,ms)),until=async(fn,text)=>{const end=Date.now()+120000;while(!fn()){if(Date.now()>end)throw Error(text+' · '+$('projectionStatus').textContent);await wait(35);}};
  const ready=()=>until(()=>state.viewReady&&!state.rendering&&!state.scenePreparing,'render timeout');
  await window.__uiReady;await window.__resourcesReady;await loadFile(${JSON.stringify(path.join(out,'original.litematic'))});await ready();
  els.rotation.value=0;els.slant.value=0;syncAngleLabels();drawView();
  const screen=p=>{const m=state.view.lastMatrix,v=[...p,1],clip=[0,0,0,0];for(let r=0;r<4;r++)for(let c=0;c<4;c++)clip[r]+=m[c*4+r]*v[c];const rect=els.gl.getBoundingClientRect();return{clientX:rect.left+(clip[0]/clip[3]+1)*rect.width/2,clientY:rect.top+(1-clip[1]/clip[3])*rect.height/2};};
  const click=async(button,p,shiftKey=false)=>{const token=state.file;els.viewport.dispatchEvent(new PointerEvent('pointerdown',{...screen(p),button,shiftKey,pointerId:1,bubbles:true,cancelable:true}));await wait(100);await until(()=>!$('projectionExport').disabled,'edit timeout');await ready();};
  state.selection.setCorner(0,[-1,-1,-1]);state.selection.setCorner(1,[3,1,1]);state.selection.renderEnabled=true;state.selection.enabled=true;selectionChanged();await until(()=>!selectionMeshRunning&&!!state.view.selectionView,'selection timeout');
  window.dispatchEvent(new KeyboardEvent('keydown',{code:'KeyG',key:'g',bubbles:true,cancelable:true}));check(window.projectionEditor.enabled,'G did not enable editing');check(!state.selection.enabled&&state.selection.renderEnabled,'edit mode broke partial rendering');
  await wait(100);drawView();Array.from($('projectionTabs').children).find(b=>b.textContent==='全部').click();$('projectionSearch').value='glass';$('projectionSearch').dispatchEvent(new Event('input'));document.querySelector('#projectionPalette [data-block="glass"]').click();
  await click(2,[.5,.5,.5],true);let found=await window.engine.searchBlocks({query:'minecraft:glass'});check(found.total===1&&found.results[0].position.join(',')==='0,0,1','3D placement / partial pick failed');
  await click(0,[.5,.5,1.5]);found=await window.engine.searchBlocks({query:'minecraft:glass'});check(found.total===0,'3D deletion failed');
  $('projectionUndo').click();await until(()=>!$('projectionExport').disabled,'undo timeout');await ready();check((await window.engine.searchBlocks({query:'minecraft:glass'})).total===1,'undo failed');
  $('projectionRedo').click();await until(()=>!$('projectionExport').disabled,'redo timeout');await ready();check((await window.engine.searchBlocks({query:'minecraft:glass'})).total===0,'redo failed');
  $('projectionDebug').click();await click(0,[.5,.5,.5]);check($('projectionStatus').textContent.includes('facing = north'),'debug property select failed');
  await click(2,[.5,.5,.5]);let hit=await window.engine.editPick({origin:[.5,.5,2.5],direction:[0,0,-1],maximum:10,selection:{min:[-1,-1,-1],max:[4,2,2]}});check(hit.states.facing==='east','debug forward value failed');
  await click(2,[.5,.5,.5],true);hit=await window.engine.editPick({origin:[.5,.5,2.5],direction:[0,0,-1],maximum:10,selection:{min:[-1,-1,-1],max:[4,2,2]}});check(hit.states.facing==='north','debug reverse failed');
  await click(0,[.5,.5,.5]);check($('projectionStatus').textContent.includes('triggered'),'next debug property failed');await click(2,[.5,.5,.5]);
  hit=await window.engine.editPick({origin:[.5,.5,2.5],direction:[0,0,-1],maximum:10});check(hit.states.triggered==='true','non-rendered property lost');
  await until(()=>!!state.view.selectionView&&!selectionMeshRunning,'partial render rebuild timeout');check(state.selection.renderEnabled&&state.view.selectionView,'partial render was lost after modifications');
  setFly(true);pauseFlyLook();state.flyPos=[.5,.5,2.5];state.flyYaw=0;state.flyPitch=0;drawView();
  await window.projectionEditor.interact(2,{});await ready();hit=await window.engine.editPick({origin:[.5,.5,2.5],direction:[0,0,-1],maximum:10});check(hit.states.triggered==='false','fly debug failed');check(state.fly&&state.flyPos[2]===2.5,'edit moved fly camera');
  const exported=await window.projectionEditor.export();check(exported.author==='LiteKinda','export author wrong');
  setFly(false);state.selection.renderEnabled=false;selectionChanged();await window.projectionEditor.toggle(false);await window.appUi.setKeybindings({...shortcutBindings,edit:['KeyJ']});setShortcutBindings({...shortcutBindings,edit:['KeyJ']});window.projectionEditor.refreshHints();check($('menuEditProjection').textContent.includes('J'),'custom key hint failed');
  window.dispatchEvent(new KeyboardEvent('keydown',{code:'KeyJ',key:'j',bubbles:true,cancelable:true}));check(window.projectionEditor.enabled,'custom key toggle failed');
  $('projectionSearch').value='';$('projectionSearch').dispatchEvent(new Event('input'));return{pass:true,quads:state.view.quadCount,exportPath:exported.path};
 })()`);
 await win.webContents.executeJavaScript("els.rotation.value='35';els.slant.value='25';syncAngleLabels();$('projectionSearch').value='';$('projectionSearch').dispatchEvent(new Event('input'));lastViewKey=null;drawView()");await new Promise(r=>setTimeout(r,400));fs.writeFileSync(path.join(out,'editor-ui.png'),(await win.webContents.capturePage()).toPNG());
 if(!original.equals(fs.readFileSync(path.join(out,'original.litematic'))))throw Error('Source file changed');
 await win.webContents.executeJavaScript(`(async()=>{await loadFile(${JSON.stringify(path.join(out,'ui-edited.litematic'))});if(window.projectionEditor.enabled)throw Error('new file retained edit mode');if(state.info.author!=='LiteKinda')throw Error('reopen author');
state.projectionMode='streaming';await loadFile(${JSON.stringify(path.join(out,'original.litematic'))});
const until=async fn=>{const end=Date.now()+120000;while(!fn()){if(Date.now()>end)throw Error('stream edit timeout');await new Promise(r=>setTimeout(r,40));}};
await until(()=>state.viewReady&&!state.rendering&&!state.scenePreparing);if(!state.view.stream)throw Error('stream not used');window.projectionEditor.toggle(true);$('projectionDebug').click();setFly(true);pauseFlyLook();state.flyPos=[.5,.5,2.5];state.flyYaw=0;state.flyPitch=0;drawView();await window.projectionEditor.interact(2,{});await until(()=>state.viewReady&&!state.rendering&&!state.scenePreparing);const hit=await window.engine.editPick({origin:[.5,.5,2.5],direction:[0,0,-1],maximum:10});if(hit.states.facing!=='east'||!state.view.stream)throw Error('stream edit failed');await window.projectionEditor.history('undo');await window.projectionEditor.history('undo');if(state.view.gl.getError())throw Error('stream GL error');await window.projectionEditor.toggle(false);setFly(false);$('menuBoard').click();$('boardViewSide').click();if(!$('boardDialog').open||$('boardPreviewCanvas'))throw Error('flat editor was not kept independent');$('boardClose').click();})()`);
 fs.writeFileSync(path.join(out,'ui-result.json'),JSON.stringify(result,null,2));console.log('PASS: G / custom key, 3D pointer placement / deletion, partial pick, undo / redo, vanilla debug stick / shift, fly compatibility, export / reopen, original preservation and independent 2D board');app.quit();
 }catch(error){console.error(error.stack);fs.writeFileSync(path.join(out,'ui-result.json'),JSON.stringify({pass:false,error:error.stack},null,2));app.exit(1);}
});});require('../../webapp/main.js');
