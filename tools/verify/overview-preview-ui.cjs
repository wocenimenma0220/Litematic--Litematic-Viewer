const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=process.argv[2]||path.resolve(__dirname,'../..');process.argv=process.argv.slice(0,2);const out=path.join(__dirname,'cache-large-output/corrected-overview');fs.mkdirSync(out,{recursive:true});app.setPath('userData',path.join(out,'electron'));
process.env.JAVA_TOOL_OPTIONS=`-Dlitematicrender.cache.directory=${root.replaceAll('\\','/')}/tools/verify/cache-large-output/color-scenes -Dlitematicrender.skins.offline=true`;
app.commandLine.appendSwitch('disable-backgrounding-occluded-windows');app.commandLine.appendSwitch('disable-renderer-backgrounding');BrowserWindow.prototype.show=function(){this.setPosition(-20000,-20000,false);this.showInactive();};
const sm=require(path.join(root,'webapp/ui-settings')),Settings=sm.UiSettings;sm.UiSettings=class extends Settings{constructor(){super(out);}};
app.once('browser-window-created',(_event,win)=>{win.webContents.setBackgroundThrottling(false);win.webContents.once('did-finish-load',async()=>{try{
 const result=await win.webContents.executeJavaScript(`(async()=>{
  await window.__uiReady;if(els.welcome.open)await dismissWelcome(false);await window.engine.loadResources(null);state.largeProjection=true;els.largeProjection.checked=true;
  const loading=loadFile('D:/litematic-display/璃月港.litematic'),end=Date.now()+60000;while(!state.overview||!state.viewReady){if(Date.now()>end)throw Error('no overview '+els.log.textContent);await new Promise(r=>setTimeout(r,20));}
  await state.view.stream.settled();drawView();const stats=state.view.stream.stats();if(stats.loads!==stats.residentSections||stats.residentSections!==stats.requestedSections)throw Error('overview alias or coverage failure '+JSON.stringify(stats));
  ++gpuBuildGeneration;await window.engine.cancelPreview();await loading;state.scenePreparing=false;setBusy(false);drawView();return {pass:true,stats,log:els.log.textContent};
 })()`);fs.writeFileSync(path.join(out,'preview.png'),(await win.webContents.capturePage()).toPNG());fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({...result,log:undefined}));app.quit();
 }catch(e){console.error(e);app.exit(1);}});});require(path.join(root,'webapp/main.js'));setTimeout(()=>app.exit(1),120000).unref();
