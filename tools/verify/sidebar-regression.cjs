const {app,BrowserWindow}=require('electron');
const fs=require('node:fs');
const path=require('node:path');
const [packageRoot,schematic,output]=process.argv.slice(2);
process.argv=process.argv.slice(0,2);
fs.mkdirSync(path.dirname(output),{recursive:true});
app.setPath('userData',output.replace(/\.json$/,'-electron'));
BrowserWindow.prototype.show=function(){};
const report={pass:false};
app.once('browser-window-created',(_event,win)=>{
  win.webContents.once('did-finish-load',async()=>{
    const run=code=>win.webContents.executeJavaScript('(async()=>{'+code+'})()');
    try {
      const result=await run(`
        await window.__uiReady;
        if(els.welcome.open)await dismissWelcome(false);
        await window.engine.loadResources(null); await loadFile(${JSON.stringify(schematic)});
        if(!state.viewReady)throw new Error('WebGL preview unavailable');
        const wait=async predicate=>{const end=Date.now()+10000;while(!await predicate()){if(Date.now()>end)throw new Error('Sidebar action timed out');await new Promise(r=>setTimeout(r,25));}};
        const button=els.toggleSidebar.getBoundingClientRect(),panel=els.sidebarPanel.getBoundingClientRect();
        if(Math.abs((button.top+button.bottom-panel.top-panel.bottom)/2)>1 || Math.abs((button.left+button.right)/2-panel.right)>1)throw new Error('Toggle is not centered on sidebar edge');
        const before=els.viewport.clientWidth,view=state.view,quads=view.quadCount;
        els.rotation.value='72';els.slant.value='26';state.zoom=1.4;els.zoom.value='1.4';syncAngleLabels();drawView();
        const originalFile=state.file;
        els.toggleSidebar.click();
        await wait(async()=>state.sidebarCollapsed && (await window.appUi.settings()).sidebarCollapsed);
        await new Promise(r=>requestAnimationFrame(r));
        const after=els.viewport.clientWidth,collapsed=els.toggleSidebar.getBoundingClientRect();
        if(after-before!==300)throw new Error('Hidden sidebar did not add 300px to viewport: '+before+'/'+after);
        if(getComputedStyle(els.sidebar).display!=='none' || !els.sidebar.inert || els.toggleSidebar.getAttribute('aria-expanded')!=='false')throw new Error('Collapsed sidebar controls are still active');
        if(collapsed.left<0 || collapsed.right>innerWidth)throw new Error('Restore button is outside window');
        if(state.file!==originalFile || state.zoom!==1.4 || els.rotation.value!=='72' || els.slant.value!=='26' || state.view!==view || state.view.quadCount!==quads)throw new Error('Collapse reset model or camera');
        els.toggleSidebar.click();await wait(async()=>!state.sidebarCollapsed && !(await window.appUi.settings()).sidebarCollapsed);
        await new Promise(r=>requestAnimationFrame(r));
        if(els.viewport.clientWidth!==before || state.view.gl.getError()!==0)throw new Error('Expanded view did not recover correctly');
        els.toggleSidebar.click();await wait(async()=>(await window.appUi.settings()).sidebarCollapsed);
        return {before,after,quads,preservedCamera:true,centered:true,restoreVisible:true};
      `);
      await new Promise(resolve=>{win.webContents.once('did-finish-load',resolve);win.webContents.reload();});
      const restored=await run(`await window.__uiReady;if(els.welcome.open)await dismissWelcome(false);return state.sidebarCollapsed && els.toggleSidebar.getAttribute('aria-expanded')==='false';`);
      if(!restored)throw new Error('Collapse preference was not restored');
      win.setSize(980,660);
      await new Promise(r=>setTimeout(r,100));
      const minimum=await run(`const rect=els.toggleSidebar.getBoundingClientRect();const panel=els.sidebarPanel.getBoundingClientRect();return {visible:rect.left>=0&&rect.right<=innerWidth,centered:Math.abs((rect.top+rect.bottom-panel.top-panel.bottom)/2)<1};`);
      if(!minimum.visible||!minimum.centered)throw new Error('Toggle unusable at minimum window size');
      Object.assign(report,{pass:true,...result,persisted:true,minimum});
      fs.writeFileSync(output,JSON.stringify(report,null,2));app.quit();
    }catch(error){report.error=error.stack;fs.writeFileSync(output,JSON.stringify(report,null,2));app.quit();}
  });
});
require(path.join(packageRoot,'webapp/main.js'));
setTimeout(()=>{if(!report.pass&&!report.error){report.error='Sidebar regression timed out';fs.writeFileSync(output,JSON.stringify(report,null,2));app.quit();}},90000).unref();
