const {chromium}=require('C:/Users/roy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const path=require('node:path'),fs=require('node:fs'),assert=require('node:assert/strict');
(async()=>{
  const browser=await chromium.launch({headless:true,channel:'msedge',args:['--allow-file-access-from-files']});
  try{
    const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];
    page.on('pageerror',e=>{if(e.stack?.includes('/board'))errors.push(e.message);});
    await page.goto('file:///'+path.resolve(__dirname,'../../webapp/renderer/index.html').replaceAll('\\','/'));
    await page.locator('#menuBoard').click();await page.locator('#boardViewSide').click();await page.locator('#boardPause').click();
    await page.locator('#boardTabs button').filter({hasText:'全部',exact:true}).click();
    const settle=()=>page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    const load=async(data)=>{await page.locator('#boardFile').setInputFiles({name:'test.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify({version:2,view:'side',zoom:48,pan:{x:200,y:120},...data}))});await page.waitForFunction(()=>document.getElementById('boardFile').value==='');await settle();};
    const packet=async()=>{const ready=page.waitForEvent('download');await page.locator('#boardSave').click();const stream=await(await ready).createReadStream();let text='';for await(const chunk of stream)text+=chunk;return JSON.parse(text);};
    const choose=async(id)=>{await page.locator('#boardSearch').fill(id);await page.locator(`#boardPalette [data-block="${id}"]`).click();};
    const state=async(key,value)=>{await page.getByRole('button',{name:key+'='+value,exact:true}).click();};
    const base=(id,facing,extended='true')=>({id,states:{facing,extended}});
    const board=await page.locator('#boardCanvas').boundingBox();
    const point=(x,y)=>({x:board.x+200+(x+.5)*48,y:board.y+120+(y+.5)*48});
    async function click(x,y){const p=point(x,y);await page.mouse.click(p.x,p.y);await settle();}
    async function drag(x,y,tx,ty){const a=point(x,y),b=point(tx,ty);await page.mouse.move(a.x,a.y);await page.mouse.down({button:'right'});await page.mouse.move(b.x,b.y,{steps:5});await page.mouse.up({button:'right'});await settle();}
    await load({cells:[]});await choose('sticky_piston');await state('facing','east');await state('extended','true');
    const thumbnail=await page.getByRole('button',{name:'extended=true',exact:true}).locator('canvas').evaluate(canvas=>{
      const pixels=canvas.getContext('2d').getImageData(0,0,32,32).data;
      let left=0,right=0;for(let y=0;y<32;y++)for(let x=0;x<32;x++)if(pixels[(y*32+x)*4+3]){if(x<16)left++;else right++;}
      return {left,right};
    });assert(thumbnail.left>100&&thumbnail.right>50,'state thumbnail fits both base and head');
    // The ghost must already include the second cell before placing anything.
    await page.mouse.move(board.x+20,board.y+20);await settle();
    const pixels=()=>page.locator('#boardCanvas').evaluate(c=>Array.from(c.getContext('2d').getImageData(250,122,44,44).data));
    const before=await pixels();const p=point(0,0);await page.mouse.move(p.x,p.y);await settle();const after=await pixels();
    assert(after.filter((n,i)=>i%4!==3&&n!==before[i]).length>300,'placement ghost draws the head in the adjacent cell');
    await click(0,0);let data=await packet();assert.equal(data.cells.length,2);assert(data.cells.some(([k,b])=>k==='1,0'&&b.id==='piston_head'&&b.states.type==='sticky'));
    await drag(1,0,3,1);data=await packet();assert(data.cells.some(([k,b])=>k==='2,1'&&b.id==='sticky_piston'));assert(data.cells.some(([k,b])=>k==='3,1'&&b.id==='piston_head'));
    await page.locator('#boardUndo').click();data=await packet();assert(data.cells.some(([k,b])=>k==='0,0'&&b.id==='sticky_piston'));
    await page.locator('#boardRedo').click();data=await packet();assert(data.cells.some(([k,b])=>k==='3,1'&&b.id==='piston_head'));
    await state('facing','west');await click(2,1);data=await packet();assert(data.cells.some(([k,b])=>k==='1,1'&&b.id==='piston_head'));assert(!data.cells.some(([k])=>k==='3,1'));
    await state('extended','false');await click(2,1);assert.equal((await packet()).cells.length,1);
    await state('extended','true');await click(2,1);await page.locator('#boardEraser').click();await click(1,1);assert.equal((await packet()).cells.length,0,'erasing head clears both cells');
    await load({cells:[['1,0',{id:'stone'}]]});await choose('piston');await state('facing','east');await state('extended','true');await click(0,0);
    assert.equal((await packet()).cells.length,1);assert((await page.locator('#boardStatus').innerText()).includes('活塞头'));
    // Compare the actual canvas pixels with complete vanilla head geometry. The
    // overlap check catches the missing segment of rod inside the base cell.
    for(const view of ['side','top']){
      const cells=[];for(const [row,id]of ['piston','sticky_piston'].entries())for(const [col,facing]of ['north','south','east','west','up','down'].entries())cells.push([`${col*3},${row*4}`,base(id,facing)]);
      await load({view,zoom:48,pan:{x:180,y:100},cells});await page.locator('#boardBrush').click();await choose('stone');await page.mouse.move(20,20);await settle();
      data=await packet();assert.equal(data.cells.length,20,'both views project four directions into an extra cell');await load(data);
      const result=await page.evaluate(async(data)=>{
        const images=await Promise.all(['board-atlas.png','board-piston-parts.png'].map(async src=>{const image=new Image();image.src=src;await image.decode();return image;}));
        const actual=document.getElementById('boardCanvas').getContext('2d'),expected=document.createElement('canvas');expected.width=expected.height=32;
        const ctx=expected.getContext('2d'),match=(a,b)=>Object.entries(a).every(([k,v])=>b[k]===v);
        function sprite(image,index){ctx.drawImage(image,index%64*32,Math.floor(index/64)*32,32,32,0,0,32,32);}
        let checked=0,mismatches=0,rodPixels=0;
        for(const [key,cell]of data.cells){
          if(!['piston','sticky_piston','piston_head'].includes(cell.id))continue;
          ctx.clearRect(0,0,32,32);const states=cell.states,view=data.view==='top'?'top':'sprite';
          if(cell.id==='piston_head')sprite(images[1],BoardPistonParts.visuals.find(v=>match(v.when,states))[view]);
          else{
            const def=BoardCatalog.find(b=>b.id===cell.id);sprite(images[0],def.visuals.find(v=>match(v.when,states))[view]);
            const outOfPlane=data.view==='top'?['up','down']:['north','south'];
            if(!outOfPlane.includes(states.facing)||states.facing===(data.view==='top'?'up':'north')){
              const head=BoardPistonParts.visuals.find(v=>v.when.facing===states.facing&&v.when.type===(cell.id==='sticky_piston'?'sticky':'normal'));
              const before=ctx.getImageData(0,0,32,32).data;sprite(images[1],head.overlap[view]);const after=ctx.getImageData(0,0,32,32).data;
              for(let i=3;i<after.length;i+=4)if(after[i]&&!before[i])rodPixels++;
            }
          }
          const reference=ctx.getImageData(0,0,32,32).data,[x,y]=key.split(',').map(Number);
          // Sample pixel centers so fractional zoom and grid boundaries cannot
          // affect the comparison; transparent areas retain the board background.
          for(let sy=1;sy<31;sy++)for(let sx=1;sx<31;sx++){
            const i=(sy*32+sx)*4;if(reference[i+3]!==255)continue;
            const pixel=actual.getImageData(Math.floor(data.pan.x+(x+(sx+.5)/32)*data.zoom),Math.floor(data.pan.y+(y+(sy+.5)/32)*data.zoom),1,1).data;
            checked++;if([0,1,2].some(j=>pixel[j]!==reference[i+j]))mismatches++;
          }
        }
        return {checked,mismatches,rodPixels};
      },data);
      assert(result.checked>9000);assert.equal(result.mismatches,0,view+' head, base and rod pixels');assert(result.rodPixels>100,'rod reaches into the base');
      const output=path.join(__dirname,'piston-output');fs.mkdirSync(output,{recursive:true});await page.screenshot({path:path.join(output,'board-'+view+'.png')});
    }
    assert.deepEqual(errors,[]);console.log('PASS: 2D ghost and state previews, paired head drag/undo/redo/rotate/retract/delete, blocked placement, side/top save/load, actual normal/sticky head and rod pixels');
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
