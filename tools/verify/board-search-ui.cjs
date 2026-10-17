
'use strict';
const {app,BrowserWindow,dialog}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../..'),out=path.join(__dirname,'board-search-output');fs.mkdirSync(out,{recursive:true});app.setPath('userData',path.join(out,'electron'));
BrowserWindow.prototype.show=function(){};
app.commandLine.appendSwitch('disable-renderer-backgrounding');
const settings=require('../../webapp/ui-settings'),Original=settings.UiSettings;
fs.mkdirSync(path.join(out,'settings'),{recursive:true});fs.writeFileSync(path.join(out,'settings/ui.json'),JSON.stringify({hideWelcome:true,projectionMode:'full',clearCacheOnExit:false}));
settings.UiSettings=class extends Original{constructor(){super(out);}};
dialog.showSaveDialog=async()=>({canceled:false,filePath:path.join(out,'ui-export.litematic')});
app.once('browser-window-created',(_event,win)=>{
  win.webContents.setBackgroundThrottling(false);win.webContents.setAudioMuted(true);
  win.webContents.once('did-finish-load',async()=>{
    try{
      const result=await win.webContents.executeJavaScript(`(async()=>{
        const check=(ok,message)=>{if(!ok)throw Error(message);},wait=ms=>new Promise(r=>setTimeout(r,ms));
        const until=async(fn,message)=>{const end=Date.now()+120000;while(!fn()){if(Date.now()>end)throw Error(message);await wait(40);}};
        await window.__uiReady;await window.__resourcesReady;
        await loadFile(${JSON.stringify(path.join(out,'many.litematic'))});await until(()=>state.viewReady&&!state.rendering,'main view timeout');
        const query=$('blockSearchQuery');query.value='石头';$('blockSearchRun').click();await until(()=>!$('blockSearchRun').disabled,'search timeout');
        check($('blockSearchStatus').textContent.includes('10,005'),'count missing');check(state.view.searchBoxes.length===10000,'page not bounded');
        drawView();check(state.view.gl.getError()===0,'search GL error');
        const g=state.view.gl,pixels=new Uint8Array(4*els.gl.width*els.gl.height);g.readPixels(0,0,els.gl.width,els.gl.height,g.RGBA,g.UNSIGNED_BYTE,pixels);
        let cyan=0;for(let i=0;i<pixels.length;i+=4)if(pixels[i+1]>pixels[i]*1.5&&pixels[i+2]>pixels[i]*1.5&&pixels[i+2]>100)cyan++;check(cyan>50,'highlight not visible');
        $('blockSearchPrev').click();await until(()=>!$('blockSearchPrev').disabled,'previous page timeout');check($('blockSearchStatus').textContent.includes('10005'),'last result missing');check(state.view.searchBoxes.length===5,'page did not change');
        check(state.fly,'locate did not enter perspective');$('blockSearchNext').click();await until(()=>!$('blockSearchNext').disabled,'wrap timeout');check($('blockSearchStatus').textContent.includes('当前 1'),'wrap failed');setFly(false);
        query.value='不存在';$('blockSearchRun').click();await until(()=>!$('blockSearchRun').disabled,'empty search timeout');check(!window.blockSearch.active&&!state.view.searchBoxes.length,'empty result left highlights');
        $('blockSearchClear').click();const originalFile=state.file;
        $('menuBoard').click();$('boardViewSide').click();$('boardPause').click();
        check($('boardDialog').open&&!$('boardPreviewCanvas'),'2D tool must be independent');$('boardClose').click();
        check(state.file===originalFile&&state.viewReady,'2D board changed the projection');
        const rendered=els.gl.toDataURL();
        $('blockSearchClear').click();state.projectionMode='streaming';
        await loadFile(${JSON.stringify(path.join(out,'many.litematic'))});await until(()=>state.viewReady&&!state.rendering&&!state.scenePreparing,'stream preview timeout');
        check(!!state.view.stream,'streaming mode missing');await state.view.stream.settled();
        query.value='stone';$('blockSearchRun').click();await until(()=>!$('blockSearchRun').disabled,'stream search timeout');lastViewKey=null;drawView();
        check(state.view.searchBoxes.length===10000&&state.view.gl.getError()===0,'stream highlight failed');
        return {pass:true,cyan,rendered};
      })()`);
      fs.writeFileSync(path.join(out,'board-preview.png'),Buffer.from(result.rendered.split(',')[1],'base64'));delete result.rendered;
      fs.writeFileSync(path.join(out,'ui-result.json'),JSON.stringify(result,null,2));console.log('PASS: actual Electron search, rendered highlights, paging / wrap, streamed highlighting and independent flat editor');app.quit();
    }catch(error){console.error(error.stack);fs.writeFileSync(path.join(out,'ui-result.json'),JSON.stringify({pass:false,error:error.stack},null,2));app.exit(1);}
  });
});
require('../../webapp/main.js');
