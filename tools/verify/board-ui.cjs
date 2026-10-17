const {chromium}=require('C:/Users/roy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
  const browser=await chromium.launch({headless:true,channel:'msedge'});
  try{
    const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];
    await page.addInitScript(()=>{window.__placementAudio=[];const play=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(){window.__placementAudio.push({src:this.src,rate:this.playbackRate});return play.call(this);};});
    page.on('pageerror',e=>{if(e.stack?.includes('/board.js'))errors.push(e.message);});
    await page.goto('file:///'+path.resolve(__dirname,'../../webapp/renderer/index.html').replaceAll('\\','/'));
    await page.evaluate(()=>localStorage.setItem('litematic-board-v2-side',JSON.stringify({version:2,view:'side',cells:[['0,0',{id:'stone'}]]})));
    const choose=async id=>{await page.locator('#boardSearch').fill(id);await page.locator(`#boardPalette button[data-block="${id}"]`).click();};
    const packet=async()=>{const ready=page.waitForEvent('download');await page.locator('#boardSave').click();const download=await ready;assert.equal(download.suggestedFilename(),'二维搭建.json');const stream=await download.createReadStream();let text='';for await(const chunk of stream)text+=chunk;return JSON.parse(text);};
    const load=async data=>{await page.locator('#boardFile').setInputFiles({name:'test.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(data))});await page.waitForFunction(()=>document.getElementById('boardFile').value==='');};
    await page.locator('#menuBoard').click();assert(await page.locator('#boardViewDialog').evaluate(d=>d.open));assert.equal(await page.locator('#boardDialog').evaluate(d=>d.open),false);
    await page.locator('#boardViewSide').click();await page.locator('#boardPalette button').first().waitFor();
    assert.equal((await packet()).cells.length,0,'initial board does not restore stale local storage');
    await page.locator('#boardTabs button').filter({hasText:'全部',exact:true}).click();
    await choose('dispenser');assert.equal(await page.locator('#boardState fieldset').count(),2);
    assert.equal(await page.locator('[data-property="facing"] button').count(),6);await page.getByRole('button',{name:'facing=east',exact:true}).click();await page.getByRole('button',{name:'triggered=true',exact:true}).click();
    assert.equal(await page.locator('[data-property="facing"]').innerText(),'方向');
    await choose('observer');assert.equal(await page.locator('#boardState fieldset').count(),1);
    await choose('rail');assert.equal(await page.locator('[data-property="shape"] button').count(),10);await page.getByRole('button',{name:'shape=ascending_east',exact:true}).click();
    await choose('powered_rail');assert.equal(await page.locator('[data-property="shape"] button').count(),6);assert(await page.locator('[data-property="waterlogged"]').isVisible());assert(await page.locator('[data-property="powered"]').isVisible());
    await choose('repeater');assert.equal(await page.locator('[data-property="delay"] button').count(),4);assert(await page.locator('[data-property="locked"]').isVisible());
    await choose('comparator');assert.equal(await page.locator('[data-property="mode"] button').count(),2);
    await choose('oak_slab');await page.getByRole('button',{name:'type=double',exact:true}).click();assert(await page.getByRole('button',{name:'waterlogged=true',exact:true}).isDisabled());
    await page.locator('#boardPause').click();
    const source={id:'oak_stairs',states:{facing:'east',half:'top',shape:'inner_left',waterlogged:'true'}};
    await load({version:2,view:'side',zoom:40,pan:{x:300,y:100},cells:[['0,0',source],['4,0',{id:'stone'}]]});
    const r=await page.locator('#boardCanvas').boundingBox();
    async function drag(x,y,dx,dy){await page.mouse.move(r.x+x,r.y+y);await page.mouse.down({button:'right'});await page.mouse.move(r.x+x+dx,r.y+y+dy,{steps:5});await page.mouse.up({button:'right'});}
    await drag(320,120,80,40);let data=await packet();assert(!data.cells.some(([k])=>k==='0,0'));const moved=data.cells.find(([k])=>k==='2,1');assert.equal(moved[1].states.shape,'inner_left');assert.equal(moved[1].states.waterlogged,'true');
    await page.locator('#boardUndo').click();data=await packet();assert(data.cells.some(([k])=>k==='0,0'));await page.locator('#boardRedo').click();
    await drag(400,160,80,-40);data=await packet();assert(data.cells.some(([k])=>k==='2,1'),'occupied destination cancels move');
    await drag(100,70,60,40);data=await packet();assert.deepEqual(data.pan,{x:360,y:140});assert.equal(data.cells.length,2,'blank right drag only pans');
    await page.mouse.move(r.x+600,r.y+250);await page.mouse.wheel(0,-350);data=await packet();assert(data.zoom>40);
    await page.locator('#boardClose').click();await page.locator('#menuBoard').click();await page.locator('#boardViewTop').click();assert.equal((await packet()).view,'top');
    await load({version:2,view:'top',zoom:40,pan:{x:500,y:160},cells:[['0,0',{id:'water'}],['3,0',{id:'stone'}],['-2,-1',{id:'powered_rail',states:{shape:'ascending_east',powered:'true',waterlogged:'false'}}]]});
    await choose('rail');await page.getByRole('button',{name:'shape=ascending_east',exact:true}).click();
    await page.screenshot({path:path.resolve(__dirname,'board-ui-top.png')});
    await page.locator('#boardClose').click();await page.locator('#menuBoard').click();await page.locator('#boardViewSide').click();assert.equal((await packet()).cells.length,2,'views keep separate boards');
    await page.screenshot({path:path.resolve(__dirname,'board-ui.png')});
    await load({version:1,cells:[['0,0',{id:'dispenser',facing:'up',active:true}]]});data=await packet();assert.equal(data.cells[0][1].states.facing,'up');assert.equal(data.cells[0][1].states.triggered,'true');
    await load({version:2,view:'side',zoom:40,pan:{x:300,y:100},cells:[]});
    for(const [id,index]of [['ice',1],['packed_ice',2],['blue_ice',3],['copper_chest',4]]){await choose(id);await page.mouse.click(r.x+320+(index-1)*40,r.y+120);const last=await page.evaluate(()=>window.__placementAudio.at(-1));assert(last.src.includes(index<=3?`board-audio/${index}.mp3`:'board-audio/sounds/'));if(index<=3)assert.equal(last.rate,1);}
    const count=await page.evaluate(()=>window.__placementAudio.length);await page.mouse.click(r.x+440,r.y+120);assert.equal(await page.evaluate(()=>window.__placementAudio.length),count,'unchanged cell does not replay audio');
    const decoded=await page.evaluate(async()=>{const files=['board-audio/1.mp3','board-audio/2.mp3','board-audio/3.mp3',BoardSounds.copper_chest.samples[0].file,BoardSounds.chiseled_cinnabar.samples[0].file];return Promise.all(files.map(file=>new Promise((resolve,reject)=>{const audio=new Audio();audio.addEventListener('loadedmetadata',()=>resolve(audio.duration),{once:true});audio.addEventListener('error',()=>reject(new Error(file)),{once:true});audio.src=file;audio.load();})));});assert(decoded.every(n=>n>0&&Number.isFinite(n)));
    await choose('rail');await page.screenshot({path:path.resolve(__dirname,'board-ui-icons.png')});
    await choose('copper_chest');await page.screenshot({path:path.resolve(__dirname,'board-ui-chest.png')});
    assert.deepEqual(errors,[]);console.log('PASS: fresh empty board, view chooser, state options, drag/undo/zoom, save compatibility, flat icons/chests, original/custom audio playback and decoding');
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
