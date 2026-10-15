const {app,BrowserWindow}=require('electron');
const fs=require('node:fs');
const path=require('node:path');
const [packageRoot,schematic,output,reference,entityFixture]=process.argv.slice(2);
process.argv=process.argv.slice(0,2);
fs.mkdirSync(path.dirname(output),{recursive:true});
app.setPath('userData',output.replace(/\.json$/,'-electron'));
BrowserWindow.prototype.show=function(){};
const report={pass:false,checks:[]};
fs.writeFileSync(output,JSON.stringify(report,null,2));
const check=(ok,message)=>{if(!ok)throw new Error(message);report.checks.push(message);};
app.once('browser-window-created',(_event,win)=>{
  win.webContents.once('did-finish-load',async()=>{
    const run=code=>win.webContents.executeJavaScript('(async()=>{'+code+'})()');
    const wait=async code=>{const end=Date.now()+15000;while(!await run(code)){if(Date.now()>end)throw new Error('Inspection timed out');await new Promise(r=>setTimeout(r,25));}};
    const input=(type,x,y,button='left')=>win.webContents.sendInputEvent({type,x:Math.round(x),y:Math.round(y),button,clickCount:1});
    async function center(){return run(`const r=els.gl.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2};`);}
    async function click(x,y){input('mouseMove',x,y);input('mouseDown',x,y);input('mouseUp',x,y);}
    async function aim(x,y=.45){await run(`closeInspector();setFly(true);const warp=new MouseEvent('mousemove',{bubbles:true});Object.defineProperty(warp,'movementX',{value:300});Object.defineProperty(warp,'movementY',{value:-180});els.viewport.dispatchEvent(warp);state.keys.clear();state.flyPos=[${x},${y},-2];state.flyYaw=Math.PI;state.flyPitch=0;drawView();`);}
    try{
      await run(`window.__lockRequests=0;document.exitPointerLock=()=>{Object.defineProperty(document,'pointerLockElement',{configurable:true,value:null});document.dispatchEvent(new Event('pointerlockchange'));};els.viewport.requestPointerLock=()=>{window.__lockRequests++;Object.defineProperty(document,'pointerLockElement',{configurable:true,value:els.viewport});document.dispatchEvent(new Event('pointerlockchange'));return Promise.resolve();};await window.__uiReady;if(els.welcome.open)await dismissWelcome(false);await window.engine.loadResources(null);await loadFile(${JSON.stringify(schematic)});if(!state.viewReady)throw new Error('Actual WebGL failed');`);
      await aim(.5);let p=await center();
      check(await run(`return window.__lockRequests>0;`),'fly mode requests mouse lock');
      const ray=await run(`return state.view.rayFromScreen(${p.x},${p.y});`);
      check(ray&&ray.direction[2]>.999&&Math.abs(ray.direction[0])<.001,'screen ray matches fly camera');
      check(await run(`const a=els.crosshair.getBoundingClientRect(),b=els.gl.getBoundingClientRect();return !els.crosshair.classList.contains('hidden')&&Math.abs(a.left+a.width/2-b.left-b.width/2)<1&&Math.abs(a.top+a.height/2-b.top-b.height/2)<1&&els.crosshair.naturalWidth===15;`),'original Minecraft crosshair is centered');
      await click(p.x-180,p.y);await wait(`return state.inspectedBlock?.id==='minecraft:chest';`);
      let first=await run(`return {block:state.inspectedBlock,visible:!els.inspector.classList.contains('hidden'),label:els.inspectName.textContent,items:els.inspectItems.textContent};`);
      check(first.visible&&first.label.includes('箱')&&first.items.includes('钻石')&&first.items.includes('96')&&first.block.container.items.length===2,'crosshair selection and same-NBT quantity aggregation');
      check(first.block.position.join(',')==='0,0,0'&&first.block.container.status==='saved','clicked geometry resolves correct cell');
      check(await run(`return !state.flyLookActive;`),'inspection releases mouse look');
      // Hidden Chromium does not forward hardware hover moves; exercise the real mousemove handler.
      await aim(.5);p=await center();await run(`hoverPoint=[${p.x},${p.y}];const movement=new MouseEvent('mousemove',{bubbles:true,buttons:0,clientX:${p.x}+22,clientY:${p.y}+8});Object.defineProperty(movement,'movementX',{value:22});Object.defineProperty(movement,'movementY',{value:8});els.gl.dispatchEvent(movement);`);
      await new Promise(r=>setTimeout(r,150));
      const looked=await run(`return {selected:!!state.inspectedBlock,yaw:state.flyYaw,active:state.flyLookActive,dragging,hoverPoint};`);
      if(looked.selected||Math.abs(looked.yaw-Math.PI)<=.01||looked.dragging)report.look=looked;
      check(!looked.selected&&Math.abs(looked.yaw-Math.PI)>.01&&!looked.dragging,'mouse movement rotates without holding either button');
      const batching=await run(`cancelAnimationFrame(state.animHandle);state.animHandle=0;let draws=0;const original=state.view.draw;state.view.draw=function(...args){draws++;return original.apply(this,args);};hoverPoint=[${p.x},${p.y}];for(let i=1;i<=100;i++){const event=new MouseEvent('mousemove',{bubbles:true,clientX:${p.x}+i,clientY:${p.y}});Object.defineProperty(event,'movementX',{value:1});els.gl.dispatchEvent(event);}const during=draws;flyTick(performance.now());cancelAnimationFrame(state.animHandle);state.animHandle=0;const after=draws;state.view.draw=original;return {during,after};`);
      check(batching.during===0&&batching.after===1,'100 mouse events produce one frame instead of 100 redraws');
      const relative=await run(`const old=state.flyYaw;Object.defineProperty(document,'pointerLockElement',{configurable:true,value:els.viewport});const event=new MouseEvent('mousemove',{bubbles:true,clientX:0,clientY:0});Object.defineProperty(event,'movementX',{value:8});Object.defineProperty(event,'movementY',{value:0});els.gl.dispatchEvent(event);delete document.pointerLockElement;return state.flyYaw-old;`);
      check(Math.abs(relative+8*.0025)<1e-9,'locked mouse uses relative movement');
      await run(`pauseFlyLook();`);const pausedYaw=await run(`return state.flyYaw;`);await run(`els.gl.dispatchEvent(new MouseEvent('mousemove',{bubbles:true,clientX:${p.x}+35,clientY:${p.y}}));`);
      check(await run(`return state.flyYaw===${pausedYaw};`),'paused view allows UI interaction');
      await aim(.5);p=await center();input('mouseDown',p.x,p.y,'right');input('mouseUp',p.x,p.y,'right');
      await new Promise(r=>setTimeout(r,100));
      check(await run(`return !state.inspectedBlock;`),'right-click does not open inventory');
      await aim(2.5);p=await center();await click(p.x,p.y);await wait(`return state.inspectedBlock?.container?.doubleChest;`);
      let pair=await run(`return state.inspectedBlock.container;`);
      check(pair.slots===54&&pair.items.length===2&&pair.items.some(i=>i.count===20&&i.stacks===2)&&pair.items.some(i=>i.displaySlot===27),'double chest combines matching stacks across both halves');
      await aim(5.5);p=await center();await click(p.x,p.y);await wait(`return state.inspectedBlock?.id==='minecraft:red_shulker_box';`);
      check(await run(`return els.inspectItems.textContent.includes('测试苹果 <safe>') && !els.inspectItems.querySelector('safe');`),'modern item name is visible text');
      const slot=await run(`const r=els.inspectItems.querySelector('button').getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2,yaw:state.flyYaw};`);
      await click(slot.x,slot.y);await wait(`return els.inspectItemDetails.open;`);
      check(await run(`return els.inspectItemData.textContent.includes('minecraft:custom_name')&&state.flyYaw===${slot.yaw};`),'item details click does not rotate camera');
      check(await run(`const zoom=state.zoom;const event=new WheelEvent('wheel',{bubbles:true,cancelable:true,deltaY:80});els.inspectItems.dispatchEvent(event);return state.zoom===zoom&&!event.defaultPrevented;`),'inventory panel scrolling does not zoom the scene');
      win.webContents.sendInputEvent({type:'keyDown',keyCode:'V'});win.webContents.sendInputEvent({type:'keyUp',keyCode:'V'});
      await wait(`return !state.fly&&els.inspector.classList.contains('hidden');`);
      check(await run(`return !state.fly&&els.inspector.classList.contains('hidden');`),'V exits fly mode while an inventory item has focus');
      await run(`closeInspector();setFly(false);drawView();`);p=await center();await click(p.x,p.y);await new Promise(r=>setTimeout(r,100));
      check(await run(`return !state.inspectedBlock;`),'orbit mode keeps its existing click behavior');
      await aim(9.5,1.14);p=await center();await click(p.x,p.y);await wait(`return state.inspectedBlock?.id==='minecraft:decorated_pot';`);
      check(await run(`return state.inspectedBlock.position[1]===0&&state.inspectedBlock.container.items[0].count===12;`),'overhanging pot neck resolves original block and stored item');
      await run(`setFly(false);`);check(await run(`return els.inspector.classList.contains('hidden')&&els.crosshair.classList.contains('hidden');`),'leaving fly mode hides inspection and crosshair');
      const health=await run(`return {error:state.view.gl.getError(),quads:state.view.quadCount};`);check(health.error===0&&health.quads>0,'actual WebGL remains healthy');
      if(entityFixture){
        await run(`await loadFile(${JSON.stringify(entityFixture)});`);await aim(.5);p=await center();await click(p.x,p.y);await wait(`return state.inspectedBlock?.id==='minecraft:chest_minecart';`);
        check(await run(`return state.inspectedBlock.kind==='entity'&&state.inspectedBlock.container.items[0].count===96&&els.inspectStateTitle.textContent==='实体状态'&&els.stats.textContent.includes('实体 1');`),'real entity picking, vehicle contents and entity status panel');
        const entityPixels=await run(`drawView();const gl=state.view.gl,pixel=new Uint8Array(4);gl.readPixels(Math.floor(els.gl.width/2),Math.floor(els.gl.height/2),1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixel);return {pixel:[...pixel],error:gl.getError()};`);
        check(entityPixels.pixel[3]>0&&entityPixels.error===0,'entity geometry is visible in actual WebGL');report.vehicle=entityPixels;
        await run(`setFly(false);`);
      }
      if(reference){
        const previousSource=fs.readFileSync(path.join(__dirname,'fly-output/gpu-view-before.js'),'utf8');
        const previousMethod=previousSource.match(/\n  _sortTranslucent\(layer, dir\) \{[\s\S]*?\n  \}/)[0].trim().replace('_sortTranslucent(','function previousSort(').replaceAll('VERTICES_PER_QUAD','6');
        const sample=await run(`await window.skins.setOnline(false);await loadFile(${JSON.stringify(reference)});setFly(true);cancelAnimationFrame(state.animHandle);state.animHandle=0;drawView();${previousMethod};const actual=state.view.layers.find(l=>l.centroids);const copy=()=>({...actual,order:Array.from({length:actual.quads},(_,i)=>i),indices:actual.indices?.slice(),depths:new Float64Array(actual.quads),lastDirection:null,radix:null,bspTree:null,visibleIds:null,visibleCount:undefined,lastVisibilityVersion:undefined});const old=copy(),fresh=copy();fresh.order=Uint32Array.from(fresh.order);const fake={gl:{ELEMENT_ARRAY_BUFFER:1,ARRAY_BUFFER:2,DYNAMIC_DRAW:3,bindBuffer(){},bufferData(){}}};let before=0,after=0;for(let frame=0;frame<24;frame++){const dir=[Math.sin(frame*.008),0,Math.cos(frame*.008)];let start=performance.now();previousSort.call(fake,old,dir);const a=performance.now()-start;start=performance.now();GpuView.prototype._sortTranslucent.call(fake,fresh,dir);const b=performance.now()-start;if(old.order.some((id,i)=>id!==fresh.order[i]))throw new Error('Reference depth ordering changed');if(frame>=4){before+=a;after+=b;}}const result={quads:state.view.quadCount,transparentFaces:actual.quads,sampledFrames:20,previousSortAndIndicesMillis:before/20,newSortAndIndicesMillis:after/20,glError:state.view.gl.getError()};setFly(false);return result;`);
        check(sample.glError===0&&sample.transparentFaces>2048,'real reference uses new sorting path with exact ordering');
        report.reference=sample;
        const culling=await run(`
          const gpu=state.view,gl=gpu.gl,center=gpu.center,frames=[];
          const read=()=>{const pixels=new Uint8Array(els.gl.width*els.gl.height*4);gl.readPixels(0,0,els.gl.width,els.gl.height,gl.RGBA,gl.UNSIGNED_BYTE,pixels);return pixels;};
          for(let i=0;i<8;i++) {
            const angle=i*Math.PI/4;
            const eye=[center[0],center[1],center[2]],target=[eye[0]+Math.sin(angle),eye[1]-.15,eye[2]+Math.cos(angle)];
            const camera={eye,target,fovDeg:70};
            gpu.draw({...camera,culling:false});const full=read();
            gpu.draw(camera);const culled=read();
            if(full.some((value,index)=>value!==culled[index]))throw new Error('Actual reference culling changed pixels at view '+i);
            frames.push({...gpu.drawStats});
          }
          if(!frames.some(frame=>frame.visibleQuads<frame.totalQuads))throw new Error('Actual reference did not cull any geometry');
          const result={pixelsEqual:true,views:frames.length,totalQuads:gpu.quadCount,minimumVisibleQuads:Math.min(...frames.map(frame=>frame.visibleQuads)),maximumVisibleQuads:Math.max(...frames.map(frame=>frame.visibleQuads)),chunks:frames[0].totalChunks,maximumDrawCalls:Math.max(...frames.map(frame=>frame.drawCalls))};drawView();return result;
        `);
        check(culling.pixelsEqual&&culling.views===8,'actual reference culling matches complete geometry at 8 interior viewpoints');
        report.referenceCulling=culling;
      }
      Object.assign(report,{pass:true,health});fs.writeFileSync(output,JSON.stringify(report,null,2));app.quit();
    }catch(error){report.error=error.stack;fs.writeFileSync(output,JSON.stringify(report,null,2));app.exit(1);}
  });
});
require(path.join(packageRoot,'webapp/main.js'));
setTimeout(()=>{if(!report.pass&&!report.error){report.error='Inspection regression timed out';fs.writeFileSync(output,JSON.stringify(report,null,2));app.quit();}},120000).unref();
