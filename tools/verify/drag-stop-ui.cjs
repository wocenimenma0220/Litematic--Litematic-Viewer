'use strict';
// Exercise real main/preload/renderer IPC, native file drops and actual Java process interruption.
const {app,BrowserWindow}=require('electron');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..'),out=path.join(__dirname,'drag-stop-output');
const small=process.argv[2]||'D:/litematic-display/1.8w-潜影贝收集.litematic';
const large=process.argv[3]||'D:/litematic-display/璃月港.litematic';
process.argv=process.argv.slice(0,2);
fs.mkdirSync(path.join(out,'runtime/settings'),{recursive:true});
fs.writeFileSync(path.join(out,'runtime/settings/ui.json'),JSON.stringify({hideWelcome:true,clearCacheOnExit:false,projectionMode:'full'}));
app.setPath('userData',path.join(out,'electron'));
process.env.JAVA_TOOL_OPTIONS=`-Dlitematicrender.skins.offline=true -Dlitematicrender.cache.directory=${out.replaceAll('\\','/')}/scenes -Dlitematicrender.resources.cache.directory=${out.replaceAll('\\','/')}/resources`;
const settings=require('../../webapp/ui-settings'),Settings=settings.UiSettings;
settings.UiSettings=class extends Settings{constructor(){super(path.join(out,'runtime'));}};
require('../../webapp/cache-cleanup').clearRenderingCache=async()=>{};
const {EngineClient}=require('../../webapp/engine-client'),originalCall=EngineClient.prototype.call;
const calls=[],clients=[];
EngineClient.prototype.call=function(method,params){
  if(!clients.includes(this))clients.push(this);
  const record={method,pid:this.proc?.pid,at:Date.now()};calls.push(record);
  return originalCall.call(this,method,params).then(result=>{record.stage=result?.stage;record.done=true;return result;},error=>{record.error=error.message;throw error;});
};
BrowserWindow.prototype.show=function(){this.setPosition(-20000,-20000,false);this.showInactive();};
app.commandLine.appendSwitch('disable-renderer-backgrounding');
let failed=false;
const report={pass:false,checks:[]};
app.on('will-quit',()=>{if(failed)app.exit(1);});
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(predicate,label,timeout=30000){const end=Date.now()+timeout;while(!await predicate()){if(Date.now()>end)throw Error('Timed out: '+label);await wait(20);}}
app.once('browser-window-created',(_event,win)=>{
  win.webContents.setBackgroundThrottling(false);
  win.webContents.once('did-finish-load',async()=>{
    const evaluate=script=>win.webContents.executeJavaScript(script);
    const viewState=()=>evaluate('({file:state.file,ready:state.viewReady,cpu:state.cpuPreviewReady,stopped:state.stopped,stopping:state.stopping,opening:state.fileOpening,busy:state.rendering,preparing:state.scenePreparing,needsOpen:state.engineNeedsOpen,status:els.status.textContent,log:els.log.textContent})');
    const ready=()=>until(async()=>{const s=await viewState();return s.ready&&!s.busy&&!s.preparing&&!s.opening;},'preview ready',60000);
    const nativeDrag=async(files,type='dragEnter')=>{
      await win.webContents.debugger.sendCommand('Input.dispatchDragEvent',{type,x:160,y:120,data:{items:[],files,dragOperationsMask:1}});
    };
    const drop=async files=>{await nativeDrag(files);await nativeDrag(files,'dragOver');await nativeDrag(files,'drop');};
    const stop=async()=>{
      const previous=clients[clients.length-1],start=Date.now();
      await evaluate('els.stopRender.click()');
      await until(async()=>{const s=await viewState();return s.stopped&&!s.stopping&&!s.busy;},'force stop');
      assert.equal(previous.isRunning(),false,'previous Java process survived force stop');
      assert.ok(Date.now()-start<5000,'force stop waited for the blocked render');
      const s=await viewState();assert.equal(s.ready,false);assert.equal(s.needsOpen,true);
      assert.equal(await evaluate('els.render.disabled||!els.export.disabled||!els.stopRender.disabled'),false,'stopped button state invalid');
      return Date.now()-start;
    };
    try{
      win.webContents.debugger.attach('1.3');
      // Native Chromium File objects must cross contextBridge and resolve through Electron webUtils.
      await nativeDrag([small]);
      const hint=await evaluate('({hidden:els.fileDropHint.classList.contains("hidden"),rect:els.fileDropHint.getBoundingClientRect().toJSON(),radius:getComputedStyle(els.fileDropHint).borderRadius})');
      assert.equal(hint.hidden,false);assert.ok(hint.rect.left<30&&hint.rect.top<70);assert.equal(hint.radius,'16px');
      await evaluate('var dragData=new DataTransfer();dragData.items.add(new File([""],"nested.litematic"));els.open.dispatchEvent(new DragEvent("dragenter",{bubbles:true,dataTransfer:dragData}));els.sidebar.dispatchEvent(new DragEvent("dragleave",{bubbles:true,dataTransfer:dragData}));');
      assert.equal(await evaluate('els.fileDropHint.classList.contains("hidden")'),false,'nested drag targets made the card flicker');
      await win.webContents.debugger.sendCommand('Input.dispatchDragEvent',{type:'dragCancel',x:160,y:120,data:{items:[],dragOperationsMask:1}});
      await evaluate('window.dispatchEvent(new DragEvent("dragend"))');
      assert.equal(await evaluate('els.fileDropHint.classList.contains("hidden")'),true);
      await drop([small]);await ready();
      assert.equal(path.resolve((await viewState()).file),path.resolve(small));
      assert.ok(await evaluate('state.view.quadCount>0&&state.view.gl.getError()===0'));
      // Repeat after readiness to capture the drop card over the user's actual schematic.
      await nativeDrag([small]);await wait(180);
      assert.equal(await evaluate('els.fileDropHint.classList.contains("hidden")'),false,'drop card disappeared during drag');
      fs.writeFileSync(path.join(out,'drop-hint.png'),(await win.webContents.capturePage()).toPNG());
      await win.webContents.debugger.sendCommand('Input.dispatchDragEvent',{type:'dragCancel',x:160,y:120,data:{items:[],dragOperationsMask:1}});
      await evaluate('window.dispatchEvent(new DragEvent("dragend"))');
      report.checks.push('native .litematic drop, startup readiness, rounded top-left card and drag exit');
      const generation=await evaluate('fileOpenGeneration');
      await drop([path.join(root,'README.md')]);await drop([small,large]);
      assert.equal(await evaluate('fileOpenGeneration'),generation,'invalid drop interrupted preview');
      report.checks.push('invalid and multiple files leave the preview running');

      // Start an independent background export before killing only the preview engine.
      const exportPath=path.join(out,'uninterrupted.png');
      const job=await evaluate(`window.exportsApi.start('png',{...renderParams(96,96),out:${JSON.stringify(exportPath)}})`);
      report.manualStopMillis=await stop();
      await evaluate('window.rebuilding=rebuildPreview();void 0');await ready();await evaluate('window.rebuilding');
      await until(async()=>evaluate(`window.exportsApi.list().then(jobs=>jobs.some(job=>job.id===${JSON.stringify(job.id)}&&job.state==='completed'))`),'independent PNG export',60000);
      assert.ok(fs.statSync(exportPath).size>100);
      report.checks.push('manual stop kills Java, rerender restores resources, background PNG completes');

      // Stop a synchronous full-mesh RPC: a queued Java cancel cannot pass this case.
      let at=calls.length;
      await evaluate(`state.projectionMode='full';window.replacing=loadFile(${JSON.stringify(large)});void 0`);
      await until(()=>calls.slice(at).some(c=>c.method==='exportScene'&&!c.done),'blocked full mesh',60000);
      report.fullStopMillis=await stop();await evaluate('window.replacing');
      await wait(150);assert.equal((await viewState()).stopped,true,'late full mesh revived stopped UI');
      report.checks.push('blocked synchronous full-mesh generation really stops');

      // CPU rendering uses a different queue and must not publish its old image after cancellation.
      at=calls.length;
      await evaluate('state.previewBackend="cpu";els.previewBackend.value="cpu";window.cpuLoading=rebuildPreview();void 0');
      await until(()=>calls.slice(at).some(c=>c.method==='renderPreview'&&!c.done),'blocked CPU preview',60000);
      report.cpuStopMillis=await stop();await evaluate('window.cpuLoading');
      await wait(150);assert.equal((await viewState()).cpu,false,'old CPU image was restored');
      report.checks.push('CPU render is interrupted without publishing a stale image');

      // A new native drop must stop the asynchronous progressive lighting/mesh worker as well.
      await evaluate('state.previewBackend="gpu";els.previewBackend.value="gpu";state.projectionMode="streaming";window.progressiveLoading=rebuildPreview();void 0');
      await until(async()=>evaluate('state.scenePreparing'),'progressive preparation',60000);
      const previewClient=clients.findLast(client=>client.proc&&client.isRunning()&&!client.stopping);
      const workRoots=previewClient.temporaryCaches,pid=previewClient.proc.pid;
      const interrupted=[];
      await until(()=>{
        for(const [kind,prefix] of [['scenes',`.work-${pid}-`],['previews',`preview-${pid}-`]]){
          if(!fs.existsSync(workRoots[kind]))return false;
          const name=fs.readdirSync(workRoots[kind]).find(name=>name.startsWith(prefix));
          if(!name)return false;
          if(!interrupted.includes(path.join(workRoots[kind],name)))interrupted.push(path.join(workRoots[kind],name));
        }
        return true;
      },'progressive temporary work written',60000);
      await drop([small]);await ready();await evaluate('window.progressiveLoading');
      assert.equal(previewClient.isRunning(),false,'progressive worker survived replacement');
      for(const directory of interrupted)assert.equal(fs.existsSync(directory),false,'interrupted preview files leaked: '+directory);
      assert.equal(path.resolve((await viewState()).file),path.resolve(small));
      report.checks.push('native replacement interrupts progressive preparation and removes only its unfinished files');

      // Two rapid replacements, including one during engine recovery: last file wins.
      await evaluate('els.viewport.requestPointerLock=()=>Promise.reject(Error("hidden test"));setFly(true)');
      await drop([large]);await drop([small]);await ready();
      assert.equal(path.resolve((await viewState()).file),path.resolve(small));
      assert.equal(await evaluate('state.fly||state.keys.size>0'),false,'roaming controls leaked into replacement');
      assert.equal(await evaluate('els.fileDropHint.classList.contains("hidden")'),true);
      await wait(300);
      const final=await viewState();assert.equal(path.resolve(final.file),path.resolve(small));assert.equal(final.ready,true);
      assert.ok(!/3D 视图不可用|引擎退出|打开失败|CPU 预览失败/.test(final.log),final.log);
      const layout=await evaluate('({stop:els.stopRender.getBoundingClientRect().toJSON(),background:els.bgMode.getBoundingClientRect().toJSON(),width:innerWidth})');
      assert.ok(layout.stop.right<=layout.background.left&&layout.stop.left>0&&layout.background.right<layout.width,'toolbar layout clipped');
      assert.equal(await evaluate('state.view.gl.getError()'),0);
      win.setSize(980,660);await wait(100);
      const narrow=await evaluate('({stop:els.stopRender.getBoundingClientRect().toJSON(),background:els.bgMode.getBoundingClientRect().toJSON(),fullscreen:els.fullscreen.getBoundingClientRect().toJSON(),width:innerWidth})');
      assert.ok(narrow.stop.left>=0&&narrow.stop.right<=narrow.background.left&&narrow.fullscreen.right<=narrow.width,'toolbar clipped at minimum window size');
      win.setSize(1360,900);await wait(100);
      report.checks.push('rapid drops during recovery keep only the last file and exit roaming cleanly');
      report.pass=true;report.final=final;report.calls=calls;
      fs.writeFileSync(path.join(out,'final.png'),(await win.webContents.capturePage()).toPNG());
      console.log(JSON.stringify({...report,final:undefined,calls:undefined}));
    }catch(error){failed=true;report.error=error.stack;report.debug=await viewState().catch(()=>null);console.error(error.stack,report.debug);}
    finally{
      fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(report,null,2));
      if(win.webContents.debugger.isAttached())win.webContents.debugger.detach();
      app.quit();
    }
  });
});
require('../../webapp/main');
setTimeout(()=>{failed=true;console.error('drag/stop test timed out');app.quit();},240000).unref();
