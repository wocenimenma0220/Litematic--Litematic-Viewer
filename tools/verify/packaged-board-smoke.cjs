'use strict';
const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(process.argv[2]),out=path.join(__dirname,'release-output');fs.mkdirSync(out,{recursive:true});
const expectedVersion=require('../../webapp/package.json').version;
app.setPath('userData',path.join(out,'electron'));BrowserWindow.prototype.show=function(){};
process.argv=process.argv.slice(0,2);let failed=false;
const until=async(fn,timeout=60000)=>{const end=Date.now()+timeout;while(!await fn()){if(Date.now()>end)throw Error('Packaged app timed out');await new Promise(r=>setTimeout(r,100));}};
app.once('browser-window-created',(_event,win)=>{
  win.webContents.setAudioMuted(true);win.webContents.setBackgroundThrottling(false);
  win.webContents.once('did-finish-load',async()=>{
    const evaluate=code=>win.webContents.executeJavaScript(code);
    try{
      await until(async()=>{try{return !!await evaluate('window.engine.ping()');}catch{return false;}});
      assert.equal(require(path.join(root,'webapp/package.json')).version,expectedVersion);
      await evaluate(`if(document.getElementById('welcomeDialog')?.open)document.getElementById('welcomeDialog').close();for(const d of document.querySelectorAll('dialog[open]'))d.close();document.getElementById('menuBoard').click();document.getElementById('boardViewSide').click();`);
      const info=await evaluate(`({blocks:BoardCatalog.length,sounds:Object.keys(BoardSounds).length,view:document.getElementById('boardTitle').textContent,portal:BoardSounds.nether_portal.event})`);
      assert.equal(info.blocks,1135);assert.equal(info.sounds,1135);assert.equal(info.portal,'block.stone.place');assert(info.view.includes('侧视'));
      const banners=await evaluate(`(async()=>{const img=new Image();img.src='board-atlas.png';await img.decode();const c=document.createElement('canvas');c.width=c.height=32;const ctx=c.getContext('2d');const sprites=['red','blue'].map(color=>{const index=BoardCatalog.find(b=>b.id===color+'_banner').visuals[0].icon;ctx.clearRect(0,0,32,32);ctx.drawImage(img,index%64*32,Math.floor(index/64)*32,32,32,0,0,32,32);return{center:[...ctx.getImageData(15,15,1,1).data],corner:ctx.getImageData(0,0,1,1).data[3]};});document.getElementById('boardToolsHandle').click();const ratio=document.getElementById('boardTools').getBoundingClientRect().height/document.getElementById('boardDialog').getBoundingClientRect().height;document.getElementById('boardToolsHandle').click();return{sprites,ratio};})()`);
      assert(banners.sprites[0].center[0]>banners.sprites[0].center[2]);assert(banners.sprites[1].center[2]>banners.sprites[1].center[0]);assert(banners.sprites.every(s=>s.corner===0));assert(Math.abs(banners.ratio-.618)<.003);
      const previews=await evaluate(`(()=>{document.querySelector('#boardTabs button:last-child').click();const choose=id=>{const search=document.getElementById('boardSearch');search.value=id;search.dispatchEvent(new Event('input'));document.querySelector('[data-block="'+id+'"]').click();};const bounds=canvas=>{const pixels=canvas.getContext('2d').getImageData(0,0,32,32).data;let x0=32,y0=32,x1=-1,y1=-1;for(let y=0;y<32;y++)for(let x=0;x<32;x++)if(pixels[(y*32+x)*4+3]){x0=Math.min(x0,x);y0=Math.min(y0,y);x1=Math.max(x1,x);y1=Math.max(y1,y);}return{width:x1-x0+1,height:y1-y0+1};};choose('rail');const rail={palette:bounds(document.querySelector('[data-block="rail"] canvas')),side:bounds(document.querySelector('[aria-label="shape=north_south"] canvas'))};choose('red_banner');return{rail,bannerOptions:document.querySelectorAll('[data-property="rotation"] button').length,bannerFront:bounds(document.querySelector('[aria-label="rotation=0"] canvas')),bannerEdge:bounds(document.querySelector('[aria-label="rotation=4"] canvas'))};})()`);
      assert(previews.rail.palette.height>=24);assert(previews.rail.side.height<=3);assert.equal(previews.bannerOptions,16);assert(previews.bannerFront.width>=10);assert(previews.bannerEdge.width<=4);
      await evaluate(`window.played=[];const play=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(){played.push(this.src);return play.call(this);};document.querySelector('#boardTabs button:last-child').click();const search=document.getElementById('boardSearch');search.value='ice';search.dispatchEvent(new Event('input'));document.querySelector('[data-block="ice"]').click();`);
      const rect=await evaluate(`(()=>{const r=document.getElementById('boardCanvas').getBoundingClientRect();return{x:Math.round(r.x+100),y:Math.round(r.y+100)}})()`);
      win.webContents.sendInputEvent({type:'mouseDown',button:'left',clickCount:1,...rect});win.webContents.sendInputEvent({type:'mouseUp',button:'left',clickCount:1,...rect});
      await until(async()=>await evaluate(`played.some(src=>src.endsWith('board-audio/1.mp3'))`));
      const durations=await evaluate(`Promise.all([BoardSounds.ice,BoardSounds.packed_ice,BoardSounds.blue_ice,BoardSounds.copper_chest].map(s=>new Promise((resolve,reject)=>{const a=new Audio();a.onloadedmetadata=()=>resolve(a.duration);a.onerror=()=>reject(Error(a.src));a.src=s.samples[0].file;a.load();})))`);
      assert(durations.every(n=>n>0&&Number.isFinite(n)));
      fs.writeFileSync(path.join(out,'packaged-board.png'),(await win.webContents.capturePage()).toPNG());
      await evaluate(`document.getElementById('boardClose').click()`);
      const schematic=process.env.LITEMATIC_SMOKE_SCHEMATIC||'D:/litematic-display/1.8w-潜影贝收集.litematic';
      await evaluate(`loadFile(${JSON.stringify(schematic)})`);
      await until(async()=>evaluate('state.viewReady&&!state.rendering&&!state.scenePreparing&&!state.fileOpening'),90000);
      const preview=await evaluate('({ready:state.viewReady,backend:state.previewBackend,quads:state.view?.quadCount||0})');assert(preview.ready);assert(preview.quads>0);
      fs.writeFileSync(path.join(out,'packaged-preview.png'),(await win.webContents.capturePage()).toPNG());
      const report={pass:true,version:expectedVersion,...info,banners,previews,audioDurations:durations,preview};fs.writeFileSync(path.join(out,'packaged-result.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
    }catch(error){failed=true;fs.writeFileSync(path.join(out,'packaged-result.json'),JSON.stringify({pass:false,error:error.stack},null,2));console.error(error.stack);}finally{app.quit();}
  });
});
app.on('will-quit',()=>{if(failed)app.exit(1);});
require(path.join(root,'webapp/main.js'));
setTimeout(()=>{console.error('Packaged smoke timeout');app.exit(1);},180000).unref();
