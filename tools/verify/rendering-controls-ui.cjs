const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=process.argv[2]||path.resolve(__dirname,'../..');process.argv=process.argv.slice(0,2);
const out=path.join(__dirname,'feature-output/ui');fs.mkdirSync(out,{recursive:true});app.setPath('userData',path.join(out,'electron'));
app.commandLine.appendSwitch('disable-renderer-backgrounding');
BrowserWindow.prototype.show=function(){this.setPosition(-20000,-20000,false);this.showInactive();};
const settingsModule=require(path.join(root,'webapp/ui-settings')),OriginalSettings=settingsModule.UiSettings;
settingsModule.UiSettings=class extends OriginalSettings {constructor(){super(out);}};
const {EngineClient}=require(path.join(root,'webapp/engine-client')),original=EngineClient.prototype.call,calls=[];
EngineClient.prototype.call=function(method,params){return original.call(this,method,params).then(result=>{calls.push({method,lightingEnabled:params.lightingEnabled,streaming:params.streaming,format:result.meshFormat});return result;});};
app.once('browser-window-created',(_event,win)=>{
 win.webContents.setBackgroundThrottling(false);
 win.webContents.once('did-finish-load',async()=>{
  try {
   const result=await win.webContents.executeJavaScript(`(async()=>{
    const check=(ok,text)=>{if(!ok)throw Error(text);};const wait=ms=>new Promise(r=>setTimeout(r,ms));
    const idle=async()=>{const deadline=Date.now()+120000;while(state.rendering||state.scenePreparing){if(Date.now()>deadline)throw Error('preview timeout');await wait(50);}check(state.viewReady,'preview not ready');};
    await window.__uiReady;await window.appUi.setLargeProjection(false);await window.appUi.setLightingEnabled(true);await window.appUi.setLodPreview(true);state.largeProjection=false;state.lodPreview=true;els.largeProjection.checked=false;els.lightingEnabled.checked=true;els.lodPreview.checked=true;if(els.welcome.open)await dismissWelcome(false);
    check(!els.largeProjection.checked&&els.lightingEnabled.checked,'incorrect fresh defaults');
    await window.engine.loadResources(null);await loadFile(${JSON.stringify(path.join(__dirname,'feature-output/water-trapdoors.litematic'))});await idle();
    check(!state.view.stream,'default preview unexpectedly chunked');
    els.viewport.requestPointerLock=()=>Promise.reject(Error('hidden test'));setFly(true);
    check(state.flyPos.every((v,i)=>v===state.view.center[i]),'fly did not start in projection centre');
    els.showCoordinates.checked=true;els.showCoordinates.dispatchEvent(new Event('change'));await wait(100);drawView();
    check(!els.coordinates.classList.contains('hidden')&&els.coordinates.textContent.includes('X:'),'coordinates missing');
    state.flyPos[0]=-12.25;state.flyPos[1]=3.5;state.flyPos[2]=24.75;drawView();
    check(els.coordinates.textContent==='X: -12.25  Y: 3.50  Z: 24.75','coordinates stale or wrong');
    const position=state.flyPos.slice();els.lightingEnabled.checked=false;els.lightingEnabled.dispatchEvent(new Event('change'));await idle();
    check(els.realLighting.disabled&&state.flyPos.every((v,i)=>v===position[i]),'lighting toggle changed camera or smoothing control');
    const params=renderParams(160,120);check(params.lightingEnabled===false,'PNG/GIF params lost lighting toggle');
    await window.engine.render({...params,out:${JSON.stringify(path.join(out,'flat.png'))}});
    await window.engine.renderAnimation({...params,out:${JSON.stringify(path.join(out,'flat.gif'))},frames:2,delay:5});
    els.largeProjection.checked=true;els.largeProjection.dispatchEvent(new Event('change'));await idle();
    check(!!state.view.stream,'large projection toggle did not enable sections');
    els.lodPreview.checked=false;els.lodPreview.dispatchEvent(new Event('change'));await idle();
    setFly(false);drawView();const deadline=Date.now()+10000;while([...state.view.stream.residents.values()].some(r=>r.level!==0)||state.view.stream.residents.size<state.view.stream.manifest.sections.length){if(Date.now()>deadline)throw Error('full detail sections did not load '+JSON.stringify({stats:state.view.stream.stats(),wanted:[...state.view.stream.wanted].map(([id,t])=>[id,t.level]),resident:[...state.view.stream.residents].map(([id,t])=>[id,t.level]),failed:[...state.view.stream.failed],camera:state.view.stream.lastCamera}));drawView();await wait(100);}
    check([...state.view.stream.residents.values()].every(r=>r.level===0),'disabled LOD fell back to coarse geometry');
    check(els.coordinates.classList.contains('hidden'),'coordinates visible outside fly');
    els.largeProjection.checked=false;els.largeProjection.dispatchEvent(new Event('change'));await idle();check(!state.view.stream,'large mode off retained stream');
    els.lightingEnabled.checked=true;els.lightingEnabled.dispatchEvent(new Event('change'));await idle();check(!els.realLighting.disabled,'smoothing did not reenable');
    const settings=await window.appUi.settings();check(settings.showCoordinates===true&&settings.largeProjection===false&&settings.lightingEnabled===true,'settings not saved');
    els.sidebar.scrollTop=els.lightingEnabled.closest('.group').offsetTop;drawView();await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
    return {pass:true,centre:state.view.center,coordinates:els.coordinates.textContent,settings};
   })()`);
   const fresh=await new OriginalSettings(out).settings();if(!fresh.showCoordinates||fresh.largeProjection||!fresh.lightingEnabled)throw Error('fresh settings reload lost controls');
   fs.writeFileSync(path.join(out,'preview.png'),(await win.webContents.capturePage()).toPNG());
   fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({...result,calls},null,2));console.log(JSON.stringify({pass:true,calls:calls.filter(c=>['exportScene','render','renderAnimation'].includes(c.method))}));app.quit();
  } catch(error){fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,error:error.stack},null,2));console.error(error.stack);app.exit(1);}
 });
});
require(path.join(root,'webapp/main.js'));setTimeout(()=>app.exit(1),240000).unref();
